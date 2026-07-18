package com.intelligentcache.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LRUCacheTest {

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<String, String>(0));
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<String, String>(-1));
    }

    @Test
    void evictsLeastRecentlyUsedWhenOverCapacity() {
        // Capacity 3. Insert a,b,c -> full. Insert d -> must evict a (LRU).
        LRUCache<String, Integer> cache = new LRUCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.put("d", 4);

        assertNull(cache.get("a"), "a should have been evicted as least-recently-used");
        assertEquals(2, cache.get("b").intValue());
        assertEquals(3, cache.get("c").intValue());
        assertEquals(4, cache.get("d").intValue());
        assertEquals(3, cache.size());
        assertEquals(1, cache.stats().getEvictions());
    }

    @Test
    void getRefreshesRecencyAndProtectsFromEviction() {
        // Capacity 3: a,b,c. Access 'a' (now MRU). Insert d -> must evict 'b', not 'a'.
        LRUCache<String, Integer> cache = new LRUCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        cache.get("a"); // a becomes most-recently-used

        cache.put("d", 4);

        assertNull(cache.get("b"), "b should be evicted: it was the true LRU after 'a' was touched");
        assertEquals(1, cache.get("a").intValue());
        assertEquals(3, cache.get("c").intValue());
        assertEquals(4, cache.get("d").intValue());
    }

    @Test
    void updatingExistingKeyDoesNotEvictAndRefreshesRecency() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("a", 100); // update, 'a' becomes MRU, no eviction should occur

        assertEquals(0, cache.stats().getEvictions());
        assertEquals(2, cache.size());

        cache.put("c", 3); // now must evict 'b' (LRU), not 'a'
        assertNull(cache.get("b"));
        assertEquals(100, cache.get("a").intValue());
        assertEquals(3, cache.get("c").intValue());
    }

    @Test
    void orderSnapshotReflectsMostToLeastRecentlyUsed() {
        LRUCache<String, Integer> cache = new LRUCache<>(5);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.get("a"); // a -> MRU

        List<String> order = cache.orderSnapshot();
        assertEquals(List.of("a", "c", "b"), order);
    }

    @Test
    void evictionListenerFiresWithCorrectKeyAndValue() {
        LRUCache<String, Integer> cache = new LRUCache<>(1);
        AtomicInteger evictedKeyHolder = new AtomicInteger(-1);
        StringBuilder evictedKey = new StringBuilder();
        cache.setEvictionListener((k, v, reason) -> {
            evictedKey.append(k);
            evictedKeyHolder.set(v);
        });

        cache.put("a", 1);
        cache.put("b", 2); // evicts 'a'

        assertEquals("a", evictedKey.toString());
        assertEquals(1, evictedKeyHolder.get());
    }

    @Test
    void removeDoesNotCountAsEviction() {
        LRUCache<String, Integer> cache = new LRUCache<>(3);
        cache.put("a", 1);
        cache.remove("a");
        assertEquals(0, cache.stats().getEvictions());
        assertEquals(0, cache.size());
    }

    @Test
    void containsKeyDoesNotAffectRecencyOrder() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.containsKey("a"); // must NOT promote 'a'

        cache.put("c", 3); // 'a' is still LRU -> evicted, not 'b'
        assertNull(cache.get("a"));
        assertEquals(2, cache.get("b").intValue());
    }
}
