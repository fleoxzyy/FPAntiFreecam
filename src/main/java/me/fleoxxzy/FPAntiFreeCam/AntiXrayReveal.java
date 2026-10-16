package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import org.bukkit.Bukkit;
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
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NEW: line-of-sight reveal side of Anti-Xray.
 *
 * <p>Every 5 ticks, on the player's own region thread, casts rays from every
 * camera the client could be using (first person, F5 back, F5 front, the
 * third-person ones clipped against walls like the vanilla camera) to the
 * exposed faces of the ores {@link AntiXrayEngine} hid. Ores that any camera
 * can see are added to the player's sticky revealed set and sent real with
 * BLOCK_CHANGE / MULTI_BLOCK_CHANGE grouped per section, never with a chunk
 * re-send.
 *
 * <p>"Zero false positives" is the rule throughout: anything we can't decide
 * (a ray into an unloaded chunk or another Folia region) counts as visible.
 *
 * <p>Also listens for block breaks / explosions / pistons / fluid flow and
 * re-checks the ores around them straight away, so mining into a wall never
 * shows fake stone that turns out to be an ore.
 */
public final class AntiXrayReveal implements Listener {

    private final FPAntiFreeCam plugin;
    private final AntiXrayEngine engine;

    /** F5 camera distance (vanilla third-person distance). */
    private static final double CAMERA_DISTANCE = 4.0;
    /** How far a clipped camera is pulled back in front of the wall it hit. */
    private static final double CAMERA_CLIP_MARGIN = 0.2;
    private static final double SKIP_MOVE_SQ = 0.5 * 0.5;
    private static final double SKIP_TURN_COS = Math.cos(Math.toRadians(10));
    /** Block events re-check ores this many blocks around the changed block. */
    private static final int EVENT_RADIUS = 2;
    /** Corner sample points are pulled this far toward the face center. */
    private static final double CORNER_INSET = 0.15;
    /** Sample points sit this far inside the ore so the ray always enters its cell. */
    private static final double FACE_INSET = 0.01;
    private static final int MAX_CANDIDATES = 1 << 20;

    private static final int VISIBLE = 0, HIDDEN = 1, NO_BUDGET = 2;

