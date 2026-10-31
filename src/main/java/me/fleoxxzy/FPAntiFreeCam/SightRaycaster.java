package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Shared voxel DDA (Amanatides-Woo) used by every line-of-sight check in the
 * plugin.
 *
 * <p>NEW: extracted from SmartFillReveal#computeLineOfSight so Smart Fill's
 * cave sweep and the Anti-Xray ore check walk rays the exact same way, with
 * the exact same safety guards (never trigger a chunk load, never read
 * another Folia region's blocks, never leave the world's height range).
 * Two separate copies of this loop would sooner or later drift apart, and a
 * guard fixed in one but not the other is a Folia crash waiting to happen.
 *
 * <p>Must run on the thread that owns the region the ray starts in (reads
 * world blocks). Rays stop on their own at the first chunk owned by another
 * region, so a ray never crosses into foreign region data.
 */
public final class SightRaycaster {

    private SightRaycaster() {}

    /** Called for every cell the ray enters, in order, including sight-blocking ones. */
    @FunctionalInterface
    public interface CellVisitor {
        /**
         * @param type   block type, or null when the fast block reader answered (don't rely on it)
         * @param tEnter distance along the ray at which it entered this cell
         * @param blocks true if this cell stops sight
         * @return false to stop the walk (HIT_BLOCK if blocks, else STOPPED);
         *         true to continue, even through a blocking cell
         */
        boolean visit(int x, int y, int z, Material type, boolean blocks, double tEnter);
    }

    /** The walk ran past maxDist without being stopped. */
    public static final int REACHED_END   = 0;
    /** The walk stopped at a sight-blocking cell. */
    public static final int HIT_BLOCK     = 1;
    /** The visitor returned false. */
    public static final int STOPPED       = 2;
    /** The shared step budget ran out. */
    public static final int OUT_OF_BUDGET = 3;
    /** The ray left the world's height range, or hit an unloaded / foreign-region chunk or a read error. */
    public static final int UNREADABLE    = 4;

    /** Per-Material "does this block stop a sight ray" lookup, lazily filled. 0 = unknown, 1 = blocks, 2 = see-through. */
    private static final byte[] SIGHT_BLOCKING = new byte[Material.values().length];

