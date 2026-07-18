package com.intelligentcache.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe hit/miss/eviction counters for a single cache instance.
 * Uses {@link AtomicLong} rather than synchronized methods -- these counters
 * are incremented on every single get/put, so they're by far the hottest
 * path in the whole system, and atomics avoid lock contention there.
 *
 * This is intentionally a plain mutable counters object (not a Java
 * {@code record}) because it's mutated in place at very high frequency;
 * {@link #snapshot()} returns an immutable value object for callers
 * (the dashboard, the benchmarking harness) that just want a point-in-time read.
 */
public final class CacheStats {

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong puts = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();
    private final AtomicLong rejections = new AtomicLong(); // TinyLFU admission-filter rejections; always 0 for other policies

    void recordHit() { hits.incrementAndGet(); }
    void recordMiss() { misses.incrementAndGet(); }
    void recordEviction() { evictions.incrementAndGet(); }
    void recordPut() { puts.incrementAndGet(); }
    void recordExpiration() { expirations.incrementAndGet(); }
    void recordRejection() { rejections.incrementAndGet(); }

    public long getHits() { return hits.get(); }
    public long getMisses() { return misses.get(); }
    public long getEvictions() { return evictions.get(); }
    public long getPuts() { return puts.get(); }
    public long getExpirations() { return expirations.get(); }
    public long getRejections() { return rejections.get(); }

    public double hitRate() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        return total == 0 ? 0.0 : (double) h / total;
    }

    public void reset() {
        hits.set(0);
        misses.set(0);
        evictions.set(0);
        puts.set(0);
        expirations.set(0);
        rejections.set(0);
    }

    /** Immutable point-in-time view, safe to hand to the dashboard/benchmark harness. */
    public record Snapshot(long hits, long misses, long evictions, long puts,
                            long expirations, long rejections, double hitRate) {}

    public Snapshot snapshot() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double rate = total == 0 ? 0.0 : (double) h / total;
        return new Snapshot(h, m, evictions.get(), puts.get(), expirations.get(), rejections.get(), rate);
    }

    @Override
    public String toString() {
        Snapshot s = snapshot();
        return "CacheStats{hits=%d, misses=%d, hitRate=%.2f%%, evictions=%d, puts=%d, expirations=%d, rejections=%d}"
                .formatted(s.hits(), s.misses(), s.hitRate() * 100, s.evictions(), s.puts(), s.expirations(), s.rejections());
    }
}
