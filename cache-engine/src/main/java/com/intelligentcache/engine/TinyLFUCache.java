package com.intelligentcache.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TinyLFU, as used inside Caffeine (the most widely used production Java
 * cache library): a normal LRU-ordered cache, but new keys aren't admitted
 * unconditionally when the cache is full -- instead, a new key only gets in
 * if its estimated recent access frequency (from a {@link CountMinSketch})
 * beats the frequency of whatever's currently the LRU eviction candidate.
 * If not, the {@code put()} is simply rejected: the existing entries stay,
 * and the new key doesn't get cached this time (though its frequency
 * estimate still went up, so it has a better shot on a future attempt).
 *
 * <p>This inverts the failure mode of plain LRU: LRU can be flooded by a
 * one-time sequential scan, evicting every genuinely hot key to make room
 * for items that will never be touched again. TinyLFU's admission filter
 * means a scan of never-repeated keys mostly just... fails to get admitted
 * at all, since each new key starts with a low frequency estimate and the
 * cache is already full of entries that have been touched before. See
 * {@code cache-benchmarks.md} for a real measured comparison.
 *
 * <h2>Documented simplification vs. production TinyLFU (Caffeine)</h2>
 * Caffeine's real W-TinyLFU additionally splits the cache into a small
 * "admission window" (plain LRU, ~1% of capacity) plus a segmented
 * probationary/protected main region, so a newly-popular key gets a short
 * grace period to prove itself before the frequency-based admission filter
 * has to judge it against long-established entries. This implementation
 * uses a single main LRU region with no separate window -- simpler to
 * reason about and test, and it still demonstrates the core mechanism (a
 * frequency sketch gating admission), but it will reject some legitimately
 * newly-popular keys that W-TinyLFU's window would have protected. Not
 * overselling this: it's "TinyLFU's admission idea, implemented plainly,"
 * not "Caffeine."
 */
public final class TinyLFUCache<K, V> implements Cache<K, V> {

    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final CountMinSketch<K> sketch;
    private final ReentrantLock lock = new ReentrantLock();
    private final CacheStats stats = new CacheStats();
    private volatile EvictionListener<K, V> evictionListener;

    private final Node<K, V> head = new Node<>(null, null); // MRU end
    private final Node<K, V> tail = new Node<>(null, null); // LRU end (eviction candidate)

    public TinyLFUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        this.sketch = new CountMinSketch<>(capacity);
        head.next = tail;
        tail.prev = head;
    }

    public void setEvictionListener(EvictionListener<K, V> listener) {
        this.evictionListener = listener;
    }

    @Override
    public V get(K key) {
        lock.lock();
        try {
            sketch.increment(key); // every access -- hit or miss -- is a frequency signal
            Node<K, V> node = map.get(key);
            if (node == null) {
                stats.recordMiss();
                return null;
            }
            moveToFront(node);
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
            sketch.increment(key);

            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                moveToFront(existing);
                stats.recordPut();
                return;
            }

            if (map.size() < capacity) {
                // Room available -- admit unconditionally, same as plain LRU
                // would. The admission filter only matters once the cache is
                // full and something has to lose its spot.
                Node<K, V> fresh = new Node<>(key, value);
                map.put(key, fresh);
                addToFront(fresh);
                stats.recordPut();
                return;
            }

            Node<K, V> victim = tail.prev; // current LRU eviction candidate
            int candidateFreq = sketch.estimate(key);
            int victimFreq = sketch.estimate(victim.key);

            if (candidateFreq > victimFreq) {
                unlink(victim);
                map.remove(victim.key);
                evictedKey = victim.key;
                evictedValue = victim.value;
                evicted = true;
                stats.recordEviction();

                Node<K, V> fresh = new Node<>(key, value);
                map.put(key, fresh);
                addToFront(fresh);
                stats.recordPut();
            } else {
                // Admission filter rejects the new key: the incumbent has an
                // equal-or-better frequency estimate, so it keeps its spot.
                // Nothing is cached for `key` this time.
                stats.recordRejection();
            }
        } finally {
            lock.unlock();
        }

        if (evicted && evictionListener != null) {
            evictionListener.onEvict(evictedKey, evictedValue,
                    "TinyLFU: evicted for lower estimated frequency than admission candidate");
        }
    }

    @Override
    public V remove(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node == null) {
                return null;
            }
            unlink(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean containsKey(K key) {
        lock.lock();
        try {
            return map.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
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
        return "TinyLFU";
    }

    @Override
    public Map<K, V> entrySnapshot() {
        lock.lock();
        try {
            Map<K, V> result = new HashMap<>(map.size());
            for (Map.Entry<K, Node<K, V>> e : map.entrySet()) {
                result.put(e.getKey(), e.getValue().value);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** Test/debug helper: the sketch's current frequency estimate for a key. */
    int estimatedFrequency(K key) {
        return sketch.estimate(key);
    }

    // --- Internal linked-list helpers. Callers must hold `lock`. ---

    private void addToFront(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    private void moveToFront(Node<K, V> node) {
        unlink(node);
        addToFront(node);
    }
}
