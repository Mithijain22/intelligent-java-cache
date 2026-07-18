package com.intelligentcache.engine.bench;

import com.intelligentcache.engine.Cache;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Replays a fixed sequence of keys against a {@link Cache} using the
 * standard "cache-aside" access pattern: on a hit, just read; on a miss,
 * "fetch" (simulated -- there's no real backing store here, we just insert
 * a placeholder value) and populate the cache so the next access to that
 * key can hit.
 *
 * <h2>What's actually timed</h2>
 * Only the {@link Cache#get} call itself is timed per operation. The
 * populate-on-miss {@code put()} deliberately happens outside the timed
 * span: in a real system, the caller-visible latency of a miss is dominated
 * by the backing-store fetch (which we're not simulating with a realistic
 * delay), not by the cache's own bookkeeping -- so timing only {@code get()}
 * keeps the reported latency meaningful as "cost of asking the cache",
 * comparable across policies, rather than being swamped by an arbitrary
 * fake fetch cost.
 *
 * Running the exact same trace against multiple policies back-to-back
 * (via {@link #runAll}) with fresh cache instances per run is what makes the
 * hit-rate/eviction-count differences in the results attributable to the
 * eviction policy alone -- everything else about the run is identical.
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {}

    private static final String PLACEHOLDER_VALUE = "v";

    /** Runs one trace against one already-constructed cache instance. */
    public static BenchmarkResult run(String traceLabel, List<String> keys, Cache<String, String> cache) {
        return run(traceLabel, keys, cache, null);
    }

    /**
     * Same as {@link #run(String, List, Cache)}, with an optional callback
     * fired after every operation (used by the API layer to stream live
     * progress to the dashboard during a trace replay -- see
     * {@code BenchmarkController} in cache-api).
     */
    public static BenchmarkResult run(String traceLabel, List<String> keys, Cache<String, String> cache,
                                       Consumer<OperationEvent> onEachOperation) {
        int n = keys.size();
        long[] latenciesNanos = new long[n];
        long startWallClock = System.currentTimeMillis();

        for (int i = 0; i < n; i++) {
            String key = keys.get(i);

            long t0 = System.nanoTime();
            String value = cache.get(key);
            long t1 = System.nanoTime();
            latenciesNanos[i] = t1 - t0;

            boolean hit = value != null;
            if (!hit) {
                cache.put(key, PLACEHOLDER_VALUE); // simulated backing-store populate, untimed
            }

            if (onEachOperation != null) {
                onEachOperation.accept(new OperationEvent(key, hit, i));
            }
        }

        long endWallClock = System.currentTimeMillis();
        long wallClockMillis = Math.max(1, endWallClock - startWallClock);

        var stats = cache.stats().snapshot();
        double avgLatency = average(latenciesNanos);
        double p99Latency = percentile(latenciesNanos, 0.99);
        double opsPerSecond = n / (wallClockMillis / 1000.0);

        return new BenchmarkResult(
                cache.policyName(), traceLabel, n,
                stats.hits(), stats.misses(), stats.hitRate(), stats.evictions(),
                avgLatency, p99Latency, opsPerSecond, wallClockMillis
        );
    }

    /**
     * Runs the same trace against several policies back-to-back, each with
     * a freshly constructed cache instance (from its factory), so results
     * are directly comparable -- no shared state or warm-cache advantage
     * leaks from one policy's run into the next.
     *
     * @param policyFactories name -&gt; supplier of a fresh {@code Cache} instance for that policy
     */
    public static Map<String, BenchmarkResult> runAll(
            String traceLabel, List<String> keys, Map<String, Supplier<Cache<String, String>>> policyFactories) {
        Map<String, BenchmarkResult> results = new LinkedHashMap<>();
        for (Map.Entry<String, Supplier<Cache<String, String>>> entry : policyFactories.entrySet()) {
            Cache<String, String> cache = entry.getValue().get();
            results.put(entry.getKey(), run(traceLabel, keys, cache));
        }
        return results;
    }

    private static double average(long[] values) {
        if (values.length == 0) return 0.0;
        long sum = 0;
        for (long v : values) sum += v;
        return (double) sum / values.length;
    }

    private static double percentile(long[] values, double p) {
        if (values.length == 0) return 0.0;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(p * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index];
    }

    /** Fired after each operation during a trace replay, for live progress streaming. */
    public record OperationEvent(String key, boolean hit, int operationIndex) {}
}
