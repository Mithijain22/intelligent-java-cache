package com.intelligentcache.api.web.dto;

/**
 * Body for {@code PUT /cache/{key}}. {@code ttlSeconds} is optional --
 * omit it (or send {@code null}) for an entry that never expires.
 */
public record PutRequest(String value, Long ttlSeconds) {}
