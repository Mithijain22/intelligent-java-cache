package com.intelligentcache.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TinyLFUCacheTest {

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TinyLFUCache<String, String>(0));
    }

    @Test
    void basicPutAndGet() {
        TinyLFUCache<String, Integer> cache = new TinyLFUCache<>(3);
        cache.put("a", 1);
        assertEquals(1, cache.get("a").intValue());
    }

    @Test
    void admitsFreelyWhileBelowCapacity() {
        TinyLFUCache<String, Integer> cache = new TinyLFUCache<>(5);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        assertEquals(3, cache.size());
        assertEquals(0, cache.stats().getRejections());
    }

    @Test
    void sketchEstimatesHigherFrequencyForMoreAccessedKeys() {
        TinyLFUCache<String, String> cache = new TinyLFUCache<>(50);
        for (int i = 0; i < 10; i++) cache.get("popular");
        for (int i = 0; i < 2; i++) cache.get("rare");
        assertTrue(cache.estimatedFrequency("popular") > cache.estimatedFrequency("rare"));
    }

    @Test
    void protectsHotKeysFromOneTimeSequentialScanPollution() {
        // This is TinyLFU's core selling point over plain LRU: a cache full
        // of genuinely popular entries should survive a flood of
        // never-repeated keys, because each new key has to out-score the
        // current LRU eviction candidate on estimated frequency before it's
        // admitted at all.
        int capacity = 100;
        TinyLFUCache<String, String> tinylfu = new TinyLFUCache<>(capacity);
        LRUCache<String, String> lru = new LRUCache<>(capacity);

        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < capacity; i++) {
                String key = "hot-" + i;
                if (tinylfu.get(key) == null) tinylfu.put(key, "v");
                if (lru.get(key) == null) lru.put(key, "v");
            }
        }
        assertEquals(capacity, tinylfu.size());
        assertEquals(capacity, lru.size());

        for (int i = 0; i < 10_000; i++) {
            String key = "scan-" + i;
            if (tinylfu.get(key) == null) tinylfu.put(key, "v");
            if (lru.get(key) == null) lru.put(key, "v");
        }

        int tinylfuSurvivors = 0;
        int lruSurvivors = 0;
        for (int i = 0; i < capacity; i++) {
            if (tinylfu.containsKey("hot-" + i)) tinylfuSurvivors++;
            if (lru.containsKey("hot-" + i)) lruSurvivors++;
        }

        assertTrue(tinylfuSurvivors >= capacity * 0.9,
                "TinyLFU should protect nearly all hot keys, survived: " + tinylfuSurvivors);
        assertTrue(lruSurvivors <= capacity * 0.1,
                "plain LRU should lose nearly all hot keys to the scan, survived: " + lruSurvivors);
        assertTrue(tinylfu.stats().getRejections() > 0, "TinyLFU should have rejected scan admissions");
    }

    @Test
    void removeDoesNotCountAsEviction() {
        TinyLFUCache<String, Integer> cache = new TinyLFUCache<>(5);
        cache.put("a", 1);
        cache.remove("a");
        assertEquals(0, cache.stats().getEvictions());
        assertFalse(cache.containsKey("a"));
    }

    @Test
    void updatingExistingKeyNeverCountsAsRejection() {
        TinyLFUCache<String, Integer> cache = new TinyLFUCache<>(1);
        cache.put("a", 1);
        cache.put("a", 2); // update, not a new-key admission decision
        assertEquals(0, cache.stats().getRejections());
        assertEquals(2, cache.get("a").intValue());
    }

    @Test
    void entrySnapshotMatchesSize() {
        TinyLFUCache<String, Integer> cache = new TinyLFUCache<>(5);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(cache.size(), cache.entrySnapshot().size());
    }
}
