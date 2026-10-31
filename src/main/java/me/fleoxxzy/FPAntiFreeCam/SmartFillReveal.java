package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic reveal calculations for Smart Fill cave camouflage.
 *
 * <p>IMPROVEMENT (1.5.3): a block is real for the player if any of these is true:
 * <ol>
 *   <li><b>Near radius</b> ({@code near-radius}, default 6): everything this
 *       close, even through rock. Covers mining into a wall, block updates and
 *       pistons before the next fill.</li>
 *   <li><b>Connected air</b>: a bounded flood fill from the eye through
 *       see-through blocks, up to {@code reveal-radius}, plus a 2-block shell
 *       around it (the walls you can see and a bit behind them). Stored as
 *       4×4×4 sub-cells. Rock between caves stays hidden, so a base behind a
 *       5+ block wall is no longer revealed just for being within 28 blocks.
 *       Out of budget (huge cavern) or an unreadable chunk: the old sphere
 *       applies there. When unsure, reveal.</li>
 *   <li><b>Line of sight</b>: the sweep marks the sub-cells along each ray and
 *       the one it stops in, padded by one sub-cell, up to 48 blocks. Farther
 *       out the rays are spread too thin for that, so whole sections.</li>
 *   <li><b>Grace</b>: anything revealed within the last
 *       {@code recently-revealed-ttl-seconds} (default 20), per sub-cell.</li>
 * </ol>
 * The look cone that used to reveal through rock is gone; the sight sweep
 * already covers what the player actually looks at.
 *
 * <p>Threads: fill and sight are computed on the player's own thread (region
 * thread on Folia) and published as immutable snapshots. {@link #isAirRevealed}
 * runs on Netty and only reads those snapshots, without allocating.
 */
public final class SmartFillReveal implements Listener {

    private final FPAntiFreeCam plugin;

    private volatile int revealRadius   = 28;
    private volatile int revealRadiusSq = 28 * 28;
    private volatile int nearRadius     = 6;
    private volatile int nearRadiusSq   = 6 * 6;

    /** Only used to size the look-ahead chunk refresh now (see maybeRefreshSmartFillProximity). */
    private volatile int lookDistance = 48;

    /** 0 disables the grace window entirely (instant revert). */
    private volatile long recentlyRevealedTtlMs = 20_000L;

    // ── Line-of-sight sweep ──────────────────────────────────────────────
    private volatile boolean sightEnabled = true;
    private volatile boolean sightFollowRenderDistance = true;
    private volatile int     sightMaxDistanceCap = 128;
    private volatile int     sightBudget = 60_000;

    /**
     * Re-sweep / re-fill at least this often even when standing still.
     * IMPROVEMENT: was 5 s. Every block change that can open air now marks
     * the fill and sweep dirty (see the events below), so this is only a
     * safety net and can run less often.
     */
    private static final long SIGHT_MAX_AGE_MS = 15_000L;
    /** Re-sweep early once the eye has moved this far (squared, in blocks). */
    private static final int  SIGHT_MOVE_THRESHOLD_SQ = 2 * 2;
    /** Re-sweep when the look direction turned more than ~30° and the last sweep ran out of budget. */
    private static final double SIGHT_TURN_COS = Math.cos(Math.toRadians(30));
    /** Rays mark sub-cells up to this distance, whole sections beyond it. */
    private static final double SIGHT_SUBCELL_DISTANCE = 48.0;

    // ── Connected-air flood fill ─────────────────────────────────────────
    /** Cells read per flood fill. Past this the rest of reveal-radius falls back to the sphere. */
    private static final int FILL_BUDGET = 40_000;
    /** Re-fill once the eye moved this far (squared, in blocks). */
    private static final int FILL_MOVE_THRESHOLD_SQ = 2 * 2;
    /** Same, for a fill that ran out of budget (whole sphere revealed). */
    private static final int SPHERE_MOVE_THRESHOLD_SQ = 8 * 8;
    /** Fills started per 5-tick pass, across all players. */
    private static final int MAX_FILLS_PER_PASS = 8;
    /** A fill another player in the same block may reuse while this young. */
    private static final long SHARE_MAX_AGE_MS = 1_000L;
    /** Chunks per axis in computeFill's top cache (reveal-radius 48 + 8 spans at most 9). */
    private static final int TOP_WINDOW = 10;
    private final java.util.concurrent.atomic.AtomicInteger fillsThisPass = new java.util.concurrent.atomic.AtomicInteger();

    /** Start of a 5-tick raycast pass (global / main thread). */
    public void newPass() { fillsThisPass.set(0); }

    /** Bit 63 tags a section key, so sub-cell and section keys can share one grace map. */
    private static final long SECTION_TAG = 1L << 63;

    /** One connected-air result. Immutable once published. */
    private static final class Fill {
        final LongSet cells;          // sub-cell keys: reached air + 2-block shell + near radius
        final LongSet sphereChunks;   // chunk columns the fill couldn't read: sphere applies there
        final boolean sphereAll;      // budget ran out: sphere applies everywhere
        final UUID worldId;
        final int ox, oy, oz;         // eye cell the fill started from
        final long computedAt;
        /** Feet cell (second seed, near radius). Only used to share fills. */
        int fx, fy, fz;

        Fill(LongSet cells, LongSet sphereChunks, boolean sphereAll, UUID worldId,
             int ox, int oy, int oz, long computedAt) {
            this.cells = cells; this.sphereChunks = sphereChunks; this.sphereAll = sphereAll;
            this.worldId = worldId; this.ox = ox; this.oy = oy; this.oz = oz;
            this.computedAt = computedAt;
        }
    }

    /** One line-of-sight sweep. Immutable once published. */
    private static final class Sight {
        final LongSet near;   // sub-cell keys, padded by one sub-cell
        final LongSet far;    // section keys (tagged) beyond SIGHT_SUBCELL_DISTANCE
        final UUID worldId;
        final int eyeX, eyeY, eyeZ;
        final double lx, ly, lz;
        final boolean budgetHit;
        final long computedAt;

        Sight(LongSet near, LongSet far, UUID worldId, int eyeX, int eyeY, int eyeZ,
              double lx, double ly, double lz, boolean budgetHit, long computedAt) {
            this.near = near; this.far = far; this.worldId = worldId;
            this.eyeX = eyeX; this.eyeY = eyeY; this.eyeZ = eyeZ;
            this.lx = lx; this.ly = ly; this.lz = lz;
            this.budgetHit = budgetHit; this.computedAt = computedAt;
        }
    }

    /** Per-player holder. Fields are replaced wholesale, never mutated in place. */
    private static final class State {
        volatile Fill fill;
        volatile Sight sight;
        /** Sub-cell / section key → last time it was really revealed. */
        volatile LongLongMap grace = LongLongMap.EMPTY;
        /** Oldest stamp in grace (under the State lock). */
        long graceOldest = Long.MAX_VALUE;
        /** A block near the last fill opened up (break, explosion, piston). */
        volatile boolean dirty;
        /** NEW: same for the sight sweep, which reaches much further. */
        volatile boolean sightDirty;
        /** Fill skipped by the per-pass cap; runs first next pass. */
        volatile boolean deferred;
        /** Bumped on every new fill or sight snapshot (proximity refresh trigger). */
        volatile int version;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public SmartFillReveal(FPAntiFreeCam plugin) {
        this.plugin = plugin;
        loadSettings();
    }

    public void loadSettings() {
        var cfg = plugin.getConfig();
        int r = Math.max(16, Math.min(48, cfg.getInt("protection.smart-fill.reveal-radius", 28)));
        revealRadius = r;
        revealRadiusSq = r * r;

        int n = Math.max(2, Math.min(16, cfg.getInt("protection.smart-fill.near-radius", 6)));
        nearRadius = Math.min(n, r);
        nearRadiusSq = nearRadius * nearRadius;

        lookDistance = Math.max(24, Math.min(64, cfg.getInt("protection.smart-fill.look-distance",
                cfg.getInt("protection.smart-fill.raycast-distance", 48))));

        int ttlSeconds = Math.max(0, Math.min(300,
                cfg.getInt("protection.smart-fill.recently-revealed-ttl-seconds", 20)));
        recentlyRevealedTtlMs = ttlSeconds * 1_000L;

        sightEnabled = cfg.getBoolean("protection.smart-fill.reveal-line-of-sight", true);
        sightFollowRenderDistance = cfg.getBoolean("protection.smart-fill.reveal-follow-render-distance", true);
        sightMaxDistanceCap = Math.max(32, Math.min(256,
                cfg.getInt("protection.smart-fill.reveal-max-distance-cap", 128)));
        sightBudget = Math.max(5_000, Math.min(500_000,
                cfg.getInt("protection.smart-fill.reveal-sight-budget", 60_000)));
        // Radii changed: every snapshot is stale. Until the next fill the sphere applies.
        states.clear();
    }

    public int getRevealRadius() { return revealRadius; }
    public int getLookDistance() { return lookDistance; }
    public boolean isSightEnabled() { return sightEnabled; }

    // ═════════════════════════════════════════════════════════════════════
    //  Netty-side decision
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Whether the block at (bX, bY, bZ) stays real for the player. Netty IO
     * thread (CHUNK_DATA / BLOCK_CHANGE / MULTI_BLOCK_CHANGE, sounds, tile
     * entities). Primitive key math and a few hash probes, no allocation.
     *
     * @param pBX,pBY,pBZ the player's current block position
     */
    public boolean isAirRevealed(int bX, int bY, int bZ, UUID playerId,
                                 int pBX, int pBY, int pBZ) {
        int dx = bX - pBX, dy = bY - pBY, dz = bZ - pBZ;
        int distSq = dx * dx + dy * dy + dz * dz;

        // 1. Near radius, through anything.
        if (distSq <= nearRadiusSq) return true;
        boolean inSphere = distSq <= revealRadiusSq;
        if (playerId == null) return inSphere;

        State s = states.get(playerId);
        Fill f = s != null ? s.fill : null;
        // No fill yet (join, teleport, reload): the old sphere until the first one.
        if (f == null) {
            if (inSphere) return true;
        } else {
            if (inSphere && (f.sphereAll || (!f.sphereChunks.isEmpty()
                    && f.sphereChunks.contains(columnKey(bX >> 4, bZ >> 4))))) return true;
            // 2. Connected air + shell.
            if (f.cells.contains(subKey(bX >> 2, bY >> 2, bZ >> 2))) return true;
        }
        if (s == null) return false;

        long sub = subKey(bX >> 2, bY >> 2, bZ >> 2);
        long sec = sectionKey(bX >> 4, bY >> 4, bZ >> 4);
        // 3. Line of sight.
        Sight sight = s.sight;
        if (sight != null && (sight.near.contains(sub) || sight.far.contains(sec))) return true;

        // 4. Grace window.
        long ttl = recentlyRevealedTtlMs;
        if (ttl <= 0) return false;
        LongLongMap g = s.grace;
        if (g.isEmpty()) return false;
        long now = System.currentTimeMillis();
        long t = g.get(sub);
        if (t != LongLongMap.MISSING && now - t <= ttl) return true;
        t = g.get(sec);
        return t != LongLongMap.MISSING && now - t <= ttl;
    }

    /**
     * NEW: true if every block of the 4×4×4 sub-cell (sx, sy, sz) is real for
     * the player right now, from the fill / sight snapshots alone. Used to
     * decide chunk re-sends: a masked sub-cell that is revealed now needs one,
     * and since the whole sub-cell is revealed the re-send won't mask it again,
     * so this can't cause a re-send loop. The near radius isn't checked here
     * (it's per block); the fill already contains it at sub-cell level.
     * Any thread.
     */
    public boolean isSubCellRevealed(UUID playerId, int sx, int sy, int sz, int pBX, int pBY, int pBZ) {
        State s = states.get(playerId);
        Fill f = s != null ? s.fill : null;
        long sub = subKey(sx, sy, sz);
        if (f == null || f.sphereAll || (!f.sphereChunks.isEmpty()
                && f.sphereChunks.contains(columnKey(sx >> 2, sz >> 2)))) {
            // Sphere: only if the whole sub-cell is well inside it, so a small
            // step back before the re-send arrives doesn't mask it again.
            if (farthestSq(sx, sy, sz, pBX, pBY, pBZ) <= sq(revealRadius - 2)) return true;
        }
        if (f != null && f.cells.contains(sub)) return true;
        if (s == null) return false;
        Sight sight = s.sight;
        return sight != null && (sight.near.contains(sub) || sight.far.contains(sectionKey(sx >> 2, sy >> 2, sz >> 2)));
    }

    /** Position check for tile entity stripping. */
    public boolean isPositionRevealed(UUID playerId, int bX, int bY, int bZ) {
        Player p = org.bukkit.Bukkit.getPlayer(playerId);
        if (p == null) return false;
        Location loc = p.getLocation();
        return isAirRevealed(bX, bY, bZ, playerId, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Past this distance (blocks, feet to section) nothing but the far
     * sight sweep can reveal a sub-cell: the fill, the sphere, the near
     * radius and the padded near sight all end well before it.
     */
    public int getFarOnlyDistance() {
        return Math.max(revealRadius + 12, (int) SIGHT_SUBCELL_DISTANCE + 12);
    }

    /** Same as isSubCellRevealed for a section past getFarOnlyDistance. */
    public boolean isSectionSightRevealed(UUID playerId, int cx, int secY, int cz) {
        State s = states.get(playerId);
        Sight sight = s != null ? s.sight : null;
        return sight != null && sight.far.contains(sectionKey(cx, secY, cz));
    }

    /** Bumped on every new fill or sight snapshot; lets the proximity task skip unchanged players. */
    public int getRevealVersion(UUID playerId) {
        State s = states.get(playerId);
        return s != null ? s.version : 0;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Connected-air flood fill (player's thread)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Called from the raycast task every 5 ticks on the player's own thread.
     * Re-runs the flood fill when the eye moved 2+ blocks, a block nearby
     * opened up, the world changed, or the last one is older than 5 s.
     */
    public void updateLosReveal(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!plugin.isSmartFillActiveFor(player)) return;
        if (PlatformUtil.isFolia() && !PlatformUtil.isOwnedByCurrentRegion(player)) return;
        UUID id = player.getUniqueId();
        State s = states.computeIfAbsent(id, k -> new State());
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        int ex = eye.getBlockX(), ey = eye.getBlockY(), ez = eye.getBlockZ();
        long now = System.currentTimeMillis();

        Fill prev = s.fill;
        if (prev != null && prev.worldId.equals(world.getUID()) && !s.dirty) {
            int mx = ex - prev.ox, my = ey - prev.oy, mz = ez - prev.oz;
            // IMPROVEMENT: a fill that ran out of budget reveals the whole
            // sphere around the live position, so moving doesn't change it.
            // It is kept until the player moved 8 blocks instead of 2.
            int moveSq = prev.sphereAll ? SPHERE_MOVE_THRESHOLD_SQ : FILL_MOVE_THRESHOLD_SQ;
            if (mx * mx + my * my + mz * mz < moveSq
                    && now - prev.computedAt < SIGHT_MAX_AGE_MS) return;
        }
        // IMPROVEMENT: at most MAX_FILLS_PER_PASS fills start per 5-tick pass,
        // so many players moving at once don't all fill in the same tick.
        // A player that had to wait goes first on the next pass (250 ms).
        if (!s.deferred && fillsThisPass.incrementAndGet() > MAX_FILLS_PER_PASS) {
            s.deferred = true;
            return;
        }
        s.deferred = false;
        s.dirty = false;
        Location feet = player.getLocation();
        Fill next;
        try {
            // NEW (follow-terrain): the highest masked spot nearby can be
            // inside a mountain, above ceiling-y.
            int ceilingY = plugin.maxRevealTopNear(world, ex, ez, revealRadius + 2);
            if (ey - revealRadius - 2 > ceilingY && feet.getBlockY() - nearRadius - 4 > ceilingY) {
                // IMPROVEMENT: nothing the fill can reach is ever masked up
                // here, so its result would be empty anyway. Same answer
                // without reading any blocks.
                next = new Fill(LongSet.EMPTY, LongSet.EMPTY, false, world.getUID(), ex, ey, ez, now);
            } else {
                int fx = feet.getBlockX(), fy = feet.getBlockY(), fz = feet.getBlockZ();
                next = sharedFill(s, world.getUID(), ex, ey, ez, fx, fy, fz, now);
                if (next == null) {
                    next = computeFill(world, ex, ey, ez, fx, fy, fz, now, ceilingY);
                    next.fx = fx; next.fy = fy; next.fz = fz;
                }
            }
        } catch (Exception e) {
            plugin.dbg("Smart Fill flood fill failed for " + player.getName() + ": " + e.getMessage());
            return;
        }
        synchronized (s) {
            s.fill = next;
            if (prev != null && prev.worldId.equals(world.getUID())) retire(s, prev.cells, next.cells, null, null, now);
            s.version++;
        }
    }

    /**
     * IMPROVEMENT: players standing in the same block (spawn, bases, mining
     * together) get the same flood fill, so a fresh one (< 1 s, nothing
     * opened since) is reused instead of read again. Fills never change
     * after they are built, so sharing them is safe. Exact block only, so
     * the result is identical.
     */
    private Fill sharedFill(State self, UUID wid, int ex, int ey, int ez, int fx, int fy, int fz, long now) {
        for (State o : states.values()) {
            if (o == self || o.dirty) continue;
            Fill f = o.fill;
            if (f == null || f.ox != ex || f.oy != ey || f.oz != ez) continue;
            if (f.fx != fx || f.fy != fy || f.fz != fz || f.cells.isEmpty()) continue;
            if (now - f.computedAt > SHARE_MAX_AGE_MS || !f.worldId.equals(wid)) continue;
            return f;
        }
        return null;
    }

    private Fill computeFill(World world, int ox, int oy, int oz, int px, int py, int pz, long now, int areaTop) {
        final int R = revealRadius, RS = R * R, D = 2 * R + 1;
        final int minY = world.getMinHeight(), maxY = world.getMaxHeight();
        final int ceilingY = plugin.getSmartFillCeilingY();
        // NEW (follow-terrain): per-column tops above ceiling-y. Null when off.
        final SmartFillEngine.CoverLookup cover = areaTop > ceilingY ? plugin.coverLookup(world) : null;
        final boolean folia = PlatformUtil.isFolia();

        // IMPROVEMENT: buffers are reused per thread (Folia: per region
        // thread) instead of allocating ~270 KB on every run.
        Buffers buf = buffers.get();
        int words = (D * D * D + 63) >>> 6;
        if (buf.visited.length < words) buf.visited = new long[words];
        else Arrays.fill(buf.visited, 0, words, 0L);
        long[] visited = buf.visited;
        int[] queue = buf.queue;
        int head = 0, tail = 0;
        int budget = FILL_BUDGET;
        boolean sphereAll = false;

        // Chunk readability cache: R ≤ 48 spans at most 7 chunks per axis.
        final int ccx0 = (ox - R) >> 4, ccz0 = (oz - R) >> 4;
        byte[] chunkState = new byte[9 * 9]; // 0 unknown, 1 readable, 2 not readable
        short[][] coverTops = cover != null ? new short[9 * 9][] : null;
        // IMPROVEMENT: on Paper, read block states straight from the chunk
        // (FastBlockReader, same as the sight rays) instead of World#getType.
        Object level = FastBlockReader.AVAILABLE ? FastBlockReader.level(world) : null;
        Object[] chunks = level != null ? new Object[9 * 9] : null;
        LongSet sphereChunks = null;

        // Seeds: eye and feet cell, whatever they are.
        int[] seeds = { 0, 0, 0, px - ox, py - oy, pz - oz };
        for (int i = 0; i < seeds.length; i += 3) {
            int dx = seeds[i], dy = seeds[i + 1], dz = seeds[i + 2];
            if (Math.abs(dx) > R || Math.abs(dy) > R || Math.abs(dz) > R) continue;
            int y = oy + dy;
            if (y < minY || y >= maxY) continue;
            int idx = ((dx + R) * D + (dy + R)) * D + (dz + R);
            if ((visited[idx >>> 6] & (1L << idx)) != 0) continue;
            visited[idx >>> 6] |= 1L << idx;
            queue[tail++] = pack(dx, dy, dz);
        }

        outer:
        while (head < tail) {
            int c = queue[head++];
            int cdx = unpackX(c), cdy = unpackY(c), cdz = unpackZ(c);
            for (int n = 0; n < 6; n++) {
                int dx = cdx, dy = cdy, dz = cdz;
                switch (n) {
                    case 0: dx++; break;
                    case 1: dx--; break;
                    case 2: dy++; break;
                    case 3: dy--; break;
                    case 4: dz++; break;
                    default: dz--; break;
                }
                if (dx * dx + dy * dy + dz * dz > RS) continue;
                int y = oy + dy;
                if (y < minY || y >= maxY) continue;
                // Nothing above the band is ever masked. Air up there is only
                // walked through below eye level (to follow a shaft or stairs
                // down), so open sky doesn't eat the budget. With
                // follow-terrain the band ends at each column's own top.
                int x = ox + dx, z = oz + dz;
                int ci = ((x >> 4) - ccx0) * 9 + ((z >> 4) - ccz0);
                if (y > ceilingY + 2 && y > oy) {
                    if (cover == null) continue;
                    // IMPROVEMENT: the chunk's tops are looked up once per fill, not per cell.
                    short[] t = coverTops[ci];
                    if (t == null) coverTops[ci] = t = cover.columnTops(x, z);
                    if (t.length == 0 || y > t[((z & 15) << 4) | (x & 15)] + 2) continue;
                }
                int idx = ((dx + R) * D + (dy + R)) * D + (dz + R);
                if ((visited[idx >>> 6] & (1L << idx)) != 0) continue;
                visited[idx >>> 6] |= 1L << idx;

                byte st = chunkState[ci];
                if (st == 0) {
                    boolean ok = world.isChunkLoaded(x >> 4, z >> 4)
                            && (!folia || PlatformUtil.isOwnedByCurrentRegion(new Location(world, x, y, z)));
                    if (ok && level != null) {
                        try {
                            chunks[ci] = FastBlockReader.chunk(level, x >> 4, z >> 4);
                            if (chunks[ci] == null) ok = false;
                        } catch (Throwable e) {
                            FastBlockReader.AVAILABLE = false;
                            level = null;
                        }
                    }
                    st = ok ? (byte) 1 : (byte) 2;
                    chunkState[ci] = st;
                    if (!ok) {
                        if (sphereChunks == null) sphereChunks = new LongSet(16);
                        sphereChunks.add(columnKey(x >> 4, z >> 4));
                    }
                }
                if (st == 2) continue;

                if (--budget < 0) { sphereAll = true; break outer; }
                boolean blocks;
                int known = -1;
                Object state = null;
                if (level != null) {
                    try {
                        state = FastBlockReader.state(chunks[ci], x, y, z);
                        known = FastBlockReader.cached(state);
                    } catch (Throwable e) {
                        FastBlockReader.AVAILABLE = false;
                        level = null;
                        state = null;
                    }
                }
                if (known >= 0) {
                    blocks = known == 1;
                } else {
                    try {
                        blocks = SightRaycaster.blocksSight(world.getType(x, y, z));
                        if (state != null) FastBlockReader.remember(state, blocks);
                    } catch (Exception e) {
                        blocks = false; // unsure: treat as open, reveals more
                    }
                }
                if (!blocks) queue[tail++] = pack(dx, dy, dz);
            }
        }

        // Reached cells → sub-cells, remembering per sub-cell which octant
        // neighbours the 2-block shell reaches into. A cell at local 0/1 on an
        // axis reaches the lower neighbour, at 2/3 the upper one.
        LongLongMap octants = new LongLongMap(Math.max(64, tail / 16));
        // IMPROVEMENT: per-chunk tops for this fill, looked up once per chunk
        // instead of once per reached cell (CoverLookup.slot was ~0.8% on a
        // weak host). Same values, so the result is identical.
        final int tb0x = (ox - R - 8) >> 4, tb0z = (oz - R - 8) >> 4;
        final int[] chunkTops = cover != null ? new int[TOP_WINDOW * TOP_WINDOW] : null;
        if (chunkTops != null) Arrays.fill(chunkTops, Integer.MIN_VALUE);
        for (int i = 0; i < tail; i++) {
            int c = queue[i];
            int x = ox + unpackX(c), y = oy + unpackY(c), z = oz + unpackZ(c);
            if (y - 2 > ceilingY && (cover == null
                    || y - 2 > chunkTopAround(cover, chunkTops, tb0x, tb0z, x, z))) continue; // never masked up there
            int o = ((x & 3) >> 1) | (((y & 3) >> 1) << 1) | (((z & 3) >> 1) << 2);
            octants.putOr(subKey(x >> 2, y >> 2, z >> 2), 1L << o);
        }
        LongSet cells = new LongSet(Math.max(64, octants.size() * 4));
        final int ceilSub = ceilingY >> 2;
        octants.forEach((key, mask) -> {
            int sx = subX(key), sy = subY(key), sz = subZ(key);
            for (int o = 0; o < 8; o++) {
                if ((mask & (1L << o)) == 0) continue;
                int ux = (o & 1) != 0 ? 1 : -1, uy = (o & 2) != 0 ? 1 : -1, uz = (o & 4) != 0 ? 1 : -1;
                for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int k = 0; k < 2; k++) {
                    int ty = sy + b * uy;
                    int tx = sx + a * ux, tz = sz + k * uz;
                    if (ty > ceilSub && (cover == null
                            || ty > chunkTop(cover, chunkTops, tb0x, tb0z, tx >> 2, tz >> 2) >> 2)) continue;
                    cells.add(subKey(tx, ty, tz));
                }
            }
        });

        // Near radius at sub-cell level (the per-block check in isAirRevealed
        // is live; this keeps it for the grace window and the re-send check).
        int N = nearRadius, NS = N * N;
        final int nearTopSub = Math.max(ceilingY, areaTop) >> 2;
        for (int sx = (px - N) >> 2; sx <= (px + N) >> 2; sx++) {
            for (int sy = (py - N) >> 2; sy <= Math.min((py + N) >> 2, nearTopSub); sy++) {
                for (int sz = (pz - N) >> 2; sz <= (pz + N) >> 2; sz++) {
                    if (nearestSq(sx, sy, sz, px, py, pz) <= NS) cells.add(subKey(sx, sy, sz));
                }
            }
        }

        return new Fill(cells, sphereChunks != null ? sphereChunks : LongSet.EMPTY, sphereAll,
                world.getUID(), ox, oy, oz, now);
    }

    /** Highest reveal top of chunk (cx, cz), from the fill's per-chunk cache. */
    private static int chunkTop(SmartFillEngine.CoverLookup cover, int[] tops, int bx, int bz, int cx, int cz) {
        int ix = cx - bx, iz = cz - bz;
        if (ix < 0 || iz < 0 || ix >= TOP_WINDOW || iz >= TOP_WINDOW) return cover.chunkTop(cx << 4, cz << 4);
        int i = ix * TOP_WINDOW + iz;
        int t = tops[i];
        if (t == Integer.MIN_VALUE) tops[i] = t = cover.chunkTop(cx << 4, cz << 4);
        return t;
    }

    /** Same as CoverLookup#chunkTopAround(x, z, 2), from the fill's per-chunk cache. */
    private static int chunkTopAround(SmartFillEngine.CoverLookup cover, int[] tops, int bx, int bz, int x, int z) {
        int t = Integer.MIN_VALUE;
        for (int cx = (x - 2) >> 4; cx <= (x + 2) >> 4; cx++) {
            for (int cz = (z - 2) >> 4; cz <= (z + 2) >> 4; cz++) {
                int m = chunkTop(cover, tops, bx, bz, cx, cz);
                if (m > t) t = m;
            }
        }
        return t;
    }

    /** Flood fill scratch space, one per thread. The queue is never kept past a run. */
    private static final class Buffers {
        long[] visited = new long[0];
        final int[] queue = new int[FILL_BUDGET + 2];
    }
    private final ThreadLocal<Buffers> buffers = ThreadLocal.withInitial(Buffers::new);

    private static int pack(int dx, int dy, int dz) {
        return ((dx + 128) << 16) | ((dy + 128) << 8) | (dz + 128);
    }
    private static int unpackX(int p) { return ((p >>> 16) & 0xFF) - 128; }
    private static int unpackY(int p) { return ((p >>> 8) & 0xFF) - 128; }
    private static int unpackZ(int p) { return (p & 0xFF) - 128; }

    // ═════════════════════════════════════════════════════════════════════
    //  Line-of-sight sweep (player's thread)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Sweeps evenly spread rays from the player's eye through see-through
     * blocks and records the sub-cells each ray passes through and stops in
     * (sections beyond 48 blocks). Rays are cast in order of how close they
     * are to the look direction, so if the budget runs out it's the rays
     * behind the player that get dropped; turning more than ~30° then
     * re-runs the sweep early.
     *
     * <p>Must run on the thread that owns the player's region.
     *
     * @return chunk column keys ({@code (cx << 32) | (cz & 0xFFFFFFFFL)})
     *         that gained sight cells; the caller filters them with
     *         {@code ChunkListener#shouldRefreshForReveal}. Empty if the
     *         sweep was skipped.
     */
    public Set<Long> computeLineOfSight(Player player) {
        if (!sightEnabled || player == null || !player.isOnline()) return Collections.emptySet();

        UUID id = player.getUniqueId();
        State s = states.computeIfAbsent(id, k -> new State());
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        int ex = eye.getBlockX(), ey = eye.getBlockY(), ez = eye.getBlockZ();
        Vector look = eye.getDirection();
        long now = System.currentTimeMillis();

        Sight prev = s.sight;
        if (prev != null && !prev.worldId.equals(world.getUID())) prev = null;
        if (prev != null) {
            int mx = ex - prev.eyeX, my = ey - prev.eyeY, mz = ez - prev.eyeZ;
            boolean turned = prev.budgetHit
                    && look.getX() * prev.lx + look.getY() * prev.ly + look.getZ() * prev.lz < SIGHT_TURN_COS;
            if (mx * mx + my * my + mz * mz < SIGHT_MOVE_THRESHOLD_SQ
                    && now - prev.computedAt < SIGHT_MAX_AGE_MS && !turned && !s.sightDirty) {
                return Collections.emptySet();
            }
        }
        s.sightDirty = false;

        int maxDist = sightMaxDistanceCap;
        if (sightFollowRenderDistance) maxDist = Math.min(maxDist, PlatformUtil.viewDistance(player) * 16);
        maxDist = Math.max(maxDist, revealRadius);

        double[] dirs = directionsFor(maxDist);
        int rayCount = dirs.length / 3;

        long[] order = new long[rayCount];
        for (int i = 0; i < rayCount; i++) {
            double dot = dirs[i * 3] * look.getX() + dirs[i * 3 + 1] * look.getY() + dirs[i * 3 + 2] * look.getZ();
            long rank = (long) ((1.0 - dot) * 1_000_000); // 0 = straight ahead, 2_000_000 = straight behind
            order[i] = (rank << 32) | i;
        }
        Arrays.sort(order);

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        final int ceilingY = plugin.getSmartFillCeilingY();
        // NEW (follow-terrain): rays keep going above ceiling-y while a
        // mountain nearby may still be masked. Flat areas take the old path.
        final int bandTop = plugin.maxRevealTopNear(world, ex, ez, maxDist);
        final SmartFillEngine.CoverLookup cover = bandTop > ceilingY ? plugin.coverLookup(world) : null;
        boolean folia = PlatformUtil.isFolia();
        double ox = eye.getX(), oy = eye.getY(), oz = eye.getZ();

        LongSet raw = new LongSet(2048);
        LongSet far = new LongSet(256);
        long[] last = { Long.MIN_VALUE, Long.MIN_VALUE };
        int[] budget = { sightBudget };
        boolean budgetHit = false;

        for (long packed : order) {
            int r = (int) packed;
            double dx = dirs[r * 3], dy = dirs[r * 3 + 1], dz = dirs[r * 3 + 2];
            boolean ascending = dy >= 0;

            int result = SightRaycaster.walk(world, minY, maxY, folia, ox, oy, oz, dx, dy, dz, maxDist, budget,
                    (x, y, z, type, blocks, t) -> {
                        if (y <= ceilingY || (cover != null && y <= cover.top(x, z))) {
                            // The cell a ray stops in is the surface the player sees: recorded too.
                            if (t <= SIGHT_SUBCELL_DISTANCE) {
                                long k = subKey(x >> 2, y >> 2, z >> 2);
                                if (k != last[0]) { raw.add(k); last[0] = k; }
                            } else {
                                long k = sectionKey(x >> 4, y >> 4, z >> 4);
                                if (k != last[1]) { far.add(k); last[1] = k; }
                            }
                            return !blocks;
                        }
                        if (blocks) return false;
                        // Above the camouflage band and not coming back down.
                        return !ascending || y <= bandTop;
                    });
            if (result == SightRaycaster.OUT_OF_BUDGET) { budgetHit = true; break; }
        }

        // Pad by one sub-cell: neighbouring rays are up to ~2 blocks apart at 48.
        LongSet near = new LongSet(Math.max(64, raw.size() * 8));
        final int ceilSub = ceilingY >> 2;
        raw.forEach(k -> {
            int sx = subX(k), sy = subY(k), sz = subZ(k);
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int c = -1; c <= 1; c++) {
                if (sy + b > ceilSub && (cover == null
                        || sy + b > cover.chunkTop((sx + a) << 2, (sz + c) << 2) >> 2)) continue;
                near.add(subKey(sx + a, sy + b, sz + c));
            }
        });

        Sight next = new Sight(near, far, world.getUID(), ex, ey, ez,
                look.getX(), look.getY(), look.getZ(), budgetHit, now);
        synchronized (s) {
            s.sight = next;
            if (prev != null) retire(s, prev.near, near, prev.far, far, now);
            s.version++;
        }

        // Columns that gained sight cells. The caller checks the masked
        // sub-cells in them, so this only has to be a superset.
        Set<Long> columns = new HashSet<>();
        final Sight before = prev;
        near.forEach(k -> {
            if (before != null && before.near.contains(k)) return;
            columns.add(columnKey(subX(k) >> 2, subZ(k) >> 2));
        });
        far.forEach(k -> {
            if (before != null && before.far.contains(k)) return;
            columns.add(columnKey(subX(k), subZ(k)));
        });
        return columns;
    }

    /** Drops a player's fill and sight results (teleport, world change, Smart Fill no longer active). */
    public void clearSight(UUID id) {
        states.remove(id);
    }

    private final Map<Integer, double[]> directionCache = new ConcurrentHashMap<>();

    /**
     * Evenly spread unit directions (Fibonacci sphere) as a flat xyz array.
     * The count scales with distance so neighbouring rays are at most ~13
     * blocks apart at the far end, i.e. no section is skipped between rays.
     */
    private double[] directionsFor(int maxDist) {
        return directionCache.computeIfAbsent(maxDist, d -> {
            int n = (int) Math.round(4 * Math.PI * d * d / 256.0 * 1.5);
            n = Math.max(256, Math.min(4096, n));
            double[] out = new double[n * 3];
            double golden = Math.PI * (3 - Math.sqrt(5));
            for (int i = 0; i < n; i++) {
                double yy = 1 - (i + 0.5) * 2.0 / n;
                double rad = Math.sqrt(1 - yy * yy);
                double theta = golden * i;
                out[i * 3] = Math.cos(theta) * rad;
                out[i * 3 + 1] = yy;
                out[i * 3 + 2] = Math.sin(theta) * rad;
            }
            return out;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Grace window
    // ═════════════════════════════════════════════════════════════════════

    /**
     * A snapshot was replaced: what it revealed and the new one doesn't goes
     * into the grace map with the current time (keys still revealed get
     * stamped when they leave). Rebuilt as a new map and published, so Netty
     * never sees a half-written one. Caller holds the State lock.
     */
    private void retire(State s, LongSet oldA, LongSet newA, LongSet oldB, LongSet newB, long now) {
        long ttl = recentlyRevealedTtlMs;
        if (ttl <= 0) { s.grace = LongLongMap.EMPTY; return; }
        LongLongMap old = s.grace;
        // IMPROVEMENT: most new snapshots reveal the same as the last one and
        // nothing in the grace map has expired yet. Then the map stays as it
        // is, instead of being rebuilt (and allocated) on every fill / sweep.
        // Netty reads it without a lock, so it can't be edited in place.
        boolean expired = !old.isEmpty() && now - s.graceOldest > ttl;
        if (!expired && !leaves(oldA, newA) && (oldB == null || !leaves(oldB, newB))) return;
        LongLongMap g = new LongLongMap(old.size() + 64);
        old.forEach((k, v) -> { if (now - v <= ttl) g.putMax(k, v); });
        oldA.forEach(k -> { if (!newA.contains(k)) g.putMax(k, now); });
        if (oldB != null) oldB.forEach(k -> { if (!newB.contains(k)) g.putMax(k, now); });
        long[] oldest = { Long.MAX_VALUE };
        g.forEach((k, v) -> { if (v < oldest[0]) oldest[0] = v; });
        s.graceOldest = oldest[0];
        s.grace = g.isEmpty() ? LongLongMap.EMPTY : g;
    }

    /** True if some key of oldSet is missing from newSet. */
    private static boolean leaves(LongSet oldSet, LongSet newSet) {
        boolean[] found = { false };
        oldSet.forEach(k -> { if (!found[0] && !newSet.contains(k)) found[0] = true; });
        return found[0];
    }

    /** Every ~60 s: drops expired grace entries. */
    public void pruneRecentlyRevealed() {
        long ttl = recentlyRevealedTtlMs;
        long now = System.currentTimeMillis();
        for (State s : states.values()) {
            synchronized (s) {
                LongLongMap old = s.grace;
                if (old.isEmpty()) continue;
                if (ttl <= 0) { s.grace = LongLongMap.EMPTY; continue; }
                LongLongMap g = new LongLongMap(old.size());
                old.forEach((k, v) -> { if (now - v <= ttl) g.putMax(k, v); });
                long[] oldest = { Long.MAX_VALUE };
                g.forEach((k, v) -> { if (v < oldest[0]) oldest[0] = v; });
                s.graceOldest = oldest[0];
                s.grace = g.isEmpty() ? LongLongMap.EMPTY : g;
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Block changes that open up air
    // ═════════════════════════════════════════════════════════════════════
    //
    // The fill only re-runs on movement. A wall broken by someone else (or an
    // explosion, or a piston) can join two caves, so fills near it are marked
    // dirty. Reads only the stored fill origin, never another player's
    // entity state, so this is safe on any Folia region thread.

    private void markDirtyNear(World world, int x, int y, int z) {
        if (world == null || states.isEmpty()) return;
        UUID wid = world.getUID();
        for (State s : states.values()) markDirty(s, wid, x, y, z);
    }

    /**
     * NEW: a block change that turns into air is about to reach this player
     * (any cause: WorldEdit, other plugins, fluids, physics). Netty-safe:
     * only reads the stored snapshots and sets flags.
     */
    public void onAirSent(UUID playerId, UUID worldId, int x, int y, int z) {
        State s = states.get(playerId);
        if (s != null) markDirty(s, worldId, x, y, z);
    }

    private void markDirty(State s, UUID wid, int x, int y, int z) {
        int reach = revealRadius + 2, reachSq = reach * reach;
        long sightReach = sightMaxDistanceCap + 2L, sightReachSq = sightReach * sightReach;
        Fill f = s.fill;
        if (f != null && f.worldId.equals(wid)) {
            int dx = x - f.ox, dy = y - f.oy, dz = z - f.oz;
            if (dx * dx + dy * dy + dz * dz <= reachSq) s.dirty = true;
        }
        // NEW: the sweep is now kept up to 15 s, so a wall opened in its
        // view must re-run it too.
        Sight g = s.sight;
        if (g != null && g.worldId.equals(wid)) {
            long dx = x - g.eyeX, dy = y - g.eyeY, dz = z - g.eyeZ;
            if (dx * dx + dy * dy + dz * dz <= sightReachSq) s.sightDirty = true;
        }
    }

    private void markDirty(List<Block> blocks) {
        if (blocks.isEmpty()) return;
        // BUGFIX: only the first block was used, so a big blast (TNT chain,
        // wither) could open air far from it without a re-fill. Now every
        // 16th block and the last one are marked.
        for (int i = 0; i < blocks.size(); i += 16) {
            Block b = blocks.get(i);
            markDirtyNear(b.getWorld(), b.getX(), b.getY(), b.getZ());
        }
        Block last = blocks.get(blocks.size() - 1);
        markDirtyNear(last.getWorld(), last.getX(), last.getY(), last.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        markDirtyNear(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        markDirty(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        markDirty(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Block b = event.getBlock();
        BlockFace d = event.getDirection();
        markDirtyNear(b.getWorld(), b.getX() + d.getModX(), b.getY() + d.getModY(), b.getZ() + d.getModZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        Block b = event.getBlock();
        markDirtyNear(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    // NEW: other ways a solid block turns into air or moves away.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(org.bukkit.event.block.BlockBurnEvent event) {
        Block b = event.getBlock();
        markDirtyNear(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(org.bukkit.event.block.BlockFadeEvent event) {
        Block b = event.getBlock();
        markDirtyNear(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    /** Falling sand / gravel, endermen, ravagers, silverfish. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        Block b = event.getBlock();
        if (!SightRaycaster.blocksSight(b.getType())) return; // only solid -> something else opens air
        markDirtyNear(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Lifecycle / stats
    // ═════════════════════════════════════════════════════════════════════

    public void cleanupPlayer(UUID id) {
        states.remove(id);
    }

    public void clearAll() {
        states.clear();
    }

    /** IMPROVEMENT: two short, readable rows for /fpac stats (reveal, sight). */
    public String[] statsRows() {
        int fillCells = 0, sphere = 0, graceEntries = 0, sightCells = 0, sightSections = 0, sightPlayers = 0;
        for (State s : states.values()) {
            Fill f = s.fill;
            if (f != null) {
                fillCells += f.cells.size();
                if (f.sphereAll) sphere++;
            }
            graceEntries += s.grace.size();
            Sight si = s.sight;
            if (si != null) {
                sightPlayers++;
                sightCells += si.near.size();
                sightSections += si.far.size();
            }
        }
        String reveal = "&f" + revealRadius + " &7radius &8· &f" + nearRadius + " &7near &8· &f"
                + states.size() + " &7players &8· &f" + fillCells + " &7sub-cells"
                + (sphere > 0 ? " &8· &e" + sphere + " &7sphere" : "")
                + " &8· &f" + graceEntries + " &7grace &8(" + (recentlyRevealedTtlMs / 1000) + "s)";
        String sight = sightEnabled
                ? "&f" + sightPlayers + " &7players &8· &f" + sightCells + " &7sub-cells &8· &f" + sightSections
                    + " &7sections &8· &7cap &f" + sightMaxDistanceCap + " &8· &7budget &f" + sightBudget
                : "&7off";
        return new String[]{ reveal, sight };
    }

    public String stats() {
        String[] r = statsRows();
        return r[0] + " | " + r[1];
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Keys
    // ═════════════════════════════════════════════════════════════════════
    //
    // Sub-cell key: x 24 bits (39..62), z 24 bits (15..38), y 15 bits (0..14),
    // bit 63 clear. Section key: same layout in section coords with bit 63 set.

    static long subKey(int sx, int sy, int sz) {
        return ((sx & 0xFFFFFFL) << 39) | ((sz & 0xFFFFFFL) << 15) | (sy & 0x7FFFL);
    }

    private static long sectionKey(int cx, int sy, int cz) {
        return SECTION_TAG | subKey(cx, sy, cz);
    }

    private static int subX(long k) { return ((int) ((k >>> 39) & 0xFFFFFFL) << 8) >> 8; }
    private static int subZ(long k) { return ((int) ((k >>> 15) & 0xFFFFFFL) << 8) >> 8; }
    private static int subY(long k) { return ((int) (k & 0x7FFFL) << 17) >> 17; }

    private static long columnKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Squared distance from (px, py, pz) to the nearest block of sub-cell (sx, sy, sz). */
    private static int nearestSq(int sx, int sy, int sz, int px, int py, int pz) {
        int x0 = sx << 2, y0 = sy << 2, z0 = sz << 2;
        int nx = Math.max(x0, Math.min(px, x0 + 3)) - px;
        int ny = Math.max(y0, Math.min(py, y0 + 3)) - py;
        int nz = Math.max(z0, Math.min(pz, z0 + 3)) - pz;
        return nx * nx + ny * ny + nz * nz;
    }

    /** Squared distance from (px, py, pz) to the farthest block of sub-cell (sx, sy, sz). */
    private static int farthestSq(int sx, int sy, int sz, int px, int py, int pz) {
        int x0 = sx << 2, y0 = sy << 2, z0 = sz << 2;
        int fx = Math.max(Math.abs(x0 - px), Math.abs(x0 + 3 - px));
        int fy = Math.max(Math.abs(y0 - py), Math.abs(y0 + 3 - py));
        int fz = Math.max(Math.abs(z0 - pz), Math.abs(z0 + 3 - pz));
        return fx * fx + fy * fy + fz * fz;
    }

    private static int sq(int v) { return v * v; }
}
