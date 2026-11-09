package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEW (terrain protection): a player who joins sealed in underground (no air
 * path to the sky) is sent one fake cube per surface chunk instead of the
 * real surface, so freecam shows nothing real up there.
 *
 * <p>How deep the player is: blocks from the eye up to the natural ground
 * directly above it (trees, builds and water don't count). Within
 * reveal-3x3-depth the 3x3 chunks around turn real, within reveal-all-depth
 * everything, and it stays off until the next join.
 *
 * <p>Each chunk's fake floor follows that chunk's own ground (a few blocks
 * under its lowest column), so there is no flat stone layer across the
 * map. It is only pushed up where the player can actually get to, so they
 * never dig into or look at fake blocks from below.
 *
 * <p>The cube itself is built in ChunkListener#applyTerrainProtection.
 */
final class TerrainProtection {

    /** The join check gives up after this long (chunks never loaded, fill never ran). */
    private static final long CHECK_TIMEOUT_MS = 10_000;
    /** Blocks past a reveal depth before going back, so standing on the line doesn't flip it. */
    private static final int HYSTERESIS = 2;
    /** Air the fill reached must stay this far under the fake floor. */
    private static final int FLOOR_GAP = 3;
    /** A pushed-up floor goes this far above the reached air, so it isn't raised every block climbed. */
    private static final int FLOOR_MARGIN = 8;
    /** A chunk's fake floor sits this far under its lowest natural ground. */
    static final int SURFACE_DEPTH = 8;
    /** Reached air is tracked in a 9x9 chunk window around the player. */
    private static final int WINDOW = 4;
    /** Floor minimums of chunks farther away than this are dropped. */
    private static final int KEEP_RANGE = 12;

    /**
     * What a sealed-in player is sent. No entry means the real terrain.
     * Replaced as a whole, never changed, so Netty reads are safe.
     */
    static final class State {
        final boolean near;   // the 3x3 chunks around (pcx, pcz) are real
        final UUID worldId;
        final int pcx, pcz;
        /** Floor for a chunk that hasn't been sent faked yet and has no ground. */
        final int minFloor;
        /** Per chunk: the fake floor can't be lower than this (the player's air is there). */
        final LongLongMap needs;

        State(boolean near, UUID worldId, int pcx, int pcz, int minFloor, LongLongMap needs) {
            this.near = near; this.worldId = worldId; this.pcx = pcx; this.pcz = pcz;
            this.minFloor = minFloor; this.needs = needs;
        }

        boolean isReal(int cx, int cz) {
            return near && Math.abs(cx - pcx) <= 1 && Math.abs(cz - pcz) <= 1;
        }

        int need(int cx, int cz) {
            long v = needs.get(key(cx, cz));
            return v == LongLongMap.MISSING ? Integer.MIN_VALUE : (int) v;
        }

        /**
         * The fake floor of chunk (cx, cz): under its own ground, but never
         * under the player's air. groundFloor is Integer.MIN_VALUE if the
         * chunk has no natural ground at all.
         */
        int floorFor(int cx, int cz, int groundFloor) {
            int f = Math.max(groundFloor, need(cx, cz));
            return f != Integer.MIN_VALUE ? f : minFloor;
        }
    }

    /** A chunk the client was sent as a cube: faked above floor, ground at top. */
    static final class Faked {
        final int floor, top;
        Faked(int floor, int top) { this.floor = floor; this.top = top; }
    }

    private final FPAntiFreeCam plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    /** Joined this long ago (ms) and the sealed-in check hasn't answered yet. */
    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> versions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Faked>> faked = new ConcurrentHashMap<>();

    TerrainProtection(FPAntiFreeCam plugin) {
        this.plugin = plugin;
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ── Player thread ─────────────────────────────────────────────────────

    /**
     * Picks what surface this player is sent and re-sends the chunks that
     * changed. Player's thread, every 5 ticks, after the flood fill.
     *
     * Whether the player is sealed in is only checked once, right after
     * joining underground (see startCheck). After that only the depth under
     * the ground above the eye and the reached air of the normal Smart Fill
     * fill are used.
     */
    void update(Player p) {
        UUID id = p.getUniqueId();
        State prev = states.get(id);
        World world = p.getWorld();
        SmartFillReveal reveal = plugin.getSmartFillReveal();
        if (reveal == null || !plugin.isTerrainProtectionWorld(world) || !plugin.isSmartFillActiveFor(p)) {
            pending.remove(id);
            if (prev != null) clear(p, true);
            return;
        }
        Long pendingSince = pending.get(id);
        if (prev == null && pendingSince == null) return; // nothing to do until the next join
        UUID worldId = world.getUID();
        if (prev != null && !prev.worldId.equals(worldId)) {
            forget(id);
            prev = null;
            if (pendingSince == null) return;
        }
        Location eye = p.getEyeLocation();
        int ex = eye.getBlockX(), ey = eye.getBlockY(), ez = eye.getBlockZ();
        int depth = depthUnderGround(world, ex, ey, ez);
        int allDepth = plugin.getTerrainRevealAllDepth(), nearDepth = plugin.getTerrainReveal3x3Depth();
        long now = System.currentTimeMillis();

        int airTop;
        if (pendingSince != null) {
            airTop = reveal.enclosedAirTop(id, worldId, ex, ey, ez);
            if (airTop == SmartFillReveal.SKY_NOT_READY) {
                if (now - pendingSince < CHECK_TIMEOUT_MS) return;
                airTop = Integer.MIN_VALUE; // no answer: real surface
            }
            pending.remove(id);
            if (airTop == Integer.MIN_VALUE || depth <= allDepth + HYSTERESIS) {
                plugin.dbg("Terrain protection join check for " + p.getName() + ": not sealed in (depth " + depth + ")");
                if (prev != null) clear(p, true);
                return;
            }
            plugin.dbg("Terrain protection join check for " + p.getName() + ": sealed in (depth " + depth
                    + ", air up to Y=" + airTop + ")");
        } else {
            if (depth <= allDepth) {
                clear(p, true);
                return;
            }
            airTop = reveal.reachedAirTop(id, worldId);
        }

        boolean near = depth <= nearDepth + (prev != null && prev.near ? HYSTERESIS : 0);
        Location feet = p.getLocation();
        int pcx = feet.getBlockX() >> 4, pcz = feet.getBlockZ() >> 4;

        // Floor minimums: kept for nearby chunks, raised where the fill now
        // reaches higher. The player's own 3x3 also stays above the eye.
        LongLongMap needs = new LongLongMap(prev != null ? prev.needs.size() + 81 : 81);
        boolean[] changed = { prev == null };
        if (prev != null) {
            prev.needs.forEach((k, v) -> {
                int cx = (int) (k >> 32), cz = (int) k;
                if (Math.abs(cx - pcx) <= KEEP_RANGE && Math.abs(cz - pcz) <= KEEP_RANGE) needs.putMax(k, v);
                else changed[0] = true;
            });
        }
        int[] airs = reveal.reachedAirTops(id, worldId, pcx, pcz);
        Map<Long, Faked> sent = faked.get(id);
        Set<Long> resend = new HashSet<>();
        for (int dx = -WINDOW; dx <= WINDOW; dx++) {
            for (int dz = -WINDOW; dz <= WINDOW; dz++) {
                // Fill unknown (ran out of budget): the whole window as high as the air reached anywhere.
                int air = airs != null ? airs[(dx + WINDOW) * (2 * WINDOW + 1) + (dz + WINDOW)] : Math.max(airTop, ey);
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) air = Math.max(air, ey);
                if (air == Integer.MIN_VALUE) continue;
                int raw = air + FLOOR_GAP;
                long k = key(pcx + dx, pcz + dz);
                long have = needs.get(k);
                if (have == LongLongMap.MISSING || have < raw) {
                    needs.putMax(k, raw + FLOOR_MARGIN);
                    changed[0] = true;
                }
                // The client has fake blocks here the player can now reach:
                // re-sent with the higher floor, recorded right away so the
                // next pass doesn't ask again before the chunk went out.
                Faked f = sent != null ? sent.get(k) : null;
                if (prev != null && f != null && f.floor < raw) {
                    resend.add(k);
                    sent.put(k, new Faked((int) needs.get(k), f.top));
                }
            }
        }
        int minFloor = prev != null && prev.minFloor >= ey + FLOOR_GAP ? prev.minFloor : ey + FLOOR_GAP + FLOOR_MARGIN;

        boolean nearMoved = prev != null && (prev.near != near || (near && (prev.pcx != pcx || prev.pcz != pcz)));
        if (prev != null && !changed[0] && !nearMoved && prev.minFloor == minFloor) return;
        State next = new State(near, worldId, pcx, pcz, minFloor, changed[0] ? needs : prev.needs);
        states.put(id, next);

        if (prev == null) {
            faked.remove(id);
            versions.merge(id, 1, Integer::sum);
            plugin.dbg("Terrain protection on for " + p.getName() + " (" + (near ? "3x3" : "fake")
                    + ", depth " + depth + ", eye Y=" + ey + ")");
            plugin.refreshFullView(p, true);
            return;
        }
        if (nearMoved) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int ax = prev.pcx + dx, az = prev.pcz + dz, bx = pcx + dx, bz = pcz + dz;
                    if (prev.isReal(ax, az) != next.isReal(ax, az)) resend.add(key(ax, az));
                    if (prev.isReal(bx, bz) != next.isReal(bx, bz)) resend.add(key(bx, bz));
                }
            }
        }
        // Only what the client sees changing makes the entity hider rescan.
        if (resend.isEmpty()) return;
        versions.merge(id, 1, Integer::sum);
        plugin.refreshColumns(p, resend);
        plugin.dbg("Terrain protection " + (near ? "3x3" : "fake") + " for " + p.getName() + " (depth " + depth
                + ", " + resend.size() + " chunks re-sent)");
    }

    /** Back to the real surface. Player's thread. */
    void clear(Player p, boolean resend) {
        UUID id = p.getUniqueId();
        if (states.remove(id) == null) return;
        faked.remove(id);
        versions.merge(id, 1, Integer::sum);
        plugin.dbg("Terrain protection off for " + p.getName());
        if (resend) plugin.refreshFullView(p, true);
    }

    /**
     * Starts the sealed-in check if the player is deep enough under the
     * ground above them. Only called on join and world change, so nobody is
     * scanned all the time. Player's thread.
     */
    void startCheck(Player p) {
        UUID id = p.getUniqueId();
        pending.remove(id);
        if (plugin.getSmartFillReveal() == null || !plugin.isTerrainProtectionWorld(p.getWorld())
                || !plugin.isSmartFillActiveFor(p)) return;
        Location eye = p.getEyeLocation();
        int depth = depthUnderGround(p.getWorld(), eye.getBlockX(), eye.getBlockY(), eye.getBlockZ());
        if (depth <= plugin.getTerrainRevealAllDepth() + HYSTERESIS) return;
        pending.put(id, System.currentTimeMillis());
        plugin.dbg("Terrain protection join check started for " + p.getName() + " (depth " + depth + ")");
    }

    /**
     * Blocks from the eye up to the highest natural ground in its column, 0
     * if there's none above it. Trees, builds and water on top are skipped,
     * so a tunnel under a forest or a base isn't counted as deeper.
     * Usually only a few reads: the scan runs down from the top.
     */
    private static int depthUnderGround(World world, int x, int eyeY, int z) {
        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
        for (int y = top; y > eyeY; y--) {
            if (isGround(world.getBlockAt(x, y, z).getType())) return y - eyeY;
        }
        return 0;
    }

    /** Per Material ordinal: 0 unknown, 1 ground, 2 not. Racy writes are harmless. */
    private static final byte[] GROUND = new byte[Material.values().length];

    private static boolean isGround(Material m) {
        int o = m.ordinal();
        byte c = o < GROUND.length ? GROUND[o] : 0;
        if (c != 0) return c == 1;
        boolean g = false;
        try {
            if (m.isBlock()) {
                WrappedBlockState s = WrappedBlockState.getByString("minecraft:" + m.getKey().getKey());
                g = s != null && SmartFillEngine.isGroundType(s.getType());
            }
        } catch (Throwable ignored) {}
        if (o < GROUND.length) GROUND[o] = (byte) (g ? 1 : 2);
        return g;
    }

    // ── Any thread ────────────────────────────────────────────────────────

    /**
     * Forgets the state without a re-send (client dropped its chunks).
     * Leaves a pending join check alone, since the JOIN_GAME packet can go
     * out after PlayerJoinEvent.
     */
    void forget(UUID id) {
        faked.remove(id);
        if (states.remove(id) != null) versions.merge(id, 1, Integer::sum);
    }

    void cleanupPlayer(UUID id) {
        states.remove(id);
        pending.remove(id);
        versions.remove(id);
        faked.remove(id);
    }

    /** The join check for this player hasn't answered yet. Netty-safe. */
    boolean isCheckPending(UUID id) {
        return id != null && pending.containsKey(id);
    }

    /** True while this player is sent a fake surface. Netty-safe. */
    boolean isProtected(UUID id) {
        return id != null && states.containsKey(id);
    }

    /** Bumped on every visible change, so cached entity results get redone. */
    int version(UUID id) {
        Integer v = id != null ? versions.get(id) : null;
        return v != null ? v : 0;
    }

    /** The player's state in this world, or null. Netty-safe. */
    State stateFor(UUID id, UUID worldId) {
        State s = id != null ? states.get(id) : null;
        return s != null && worldId != null && s.worldId.equals(worldId) ? s : null;
    }

    /**
     * Blocks above this Y in chunk (cx, cz) are faked for the player, or
     * Integer.MIN_VALUE if the chunk is real. A chunk that hasn't been sent
     * faked yet uses its floor minimum. Netty-safe.
     */
    int floor(UUID id, UUID worldId, int cx, int cz) {
        State s = stateFor(id, worldId);
        if (s == null || s.isReal(cx, cz)) return Integer.MIN_VALUE;
        Faked f = faked(id, cx, cz);
        if (f != null) return f.floor;
        return Math.max(s.need(cx, cz), s.minFloor);
    }

    /** True if a block at this position is faked for the player. Netty-safe. */
    boolean isHidden(UUID id, UUID worldId, int x, int y, int z) {
        int floor = floor(id, worldId, x >> 4, z >> 4);
        return floor != Integer.MIN_VALUE && y > floor;
    }

    /** The cube this chunk was sent as, or null. Netty-safe. */
    Faked faked(UUID id, int cx, int cz) {
        Map<Long, Faked> m = faked.get(id);
        return m != null ? m.get(key(cx, cz)) : null;
    }

    void remember(UUID id, int cx, int cz, int floor, int top) {
        faked.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(key(cx, cz), new Faked(floor, top));
    }

    void forgetChunk(UUID id, int cx, int cz) {
        Map<Long, Faked> m = faked.get(id);
        if (m != null) m.remove(key(cx, cz));
    }
}
