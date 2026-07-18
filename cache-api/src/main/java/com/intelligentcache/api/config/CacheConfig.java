package com.intelligentcache.api.config;

import com.intelligentcache.api.websocket.LiveCacheEvents;
import com.intelligentcache.engine.ARCCache;
import com.intelligentcache.engine.Cache;
import com.intelligentcache.engine.LFUCache;
import com.intelligentcache.engine.LRUCache;
import com.intelligentcache.engine.SnapshotManager;
import com.intelligentcache.engine.TinyLFUCache;
import com.intelligentcache.engine.TtlDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Wires together the plain-Java cache engine into a Spring-managed bean.
 * This is the seam between the framework-free {@code cache-engine} module
 * and the Spring world: everything above this class talks to
 * {@code Cache<String, String>} as an interface and has no idea whether
 * it's backed by LRU or LFU under the hood -- that's decided once, here,
 * from {@code application.yml}.
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public Cache<String, String> liveCache(CacheProperties props, LiveCacheEvents events) {
        Cache<String, String> policy = switch (props.policy()) {
            case LRU -> new LRUCache<>(props.capacity());
            case LFU -> new LFUCache<>(props.capacity());
            case ARC -> new ARCCache<>(props.capacity());
            case TINYLFU -> new TinyLFUCache<>(props.capacity());
        };

        // Wire real eviction events out to the WebSocket layer via the
        // decoupled pub-sub bus (see LiveCacheEvents javadoc for why this
        // isn't just a direct dependency on the broadcaster). setEvictionListener
        // is a concrete method on each policy class, not part of the shared
        // Cache<K,V> contract, so we attach it here on the concrete instance
        // before it disappears behind the TtlDecorator/Cache interface below.
        if (policy instanceof LRUCache<String, String> lru) {
            lru.setEvictionListener((key, value, reason) -> events.publishEviction(key, reason));
        } else if (policy instanceof LFUCache<String, String> lfu) {
            lfu.setEvictionListener((key, value, reason) -> events.publishEviction(key, reason));
        } else if (policy instanceof ARCCache<String, String> arc) {
            arc.setEvictionListener((key, value, reason) -> events.publishEviction(key, reason));
        } else if (policy instanceof TinyLFUCache<String, String> tinyLfu) {
            tinyLfu.setEvictionListener((key, value, reason) -> events.publishEviction(key, reason));
        }

        Duration reaperInterval = props.ttlReaperIntervalMs() > 0
                ? Duration.ofMillis(props.ttlReaperIntervalMs())
                : null;
        TtlDecorator<String, String> cache = new TtlDecorator<>(policy, reaperInterval);

        if (props.persistence() != null && props.persistence().enabled()) {
            SnapshotManager<String, String> snapshotManager = new SnapshotManager<>(String.class, String.class);
            Path snapshotPath = Path.of(props.persistence().path());

            try {
                Path parent = snapshotPath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                int loaded = snapshotManager.load(cache, snapshotPath);
                log.info("Loaded {} entries from snapshot at {}", loaded, snapshotPath);
            } catch (IOException e) {
                log.warn("Could not load snapshot from {}: {}", snapshotPath, e.getMessage());
            }

            snapshotManager.schedulePeriodicSnapshots(
                    cache, snapshotPath, Duration.ofSeconds(props.persistence().intervalSeconds()));
            log.info("Persistence enabled: snapshotting to {} every {}s",
                    snapshotPath, props.persistence().intervalSeconds());
        }

        log.info("Live cache initialized: policy={}, capacity={}, ttlReaper={}",
                props.policy(), props.capacity(),
                reaperInterval == null ? "disabled (lazy only)" : reaperInterval);

        return cache;
    }
}
