package com.intelligentcache.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleConcurrentCacheTest {

    private SimpleConcurrentCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = new SimpleConcurrentCache<>();
    }

    @Test
    void putThenGetReturnsValue() {
        cache.put("a", "1");
        assertEquals("1", cache.get("a"));
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertNull(cache.get("nope"));
    }

    @Test
    void putOverwritesExistingValue() {
        cache.put("a", "1");
        cache.put("a", "2");
        assertEquals("2", cache.get("a"));
        assertEquals(1, cache.size());
    }

    @Test
    void removeDeletesEntry() {
        cache.put("a", "1");
        assertEquals("1", cache.remove("a"));
        assertNull(cache.get("a"));
        assertFalse(cache.containsKey("a"));
    }

    @Test
    void removeMissingKeyReturnsNull() {
        assertNull(cache.remove("nope"));
    }

    @Test
    void containsKeyDoesNotAffectHitMissStats() {
        cache.put("a", "1");
        cache.containsKey("a");
        cache.containsKey("nope");
        CacheStats.Snapshot snap = cache.stats().snapshot();
        assertEquals(0, snap.hits());
        assertEquals(0, snap.misses());
    }

    @Test
    void sizeReflectsEntryCount() {
        assertEquals(0, cache.size());
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals(2, cache.size());
        cache.remove("a");
        assertEquals(1, cache.size());
    }

    @Test
    void clearEmptiesCacheButKeepsStats() {
        cache.put("a", "1");
        cache.get("a");
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(1, cache.stats().snapshot().hits());
    }

    @Test
    void statsTrackHitsAndMissesCorrectly() {
        cache.put("a", "1");
        cache.get("a");       // hit
        cache.get("a");       // hit
        cache.get("missing"); // miss
        CacheStats.Snapshot snap = cache.stats().snapshot();
        assertEquals(2, snap.hits());
        assertEquals(1, snap.misses());
        assertEquals(2.0 / 3.0, snap.hitRate(), 1e-9);
    }
}