    public AntiXrayReveal(FPAntiFreeCam plugin, AntiXrayEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Periodic visibility check (player's region thread)
    // ═════════════════════════════════════════════════════════════════════

    /** Called every 5 ticks on the thread that owns the player. */
    public void check(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!engine.isActiveFor(player)) {
            // Feature turned off by a reload, world disabled, or the player
            // gained the xray bypass: give back every ore still faked.
            release(player);
            return;
        }
        AntiXrayEngine.PlayerXray px = engine.state(player.getUniqueId());
        World world = player.getWorld();
        AntiXrayEngine.WorldMeta meta = engine.meta(world);
        boolean folia = PlatformUtil.isFolia();

        Location eyeLoc = player.getEyeLocation();
        Vector look = eyeLoc.getDirection();
        double ex = eyeLoc.getX(), ey = eyeLoc.getY(), ez = eyeLoc.getZ();
        double[] cams = cameras(world, meta, folia, ex, ey, ez, look);

        px.ex = ex; px.ey = ey; px.ez = ez;
        px.worldId = world.getUID();
        px.cams = cams;
        px.hasEye = true;

        int gen = px.gen.get();
        if (!px.incomplete && gen == px.lastGen && px.lastCams != null
                && camerasClose(px.lastCams, cams)
                && look.getX() * px.lastLookX + look.getY() * px.lastLookY + look.getZ() * px.lastLookZ >= SKIP_TURN_COS) {
            return;
        }
        px.lastCams = cams;
        px.lastLookX = look.getX(); px.lastLookY = look.getY(); px.lastLookZ = look.getZ();
        px.lastGen = gen;

        pruneForgotten(px, meta, ex, ey, ez);

        int maxDist = Math.min(engine.getMaxRayDistance(), Math.max(2, PlatformUtil.viewDistance(player)) * 16);
        int always = engine.getAlwaysRevealRadius();
        double maxSq = (double) maxDist * maxDist;
        double alwaysSq = (double) always * always;

        // ── Gather unrevealed exposed ores in range, reveal the close ones ──
        List<int[]> reveals = new ArrayList<>();
        int[] cx_ = new int[256], cy_ = new int[256], cz_ = new int[256], cid = new int[256];
        int[] n = { 0 };
        long[] order = new long[256];
        int chunkReach = (maxDist >> 4) + 1;
        int pcx = floor(ex) >> 4, pcz = floor(ez) >> 4;

        for (AntiXrayEngine.ChunkOres co : px.chunks.values()) {
            if (Math.abs(co.cx - pcx) > chunkReach || Math.abs(co.cz - pcz) > chunkReach) continue;
            long ck = AntiXrayEngine.columnKey(co.cx, co.cz);
            Set<Integer> rev = px.revealed.get(ck);
            final int[][] arrs = { cx_, cy_, cz_, cid };
            final long[][] ord = { order };
            co.forEachExposed((li, realId) -> {
                if (rev != null && rev.contains(li)) return;
                int x = co.worldX(li), y = co.worldY(li), z = co.worldZ(li);
                double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 <= alwaysSq) {
                    px.reveal(ck, li);
                    reveals.add(new int[]{ x, y, z, realId });
                    return;
                }
                if (d2 > maxSq || n[0] >= MAX_CANDIDATES) return;
                int i = n[0]++;
                if (i == arrs[0].length) {
                    int cap = i * 2;
                    arrs[0] = Arrays.copyOf(arrs[0], cap);
                    arrs[1] = Arrays.copyOf(arrs[1], cap);
                    arrs[2] = Arrays.copyOf(arrs[2], cap);
                    arrs[3] = Arrays.copyOf(arrs[3], cap);
                    ord[0] = Arrays.copyOf(ord[0], cap);
                }
                arrs[0][i] = x; arrs[1][i] = y; arrs[2][i] = z; arrs[3][i] = realId;
                // Nearest first, so a budget cut drops far ores rather than close ones.
                ord[0][i] = ((long) d2 << 20) | i;
            });
            cx_ = arrs[0]; cy_ = arrs[1]; cz_ = arrs[2]; cid = arrs[3]; order = ord[0];
        }

        // ── Budgeted raycasts ──
        int budgetTotal = engine.getRayBudget();
        int[] budget = { budgetTotal };
        int revealCap = maxRevealsPerCheck(player);
        boolean incomplete = false;
        Arrays.sort(order, 0, n[0]);
        for (int k = 0; k < n[0]; k++) {
            if (reveals.size() >= revealCap) { incomplete = true; break; }
            int i = (int) (order[k] & 0xFFFFF);
            int x = cx_[i], y = cy_[i], z = cz_[i];
            int r = visibility(world, meta, folia, cams, x, y, z, null, budget);
            if (r == NO_BUDGET) { incomplete = true; break; }
            if (r == VISIBLE) {
                px.reveal(AntiXrayEngine.columnKey(x >> 4, z >> 4), AntiXrayEngine.localIndex(x, y, z, meta.minY));
                reveals.add(new int[]{ x, y, z, cid[i] });
            }
        }
        px.incomplete = incomplete;

        long used = budgetTotal - Math.max(0, budget[0]);
        engine.raySteps.addAndGet(used);
        engine.rayChecks.incrementAndGet();
        if (!reveals.isEmpty()) {
            int packets = send(player, reveals);
            engine.oresRevealed.addAndGet(reveals.size());
            plugin.dbg("Anti-Xray reveal " + player.getName() + ": " + reveals.size() + " ores in " + packets
                    + " block-change packets (candidates=" + n[0] + " steps=" + used
                    + (incomplete ? " incomplete" : "") + ")");
        }
    }

    private int maxRevealsPerCheck(Player player) {
        BedrockSupport b = plugin.getBedrockSupport();
        return b != null ? b.maxXrayRevealsPerCheck(player, Integer.MAX_VALUE) : Integer.MAX_VALUE;
    }

    /**
     * Sends the real block for every ore this player was sent as fake and
     * drops their state. Uses only recorded packet data, so it never reads
     * the world and needs no chunk re-send.
     */
    public void release(Player player) {
        AntiXrayEngine.PlayerXray px = engine.takeState(player.getUniqueId());
        if (px == null) return;
        World world = player.getWorld();
        if (px.worldId != null && !px.worldId.equals(world.getUID())) return; // client already dropped those chunks
        List<int[]> reveals = new ArrayList<>();
        for (Map.Entry<Long, AntiXrayEngine.ChunkOres> e : px.chunks.entrySet()) {
            AntiXrayEngine.ChunkOres co = e.getValue();
            Set<Integer> rev = px.revealed.get(e.getKey());
            co.forEachLive((li, realId) -> {
                if (rev != null && rev.contains(li)) return;
                reveals.add(new int[]{ co.worldX(li), co.worldY(li), co.worldZ(li), realId });
            });
        }
        if (reveals.isEmpty()) return;
        int packets = send(player, reveals);
        plugin.dbg("Anti-Xray released " + reveals.size() + " ores for " + player.getName()
                + " in " + packets + " block-change packets");
    }

