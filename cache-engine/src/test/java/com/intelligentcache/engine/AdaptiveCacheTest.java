package com.intelligentcache.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveCacheTest {

    private Map<String, Supplier<Cache<String, String>>> candidates(int capacity) {
        Map<String, Supplier<Cache<String, String>>> map = new LinkedHashMap<>();
        map.put("LRU", () -> new LRUCache<>(capacity));
        map.put("LFU", () -> new LFUCache<>(capacity));
        map.put("TinyLFU", () -> new TinyLFUCache<>(capacity));
        return map;
    }

    @Test
    void requiresAtLeastTwoCandidates() {
        Map<String, Supplier<Cache<String, String>>> single = new LinkedHashMap<>();
        single.put("LRU", () -> new LRUCache<>(10));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveCache<>(single, 100, "LRU"));
    }

    @Test
    void rejectsUnknownInitialActive() {
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveCache<>(candidates(10), 100, "ARC"));
    }

    @Test
    void startsOnRequestedInitialPolicy() {
        AdaptiveCache<String, String> cache = new AdaptiveCache<>(candidates(10), 100, "LFU");
        assertEquals("LFU", cache.activePolicy());
        assertEquals("ADAPTIVE(active=LFU)", cache.policyName());
    }

    @Test
    void putThenGetReturnsRealValueFromActiveShadow() {
        AdaptiveCache<String, String> cache = new AdaptiveCache<>(candidates(10), 100, "LRU");
        cache.put("a", "real-value");
        assertEquals("real-value", cache.get("a"));
    }

    @Test
    void switchesAwayFromInitialPolicyWhenAnotherClearlyWins() {
        int capacity = 50;
        AdaptiveCache<String, String> cache = new AdaptiveCache<>(candidates(capacity), 1000, "LRU");

        // Small hot set interleaved with a heavy stream of never-repeated
        // scan keys -- LRU/LFU get flooded, TinyLFU/LFU should pull ahead.
        Random random = new Random(42);
        int scanCounter = 0;
        for (int i = 0; i < 30_000; i++) {
            String key = random.nextInt(10) == 0 ? "hot-" + random.nextInt(20) : "scan-" + (scanCounter++);
            if (cache.get(key) == null) {
                cache.put(key, "v");
            }
        }

        assertFalse(cache.switchHistory().isEmpty(), "expected at least one policy switch away from the clearly-losing initial policy");
        assertNotEquals("LRU", cache.activePolicy(), "LRU should not still be active after being flooded this badly");
    }

    @Test
    void sizeNeverExceedsCapacityAcrossSwitching() {
        int capacity = 30;
        AdaptiveCache<String, String> cache = new AdaptiveCache<>(candidates(capacity), 200, "LRU");
        Random random = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            String key = "k-" + random.nextInt(500);
            if (cache.get(key) == null) {
                cache.put(key, "v");
            }
            assertTrue(cache.size() <= capacity);
        }
    }

    @Test
    void removeAffectsActiveShadowVisibly() {
        AdaptiveCache<String, String> cache = new AdaptiveCache<>(candidates(10), 100, "LRU");
        cache.put("a", "1");
        cache.remove("a");
        assertNull(cache.get("a"));
        assertFalse(cache.containsKey("a"));
    }

    @Test
    void shadowStatsReturnsOneEntryPerCandidate() {
        AdaptiveCache<String, String> cache = new AdaptiveCache<>(candidates(10), 100, "LRU");
        cache.put("a", "1");
        cache.get("a");
        Map<String, CacheStats.Snapshot> shadowStats = cache.shadowStats();
        assertEquals(3, shadowStats.size());
        assertTrue(shadowStats.containsKey("LRU"));
        assertTrue(shadowStats.containsKey("LFU"));
        assertTrue(shadowStats.containsKey("TinyLFU"));
    }
}
