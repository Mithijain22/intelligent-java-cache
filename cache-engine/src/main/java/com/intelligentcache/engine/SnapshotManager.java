package com.intelligentcache.engine;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Persists a cache's contents to disk as JSON and reloads it on startup.
 * Deliberately simple: {@link Cache#entrySnapshot()} -&gt; JSON file -&gt; on
 * load, JSON -&gt; a sequence of {@code put()} calls. This is NOT a
 * write-ahead log and gives no durability guarantee between snapshots --
 * that's an explicit, documented scope cut (see project README, "Persistence"
 * section). It's good enough to survive a planned restart without losing the
 * whole working set, which is the actual goal here.
 *
 * Note that a reloaded cache starts with fresh recency/frequency metadata --
 * we persist key-value pairs, not internal LRU/LFU bookkeeping, so after a
 * reload every entry effectively looks "just inserted" to its eviction
 * policy. Documented tradeoff, not a bug: reconstructing exact recency/frequency
 * order would require persisting policy-internal state, which would break
 * the clean separation between {@code Cache<K,V>} and its policy internals.
 */
public final class SnapshotManager<K, V> {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Class<K> keyType;
    private final Class<V> valueType;

    public SnapshotManager(Class<K> keyType, Class<V> valueType) {
        this.keyType = keyType;
        this.valueType = valueType;
    }

    /** Writes the cache's current contents to {@code path} as pretty-printed JSON. */
    public void save(Cache<K, V> cache, Path path) throws IOException {
        Map<K, V> entries = cache.entrySnapshot();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), entries);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        // Write-to-temp-then-atomic-rename avoids a reader ever observing a
        // half-written snapshot file if save() races with a crash.
    }

    /**
     * Loads entries from {@code path} into {@code cache} via normal
     * {@code put()} calls. If the file doesn't exist (e.g. first-ever
     * startup), this is a silent no-op -- that's the expected case, not an
     * error.
     */
    public int load(Cache<K, V> cache, Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        JavaType mapType = mapper.getTypeFactory().constructMapType(HashMap.class, keyType, valueType);
        Map<K, V> entries = mapper.readValue(path.toFile(), mapType);
        for (Map.Entry<K, V> e : entries.entrySet()) {
            cache.put(e.getKey(), e.getValue());
        }
        return entries.size();
    }

    /**
     * Starts a background thread that calls {@link #save} on a fixed
     * interval, and additionally registers a JVM shutdown hook so a graceful
     * stop (Ctrl+C, `docker stop`, normal exit) also triggers one last save.
     * Returns the {@link ScheduledFuture} so the caller can cancel it, e.g.
     * on application shutdown before the hook fires.
     */
    public ScheduledFuture<?> schedulePeriodicSnapshots(Cache<K, V> cache, Path path, java.time.Duration interval) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-writer");
            t.setDaemon(true);
            return t;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                save(cache, path);
            } catch (IOException e) {
                System.err.println("Failed to save shutdown snapshot: " + e.getMessage());
            }
        }, "snapshot-shutdown-hook"));

        long millis = interval.toMillis();
        return executor.scheduleAtFixedRate(() -> {
            try {
                save(cache, path);
            } catch (IOException e) {
                System.err.println("Periodic snapshot failed: " + e.getMessage());
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }
}
