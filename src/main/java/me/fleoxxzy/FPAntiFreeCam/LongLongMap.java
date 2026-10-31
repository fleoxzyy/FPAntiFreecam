package me.fleoxxzy.FPAntiFreeCam;

import java.util.function.LongConsumer;

/**
 * NEW: open-addressing primitive long → long map (Smart Fill grace
 * timestamps). Same rules as {@link LongSet}: built on one thread, published,
 * then only read. {@link #get} returns {@link #MISSING} for absent keys.
 */
final class LongLongMap {

    static final long MISSING = Long.MIN_VALUE;
    static final LongLongMap EMPTY = new LongLongMap(1);

    /** Callback for {@link #forEach}. */
    @FunctionalInterface
    interface Entry { void accept(long key, long value); }

    private long[] keys;
    private long[] vals;
    private int mask;
    private int size;
    private boolean hasZero;
    private long zeroVal;

    LongLongMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        keys = new long[cap];
        vals = new long[cap];
        mask = cap - 1;
    }

    /** Stores the larger of the old and new value. */
    void putMax(long k, long v) {
        if (k == 0) {
            if (!hasZero) { hasZero = true; zeroVal = v; size++; }
            else if (v > zeroVal) zeroVal = v;
            return;
        }
        if ((size + 1) * 2 > keys.length) grow();
        int i = LongSet.mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == 0) { keys[i] = k; vals[i] = v; size++; return; }
            if (c == k) { if (v > vals[i]) vals[i] = v; return; }
            i = (i + 1) & mask;
        }
    }

    /** Bitwise-ORs v into the stored value (0 if absent). */
    void putOr(long k, long v) {
        if (k == 0) {
            if (!hasZero) { hasZero = true; zeroVal = v; size++; }
            else zeroVal |= v;
            return;
        }
        if ((size + 1) * 2 > keys.length) grow();
        int i = LongSet.mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == 0) { keys[i] = k; vals[i] = v; size++; return; }
            if (c == k) { vals[i] |= v; return; }
            i = (i + 1) & mask;
        }
    }

    long get(long k) {
        if (k == 0) return hasZero ? zeroVal : MISSING;
        long[] ks = keys;
        int m = ks.length - 1;
        int i = LongSet.mix(k) & m;
        while (true) {
            long c = ks[i];
            if (c == 0) return MISSING;
            if (c == k) return vals[i];
            i = (i + 1) & m;
        }
    }

    int size() { return size; }

    boolean isEmpty() { return size == 0; }

    void forEach(Entry action) {
        if (hasZero) action.accept(0, zeroVal);
        for (int i = 0; i < keys.length; i++) if (keys[i] != 0) action.accept(keys[i], vals[i]);
    }

    void forEachKey(LongConsumer action) {
        if (hasZero) action.accept(0);
        for (long k : keys) if (k != 0) action.accept(k);
    }

    private void grow() {
        long[] ok = keys, ov = vals;
        keys = new long[ok.length * 2];
        vals = new long[ok.length * 2];
        mask = keys.length - 1;
        for (int j = 0; j < ok.length; j++) {
            long k = ok[j];
            if (k == 0) continue;
            int i = LongSet.mix(k) & mask;
            while (keys[i] != 0) i = (i + 1) & mask;
            keys[i] = k;
            vals[i] = ov[j];
        }
    }
}
