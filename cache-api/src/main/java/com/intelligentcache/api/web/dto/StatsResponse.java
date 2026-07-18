package com.intelligentcache.api.web.dto;

public record StatsResponse(
        String policy,
        int size,
        long hits,
        long misses,
        double hitRate,
        long evictions,
        long puts,
        long expirations
) {}
