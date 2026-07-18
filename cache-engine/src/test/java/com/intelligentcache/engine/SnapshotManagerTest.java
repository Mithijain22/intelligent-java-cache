package com.intelligentcache.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotManagerTest {

    @Test
    void saveThenLoadRoundTripsAllEntries(@TempDir Path tempDir) throws IOException {
        LRUCache<String, Integer> original = new LRUCache<>(10);
        original.put("a", 1);
        original.put("b", 2);
        original.put("c", 3);

        SnapshotManager<String, Integer> snapshotter = new SnapshotManager<>(String.class, Integer.class);
        Path file = tempDir.resolve("snapshot.json");
        snapshotter.save(original, file);

        assertTrue(java.nio.file.Files.exists(file));

        LRUCache<String, Integer> restored = new LRUCache<>(10);
        int loaded = snapshotter.load(restored, file);

        assertEquals(3, loaded);
        assertEquals(1, restored.get("a").intValue());
        assertEquals(2, restored.get("b").intValue());
        assertEquals(3, restored.get("c").intValue());
    }

    @Test
    void loadOnMissingFileIsNoOp(@TempDir Path tempDir) throws IOException {
        SnapshotManager<String, Integer> snapshotter = new SnapshotManager<>(String.class, Integer.class);
        LRUCache<String, Integer> cache = new LRUCache<>(10);
        Path missing = tempDir.resolve("does-not-exist.json");

        int loaded = snapshotter.load(cache, missing);

        assertEquals(0, loaded);
        assertEquals(0, cache.size());
    }

    @Test
    void savedFileIsValidJsonObjectOfEntries(@TempDir Path tempDir) throws IOException {
        LRUCache<String, Integer> cache = new LRUCache<>(10);
        cache.put("a", 1);
        SnapshotManager<String, Integer> snapshotter = new SnapshotManager<>(String.class, Integer.class);
        Path file = tempDir.resolve("snapshot.json");
        snapshotter.save(cache, file);

        String content = java.nio.file.Files.readString(file);
        assertTrue(content.contains("\"a\""));
        assertTrue(content.contains("1"));
    }
}
