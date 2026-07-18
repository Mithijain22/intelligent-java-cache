package com.intelligentcache.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LFUCacheTest {

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LFUCache<String, String>(0));
    }

    @Test
    void evictsLeastFrequentlyUsedEntry() {
        LFUCache<String, Integer> cache = new LFUCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        cache.get("a"); // freq(a)=2
        cache.get("a"); // freq(a)=3
        cache.get("b"); // freq(b)=2
        // freq(c) still 1 -- c is the least frequently used

        cache.put("d", 4); // must evict 'c'

        assertNull(cache.get("c"));
        assertNotNull(cache.get("a"));
        assertNotNull(cache.get("b"));
        assertNotNull(cache.get("d"));
        assertEquals(1, cache.stats().getEvictions());
    }

    @Test
    void tieOnFrequencyFallsBackToLeastRecentlyUsed() {
        // a, b, c all inserted with freq=1 (tied). Touch 'b' then 'c' so
        // 'a' is the least-recently-touched among the freq=1 tier.
        LFUCache<String, Integer> cache = new LFUCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        // all freq=1. Access order so far: a inserted, b inserted, c inserted.
        // Touching nothing yet -- 'a' is oldest in the freq=1 bucket.

        cache.put("d", 4); // tie among a,b,c (all freq 1) -> LRU among them is 'a'

        assertNull(cache.get("a"), "a should be evicted: tied on frequency, least recently touched");
    }

    @Test
    void frequencyIncrementsOnEachGet() {
        LFUCache<String, Integer> cache = new LFUCache<>(3);
        cache.put("a", 1);
        assertEquals(1, cache.frequencyOf("a"));
        cache.get("a");
        assertEquals(2, cache.frequencyOf("a"));
        cache.get("a");
        assertEquals(3, cache.frequencyOf("a"));
    }

    @Test
    void updatingExistingKeyBumpsFrequencyWithoutEviction() {
        LFUCache<String, Integer> cache = new LFUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("a", 100); // update -- must NOT evict, must bump freq(a)

        assertEquals(0, cache.stats().getEvictions());
        assertEquals(2, cache.frequencyOf("a"));
        assertEquals(100, cache.get("a").intValue());
    }

    @Test
    void evictionListenerFiresOnCapacityEviction() {
        LFUCache<String, Integer> cache = new LFUCache<>(1);
        StringBuilder evicted = new StringBuilder();
        cache.setEvictionListener((k, v, reason) -> evicted.append(k));

        cache.put("a", 1);
        cache.put("b", 2); // evicts 'a', the only entry

        assertEquals("a", evicted.toString());
    }

    @Test
    void removeDoesNotCountAsEvictionAndCleansUpBucket() {
        LFUCache<String, Integer> cache = new LFUCache<>(3);
        cache.put("a", 1);
        cache.get("a");
        cache.remove("a");
        assertEquals(0, cache.stats().getEvictions());
        assertFalse(cache.containsKey("a"));
        assertEquals(0, cache.size());
    }

    @Test
    void containsKeyDoesNotAffectFrequency() {
        LFUCache<String, Integer> cache = new LFUCache<>(3);
        cache.put("a", 1);
        cache.containsKey("a");
        cache.containsKey("a");
        assertEquals(1, cache.frequencyOf("a"));
    }
}
