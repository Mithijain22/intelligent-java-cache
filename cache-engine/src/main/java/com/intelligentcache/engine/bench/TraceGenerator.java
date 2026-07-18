package com.intelligentcache.engine.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates synthetic access traces (sequences of key indices) used to drive
 * the same workload against multiple eviction policies for a fair
 * side-by-side comparison. Each trace is returned as {@code int[]} for
 * memory/CPU efficiency at large trace lengths -- callers map indices to
 * string keys (e.g. {@code "key-" + index}) at replay time.
 *
 * <h2>Why these four patterns specifically</h2>
 * <ul>
 *   <li><b>sequential</b>: keys 0,1,2,...,N-1 touched once each, in order.
 *       Models a one-time scan over a dataset. Any recency-based policy
 *       (LRU) does fine here as long as capacity &gt;= working set; it's the
 *       baseline "nothing interesting happens" case.</li>
 *   <li><b>uniformRandom</b>: every key equally likely on every access.
 *       No policy has an edge here -- hit rate is purely
 *       {@code capacity / keyRange} regardless of eviction strategy. Useful
 *       as a control: if your dashboard shows LRU and LFU tied on this
 *       trace, that's a correctness signal, not a bug.</li>
 *   <li><b>zipfian</b>: a small number of "hot" keys receive a
 *       disproportionate share of accesses (the classic 80/20-style
 *       real-world cache workload -- think: a handful of viral posts
 *       dominating a social feed's read traffic). This is where
 *       frequency-aware policies (LFU, TinyLFU) are expected to
 *       out-perform pure recency (LRU), because a hot key should never get
 *       evicted just because something else was touched more recently.</li>
 *   <li><b>looping</b>: a fixed cycle of {@code loopSize} keys repeated over
 *       and over. When {@code loopSize > capacity}, this is a classic
 *       LRU pathological case: every key is evicted exactly before its next
 *       use, so LRU's hit rate collapses toward zero even though the
 *       working set repeats perfectly predictably. Measured behavior in
 *       this project's own benchmark (see {@code BenchVerify} /
 *       {@code cache-benchmarks.md}): plain LFU collapses here too, and
 *       for a subtle but important reason -- with every key in the loop
 *       touched exactly once per cycle, all frequencies stay perpetually
 *       tied, so LFU's tie-break (fall back to recency) makes it behave
 *       just like LRU on this specific pattern. This is exactly the real
 *       motivation for ARC and TinyLFU (Phase 8): ARC keeps "ghost" history
 *       of recently-evicted keys so a returning key isn't treated as brand
 *       new, and TinyLFU's frequency sketch persists popularity estimates
 *       across a much longer window than one loop cycle. Don't oversell
 *       plain LFU against this trace in the README -- the benchmark data
 *       doesn't support it, and the honest story ("LFU alone doesn't fix
 *       this, here's what does") is the more interesting one anyway.</li>
 * </ul>
 */
public final class TraceGenerator {

    private TraceGenerator() {}

    /** Keys 0..keyRange-1 repeated in order until {@code length} accesses are produced. */
    public static int[] sequential(int length, int keyRange) {
        int[] trace = new int[length];
        for (int i = 0; i < length; i++) {
            trace[i] = i % keyRange;
        }
        return trace;
    }

    /** Every access picks uniformly at random from 0..keyRange-1. */
    public static int[] uniformRandom(int length, int keyRange, long seed) {
        Random random = new Random(seed);
        int[] trace = new int[length];
        for (int i = 0; i < length; i++) {
            trace[i] = random.nextInt(keyRange);
        }
        return trace;
    }

    /**
     * Zipfian-distributed accesses: key "rank 0" (index 0) is the most
     * popular, rank {@code keyRange-1} the least, with popularity
     * proportional to {@code 1 / rank^exponent}.
     *
     * @param exponent skew factor, "theta" in Zipfian literature. 0.0 = uniform,
     *                 ~1.0 = classic Zipf's law (e.g. word frequency in
     *                 natural language), higher = more skewed / hotter head.
     *                 1.2-1.5 gives a strongly skewed "few very hot keys"
     *                 pattern typical of production cache workloads.
     */
    public static int[] zipfian(int length, int keyRange, double exponent, long seed) {
        if (keyRange <= 0) {
            throw new IllegalArgumentException("keyRange must be positive");
        }
        // Precompute the cumulative distribution once (O(keyRange)), then
        // each sample is an O(log keyRange) binary search against it.
        double[] cumulative = new double[keyRange];
        double normalizer = 0.0;
        for (int rank = 1; rank <= keyRange; rank++) {
            normalizer += 1.0 / Math.pow(rank, exponent);
        }
        double running = 0.0;
        for (int rank = 1; rank <= keyRange; rank++) {
            running += (1.0 / Math.pow(rank, exponent)) / normalizer;
            cumulative[rank - 1] = running;
        }
        cumulative[keyRange - 1] = 1.0; // guard against floating-point drift

        Random random = new Random(seed);
        int[] trace = new int[length];
        for (int i = 0; i < length; i++) {
            double u = random.nextDouble();
            trace[i] = lowerBound(cumulative, u);
        }
        return trace;
    }

    /** First index whose cumulative value is &gt;= target. */
    private static int lowerBound(double[] cumulative, double target) {
        int lo = 0, hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Repeats a fixed cycle of {@code loopSize} keys (0..loopSize-1) until
     * {@code length} total accesses are produced. Pass a {@code loopSize}
     * larger than the cache capacity you're testing to reproduce the classic
     * LRU-thrashing scenario.
     */
    public static int[] looping(int length, int loopSize) {
        int[] trace = new int[length];
        for (int i = 0; i < length; i++) {
            trace[i] = i % loopSize;
        }
        return trace;
    }

    /**
     * Loads a trace from a single-column CSV file, one key per line (no
     * header). Keys are treated as opaque strings, not remapped to indices
     * -- this is for user-uploaded real traces, as opposed to the synthetic
     * generators above which work in index space.
     */
    public static List<String> fromCsv(Path path) throws IOException {
        List<String> keys = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                keys.add(trimmed);
            }
        }
        return keys;
    }

    /** Converts an index trace to string keys with a stable prefix, e.g. "key-0", "key-1", ... */
    public static List<String> toStringKeys(int[] indexTrace, String prefix) {
        List<String> keys = new ArrayList<>(indexTrace.length);
        for (int idx : indexTrace) {
            keys.add(prefix + idx);
        }
        return keys;
    }
}
