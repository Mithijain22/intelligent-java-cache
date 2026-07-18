package com.intelligentcache.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Least-Recently-Used cache, implemented from scratch with a {@link HashMap}
 * for O(1) key lookup plus an intrusive doubly linked list for O(1)
 * most-recently-used reordering and O(1) eviction of the least-recently-used
 * entry. ({@link java.util.LinkedHashMap} with {@code accessOrder=true} gives
 * you this "for free" in the JDK -- worth knowing about and mentioning in an
 * interview -- but the point of this project is to build the real mechanism.)
 *
 * <h2>Concurrency design</h2>
 * This is intentionally <b>coarse-grained locking</b>: a single
 * {@link ReentrantLock} guards both the map and the linked list for every
 * operation, including {@code get}. That might look naive, but it's the
 * correct starting point and a fair one to defend: LRU's whole definition
 * requires that every read <i>mutates</i> shared structure (the accessed node
 * moves to the front of the list), so unlike a pure read path, there is no
 * "cheap read, expensive write" split to exploit with a
 * {@link java.util.concurrent.locks.ReadWriteLock} -- reads are writes here.
 * Lock striping (splitting the keyspace across N locks, each guarding its own
 * sub-map + sub-list) is the natural next optimization if profiling shows
 * contention, and is deliberately left as a documented extension rather than
 * implemented speculatively -- premature striping would only add complexity
 * without a benchmark showing it's needed.
 *
 * All public methods hold the lock for the shortest span that keeps the
 * map and list consistent; no I/O or listener callbacks happen while holding it and
 * listener callbacks are dispatched with the value already captured, after
 * mutating the internal map/list, but note the lock is still held during the
 * callback itself unless you widen the try/finally -- kept simple here since
 * eviction listeners in this project are cheap (stats increments / event
 * publishing to a bounded queue), not blocking I/O.
 */
public final class LRUCache<K, V> implements Cache<K, V> {

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
    private final Map<K, Node<K, V>> map;
    private final ReentrantLock lock = new ReentrantLock();
    private final CacheStats stats = new CacheStats();
    private volatile EvictionListener<K, V> evictionListener;

    // Sentinel nodes simplify edge cases (empty list, single element) by
    // removing all null-checks at the boundaries.
    private final Node<K, V> head = new Node<>(null, null); // head.next == most recently used
    private final Node<K, V> tail = new Node<>(null, null); // tail.prev == least recently used

    public LRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        this.map = new HashMap<>();
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
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                moveToFront(existing);
                stats.recordPut();
                return;
            }

            if (map.size() >= capacity) {
                Node<K, V> victim = tail.prev;
                if (victim != head) { // list non-empty
                    unlink(victim);
                    map.remove(victim.key);
                    evictedKey = victim.key;
                    evictedValue = victim.value;
                    evicted = true;
                    stats.recordEviction();
                }
            }

            Node<K, V> fresh = new Node<>(key, value);
            map.put(key, fresh);
            addToFront(fresh);
            stats.recordPut();
        } finally {
            lock.unlock();
        }

        if (evicted && evictionListener != null) {
            evictionListener.onEvict(evictedKey, evictedValue, "LRU capacity eviction");
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
        return "LRU";
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

    /** Returns keys from most-recently-used to least-recently-used. Test/debug helper. */
    java.util.List<K> orderSnapshot() {
        lock.lock();
        try {
            java.util.List<K> result = new java.util.ArrayList<>(map.size());
            Node<K, V> cur = head.next;
            while (cur != tail) {
                result.add(cur.key);
                cur = cur.next;
            }
            return result;
        } finally {
            lock.unlock();
        }
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
