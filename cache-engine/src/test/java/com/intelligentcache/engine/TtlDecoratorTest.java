package com.intelligentcache.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TtlDecoratorTest {

    @Test
    void entryIsReadableBeforeExpiry() {
        TtlDecorator<String, Integer> cache = new TtlDecorator<>(new LRUCache<>(10));
        cache.put("a", 1, Duration.ofSeconds(30));
        assertEquals(1, cache.get("a").intValue());
    }

    @Test
    void entryExpiresLazilyOnAccessAfterTtl() throws InterruptedException {
        TtlDecorator<String, Integer> cache = new TtlDecorator<>(new LRUCache<>(10));
        cache.put("a", 1, Duration.ofMillis(30));
        Thread.sleep(60);
        assertNull(cache.get("a"), "entry should be expired and treated as a miss");
        assertTrue(cache.stats().getExpirations() >= 1);
    }

    @Test
    void entriesWithoutTtlNeverExpire() throws InterruptedException {
        TtlDecorator<String, Integer> cache = new TtlDecorator<>(new LRUCache<>(10));
        cache.put("a", 1); // no TTL
        Thread.sleep(50);
        assertEquals(1, cache.get("a").intValue());
    }

    @Test
    void backgroundReaperEvictsExpiredEntriesWithoutAccess() throws InterruptedException {
        TtlDecorator<String, Integer> cache = new TtlDecorator<>(new LRUCache<>(10), Duration.ofMillis(20));
        try {
            cache.put("x", 1, Duration.ofMillis(30));
            assertTrue(cache.entrySnapshot().containsKey("x"));
            Thread.sleep(150); // allow multiple sweep cycles
            assertFalse(cache.entrySnapshot().containsKey("x"), "reaper should have removed it without any get()");
        } finally {
            cache.close();
        }
    }

    @Test
    void puttingOverExistingKeyWithoutTtlClearsPreviousTtl() throws InterruptedException {
        TtlDecorator<String, Integer> cache = new TtlDecorator<>(new LRUCache<>(10));
        cache.put("a", 1, Duration.ofMillis(30));
        cache.put("a", 2); // overwrite with no TTL -- should now live forever
        Thread.sleep(60);
        assertEquals(2, cache.get("a").intValue());
    }

    @Test
    void entrySnapshotExcludesExpiredEntries() throws InterruptedException {
        TtlDecorator<String, Integer> cache = new TtlDecorator<>(new LRUCache<>(10));
        cache.put("keep", 1);
        cache.put("gone", 2, Duration.ofMillis(20));
        Thread.sleep(50);
        var snapshot = cache.entrySnapshot();
        assertTrue(snapshot.containsKey("keep"));
        assertFalse(snapshot.containsKey("gone"));
    }
}
