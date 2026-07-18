package com.intelligentcache.api.web;

import com.intelligentcache.api.web.dto.EntryResponse;
import com.intelligentcache.api.web.dto.PutRequest;
import com.intelligentcache.api.web.dto.StatsResponse;
import com.intelligentcache.engine.Cache;
import com.intelligentcache.engine.CacheStats;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * REST surface over the single "live" cache bean configured in
 * {@code application.yml} (see {@code CacheConfig}). Matches the endpoint
 * shape from the project spec:
 * {@code PUT/GET/DELETE /cache/{key}} and {@code GET /cache/stats}, plus one
 * bonus debug endpoint ({@code GET /cache}) so the dashboard can render a
 * live table of current contents without you needing a separate admin tool.
 *
 * Deliberately thin: all it does is translate HTTP verbs to {@link Cache}
 * calls and shape the response. No business logic lives here.
 */
@RestController
@RequestMapping("/cache")
public class CacheController {

    private final Cache<String, String> cache;

    public CacheController(Cache<String, String> cache) {
        this.cache = cache;
    }

    @PutMapping("/{key}")
    public ResponseEntity<EntryResponse> put(@PathVariable("key") String key, @RequestBody PutRequest request) {
        if (request.ttlSeconds() != null) {
            cache.put(key, request.value(), Duration.ofSeconds(request.ttlSeconds()));
        } else {
            cache.put(key, request.value());
        }
        return ResponseEntity.ok(new EntryResponse(key, request.value()));
    }

    @GetMapping("/{key}")
    public ResponseEntity<EntryResponse> get(@PathVariable("key") String key) {
        String value = cache.get(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new EntryResponse(key, value));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> remove(@PathVariable("key") String key) {
        String removed = cache.remove(key);
        return removed == null ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        CacheStats.Snapshot s = cache.stats().snapshot();
        return new StatsResponse(
                cache.policyName(), cache.size(),
                s.hits(), s.misses(), s.hitRate(),
                s.evictions(), s.puts(), s.expirations()
        );
    }

    /** Bonus debug endpoint: full current contents, for the dashboard's live table view. */
    @GetMapping
    public Map<String, String> allEntries() {
        return cache.entrySnapshot();
    }
}