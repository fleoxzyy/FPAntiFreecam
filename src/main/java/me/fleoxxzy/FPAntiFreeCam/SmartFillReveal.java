package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic reveal calculations for Smart Fill cave camouflage (DonutSMP style).
 *
 * <h3>How it works:</h3>
 * <ul>
 *   <li><b>Wide Proximity Sphere (default 28 blocks)</b>: Any cave or air space
 *       within 28 blocks of the player's physical body is 100% natural and open.
 *       The player never sees fake blocks inside the cave chamber they are exploring.</li>
 *   <li><b>Look-Direction Cone (up to 48 blocks)</b>: Caves down open corridors
 *       in the direction the player is looking are revealed up to 48 blocks ahead.</li>
 *   <li><b>Distant Caves (> 28-48 blocks)</b>: All air pockets and caves outside
 *       this reveal zone are seamlessly filled with realistic stone / deepslate.
 *       FreeCam mods flying out of the cave hit solid rock!</li>
 *   <li><b>Short-lived "recently revealed" grace window</b>: a section you were
 *       just standing in/near stays revealed for a short window after you leave
 *       it, instead of reverting the instant you drift outside the reveal
 *       radius. Deliberate middle ground between permanent reveal (a freecam
 *       exploit - casually walk past a hidden base once, then switch to
 *       freecam later and it's unlocked forever) and instant reversion
 *       (visible pop-in / wasted re-fill work on quick back-and-forth
 *       flying). See {@link #isAirRevealed}.</li>
 *   <li><b>Underground Relog</b>: When relogging underground, protection is disarmed
 *       so all caves are fully visible without any artifacts.</li>
 *   <li><b>Descending from Surface</b>: When going down from the surface, the reveal
 *       sphere descends with the player, gradually opening up caves as they approach.</li>
 * </ul>
 */
public final class SmartFillReveal {

    private final FPAntiFreeCam plugin;

    // Wide reveal radius (default 28 blocks)
    private int revealRadius   = 28;
    private int revealRadiusSq = 28 * 28;

    // Look-direction extension distance (default 48 blocks)
    private int lookDistance   = 48;
    private int lookDistanceSq = 48 * 48;

    // Cached look vectors from the player's eye direction
    private final Map<UUID, Vector> playerLookVectors = new ConcurrentHashMap<>();

    /**
     * NEW: per-player, per-chunk-section "last genuinely revealed" timestamp.
     * Key granularity is one 16x16x16 chunk section (not per-block - that
     * would be far too much memory for a fast-moving player) packed into a
     * single long via {@link #sectionKey}. See the class javadoc's "recently
     * revealed" bullet for why this exists.
     */
    private final Map<UUID, ConcurrentHashMap<Long, Long>> recentlyRevealed = new ConcurrentHashMap<>();

    /** 0 disables the grace window entirely (old instant-revert behavior). */
    private long recentlyRevealedTtlMs = 45_000L;

    // ── Line-of-sight sweep ──────────────────────────────────────────────
    private boolean sightEnabled = true;
    private boolean sightFollowRenderDistance = true;
    private int     sightMaxDistanceCap = 128;
    private int     sightBudget = 60_000;

    /** Re-sweep at least this often even when standing still (catches mined-out walls, opened doors). */
    private static final long SIGHT_MAX_AGE_MS = 5_000L;
    /** Re-sweep early once the eye has moved this far (squared, in blocks). */
    private static final int  SIGHT_MOVE_THRESHOLD_SQ = 2 * 2;

    /**
     * Per-player set of section keys (see {@link #sectionKey}) currently in
     * line of sight. Replaced wholesale by each sweep with an immutable set,
     * so the Netty thread only ever reads a fully built snapshot.
     */
    private final Map<UUID, SightResult> sightResults = new ConcurrentHashMap<>();

    private static final class SightResult {
        final Set<Long> sections;
        final UUID worldId;
        final int eyeX, eyeY, eyeZ;
        final long computedAt;

        SightResult(Set<Long> sections, UUID worldId, int eyeX, int eyeY, int eyeZ, long computedAt) {
            this.sections = sections;
            this.worldId = worldId;
            this.eyeX = eyeX; this.eyeY = eyeY; this.eyeZ = eyeZ;
            this.computedAt = computedAt;
        }
    }

    /** Per-Material "does this block stop a sight ray" lookup, lazily filled. 0 = unknown, 1 = blocks, 2 = see-through. */
    private static final byte[] SIGHT_BLOCKING = new byte[Material.values().length];

    public SmartFillReveal(FPAntiFreeCam plugin) {
        this.plugin = plugin;
        loadSettings();
    }

    public void loadSettings() {
        var cfg = plugin.getConfig();
        revealRadius = cfg.getInt("protection.smart-fill.reveal-radius", 28);
        revealRadius = Math.max(16, Math.min(48, revealRadius));
        revealRadiusSq = revealRadius * revealRadius;

        lookDistance = cfg.getInt("protection.smart-fill.look-distance",
                cfg.getInt("protection.smart-fill.raycast-distance", 48));
        lookDistance = Math.max(24, Math.min(64, lookDistance));
        lookDistanceSq = lookDistance * lookDistance;

        int ttlSeconds = cfg.getInt("protection.smart-fill.recently-revealed-ttl-seconds", 45);
        ttlSeconds = Math.max(0, Math.min(300, ttlSeconds));
        recentlyRevealedTtlMs = ttlSeconds * 1_000L;
        if (recentlyRevealedTtlMs == 0) recentlyRevealed.clear();

        sightEnabled = cfg.getBoolean("protection.smart-fill.reveal-line-of-sight", true);
        sightFollowRenderDistance = cfg.getBoolean("protection.smart-fill.reveal-follow-render-distance", true);
        sightMaxDistanceCap = Math.max(32, Math.min(256,
                cfg.getInt("protection.smart-fill.reveal-max-distance-cap", 128)));
        sightBudget = Math.max(5_000, Math.min(500_000,
                cfg.getInt("protection.smart-fill.reveal-sight-budget", 60_000)));
        sightResults.clear();
    }

    public int getRevealRadius() { return revealRadius; }
    public int getLookDistance() { return lookDistance; }
    public boolean isSightEnabled() { return sightEnabled; }

    /**
     * Checks whether a block at (bX, bY, bZ) should remain real or be filled
     * with solid stone/deepslate.
     *
     * <p>Called on the Netty IO thread during CHUNK_DATA/BLOCK_CHANGE/
     * MULTI_BLOCK_CHANGE packet processing. The sphere/cone checks are ~5 CPU
     * instructions with zero allocations; the grace-window lookup adds one
     * ConcurrentHashMap get and, only when freshly revealed, one put.
     */
    public boolean isAirRevealed(int bX, int bY, int bZ, UUID playerId,
                                 int pBX, int pBY, int pBZ) {
        int dx = bX - pBX;
        int dy = bY - pBY;
        int dz = bZ - pBZ;
        int distSq = dx * dx + dy * dy + dz * dz;

        boolean revealedNow = false;

        // 1. Wide proximity sphere (default 28 blocks)
        // Any air inside the cave chamber the player is in is 100% real air!
        if (distSq <= revealRadiusSq) {
            revealedNow = true;
        } else if (distSq <= lookDistanceSq && playerId != null) {
            // 2. Look-direction cone (up to 48 blocks)
            Vector look = playerLookVectors.get(playerId);
            if (look != null) {
                double dot = (dx * look.getX() + dy * look.getY() + dz * look.getZ());
                if (dot > 0) {
                    double cosAngleSq = (dot * dot) / (double) distSq;
                    if (cosAngleSq >= 0.70) { // ~33 degrees cone from gaze center
                        revealedNow = true;
                    }
                }
            }
        }

        long key = sectionKey(bX, bY, bZ);

        // 3. Line-of-sight sweep: section is actually visible from the player's eye.
        if (!revealedNow && playerId != null) {
            SightResult sight = sightResults.get(playerId);
            if (sight != null && sight.sections.contains(key)) {
                revealedNow = true;
            }
        }

        if (recentlyRevealedTtlMs <= 0 || playerId == null) {
            return revealedNow; // grace window disabled, or no player context to key it on
        }

        if (revealedNow) {
            // Refresh the grace-window timestamp only on a REAL reveal, not
            // when served from the cache below - otherwise a section could
            // be kept alive indefinitely by repeated distant fly-bys without
            // ever actually being revisited.
            recentlyRevealed.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                    .put(key, System.currentTimeMillis());
            return true;
        }

        // 4. Short-lived grace window: was this section genuinely revealed
        // recently? If so, keep it revealed a little longer instead of
        // instantly reverting - smooths out quick back-and-forth flying
        // without leaving distant/never-visited areas permanently unlocked.
        ConcurrentHashMap<Long, Long> playerCache = recentlyRevealed.get(playerId);
        if (playerCache != null) {
            Long lastSeen = playerCache.get(key);
            if (lastSeen != null && System.currentTimeMillis() - lastSeen <= recentlyRevealedTtlMs) {
                return true;
            }
        }

        return false; // Distant/stale cave -> fill with solid stone/deepslate!
    }

    /** Position check for tile entity stripping. */
    public boolean isPositionRevealed(UUID playerId, int bX, int bY, int bZ) {
        Player p = org.bukkit.Bukkit.getPlayer(playerId);
        if (p == null) return false;
        org.bukkit.Location loc = p.getLocation();
        return isAirRevealed(bX, bY, bZ, playerId, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /** Updates the cached look vector for a player (called from raycast task every 5 ticks). */
    public void updateLosReveal(Player player) {
        if (player != null && player.isOnline()) {
            playerLookVectors.put(player.getUniqueId(), player.getEyeLocation().getDirection().normalize());
        }
    }

    /**
     * Sweeps evenly spread rays from the player's eye through see-through
     * blocks and records every chunk section a ray passes through, so a
     * cavern wider than the reveal sphere is revealed as far as the player
     * can actually see across it. Rays stop at the first opaque block, so
     * nothing behind a wall or around a corner is revealed. Rays are cast in
     * order of how close they are to the look direction, so if the step
     * budget runs out it's the rays behind the player that get dropped.
     *
     * <p>Must run on the thread that owns the player's region (reads world
     * blocks). Skips the sweep if the player hasn't moved since the last one
     * and it's still fresh.
     *
     * @return chunk column keys ({@code (cx << 32) | (cz & 0xFFFFFFFFL)})
     *         holding sections that just came into sight and that the client
     *         may still have masked; these need a chunk refresh. Empty if
     *         nothing changed or the sweep was skipped.
     */
    public Set<Long> computeLineOfSight(Player player) {
        if (!sightEnabled || player == null || !player.isOnline()) return Collections.emptySet();

        UUID id = player.getUniqueId();
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        int ex = eye.getBlockX(), ey = eye.getBlockY(), ez = eye.getBlockZ();
        long now = System.currentTimeMillis();

        SightResult prev = sightResults.get(id);
        if (prev != null && !prev.worldId.equals(world.getUID())) prev = null;
        if (prev != null) {
            int mx = ex - prev.eyeX, my = ey - prev.eyeY, mz = ez - prev.eyeZ;
            if (mx * mx + my * my + mz * mz < SIGHT_MOVE_THRESHOLD_SQ
                    && now - prev.computedAt < SIGHT_MAX_AGE_MS) {
                return Collections.emptySet();
            }
        }

        int maxDist = sightMaxDistanceCap;
        if (sightFollowRenderDistance) maxDist = Math.min(maxDist, player.getViewDistance() * 16);
        maxDist = Math.max(maxDist, revealRadius);

        double[] dirs = directionsFor(maxDist);
        int rayCount = dirs.length / 3;

        // Order rays by closeness to the look direction (best first).
        Vector look = eye.getDirection();
        long[] order = new long[rayCount];
        for (int i = 0; i < rayCount; i++) {
            double dot = dirs[i * 3] * look.getX() + dirs[i * 3 + 1] * look.getY() + dirs[i * 3 + 2] * look.getZ();
            long rank = (long) ((1.0 - dot) * 1_000_000); // 0 = straight ahead, 2_000_000 = straight behind
            order[i] = (rank << 32) | i;
        }
        Arrays.sort(order);

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int ceilingY = plugin.getSmartFillCeilingY();
        boolean folia = PlatformUtil.isFolia();
        double ox = eye.getX(), oy = eye.getY(), oz = eye.getZ();

        Set<Long> sections = new HashSet<>(1024);
        int budget = sightBudget;

        rays:
        for (long packed : order) {
            int r = (int) packed;
            double dx = dirs[r * 3], dy = dirs[r * 3 + 1], dz = dirs[r * 3 + 2];

            int x = ex, y = ey, z = ez;
            int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
            double tDeltaX = dx != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
            double tDeltaY = dy != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
            double tDeltaZ = dz != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;
            double tMaxX = dx != 0 ? (dx > 0 ? (x + 1 - ox) : (ox - x)) * tDeltaX : Double.POSITIVE_INFINITY;
            double tMaxY = dy != 0 ? (dy > 0 ? (y + 1 - oy) : (oy - y)) * tDeltaY : Double.POSITIVE_INFINITY;
            double tMaxZ = dz != 0 ? (dz > 0 ? (z + 1 - oz) : (oz - z)) * tDeltaZ : Double.POSITIVE_INFINITY;

            int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;

            while (true) {
                if (--budget < 0) break rays;
                if (y < minY || y >= maxY) break;

                int cx = x >> 4, cz = z >> 4;
                if (cx != lastCx || cz != lastCz) {
                    // Never trigger a chunk load, and on Folia never read another region's blocks.
                    if (!world.isChunkLoaded(cx, cz)) break;
                    if (folia && !PlatformUtil.isOwnedByCurrentRegion(new Location(world, cx << 4, y, cz << 4))) break;
                    lastCx = cx;
                    lastCz = cz;
                }

                Material m;
                try {
                    m = world.getType(x, y, z);
                } catch (Exception e) {
                    break;
                }
                if (blocksSight(m)) break;

                if (y <= ceilingY) {
                    sections.add(sectionKey(x, y, z));
                } else if (dy >= 0) {
                    break; // above the camouflage band and not coming back down
                }

                double t;
                if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                    x += stepX; t = tMaxX; tMaxX += tDeltaX;
                } else if (tMaxY < tMaxZ) {
                    y += stepY; t = tMaxY; tMaxY += tDeltaY;
                } else {
                    z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ;
                }
                if (t > maxDist) break;
            }
        }

        sightResults.put(id, new SightResult(Collections.unmodifiableSet(sections),
                world.getUID(), ex, ey, ez, now));

        // Sections that just came into sight, minus ones the client already
        // has real blocks for: inside the reveal sphere, or within the grace window.
        Set<Long> columns = new HashSet<>();
        ConcurrentHashMap<Long, Long> grace = recentlyRevealed.get(id);
        int insideSphere = revealRadius - 14; // 14 ≈ half a section's diagonal
        int insideSphereSq = insideSphere > 0 ? insideSphere * insideSphere : -1;
        for (Long s : sections) {
            if (prev != null && prev.sections.contains(s)) continue;
            if (grace != null) {
                Long seen = grace.get(s);
                if (seen != null && now - seen <= recentlyRevealedTtlMs) continue;
            }
            int cx = (int) (s >>> 42) << 11 >> 11;            // sign-extend 21-bit fields
            int cz = (int) ((s >>> 21) & 0x1FFFFFL) << 11 >> 11;
            int sy = (int) (s & 0x1FFFFFL) << 11 >> 11;
            int qx = (cx << 4) + 8 - ex, qy = (sy << 4) + 8 - ey, qz = (cz << 4) + 8 - ez;
            if (qx * qx + qy * qy + qz * qz <= insideSphereSq) continue;
            columns.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
        }
        return columns;
    }

    /** Drops a player's line-of-sight result (world change, Smart Fill no longer active). */
    public void clearSight(UUID id) {
        sightResults.remove(id);
    }

    private static boolean blocksSight(Material m) {
        if (m == null) return false;
        int ord = m.ordinal();
        byte cached = SIGHT_BLOCKING[ord];
        if (cached == 0) {
            boolean blocks;
            try {
                blocks = m.isOccluding();
            } catch (Exception e) {
                blocks = true;
            }
            cached = blocks ? (byte) 1 : (byte) 2;
            SIGHT_BLOCKING[ord] = cached;
        }
        return cached == 1;
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

    /**
     * NEW: periodic cleanup for the "recently revealed" grace-window cache -
     * drops entries older than the TTL and empty per-player maps. Call every
     * ~60s from the same background task that already prunes the entity Y
     * cache and EntityHider's stale pairs, so this doesn't grow unbounded on
     * a server with lots of active players moving through caves.
     */
    public void pruneRecentlyRevealed() {
        if (recentlyRevealedTtlMs <= 0 || recentlyRevealed.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, ConcurrentHashMap<Long, Long>> entry : recentlyRevealed.entrySet()) {
            ConcurrentHashMap<Long, Long> map = entry.getValue();
            map.entrySet().removeIf(e -> now - e.getValue() > recentlyRevealedTtlMs);
            if (map.isEmpty()) recentlyRevealed.remove(entry.getKey(), map);
        }
    }

    public void cleanupPlayer(UUID id) {
        playerLookVectors.remove(id);
        recentlyRevealed.remove(id);
        sightResults.remove(id);
    }

    public void clearAll() {
        playerLookVectors.clear();
        recentlyRevealed.clear();
        sightResults.clear();
    }

    public String stats() {
        int graceEntries = 0;
        for (ConcurrentHashMap<Long, Long> m : recentlyRevealed.values()) graceEntries += m.size();
        int sightSections = 0;
        for (SightResult r : sightResults.values()) sightSections += r.sections.size();
        return "radius=" + revealRadius + " lookDistance=" + lookDistance
                + " activePlayers=" + playerLookVectors.size()
                + " graceTtl=" + (recentlyRevealedTtlMs / 1000) + "s"
                + " graceEntries=" + graceEntries
                + " sight=" + (sightEnabled
                        ? "cap " + sightMaxDistanceCap + " budget " + sightBudget
                          + " players " + sightResults.size() + " sections " + sightSections
                        : "off");
    }

    /** Packs a block position into its containing chunk-section's key (one entry per 16x16x16 cell). */
    private static long sectionKey(int bX, int bY, int bZ) {
        long cx = (bX >> 4) & 0x1FFFFFL;
        long cz = (bZ >> 4) & 0x1FFFFFL;
        long sy = (bY >> 4) & 0x1FFFFFL;
        return (cx << 42) | (cz << 21) | sy;
    }
}