    /** True if this material stops a sight ray (occluding, full opaque cube). Null counts as see-through. */
    public static boolean blocksSight(Material m) {
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

    /**
     * Walks a ray from (ox, oy, oz) along (dx, dy, dz) for up to maxDist
     * blocks, calling the visitor for every cell it enters.
     *
     * @param dx,dy,dz direction; must be normalized, t is in blocks
     * @param budget   one-element array holding the shared remaining step
     *                 budget; decremented once per cell
     * @return one of {@link #REACHED_END}, {@link #HIT_BLOCK}, {@link #STOPPED},
     *         {@link #OUT_OF_BUDGET}, {@link #UNREADABLE}
     */
    public static int walk(World world, int minY, int maxY, boolean folia,
                           double ox, double oy, double oz,
                           double dx, double dy, double dz,
                           double maxDist, int[] budget, CellVisitor visitor) {
        int x = floor(ox), y = floor(oy), z = floor(oz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = dz != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;
        double tMaxX = dx != 0 ? (dx > 0 ? (x + 1 - ox) : (ox - x)) * tDeltaX : Double.POSITIVE_INFINITY;
        double tMaxY = dy != 0 ? (dy > 0 ? (y + 1 - oy) : (oy - y)) * tDeltaY : Double.POSITIVE_INFINITY;
        double tMaxZ = dz != 0 ? (dz > 0 ? (z + 1 - oz) : (oz - z)) * tDeltaZ : Double.POSITIVE_INFINITY;

        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        double t = 0;
        // IMPROVEMENT: on Paper, read block states straight from the chunk
        // (FastBlockReader) instead of World#getType for every cell, which
        // was most of the line-of-sight cost. Falls back if unavailable.
        Object level = FastBlockReader.AVAILABLE ? FastBlockReader.level(world) : null;
        Object chunk = null;
        Object lastState = null;
        int lastKnown = -1;

        while (true) {
            if (--budget[0] < 0) return OUT_OF_BUDGET;
            if (y < minY || y >= maxY) return UNREADABLE;

            int cx = x >> 4, cz = z >> 4;
            if (cx != lastCx || cz != lastCz) {
                // Never trigger a chunk load, and on Folia never read another region's blocks.
                if (folia && !PlatformUtil.isOwnedByCurrentRegion(new Location(world, cx << 4, y, cz << 4))) {
                    return UNREADABLE;
                }
                if (level != null) {
                    try {
                        chunk = FastBlockReader.chunk(level, cx, cz);
                    } catch (Throwable e) {
                        FastBlockReader.AVAILABLE = false;
                        level = null;
                    }
                    if (level != null && chunk == null) return UNREADABLE;
                }
                if (level == null && !world.isChunkLoaded(cx, cz)) return UNREADABLE;
                lastCx = cx;
                lastCz = cz;
            }

            Material m = null;
            boolean blocks;
            int known = -1;
            Object state = null;
            if (level != null) {
                try {
                    state = FastBlockReader.state(chunk, x, y, z);
                    // IMPROVEMENT: rays mostly cross runs of the same state
                    // (air, stone): reuse the last answer before the cache.
                    known = state == lastState ? lastKnown : FastBlockReader.cached(state);
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
                    m = world.getType(x, y, z);
                } catch (Exception e) {
                    return UNREADABLE;
                }
                blocks = blocksSight(m);
                if (state != null) FastBlockReader.remember(state, blocks);
            }
            if (state != null) { lastState = state; lastKnown = blocks ? 1 : 0; }
            // The visitor decides whether a blocking cell ends the ray, so a
            // caller can treat a block that is about to break as see-through.
            // m is null when the fast path answered; no visitor uses it.
            if (!visitor.visit(x, y, z, m, blocks, t)) return blocks ? HIT_BLOCK : STOPPED;

            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX; t = tMaxX; tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY; t = tMaxY; tMaxY += tDeltaY;
            } else {
                z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ;
            }
            if (t > maxDist) return REACHED_END;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Cameras
    // ═════════════════════════════════════════════════════════════════════
    //
    // NEW: moved here from AntiXrayReveal so the Anti-Xray ore check and the
    // line-of-sight EntityHider use the exact same camera positions. If they
    // drifted apart, an F5 camera could see an ore but not the mob next to it.

    /** F5 camera distance (vanilla third-person distance). */
    public static final double CAMERA_DISTANCE = 4.0;
    /** How far a clipped camera is pulled back in front of the wall it hit. */
    public static final double CAMERA_CLIP_MARGIN = 0.2;
    /** Cameras that moved less than this (squared) count as unchanged. */
    public static final double SKIP_MOVE_SQ = 0.5 * 0.5;
    /** Look directions closer than this (cosine) count as unchanged. */
    public static final double SKIP_TURN_COS = Math.cos(Math.toRadians(10));

    /**
     * The eye, plus (f5Aware) a camera 4 blocks behind and in front of it,
     * as a flat {x, y, z, x, y, z, ...} array. The third-person ones are
     * pulled back toward the eye if a solid block is in the way, like the
     * vanilla camera does. Otherwise a wall behind the player would put a
     * camera inside the rock, where it could see caves the player can't.
     *
     * @param lx,ly,lz normalized look direction
     */
    public static double[] cameras(World world, int minY, int maxY, boolean folia, boolean f5Aware,
                                   double ex, double ey, double ez, double lx, double ly, double lz) {
        if (!f5Aware) return new double[]{ ex, ey, ez };
        double back = clip(world, minY, maxY, folia, ex, ey, ez, -lx, -ly, -lz);
        double front = clip(world, minY, maxY, folia, ex, ey, ez, lx, ly, lz);
        return new double[]{
                ex, ey, ez,
                ex - lx * back, ey - ly * back, ez - lz * back,
                ex + lx * front, ey + ly * front, ez + lz * front
        };
    }

    /** Distance a third-person camera can sit from the eye along (dx, dy, dz) before a wall. */
    public static double clip(World world, int minY, int maxY, boolean folia,
                              double ox, double oy, double oz, double dx, double dy, double dz) {
        double[] hit = { -1 };
        int[] budget = { 64 };
        int r = walk(world, minY, maxY, folia, ox, oy, oz, dx, dy, dz,
                CAMERA_DISTANCE, budget, (x, y, z, type, blocks, t) -> {
                    if (!blocks || t <= 0) return true; // the eye's own cell never clips
                    hit[0] = t;
                    return false;
                });
        if (r == HIT_BLOCK && hit[0] >= 0) return Math.max(0, hit[0] - CAMERA_CLIP_MARGIN);
        return CAMERA_DISTANCE; // open air, or above/below the world (also open air)
    }

    /** True if every camera in a moved less than SKIP_MOVE_SQ from the same camera in b. */
    public static boolean camerasClose(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i += 3) {
            double dx = a[i] - b[i], dy = a[i + 1] - b[i + 1], dz = a[i + 2] - b[i + 2];
            if (dx * dx + dy * dy + dz * dz >= SKIP_MOVE_SQ) return false;
        }
        return true;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