    /**
     * The eye, plus (F5-aware) a camera 4 blocks behind and in front of it.
     * The third-person ones are pulled back toward the eye if a solid block
     * is in the way, like the vanilla camera does. Otherwise a wall behind
     * the player would put a camera inside the rock, where it could see
     * caves the player can't.
     */
    private double[] cameras(World world, AntiXrayEngine.WorldMeta meta, boolean folia,
                             double ex, double ey, double ez, Vector look) {
        if (!engine.isF5Aware()) return new double[]{ ex, ey, ez };
        double lx = look.getX(), ly = look.getY(), lz = look.getZ();
        double back = clip(world, meta, folia, ex, ey, ez, -lx, -ly, -lz);
        double front = clip(world, meta, folia, ex, ey, ez, lx, ly, lz);
        return new double[]{
                ex, ey, ez,
                ex - lx * back, ey - ly * back, ez - lz * back,
                ex + lx * front, ey + ly * front, ez + lz * front
        };
    }

    private double clip(World world, AntiXrayEngine.WorldMeta meta, boolean folia,
                        double ox, double oy, double oz, double dx, double dy, double dz) {
        double[] hit = { -1 };
        int[] budget = { 64 };
        int r = SightRaycaster.walk(world, meta.minY, meta.maxY, folia, ox, oy, oz, dx, dy, dz,
                CAMERA_DISTANCE, budget, (x, y, z, type, blocks, t) -> {
                    if (!blocks || t <= 0) return true; // the eye's own cell never clips
                    hit[0] = t;
                    return false;
                });
        if (r == SightRaycaster.HIT_BLOCK && hit[0] >= 0) return Math.max(0, hit[0] - CAMERA_CLIP_MARGIN);
        return CAMERA_DISTANCE; // open air, or above/below the world (also open air)
    }

