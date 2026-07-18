package com.intelligentcache.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Least-Frequently-Used cache with true O(1) get/put/eviction, built with the
 * classic "frequency buckets" approach:
 *
 * <ul>
 *   <li>{@code keyMap}: key -> node, for O(1) lookup.</li>
 *   <li>{@code freqBuckets}: frequency -> doubly linked list of all nodes
 *       currently at that access frequency, most-recently-touched at the
 *       front. Keeping each bucket itself ordered by recency is what makes
 *       the frequency-tie case O(1) instead of requiring a scan: when two
 *       entries are tied on frequency, the true LFU entry is whichever is at
 *       the <i>back</i> of its bucket (i.e. it degrades gracefully to LRU
 *       among ties, which is the standard, well-justified tie-break).</li>
 *   <li>{@code minFreq}: the smallest frequency currently present anywhere,
 *       maintained incrementally so eviction never has to scan for the
 *       minimum -- eviction is always "pop the back of {@code freqBuckets.get(minFreq)}".</li>
 * </ul>
 *
 * <h2>Frequency-tie handling</h2>
 * If two entries have the same access count, the one that was touched
 * <i>least recently</i> among them is evicted first. This is the standard
 * LFU tie-break (falling back to LRU ordering within a frequency class) and
 * is what keeps the whole structure O(1): without it you'd need some
 * secondary ordering criterion that isn't free to maintain incrementally.
 *
 * <h2>Concurrency</h2>
 * Same coarse-grained single-lock design as {@link LRUCache}, for the same
 * reason: every {@code get} mutates bucket structure (frequency bump), so
 * there's no read/write split to exploit with a read-write lock.
 */
public final class LFUCache<K, V> implements Cache<K, V> {

    private static final class Node<K, V> {
        final K key;
        V value;
        int freq;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.freq = 1;
        }
    }

    /** Intrusive doubly linked list with sentinels; front = most recently touched. */
    private static final class Bucket<K, V> {
        final Node<K, V> head = new Node<>(null, null);
        final Node<K, V> tail = new Node<>(null, null);
        int size = 0;

        Bucket() {
            head.next = tail;
            tail.prev = head;
        }

        void addFirst(Node<K, V> node) {
            node.prev = head;
            node.next = head.next;
            head.next.prev = node;
            head.next = node;
            size++;
        }

        void remove(Node<K, V> node) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
            node.prev = null;
            node.next = null;
            size--;
        }

        Node<K, V> removeLast() {
            Node<K, V> victim = tail.prev;
            remove(victim);
            return victim;
        }

        boolean isEmpty() {
            return size == 0;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> keyMap = new HashMap<>();
    private final Map<Integer, Bucket<K, V>> freqBuckets = new HashMap<>();
    private int minFreq = 0;
    private final ReentrantLock lock = new ReentrantLock();
    private final CacheStats stats = new CacheStats();
    private volatile EvictionListener<K, V> evictionListener;

    public LFUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
    }

    public void setEvictionListener(EvictionListener<K, V> listener) {
        this.evictionListener = listener;
    }

    @Override
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = keyMap.get(key);
            if (node == null) {
                stats.recordMiss();
                return null;
            }
            bumpFrequency(node);
            stats.recordHit();
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        K evictedKey = null;
        V evictedValue = null;
        boolean evicted = false;

        lock.lock();
        try {
            Node<K, V> existing = keyMap.get(key);
            if (existing != null) {
                existing.value = value;
                bumpFrequency(existing);
                stats.recordPut();
                return;
            }

            if (keyMap.size() >= capacity) {
                Bucket<K, V> minBucket = freqBuckets.get(minFreq);
                Node<K, V> victim = minBucket.removeLast();
                keyMap.remove(victim.key);
                evictedKey = victim.key;
                evictedValue = victim.value;
                evicted = true;
                stats.recordEviction();
                // Note: we deliberately do NOT remove the (now possibly empty)
                // bucket object from freqBuckets here -- it'll be reused the
                // next time that frequency is populated, and an empty Bucket
                // is cheap to keep around. minFreq will be reset below.
            }

            Node<K, V> fresh = new Node<>(key, value); // freq = 1
            keyMap.put(key, fresh);
            freqBuckets.computeIfAbsent(1, f -> new Bucket<>()).addFirst(fresh);
            minFreq = 1;
            stats.recordPut();
        } finally {
            lock.unlock();
        }

        if (evicted && evictionListener != null) {
            evictionListener.onEvict(evictedKey, evictedValue, "LFU capacity eviction");
        }
    }

    @Override
    public V remove(K key) {
        lock.lock();
        try {
            Node<K, V> node = keyMap.remove(key);
            if (node == null) {
                return null;
            }
            Bucket<K, V> bucket = freqBuckets.get(node.freq);
            bucket.remove(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean containsKey(K key) {
        lock.lock();
        try {
            return keyMap.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return keyMap.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            keyMap.clear();
            freqBuckets.clear();
            minFreq = 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CacheStats stats() {
        return stats;
    }

    @Override
    public String policyName() {
        return "LFU";
    }

    @Override
    public Map<K, V> entrySnapshot() {
        lock.lock();
        try {
            Map<K, V> result = new HashMap<>(keyMap.size());
            for (Map.Entry<K, Node<K, V>> e : keyMap.entrySet()) {
                result.put(e.getKey(), e.getValue().value);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current access frequency for a key, or -1 if absent. Test/debug helper. */
    int frequencyOf(K key) {
        lock.lock();
        try {
            Node<K, V> node = keyMap.get(key);
            return node == null ? -1 : node.freq;
        } finally {
            lock.unlock();
        }
    }

    // Caller must hold `lock`.
    private void bumpFrequency(Node<K, V> node) {
        int oldFreq = node.freq;
        Bucket<K, V> oldBucket = freqBuckets.get(oldFreq);
        oldBucket.remove(node);
        if (oldBucket.isEmpty() && minFreq == oldFreq) {
            minFreq++;
        }
        node.freq++;
        freqBuckets.computeIfAbsent(node.freq, f -> new Bucket<>()).addFirst(node);
    }
}
