package com.intelligentcache.engine.bench;

/**
 * Outcome of replaying one trace against one cache policy. Everything the
 * dashboard's comparison chart needs in one shape, so the frontend never has
 * to reach back into a live {@code Cache} instance -- results are captured
 * as plain values at the moment the run finished.
 */
public record BenchmarkResult(
        String policyName,
        String traceLabel,
        long totalOperations,
        long hits,
        long misses,
        double hitRate,
        long evictions,
        double avgLatencyNanos,
        double p99LatencyNanos,
        double opsPerSecond,
        long wallClockMillis
) {
    @Override
    public String toString() {
        return "%s on %s: hitRate=%.2f%%, evictions=%d, avgLatency=%.0fns, p99=%.0fns, throughput=%.0f ops/sec"
                .formatted(policyName, traceLabel, hitRate * 100, evictions, avgLatencyNanos, p99LatencyNanos, opsPerSecond);
    }
}
