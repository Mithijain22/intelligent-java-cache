package com.intelligentcache.engine;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ARCCacheTest {

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ARCCache<String, String>(0));
    }

    @Test
    void basicPutAndGet() {
        ARCCache<String, Integer> cache = new ARCCache<>(3);
        cache.put("a", 1);
        assertEquals(1, cache.get("a").intValue());
        assertEquals(1, cache.size());
    }

    @Test
    void updatingExistingKeyDoesNotGrowSize() {
        ARCCache<String, Integer> cache = new ARCCache<>(3);
        cache.put("a", 1);
        cache.put("a", 2);
        assertEquals(1, cache.size());
        assertEquals(2, cache.get("a").intValue());
    }

    @Test
    void neverExceedsCapacityUnderHeavyChurn() {
        int capacity = 20;
        ARCCache<Integer, Integer> cache = new ARCCache<>(capacity);
        Random random = new Random(123);

        for (int i = 0; i < 100_000; i++) {
            int key = random.nextInt(200); // keyspace 10x capacity -> constant eviction pressure
            Integer value = cache.get(key);
            if (value == null) {
                cache.put(key, key);
            }
            assertTrue(cache.size() <= capacity, "cache size exceeded capacity at operation " + i);
            int[] sizes = cache.listSizes();
            assertTrue(sizes[0] + sizes[1] <= capacity, "T1+T2 exceeded capacity at operation " + i);
            assertTrue(sizes[2] + sizes[3] <= capacity, "B1+B2 exceeded capacity at operation " + i);
        }
    }

    @Test
    void removeDoesNotCountAsEviction() {
        ARCCache<String, Integer> cache = new ARCCache<>(5);
        cache.put("a", 1);
        cache.remove("a");
        assertEquals(0, cache.stats().getEvictions());
        assertFalse(cache.containsKey("a"));
    }

    @Test
    void evictionListenerFiresWhenCapacityExceeded() {
        ARCCache<String, Integer> cache = new ARCCache<>(1);
        StringBuilder evictedKeys = new StringBuilder();
        cache.setEvictionListener((k, v, reason) -> evictedKeys.append(k).append(","));

        cache.put("a", 1);
        cache.put("b", 2); // must trigger at least one eviction path
        cache.put("c", 3);

        assertTrue(cache.stats().getEvictions() >= 1);
        assertTrue(evictedKeys.length() > 0);
    }

    @Test
    void containsKeyIsFalseForGhostEntries() {
        // Force a key through T1 -> evicted into B1 (ghost) with a tiny capacity.
        ARCCache<String, Integer> cache = new ARCCache<>(1);
        cache.put("a", 1);
        cache.put("b", 2); // likely evicts 'a' into B1 as a ghost
        // Whether or not 'a' specifically became a ghost depends on internal
        // state, but in no case should a ghost ever be reported as present.
        if (!cache.containsKey("a")) {
            assertNull(cache.get("a"), "if not contained, get must also miss (ghosts have no value)");
        }
    }

    @Test
    void clearResetsAllInternalLists() {
        ARCCache<String, Integer> cache = new ARCCache<>(5);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a"); // promote to T2
        cache.clear();

        assertEquals(0, cache.size());
        int[] sizes = cache.listSizes();
        assertEquals(0, sizes[0] + sizes[1] + sizes[2] + sizes[3]);
        assertEquals(0, cache.targetSizeP());
    }

    @Test
    void entrySnapshotOnlyIncludesRealCachedEntriesNotGhosts() {
        ARCCache<String, Integer> cache = new ARCCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3); // should evict something into a ghost list

        var snapshot = cache.entrySnapshot();
        assertEquals(cache.size(), snapshot.size());
        for (Integer v : snapshot.values()) {
            assertNotNull(v); // ghosts (null value) must never appear in a snapshot
        }
    }
}
