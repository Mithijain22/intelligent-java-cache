package com.intelligentcache.engine;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A Count-Min Sketch: estimates how many times a key has been seen, using
 * fixed memory regardless of how many distinct keys show up (unlike, say, a
 * {@code HashMap<K, Integer>} counter per key, which grows without bound).
 * This is the frequency estimator {@link TinyLFUCache} uses to decide
 * whether a new key deserves to be admitted into the cache at all -- the
 * "TinyLFU" in the name refers specifically to using a small, approximate
 * frequency structure like this one instead of exact per-key counters,
 * which is what makes it practical at production cache sizes (this is the
 * technique Caffeine uses internally).
 *
 * <h2>How it works</h2>
 * {@code depth} independent counter rows, each {@code width} wide. To
 * increment a key's count, hash it {@code depth} different ways (one hash
 * per row) and bump the counter at that row's corresponding column. To
 * estimate a key's count, take the <b>minimum</b> across all {@code depth}
 * rows -- collisions in any single row can only ever overestimate a count
 * (two different keys landing on the same counter), never underestimate,
 * so the minimum across independently-hashed rows is the best available
 * estimate. This is the classic Count-Min Sketch guarantee.
 *
 * <h2>Aging</h2>
 * Counters are capped at {@link #COUNTER_MAX} (a simplified analogue of the
 * 4-bit saturating counters production implementations pack for memory
 * efficiency -- this implementation uses one {@code byte} per counter for
 * clarity rather than bit-packing four counters per byte, a documented
 * simplification). Every {@code sampleSize} increments, every counter in
 * the whole table is halved. Without aging, a key that was extremely
 * popular an hour ago would keep outscoring a key that's popular *right
 * now* forever -- halving lets old popularity decay so the sketch tracks
 * <i>recent</i> frequency, which is what actually matters for a cache
 * admission decision.
 */
final class CountMinSketch<K> {

    private static final int COUNTER_MAX = 15; // mimics a saturating 4-bit counter

    private final int width;
    private final int depth;
    private final byte[][] table;
    private final int sampleSize;
    private final ReentrantLock lock = new ReentrantLock();
    private int additionsSinceReset = 0;

    /**
     * @param expectedEntries rough size of the working set this sketch will
     *                        track; used to size the table wide enough to
     *                        keep collision-driven overestimation low. Sized
     *                        at 16x (not just a small multiple of) the cache
     *                        capacity -- a table sized too close to capacity
     *                        badly overestimates frequency for high-cardinality
     *                        streams (e.g. a long sequential scan touching
     *                        thousands of distinct keys), since many distinct
     *                        cold keys collide into the same counters and
     *                        collectively saturate them even though no single
     *                        key was ever popular. This was caught empirically
     *                        by this project's own TinyLFU verification test
     *                        (a 10k-key one-time scan against a 100-entry
     *                        cache) before the width was widened -- worth
     *                        knowing if you retune this for your own workload.
     */
    CountMinSketch(int expectedEntries) {
        this.width = Math.max(16, nextPowerOfTwo(expectedEntries * 16));
        this.depth = 4;
        this.table = new byte[depth][width];
        this.sampleSize = Math.max(1, width * depth) * 20; // reset roughly every 20x table capacity worth of increments
    }

    private static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    void increment(K key) {
        lock.lock();
        try {
            long hash = spread(key.hashCode());
            for (int row = 0; row < depth; row++) {
                int col = columnFor(hash, row);
                if (table[row][col] < COUNTER_MAX) {
                    table[row][col]++;
                }
            }
            additionsSinceReset++;
            if (additionsSinceReset >= sampleSize) {
                ageAllCounters();
                additionsSinceReset = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    int estimate(K key) {
        lock.lock();
        try {
            long hash = spread(key.hashCode());
            int min = COUNTER_MAX;
            for (int row = 0; row < depth; row++) {
                int col = columnFor(hash, row);
                min = Math.min(min, table[row][col]);
            }
            return min;
        } finally {
            lock.unlock();
        }
    }

    private void ageAllCounters() {
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < width; col++) {
                table[row][col] = (byte) (table[row][col] >> 1);
            }
        }
    }

    /** Derives a well-mixed 64-bit hash from a key's hashCode (Fibonacci hashing / MurmurHash-style final mix). */
    private static long spread(int h) {
        long x = h;
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return x;
    }

    /** row-specific column index, derived by mixing the base hash with the row number. */
    private int columnFor(long baseHash, int row) {
        long mixed = baseHash ^ (0x9E3779B97F4A7C15L * (row + 1));
        int h = (int) (mixed ^ (mixed >>> 32));
        return (h & 0x7FFFFFFF) & (width - 1); // width is a power of two -> fast modulo via mask
    }
}
