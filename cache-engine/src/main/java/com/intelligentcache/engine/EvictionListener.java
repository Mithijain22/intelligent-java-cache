package com.intelligentcache.engine;

/**
 * Callback fired whenever a policy evicts an entry due to capacity pressure
 * (not a plain {@code remove()}). The REST/WebSocket layer (Phase 5) attaches
 * a listener here to stream eviction events to the dashboard live; the
 * benchmarking harness (Phase 6) attaches one to count evictions per trace run.
 *
 * Kept as a tiny functional interface (not baked into CacheStats directly) so
 * multiple independent listeners can observe the same cache without polling.
 */
@FunctionalInterface
public interface EvictionListener<K, V> {
    void onEvict(K key, V value, String reason);
}
