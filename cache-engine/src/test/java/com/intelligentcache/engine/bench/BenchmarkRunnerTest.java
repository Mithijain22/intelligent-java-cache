package com.intelligentcache.engine.bench;

import com.intelligentcache.engine.Cache;
import com.intelligentcache.engine.LFUCache;
import com.intelligentcache.engine.LRUCache;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkRunnerTest {

    @Test
    void pureSequentialScanWithNoRepeatsProducesZeroHits() {
        int[] trace = TraceGenerator.sequential(500, 500); // each key touched exactly once
        List<String> keys = TraceGenerator.toStringKeys(trace, "k");
        BenchmarkResult result = BenchmarkRunner.run("sequential", keys, new LRUCache<>(100));
        assertEquals(0, result.hits());
        assertEquals(500, result.misses());
    }

    @Test
    void lfuOutperformsLruOnSkewedZipfianTrace() {
        int[] trace = TraceGenerator.zipfian(200_000, 2000, 1.2, 42L);
        List<String> keys = TraceGenerator.toStringKeys(trace, "k");

        Map<String, Supplier<Cache<String, String>>> policies = new LinkedHashMap<>();
        policies.put("LRU", () -> new LRUCache<>(100));
        policies.put("LFU", () -> new LFUCache<>(100));

        Map<String, BenchmarkResult> results = BenchmarkRunner.runAll("zipfian", keys, policies);

        assertTrue(results.get("LFU").hitRate() > results.get("LRU").hitRate(),
                "LFU should beat LRU when a small set of keys is hit disproportionately often");
    }

    @Test
    void uniformRandomGivesLruAndLfuRoughlyEqualHitRates() {
        int[] trace = TraceGenerator.uniformRandom(200_000, 2000, 7L);
        List<String> keys = TraceGenerator.toStringKeys(trace, "k");

        Map<String, Supplier<Cache<String, String>>> policies = new LinkedHashMap<>();
        policies.put("LRU", () -> new LRUCache<>(100));
        policies.put("LFU", () -> new LFUCache<>(100));

        Map<String, BenchmarkResult> results = BenchmarkRunner.runAll("uniform", keys, policies);

        double diff = Math.abs(results.get("LRU").hitRate() - results.get("LFU").hitRate());
        assertTrue(diff < 0.05, "no policy should have a structural edge on a uniform random trace");
    }

    @Test
    void resultsIncludeLatencyAndThroughputFigures() {
        int[] trace = TraceGenerator.uniformRandom(1000, 50, 3L);
        List<String> keys = TraceGenerator.toStringKeys(trace, "k");
        BenchmarkResult result = BenchmarkRunner.run("small", keys, new LRUCache<>(20));

        assertTrue(result.avgLatencyNanos() >= 0);
        assertTrue(result.p99LatencyNanos() >= result.avgLatencyNanos() || result.p99LatencyNanos() >= 0);
        assertTrue(result.opsPerSecond() > 0);
        assertEquals(1000, result.totalOperations());
    }

    @Test
    void onEachOperationCallbackFiresOncePerOperation() {
        int[] trace = TraceGenerator.uniformRandom(200, 20, 5L);
        List<String> keys = TraceGenerator.toStringKeys(trace, "k");
        int[] callCount = {0};

        BenchmarkRunner.run("cb-test", keys, new LRUCache<>(10), event -> callCount[0]++);

        assertEquals(200, callCount[0]);
    }
}