    private static boolean camerasClose(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i += 3) {
            double dx = a[i] - b[i], dy = a[i + 1] - b[i + 1], dz = a[i + 2] - b[i + 2];
            if (dx * dx + dy * dy + dz * dz >= SKIP_MOVE_SQ) return false;
        }
        return true;
    }

    /**
     * Drops reveals farther than forget-distance. The client keeps showing
     * the real block; this only means a later chunk re-send may mask it again.
     */
    private void pruneForgotten(AntiXrayEngine.PlayerXray px, AntiXrayEngine.WorldMeta meta,
                                double ex, double ey, double ez) {
        double f = engine.getForgetDistance();
        double fSq = f * f;
        for (Iterator<Map.Entry<Long, Set<Integer>>> it = px.revealed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Set<Integer>> e = it.next();
            long ck = e.getKey();
            int cx = (int) (ck >> 32), cz = (int) ck;
            Set<Integer> set = e.getValue();
            for (Iterator<Integer> si = set.iterator(); si.hasNext(); ) {
                int li = si.next();
                double y = meta.minY + (li >>> 8) + 0.5 - ey;
                double x = ((cx << 4) | (li & 15)) + 0.5 - ex;
                double z = ((cz << 4) | ((li >>> 4) & 15)) + 0.5 - ez;
                if (x * x + y * y + z * z > fSq) si.remove();
            }
            if (set.isEmpty()) it.remove();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Ray tests
    // ═════════════════════════════════════════════════════════════════════

    private static final int[][] FACES = {
            { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 }
    };

    /**
     * Tests whether any camera can see any face of the ore at (x, y, z).
     * Only faces turned toward a camera are tested. Each face is sampled at its
     * center and four inset corners, so ores seen at grazing angles still count.
     *
     * @param ignore packed positions treated as see-through (blocks that are
     *               being broken right now), or null
     */
    private int visibility(World world, AntiXrayEngine.WorldMeta meta, boolean folia, double[] cams,
                           int x, int y, int z, Set<Long> ignore, int[] budget) {
        for (int c = 0; c < cams.length; c += 3) {
            double cx = cams[c], cy = cams[c + 1], cz = cams[c + 2];
            if (floor(cx) == x && floor(cy) == y && floor(cz) == z) return VISIBLE; // camera inside the ore
            for (int[] f : FACES) {
                // Plane of this face; the camera must be on its outer side.
                if (f[0] == 1 && cx <= x + 1) continue;
                if (f[0] == -1 && cx >= x) continue;
                if (f[1] == 1 && cy <= y + 1) continue;
                if (f[1] == -1 && cy >= y) continue;
                if (f[2] == 1 && cz <= z + 1) continue;
                if (f[2] == -1 && cz >= z) continue;
                // PERF: a face covered by a solid block can never be seen, so
                // skip its five rays. Most exposed ores have only one open face.
                if (!faceOpen(world, folia, x + f[0], y + f[1], z + f[2], meta, ignore)) continue;

                for (int s = 0; s < 5; s++) {
                    double[] p = samplePoint(x, y, z, f, s);
                    int r = ray(world, meta, folia, cx, cy, cz, p[0], p[1], p[2], x, y, z, ignore, budget);
                    if (r != HIDDEN) return r;
                }
            }
        }
        return HIDDEN;
    }

    /** True unless the neighbour is known to stop sight. Unreadable neighbours count as open. */
    private static boolean faceOpen(World world, boolean folia, int x, int y, int z,
                                    AntiXrayEngine.WorldMeta meta, Set<Long> ignore) {
        if (y < meta.minY || y >= meta.maxY) return true;
        if (ignore != null && ignore.contains(pack(x, y, z))) return true;
        try {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return true;
            if (folia && !PlatformUtil.isOwnedByCurrentRegion(new Location(world, x, y, z))) return true;
            return !SightRaycaster.blocksSight(world.getType(x, y, z));
        } catch (Exception e) {
            return true;
        }
    }

    /** s = 0: face center, 1..4: corners pulled CORNER_INSET toward the center. All FACE_INSET inside the block. */
    private static double[] samplePoint(int x, int y, int z, int[] f, int s) {
        double u = 0.5, v = 0.5;
        if (s > 0) {
            u = (s & 1) != 0 ? CORNER_INSET : 1 - CORNER_INSET;
            v = (s & 2) != 0 ? CORNER_INSET : 1 - CORNER_INSET;
        }
        double px, py, pz;
        if (f[0] != 0) {
            px = f[0] > 0 ? 1 - FACE_INSET : FACE_INSET; py = u; pz = v;
        } else if (f[1] != 0) {
            py = f[1] > 0 ? 1 - FACE_INSET : FACE_INSET; px = u; pz = v;
        } else {
            pz = f[2] > 0 ? 1 - FACE_INSET : FACE_INSET; px = u; py = v;
        }
        return new double[]{ x + px, y + py, z + pz };
    }

    private int ray(World world, AntiXrayEngine.WorldMeta meta, boolean folia,
                    double ox, double oy, double oz, double tx, double ty, double tz,
                    int bx, int by, int bz, Set<Long> ignore, int[] budget) {
        double dx = tx - ox, dy = ty - oy, dz = tz - oz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return VISIBLE;
        dx /= len; dy /= len; dz /= len;

        // A camera above the build limit or below the world looks through
        // open air until the ray enters the world. Start the walk there,
        // otherwise the height guard would read it as "unsure" and reveal.
        double top = meta.maxY - 1e-4, bottom = meta.minY + 1e-4;
        if (oy > top || oy < bottom) {
            double edge = oy > top ? top : bottom;
            if (dy == 0 || (oy > top) == (dy > 0)) return HIDDEN; // moving away from the world
            double t = (edge - oy) / dy;
            if (t >= len) return HIDDEN;
            ox += dx * t; oy = edge; oz += dz * t;
            len -= t;
        }

        boolean[] reached = { false };
        int r = SightRaycaster.walk(world, meta.minY, meta.maxY, folia, ox, oy, oz, dx, dy, dz,
                len + 0.1, budget, (x, y, z, type, blocks, t) -> {
                    if (x == bx && y == by && z == bz) { reached[0] = true; return false; }
                    if (!blocks) return true;
                    if (t <= 0) return true; // the camera's own cell (eye inside a block)
                    return ignore != null && ignore.contains(pack(x, y, z));
                });
        if (reached[0]) return VISIBLE;
        if (r == SightRaycaster.OUT_OF_BUDGET) return NO_BUDGET;
        // Unloaded chunk or another Folia region in between: we can't tell,
        // and a false "hidden" is the one mistake this feature must not make.
        if (r == SightRaycaster.UNREADABLE) return VISIBLE;
        return HIDDEN;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Reveal packets
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Sends the real blocks, one BLOCK_CHANGE for a lone block or one
     * MULTI_BLOCK_CHANGE per section otherwise. Positions are already in the
     * revealed set, so ChunkListener lets these packets through untouched.
     * Returns the number of packets sent.
     */
    private int send(Player player, List<int[]> reveals) {
        Map<Long, List<int[]>> bySection = new HashMap<>();
        for (int[] r : reveals) {
            long key = (((long) (r[0] >> 4) & 0x3FFFFFL) << 42) | (((long) (r[2] >> 4) & 0x3FFFFFL) << 20)
                    | ((long) (r[1] >> 4) & 0xFFFFFL);
            bySection.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        int packets = 0;
        for (List<int[]> list : bySection.values()) {
            try {
                if (list.size() == 1) {
                    int[] r = list.get(0);
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                            new WrapperPlayServerBlockChange(new Vector3i(r[0], r[1], r[2]), r[3]));
                } else {
                    int[] first = list.get(0);
                    WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks =
                            new WrapperPlayServerMultiBlockChange.EncodedBlock[list.size()];
                    for (int i = 0; i < blocks.length; i++) {
                        int[] r = list.get(i);
                        blocks[i] = new WrapperPlayServerMultiBlockChange.EncodedBlock(r[3], r[0], r[1], r[2]);
                    }
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                            new WrapperPlayServerMultiBlockChange(
                                    new Vector3i(first[0] >> 4, first[1] >> 4, first[2] >> 4), false, blocks));
                }
                packets++;
            } catch (Exception e) {
                plugin.dbg("Anti-Xray reveal packet failed for " + player.getName() + ": " + e.getMessage());
            }
        }
        return packets;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Block events: mining, explosions, pistons, fluids
    // ═════════════════════════════════════════════════════════════════════
    //
    // BlockPhysicsEvent is deliberately not used: it fires for nearly every
    // block update and would cost far more than it saves. Any change that
    // reaches the client goes through processBlockChange, which marks the
    // neighbouring ores exposed, and the next 5-tick check picks them up.

    // NEW: worlds loaded after startup (Multiverse etc.) get their min-y /
    // height / dimension cached up front instead of on the first chunk packet.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        engine.cacheMeta(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        // The block is still solid while the event runs, so it's passed as
        // see-through: ores behind it are revealed before its BLOCK_CHANGE
        // even reaches the client.
        recheckNow(b.getWorld(), single(pack(b.getX(), b.getY(), b.getZ())), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        recheckNow(event.getBlock().getWorld(), packAll(event.blockList()), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.blockList().isEmpty()) return;
        recheckNow(event.getLocation().getWorld(), packAll(event.blockList()), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Set<Long> pos = packAll(event.getBlocks());
        BlockFace dir = event.getDirection();
        for (Block b : event.getBlocks()) {
            pos.add(pack(b.getX() + dir.getModX(), b.getY() + dir.getModY(), b.getZ() + dir.getModZ()));
        }
        Block head = event.getBlock().getRelative(dir);
        pos.add(pack(head.getX(), head.getY(), head.getZ()));
        recheckNextTick(event.getBlock().getLocation(), pos);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        Set<Long> pos = packAll(event.getBlocks());
        Block head = event.getBlock().getRelative(event.getDirection().getOppositeFace());
        pos.add(pack(head.getX(), head.getY(), head.getZ()));
        recheckNextTick(event.getBlock().getLocation(), pos);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        // Only a solid block being washed away opens a line of sight.
        if (!SightRaycaster.blocksSight(to.getType())) return;
        recheckNextTick(to.getLocation(), single(pack(to.getX(), to.getY(), to.getZ())));
    }

    /** After the change has been applied: re-check on the region that owns it. */
    private void recheckNextTick(Location loc, Set<Long> positions) {
        if (!engine.isEnabled() || loc.getWorld() == null || positions.isEmpty()) return;
        World w = loc.getWorld();
        PlatformUtil.runTask(plugin, loc, () -> recheckNow(w, positions, false));
    }

    /**
     * Re-tests every hidden ore within EVENT_RADIUS of the given positions
     * for each player whose last camera was in range, using that player's
     * cached cameras (never their entity, which another region may own).
     * Must run on the region thread that owns the positions.
     *
     * @param seeThrough treat the positions themselves as see-through (they're about to break)
     */
    private void recheckNow(World world, Set<Long> positions, boolean seeThrough) {
        if (!engine.isEnabled() || world == null || positions.isEmpty()) return;
        UUID wid = world.getUID();
        AntiXrayEngine.WorldMeta meta = engine.meta(world);
        boolean folia = PlatformUtil.isFolia();
        int maxDist = engine.getMaxRayDistance() + EVENT_RADIUS + 1;
        double maxSq = (double) maxDist * maxDist;
        Set<Long> ignore = seeThrough ? positions : null;

        for (Map.Entry<UUID, AntiXrayEngine.PlayerXray> entry : engine.allStates().entrySet()) {
            AntiXrayEngine.PlayerXray px = entry.getValue();
            double[] cams = px.cams;
            if (!px.hasEye || cams == null || !wid.equals(px.worldId)) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !engine.isActiveFor(entry.getKey(), world.getName())) continue;

            List<int[]> candidates = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (long p : positions) {
                int bx = unpackX(p), by = unpackY(p), bz = unpackZ(p);
                double dx = bx + 0.5 - px.ex, dy = by + 0.5 - px.ey, dz = bz + 0.5 - px.ez;
                if (dx * dx + dy * dy + dz * dz > maxSq) continue;
                for (int ox = -EVENT_RADIUS; ox <= EVENT_RADIUS; ox++) {
                    for (int oy = -EVENT_RADIUS; oy <= EVENT_RADIUS; oy++) {
                        for (int oz = -EVENT_RADIUS; oz <= EVENT_RADIUS; oz++) {
                            int x = bx + ox, y = by + oy, z = bz + oz;
                            if (y < meta.minY || y >= meta.maxY) continue;
                            long key = pack(x, y, z);
                            if (!seen.add(key) || (ignore != null && ignore.contains(key))) continue;
                            long ck = AntiXrayEngine.columnKey(x >> 4, z >> 4);
                            AntiXrayEngine.ChunkOres co = px.chunks.get(ck);
                            if (co == null) continue;
                            int li = AntiXrayEngine.localIndex(x, y, z, meta.minY);
                            int[] e = co.entry(li);
                            if (e == null || e[0] < 0 || px.isRevealed(ck, li)) continue;
                            candidates.add(new int[]{ x, y, z, e[0] });
                        }
                    }
                }
            }
            if (candidates.isEmpty()) continue;

            int budgetTotal = Math.max(2_000, engine.getRayBudget() / 4);
            int[] budget = { budgetTotal };
            int cap = maxRevealsPerCheck(player);
            List<int[]> reveals = new ArrayList<>();
            for (int[] c : candidates) {
                if (reveals.size() >= cap) break;
                int r = visibility(world, meta, folia, cams, c[0], c[1], c[2], ignore, budget);
                if (r == NO_BUDGET) break;
                if (r == VISIBLE) {
                    px.reveal(AntiXrayEngine.columnKey(c[0] >> 4, c[2] >> 4),
                            AntiXrayEngine.localIndex(c[0], c[1], c[2], meta.minY));
                    reveals.add(c);
                }
            }
            // Anything not settled here gets another look on the next periodic check.
            px.gen.incrementAndGet();
            engine.raySteps.addAndGet(budgetTotal - Math.max(0, budget[0]));
            if (!reveals.isEmpty()) {
                int packets = send(player, reveals);
                engine.oresRevealed.addAndGet(reveals.size());
                plugin.dbg("Anti-Xray block-event reveal " + player.getName() + ": " + reveals.size()
                        + " ores in " + packets + " block-change packets");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════════════

    private static Set<Long> packAll(Collection<Block> blocks) {
        Set<Long> out = new HashSet<>(blocks.size() * 2);
        for (Block b : blocks) out.add(pack(b.getX(), b.getY(), b.getZ()));
        return out;
    }

    /** 26 bits x, 26 bits z, 12 bits y (covers -2048..2047). */
    static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    static int unpackX(long p) { return (int) (p >> 38); }
    static int unpackZ(long p) { return (int) (p << 26 >> 38); }
    static int unpackY(long p) { return (int) (p << 52 >> 52); }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static Set<Long> single(long v) {
        Set<Long> s = new HashSet<>(2);
        s.add(v);
        return s;
    }
}
