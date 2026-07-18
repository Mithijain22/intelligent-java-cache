package com.intelligentcache.engine;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 checkpoint implementation: a thread-safe key-value store with
 * NO eviction policy. This exists purely to validate that the {@link Cache}
 * interface shape is right before any eviction logic is written -- every
 * later policy (LRU, LFU, ARC, TinyLFU) implements the exact same interface,
 * so getting this one "boring" implementation correct and well-tested first
 * means the interface itself is trustworthy.
 *
 * Thread-safety comes directly from {@link ConcurrentHashMap}, which gives
 * lock-striped (not single-lock) concurrent reads/writes out of the box.
 * There is deliberately no capacity enforcement here -- that's the whole
 * point of Phase 2+.
 */
public final class SimpleConcurrentCache<K, V> implements Cache<K, V> {

    private final ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();
    private final CacheStats stats = new CacheStats();

    @Override
    public V get(K key) {
        V value = store.get(key);
        if (value == null) {
            stats.recordMiss();
        } else {
            stats.recordHit();
        }
        return value;
    }

    @Override
    public void put(K key, V value) {
        store.put(key, value);
        stats.recordPut();
    }

    @Override
    public V remove(K key) {
        return store.remove(key);
    }

    @Override
    public boolean containsKey(K key) {
        return store.containsKey(key);
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public CacheStats stats() {
        return stats;
    }

    @Override
    public String policyName() {
        return "NONE (unbounded)";
    }

    @Override
    public java.util.Map<K, V> entrySnapshot() {
        return new java.util.HashMap<>(store);
    }
}
