package com.intelligentcache.api.websocket;

import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Tiny in-process pub-sub so {@code CacheConfig} (which constructs the live
 * cache and can attach an eviction listener to it) doesn't need to depend on
 * {@link CacheEventBroadcaster} (which depends on the {@code Cache} bean
 * that {@code CacheConfig} builds) -- that pairing would be a circular bean
 * dependency. Instead: {@code CacheConfig} publishes eviction events here
 * with no knowledge of WebSockets, and {@code CacheEventBroadcaster}
 * subscribes here with no knowledge of where evictions come from.
 */
@Component
public class LiveCacheEvents {

    private final CopyOnWriteArrayList<BiConsumer<String, String>> listeners = new CopyOnWriteArrayList<>();

    /** @param key the evicted key, {@code reason} e.g. "LRU capacity eviction" */
    public void publishEviction(String key, String reason) {
        for (BiConsumer<String, String> listener : listeners) {
            listener.accept(key, reason);
        }
    }

    public void onEviction(BiConsumer<String, String> listener) {
        listeners.add(listener);
    }
}
