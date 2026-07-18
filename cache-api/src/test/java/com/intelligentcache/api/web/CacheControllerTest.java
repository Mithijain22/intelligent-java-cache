package com.intelligentcache.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentcache.api.web.dto.PutRequest;
import com.intelligentcache.engine.Cache;
import com.intelligentcache.engine.LRUCache;
import com.intelligentcache.engine.TtlDecorator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exercises the REST layer end-to-end through MockMvc, with a real (small,
 * in-memory) {@link Cache} instance wired in via a {@code @TestConfiguration}
 * bean rather than mocking the cache -- deliberately, since the point of
 * this test is to confirm the HTTP layer correctly reflects real cache
 * behavior (404 on miss, TTL parsed correctly, stats reflect real counters),
 * not just that controller methods get called.
 */
@WebMvcTest(CacheController.class)
class CacheControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public Cache<String, String> liveCache() {
            return new TtlDecorator<>(new LRUCache<>(10));
        }
    }

    @Autowired
    private Cache<String, String> cache;

    @BeforeEach
    void clearCache() {
        cache.clear();
        cache.stats().reset();
    }

    @Test
    void putThenGetReturnsStoredValue() throws Exception {
        PutRequest request = new PutRequest("hello", null);
        mockMvc.perform(put("/cache/greeting")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("greeting"))
                .andExpect(jsonPath("$.value").value("hello"));

        mockMvc.perform(get("/cache/greeting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("hello"));
    }

    @Test
    void getMissingKeyReturns404() throws Exception {
        mockMvc.perform(get("/cache/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesEntry() throws Exception {
        cache.put("k", "v");
        mockMvc.perform(delete("/cache/k"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/cache/k"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteMissingKeyReturns404() throws Exception {
        mockMvc.perform(delete("/cache/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void statsReflectsRealHitsAndMisses() throws Exception {
        cache.put("a", "1");
        cache.get("a");        // hit
        cache.get("missing");  // miss

        mockMvc.perform(get("/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits").value(1))
                .andExpect(jsonPath("$.misses").value(1))
                .andExpect(jsonPath("$.policy").value("LRU+TTL"));
    }

    @Test
    void putWithTtlExpiresEntry() throws Exception {
        PutRequest request = new PutRequest("temp", 0L); // TTL of 0 seconds = immediately expired
        mockMvc.perform(put("/cache/short-lived")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        Thread.sleep(20);

        mockMvc.perform(get("/cache/short-lived"))
                .andExpect(status().isNotFound());
    }
}
