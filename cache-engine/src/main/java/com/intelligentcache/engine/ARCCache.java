package com.intelligentcache.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Adaptive Replacement Cache, following Megiddo &amp; Modha's ARC algorithm
 * (IBM, FAST 2003). ARC keeps four lists instead of one:
 *
 * <ul>
 *   <li><b>T1</b>: entries seen exactly once recently -- the "recency" list, LRU-ordered.</li>
 *   <li><b>T2</b>: entries seen two or more times recently -- the "frequency" list, LRU-ordered.</li>
 *   <li><b>B1</b>: "ghost" entries -- keys recently evicted from T1. No value is stored,
 *       just the fact that this key used to be in T1. A hit in B1 means "something
 *       we evicted for being merely recent came back" -- evidence T1 should be
 *       bigger.</li>
 *   <li><b>B2</b>: ghost entries recently evicted from T2. A hit in B2 means "something
 *       we evicted despite being frequently used came back" -- evidence T2 should
 *       be bigger.</li>
 * </ul>
 *
 * {@code T1 ∪ T2} holds the real cached data, bounded by capacity {@code c}.
 * {@code B1 ∪ B2} holds only keys (no values), used purely as recent-eviction
 * history, and is what lets ARC learn online whether the current workload
 * favors recency or frequency -- entirely from its own hit/miss history, with
 * no external tuning parameter. The target split point {@code p} (how much of
 * the real cache "belongs" to T1 before it starts stealing from T2) is
 * adapted after every ghost-list hit: a B1 hit nudges {@code p} up (favor
 * recency more), a B2 hit nudges it down (favor frequency more). This
 * self-tuning is exactly why ARC tends to match or beat the better of
 * plain LRU/LFU on workloads that shift character over time, without
 * anyone choosing a policy in advance.
 *
 * <h2>get() vs put() semantics for ghost hits</h2>
 * This project's {@link Cache} interface separates {@code get} (read) from
 * {@code put} (write-on-miss), matching the cache-aside pattern the
 * benchmarking harness (Phase 6) replays. A ghost-list "hit" in the ARC
 * paper assumes the caller immediately re-fetches and re-inserts the value
 * on that hit -- which, in this interface, is exactly what happens on the
 * {@code put()} call following a {@code get()} miss. So: {@code get()} only
 * ever looks at T1/T2 (a ghost entry has no value to return -- it's
 * correctly a miss); all of ARC's adaptive logic (cases II/III/IV in the
 * paper) lives in {@code put()}, triggered by the miss-then-insert that a
 * ghost hit produces.
 */
public final class ARCCache<K, V> implements Cache<K, V> {

    private enum ListId { T1, T2, B1, B2 }

