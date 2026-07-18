package com.intelligentcache.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code cache.*} block in application.yml. Using a typed
 * properties record (instead of scattering {@code @Value("${...}")}
 * everywhere) keeps all the tunables in one documented, IDE-autocompletable
 * place -- what policy the live REST-facing cache uses, its capacity, its
 * TTL reaping strategy, and its snapshot persistence settings.
 */
@ConfigurationProperties(prefix = "cache")
public record CacheProperties(
        Policy policy,
        int capacity,
        long ttlReaperIntervalMs, // 0 = active reaper disabled, lazy expiration only
        Persistence persistence
) {
    public enum Policy { LRU, LFU, ARC, TINYLFU }

    public record Persistence(
            boolean enabled,
            String path,
            long intervalSeconds
    ) {}
}
