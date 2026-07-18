package com.intelligentcache.engine;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Adds per-entry TTL to any {@link Cache} implementation via the Decorator
 * pattern, rather than baking expiration into every eviction policy. This
 * keeps {@link LRUCache}/{@link LFUCache}/etc. focused purely on eviction
 * logic, and TTL becomes an orthogonal concern you can layer on top of any
 * of them -- {@code new TtlDecorator<>(new LRUCache<>(1000))} or
 * {@code new TtlDecorator<>(new LFUCache<>(1000))} both just work.
 *
 * <h2>Design choice: lazy expiration (primary) + optional background reaper</h2>
 * Two standard approaches exist for expiring entries:
 * <ol>
 *   <li><b>Lazy expiration</b>: check the expiry timestamp on every access;
 *       if it's passed, remove and treat as a miss. Zero background CPU
 *       cost, but an entry that's written once with a short TTL and never
 *       read again will sit in memory forever -- it's only cleaned up if
 *       something happens to touch it.</li>
 *   <li><b>Active reaping</b>: a background thread periodically sweeps for
 *       expired entries and removes them regardless of whether anyone reads
 *       them. Bounds memory even for "write and forget" keys, at the cost of
 *       a periodic full scan and a background thread to manage.</li>
 * </ol>
 * This implementation always does (1) -- it's essentially free and correctness-critical
 * (you never want to hand back a logically-expired value). (2) is layered in
 * as an <b>optional</b>, off-by-default feature: pass a non-null
 * {@code sweepInterval} to the constructor to start a
 * {@link ScheduledExecutorService} that sweeps the expiry map on that
 * cadence. Left off by default because most demo/benchmark workloads in this
 * project keep reading the same key space, so lazy expiration alone keeps
 * memory bounded in practice -- but real production caches (this is exactly
 * what Redis's "active expire cycle" does) enable both for the reasons above.
 *
 * Call {@link #close()} to stop the reaper thread if one was started.
 */
public final class TtlDecorator<K, V> implements Cache<K, V>, AutoCloseable {

    private final Cache<K, V> delegate;
    private final ConcurrentHashMap<K, Long> expiryTimestamps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    public TtlDecorator(Cache<K, V> delegate) {
        this(delegate, null);
    }

    /**
     * @param sweepInterval how often the background reaper scans for expired
     *                       entries, or {@code null} to disable active reaping
     *                       and rely solely on lazy (access-triggered) expiration.
     */
    public TtlDecorator(Cache<K, V> delegate, Duration sweepInterval) {
        this.delegate = delegate;
        if (sweepInterval != null) {
            this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ttl-reaper");
                t.setDaemon(true); // never block JVM shutdown
                return t;
            });
            long millis = sweepInterval.toMillis();
            reaper.scheduleAtFixedRate(this::sweepExpired, millis, millis, TimeUnit.MILLISECONDS);
        } else {
            this.reaper = null;
        }
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<K, Long>> it = expiryTimestamps.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, Long> entry = it.next();
            if (now >= entry.getValue()) {
                K key = entry.getKey();
                it.remove();
                if (delegate.remove(key) != null) {
                    delegate.stats().recordExpiration();
                }
            }
        }
    }

    private boolean isExpired(K key) {
        Long expiry = expiryTimestamps.get(key);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiry) {
            expiryTimestamps.remove(key);
            return true;
        }
        return false;
    }

    @Override
    public V get(K key) {
        if (isExpired(key)) {
            if (delegate.remove(key) != null) {
                delegate.stats().recordExpiration();
            }
            // Falls through to delegate.get(), which will now correctly miss
            // and record the miss in stats -- we don't double-count here.
        }
        return delegate.get(key);
    }

    @Override
    public void put(K key, V value) {
        expiryTimestamps.remove(key); // no TTL -> lives until evicted/removed
        delegate.put(key, value);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        if (ttl == null) {
            put(key, value);
            return;
        }
        expiryTimestamps.put(key, System.currentTimeMillis() + ttl.toMillis());
        delegate.put(key, value);
    }

    @Override
    public V remove(K key) {
        expiryTimestamps.remove(key);
        return delegate.remove(key);
    }

    @Override
    public boolean containsKey(K key) {
        if (isExpired(key)) {
            delegate.remove(key);
            return false;
        }
        return delegate.containsKey(key);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public void clear() {
        expiryTimestamps.clear();
        delegate.clear();
    }

    @Override
    public CacheStats stats() {
        return delegate.stats();
    }

    @Override
    public String policyName() {
        return delegate.policyName() + "+TTL";
    }

    @Override
    public Map<K, V> entrySnapshot() {
        // Filter out anything already logically expired so a snapshot never
        // persists stale data, even if the reaper hasn't swept it yet.
        Map<K, V> raw = delegate.entrySnapshot();
        long now = System.currentTimeMillis();
        Map<K, V> filtered = new java.util.HashMap<>(raw.size());
        for (Map.Entry<K, V> e : raw.entrySet()) {
            Long expiry = expiryTimestamps.get(e.getKey());
            if (expiry == null || now < expiry) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        return filtered;
    }

    /** Remaining TTL for a key in millis, or -1 if the key has no TTL / isn't present. Test/debug helper. */
    long remainingTtlMillis(K key) {
        Long expiry = expiryTimestamps.get(key);
        if (expiry == null) {
            return -1;
        }
        return Math.max(0, expiry - System.currentTimeMillis());
    }

    @Override
    public void close() {
        if (reaper != null) {
            reaper.shutdownNow();
        }
    }
}