    private static final class Node<K, V> {
        final K key;
        V value; // null for ghost nodes (B1/B2)
        ListId list;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value, ListId list) {
            this.key = key;
            this.value = value;
            this.list = list;
        }
    }

    /** Intrusive doubly linked list; front = MRU, back = LRU (evict from the back). */
    private static final class Dlist<K, V> {
        final Node<K, V> head = new Node<>(null, null, null);
        final Node<K, V> tail = new Node<>(null, null, null);
        int size = 0;

        Dlist() {
            head.next = tail;
            tail.prev = head;
        }

        void addFirst(Node<K, V> n) {
            n.prev = head;
            n.next = head.next;
            head.next.prev = n;
            head.next = n;
            size++;
        }

        void remove(Node<K, V> n) {
            n.prev.next = n.next;
            n.next.prev = n.prev;
            n.prev = null;
            n.next = null;
            size--;
        }

        Node<K, V> removeLast() {
            Node<K, V> victim = tail.prev;
            remove(victim);
            return victim;
        }
    }

    private final int c; // real cache capacity (|T1| + |T2| <= c)
    private int p = 0;    // adaptive target size for T1

    private final Map<K, Node<K, V>> index = new HashMap<>();
    private final Dlist<K, V> t1 = new Dlist<>();
    private final Dlist<K, V> t2 = new Dlist<>();
    private final Dlist<K, V> b1 = new Dlist<>();
    private final Dlist<K, V> b2 = new Dlist<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final CacheStats stats = new CacheStats();
    private volatile EvictionListener<K, V> evictionListener;

    public ARCCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.c = capacity;
    }

    public void setEvictionListener(EvictionListener<K, V> listener) {
        this.evictionListener = listener;
    }

    @Override
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> n = index.get(key);
            if (n == null || n.list == ListId.B1 || n.list == ListId.B2) {
                stats.recordMiss(); // ghost entries have no value -- correctly a miss
                return null;
            }
            // Real hit in T1 or T2: any access promotes to T2 (MRU) -- ARC
            // treats "accessed at least twice" (once to insert, once here)
            // as the frequency signal, same as it would after a second put.
            (n.list == ListId.T1 ? t1 : t2).remove(n);
            n.list = ListId.T2;
            t2.addFirst(n);
            stats.recordHit();
            return n.value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        K evictedKey = null;
        V evictedValue = null;
        String evictedReason = null;

        lock.lock();
        try {
            Node<K, V> n = index.get(key);

            if (n != null && (n.list == ListId.T1 || n.list == ListId.T2)) {
                // Case I: already cached -- update and promote to T2.
                (n.list == ListId.T1 ? t1 : t2).remove(n);
                n.value = value;
                n.list = ListId.T2;
                t2.addFirst(n);
                stats.recordPut();
                return;
            }

            if (n != null && n.list == ListId.B1) {
                // Case II: ghost hit in B1 -- evidence T1 should be bigger.
                int delta = Math.max(1, b2.size == 0 ? b1.size : b1.size == 0 ? 1 : b2.size / b1.size);
                p = Math.min(c, p + delta);
                var evicted = replace(false);
                if (evicted != null) {
                    evictedKey = evicted.key;
                    evictedValue = evicted.value;
                    evictedReason = "ARC replace() during B1 ghost-hit adaptation";
                }
                b1.remove(n);
                n.value = value;
                n.list = ListId.T2;
                t2.addFirst(n);
                stats.recordPut();
            } else if (n != null && n.list == ListId.B2) {
                // Case III: ghost hit in B2 -- evidence T2 should be bigger.
                int delta = Math.max(1, b1.size == 0 ? b2.size : b2.size == 0 ? 1 : b1.size / b2.size);
                p = Math.max(0, p - delta);
                var evicted = replace(true);
                if (evicted != null) {
                    evictedKey = evicted.key;
                    evictedValue = evicted.value;
                    evictedReason = "ARC replace() during B2 ghost-hit adaptation";
                }
                b2.remove(n);
                n.value = value;
                n.list = ListId.T2;
                t2.addFirst(n);
                stats.recordPut();
            } else {
                // Case IV: true miss -- key isn't anywhere in ARC's memory.
                if (t1.size + b1.size == c) {
                    if (t1.size < c) {
                        Node<K, V> ghostVictim = b1.removeLast();
                        index.remove(ghostVictim.key);
                        var evicted = replace(false);
                        if (evicted != null) {
                            evictedKey = evicted.key;
                            evictedValue = evicted.value;
                            evictedReason = "ARC replace() during Case IV-A insert";
                        }
                    } else {
                        // |B1| == 0 and T1 is already at full capacity: evict
                        // directly from the real cache, no ghost created --
                        // there's no room left anywhere to remember it.
                        Node<K, V> victim = t1.removeLast();
                        index.remove(victim.key);
                        evictedKey = victim.key;
                        evictedValue = victim.value;
                        evictedReason = "ARC direct T1 eviction (Case IV-A, B1 empty)";
                    }
                } else if (t1.size + b1.size < c) {
                    int totalTracked = t1.size + t2.size + b1.size + b2.size;
                    if (totalTracked >= c) {
                        if (totalTracked == 2 * c) {
                            Node<K, V> ghostVictim = b2.removeLast();
                            index.remove(ghostVictim.key);
                        }
                        var evicted = replace(false);
                        if (evicted != null) {
                            evictedKey = evicted.key;
                            evictedValue = evicted.value;
                            evictedReason = "ARC replace() during Case IV-B insert";
                        }
                    }
                }

                Node<K, V> fresh = new Node<>(key, value, ListId.T1);
                t1.addFirst(fresh);
                index.put(key, fresh);
                stats.recordPut();
            }
        } finally {
            lock.unlock();
        }

        if (evictedKey != null && evictionListener != null) {
            evictionListener.onEvict(evictedKey, evictedValue, evictedReason);
        }
        if (evictedKey != null) {
            stats.recordEviction();
        }
    }

    /**
     * The paper's REPLACE(x, p) subroutine: move the LRU entry of either T1
     * or T2 into its corresponding ghost list, choosing which based on the
     * current target size {@code p}. Returns the evicted node (now living in
     * a ghost list with its value cleared) so the caller can report it, or
     * {@code null} if there was nothing to evict (both T1 and T2 empty).
     *
     * @param xInB2 true only when REPLACE is invoked from the B2 ghost-hit
     *              case (Case III) -- affects the T1-vs-T2 tie-break exactly
     *              as specified in the original algorithm.
     */
    private Node<K, V> replace(boolean xInB2) {
        if (t1.size >= 1 && ((xInB2 && t1.size == p) || t1.size > p)) {
            Node<K, V> victim = t1.removeLast();
            victim.value = null;
            victim.list = ListId.B1;
            b1.addFirst(victim);
            return victim;
        } else if (t2.size >= 1) {
            Node<K, V> victim = t2.removeLast();
            victim.value = null;
            victim.list = ListId.B2;
            b2.addFirst(victim);
            return victim;
        } else if (t1.size >= 1) {
            // t2 was empty but t1 has entries -- fall back to evicting from T1.
            Node<K, V> victim = t1.removeLast();
            victim.value = null;
            victim.list = ListId.B1;
            b1.addFirst(victim);
            return victim;
        }
        return null;
    }

    @Override
    public V remove(K key) {
        lock.lock();
        try {
            Node<K, V> n = index.remove(key);
            if (n == null) {
                return null;
            }
            V value = n.value;
            listFor(n.list).remove(n);
            return n.list == ListId.T1 || n.list == ListId.T2 ? value : null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean containsKey(K key) {
        lock.lock();
        try {
            Node<K, V> n = index.get(key);
            return n != null && (n.list == ListId.T1 || n.list == ListId.T2);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return t1.size + t2.size;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            index.clear();
            t1.head.next = t1.tail; t1.tail.prev = t1.head; t1.size = 0;
            t2.head.next = t2.tail; t2.tail.prev = t2.head; t2.size = 0;
            b1.head.next = b1.tail; b1.tail.prev = b1.head; b1.size = 0;
            b2.head.next = b2.tail; b2.tail.prev = b2.head; b2.size = 0;
            p = 0;
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
        return "ARC";
    }

    @Override
    public Map<K, V> entrySnapshot() {
        lock.lock();
        try {
            Map<K, V> result = new HashMap<>(t1.size + t2.size);
            for (Node<K, V> n = t1.head.next; n != t1.tail; n = n.next) result.put(n.key, n.value);
            for (Node<K, V> n = t2.head.next; n != t2.tail; n = n.next) result.put(n.key, n.value);
            return result;
        } finally {
            lock.unlock();
        }
    }

    private Dlist<K, V> listFor(ListId id) {
        return switch (id) {
            case T1 -> t1;
            case T2 -> t2;
            case B1 -> b1;
            case B2 -> b2;
        };
    }

    /** Debug/test helper: current sizes of all four internal lists. */
    int[] listSizes() {
        lock.lock();
        try {
            return new int[]{t1.size, t2.size, b1.size, b2.size};
        } finally {
            lock.unlock();
        }
    }

    /** Debug/test helper: current adaptive target size p. */
    int targetSizeP() {
        lock.lock();
        try {
            return p;
        } finally {
            lock.unlock();
        }
    }
}
