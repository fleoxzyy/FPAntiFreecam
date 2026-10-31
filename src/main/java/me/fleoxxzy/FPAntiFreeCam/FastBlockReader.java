package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Material;
import org.bukkit.World;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * NEW: faster block reads for {@link SightRaycaster#walk}.
 *
 * <p>World#getType goes through CraftRegionAccessor: a chunk lookup per
 * block, then a Material conversion (minecraftToBukkit, an identity map
 * lookup) on every call. That was most of the line-of-sight cost. On Paper
 * this reads the block state straight from the chunk the ray is in, and
 * remembers per block state whether it blocks sight.
 *
 * <p>The "blocks sight" answer itself still comes from
 * {@link SightRaycaster#blocksSight(Material)} (filled on a cache miss via
 * World#getType), so the rule is exactly the same as before. Reflection
 * only: if anything is missing (Spigot, older Paper, renamed internals),
 * {@link #AVAILABLE} is false and walk uses the Bukkit path.
 */
final class FastBlockReader {

    private FastBlockReader() {}

    /** (CraftWorld) -> ServerLevel */
    private static final MethodHandle GET_HANDLE;
    /** (Level, int cx, int cz) -> LevelChunk or null if not loaded */
    private static final MethodHandle GET_CHUNK;
    /** (LevelChunk, int x, int y, int z) -> BlockState */
    private static final MethodHandle GET_STATE;
    static volatile boolean AVAILABLE;

    static {
        MethodHandle h = null, c = null, s = null;
        try {
            MethodHandles.Lookup lk = MethodHandles.lookup();
            Class<?> craftWorld = Class.forName(
                    org.bukkit.Bukkit.getServer().getClass().getPackage().getName() + ".CraftWorld");
            Method gh = craftWorld.getMethod("getHandle");
            Class<?> level = gh.getReturnType();
            Method gc = level.getMethod("getChunkIfLoaded", int.class, int.class);
            Method gs = gc.getReturnType().getMethod("getBlockStateFinal", int.class, int.class, int.class);
            h = lk.unreflect(gh).asType(MethodType.methodType(Object.class, World.class));
            c = lk.unreflect(gc).asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            s = lk.unreflect(gs).asType(MethodType.methodType(Object.class, Object.class, int.class, int.class, int.class));
        } catch (Throwable ignored) {
            h = c = s = null;
        }
        GET_HANDLE = h; GET_CHUNK = c; GET_STATE = s;
        AVAILABLE = h != null;
    }

    static Object level(World w) {
        try {
            return GET_HANDLE.invokeExact(w);
        } catch (Throwable t) {
            AVAILABLE = false;
            return null;
        }
    }

    /** Null if the chunk isn't loaded. Throws if internals broke (caller turns the fast path off). */
    static Object chunk(Object level, int cx, int cz) throws Throwable {
        return GET_CHUNK.invokeExact(level, cx, cz);
    }

    static Object state(Object chunk, int x, int y, int z) throws Throwable {
        return GET_STATE.invokeExact(chunk, x, y, z);
    }

    // ── Per-state "blocks sight" cache ────────────────────────────────────
    // Direct-mapped by identity hash: block states are singletons, so the
    // same object always gives the same answer. A collision just overwrites
    // the slot. Main thread on Paper; on Folia several region threads can
    // race on a slot, which only causes a re-check: the key and value are
    // written as one immutable entry.
    private static final int SIZE = 4096, MASK = SIZE - 1;

    private static final class Entry {
        final Object state; final boolean blocks;
        Entry(Object state, boolean blocks) { this.state = state; this.blocks = blocks; }
    }

    private static final Entry[] CACHE = new Entry[SIZE];

    /** 1 = blocks, 0 = see-through, -1 = unknown (caller asks Bukkit, then {@link #remember}). */
    static int cached(Object state) {
        Entry e = CACHE[System.identityHashCode(state) & MASK];
        if (e == null || e.state != state) return -1;
        return e.blocks ? 1 : 0;
    }

    static void remember(Object state, boolean blocks) {
        CACHE[System.identityHashCode(state) & MASK] = new Entry(state, blocks);
    }
}
