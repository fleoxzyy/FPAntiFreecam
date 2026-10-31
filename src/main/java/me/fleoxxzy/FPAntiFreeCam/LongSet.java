package me.fleoxxzy.FPAntiFreeCam;

import java.util.function.LongConsumer;

/**
 * NEW: small open-addressing set of primitive longs.
 *
 * <p>Smart Fill reveal lookups run on the Netty thread for every candidate
 * block of every chunk packet. A {@code Set<Long>} boxes each key there, a
 * primitive set doesn't allocate at all. Built on one thread, then published
 * through a ConcurrentHashMap and never changed again, so lock-free reads
 * from any thread are safe.
 */
final class LongSet {

    static final LongSet EMPTY = new LongSet(1);

    private long[] keys;
    private int mask;
    private int size;
    private boolean hasZero; // 0 marks an empty slot, so key 0 is kept apart

    LongSet(int expected) {
        int cap = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        keys = new long[cap];
        mask = cap - 1;
    }

    static int mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return (int) k;
    }

    boolean add(long k) {
        if (k == 0) {
            if (hasZero) return false;
            hasZero = true;
            size++;
            return true;
        }
        if ((size + 1) * 2 > keys.length) grow();
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == 0) { keys[i] = k; size++; return true; }
            if (c == k) return false;
            i = (i + 1) & mask;
        }
    }

    boolean contains(long k) {
        if (k == 0) return hasZero;
        long[] ks = keys;
        int m = ks.length - 1;
        int i = mix(k) & m;
        while (true) {
            long c = ks[i];
            if (c == 0) return false;
            if (c == k) return true;
            i = (i + 1) & m;
        }
    }

    int size() { return size; }

    boolean isEmpty() { return size == 0; }

    void forEach(LongConsumer action) {
        if (hasZero) action.accept(0);
        for (long k : keys) if (k != 0) action.accept(k);
    }

    private void grow() {
        long[] old = keys;
        keys = new long[old.length * 2];
        mask = keys.length - 1;
        for (long k : old) {
            if (k == 0) continue;
            int i = mix(k) & mask;
            while (keys[i] != 0) i = (i + 1) & mask;
            keys[i] = k;
        }
    }
}
