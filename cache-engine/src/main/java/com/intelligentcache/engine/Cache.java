package com.intelligentcache.engine;

/**
 * Core contract for every cache implementation in this project (Phase 1 skeleton,
 * bare ConcurrentHashMap wrapper; LRU, LFU, ARC, TinyLFU in later phases; and,
 * eventually, {@code AdaptiveCache}) all implement this same interface. Keeping
 * the interface identical across policies is what lets the benchmarking harness
 * (Phase 6) and the adaptive layer (Phase 9) swap policies in and out without
 * caring which one they're driving.
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface Cache<K, V> {

    /**
     * Returns the value associated with {@code key}, or {@code null} if absent
     * or expired. A successful lookup counts as a "hit" for stats purposes; a
     * miss (absent or expired) counts as a "miss". Implementations that use
     * lazy TTL expiration should expire-and-remove the entry here if it has
     * passed its expiry time, then treat it as a miss.
     */
    V get(K key);

    /**
     * Inserts or updates the value for {@code key}. If the cache is at
     * capacity, the implementation's eviction policy chooses a victim to
     * evict before inserting the new entry. Overwriting an existing key must
     * NOT itself count as an eviction.
     */
    void put(K key, V value);

    /**
     * Inserts or updates the value for {@code key} with a time-to-live.
     * Default implementation delegates to {@link #put(Object, Object)} with
     * no expiration for caches that don't yet support TTL (Phase 1-3);
     * TTL-aware implementations (Phase 4+) override this.
     */
    default void put(K key, V value, java.time.Duration ttl) {
        put(key, value);
    }

    /**
     * Removes the mapping for {@code key} if present, returning the previous
     * value or {@code null}. Removing a key does not count as an eviction --
     * evictions are specifically policy-driven removals due to capacity
     * pressure, tracked separately in {@link CacheStats}.
     */
    V remove(K key);

    /**
     * Returns true if {@code key} is present and not expired. Implementations
     * should NOT count this as a hit/miss for stats purposes -- it's a
     * existence check, not a read.
     */
    boolean containsKey(K key);

    /** Current number of live entries in the cache. */
    int size();

    /** Removes all entries and resets size to zero. Does not reset stats. */
    void clear();

    /** Returns a live, thread-safe snapshot of hit/miss/eviction counters. */
    CacheStats stats();

    /** Human-readable name of the eviction policy, e.g. "LRU", "LFU", "TinyLFU". */
    String policyName();

    /**
     * Returns a defensive copy of all current key-value pairs, in whatever
     * order the implementation finds cheapest to produce (no ordering
     * guarantee). Used by {@link SnapshotManager} for persistence and by
     * admin/debug tooling. This is an O(n) operation that takes the cache's
     * internal lock for its duration -- it is NOT meant for the hot path,
     * only for periodic snapshotting.
     */
    java.util.Map<K, V> entrySnapshot();
}
