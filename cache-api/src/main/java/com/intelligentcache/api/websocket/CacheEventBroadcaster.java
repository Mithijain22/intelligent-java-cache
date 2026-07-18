package com.intelligentcache.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentcache.engine.Cache;
import com.intelligentcache.engine.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Fans out two kinds of events to every connected WebSocket client:
 * <ol>
 *   <li>A periodic stats snapshot (every second, via {@code @Scheduled}) --
 *       this is what drives the dashboard's live hit-rate/size/eviction
 *       counters without the frontend having to poll a REST endpoint.</li>
 *   <li>Individual eviction events, pushed immediately as they happen
 *       (wired up via {@link Cache} not exposing eviction listeners
 *       directly on the interface -- see note below) so the dashboard can
 *       show real-time "evicted key X" flashes during a trace replay.</li>
 * </ol>
 *
 * Uses raw {@link TextWebSocketHandler} rather than STOMP/{@code SimpMessagingTemplate}
 * -- for a single broadcast topic with no per-client subscriptions or
 * routing, STOMP's extra machinery isn't buying anything, and a plain
 * session set keeps this trivial to reason about.
 *
 * Note: {@code Cache<K,V>} intentionally has no {@code setEvictionListener}
 * in its public interface (LRUCache/LFUCache expose it as a concrete method,
 * not part of the shared contract, since not every implementation needs to
 * support it identically). For the single "live" REST-facing cache, {@code CacheConfig}
 * attaches a listener directly on the concrete policy instance and forwards
 * events here via {@link LiveCacheEvents} (a small pub-sub bus that avoids a
 * circular bean dependency between this class and the cache bean). The
 * benchmarking harness (Phase 6) attaches its own listeners on the
 * short-lived per-request cache instances it constructs -- those runs don't
 * touch the live cache or this broadcaster at all.
 */
@Component
public class CacheEventBroadcaster extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CacheEventBroadcaster.class);

    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Cache<String, String> liveCache;

    public CacheEventBroadcaster(Cache<String, String> liveCache, LiveCacheEvents events) {
        this.liveCache = liveCache;
        events.onEviction((key, reason) -> broadcastEviction(liveCache.policyName(), key, reason));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Dashboard client connected: {} ({} total)", session.getId(), sessions.size());
        broadcastStatsSnapshot(); // give the new client immediate state, don't make it wait a second
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Dashboard client disconnected: {} ({} total)", session.getId(), sessions.size());
    }

    /** Pushes hit/miss/eviction/size stats to every connected client once a second. */
    @Scheduled(fixedRate = 1000)
    public void broadcastStatsSnapshot() {
        if (sessions.isEmpty()) {
            return; // no point serializing anything if nobody's listening
        }
        CacheStats.Snapshot stats = liveCache.stats().snapshot();
        Map<String, Object> event = Map.of(
                "type", "stats",
                "policy", liveCache.policyName(),
                "size", liveCache.size(),
                "hits", stats.hits(),
                "misses", stats.misses(),
                "hitRate", stats.hitRate(),
                "evictions", stats.evictions(),
                "puts", stats.puts(),
                "expirations", stats.expirations()
        );
        broadcast(event);
    }

    /** Called by the benchmark/trace-replay layer to push a one-off eviction event live. */
    public void broadcastEviction(String policyLabel, String key, String reason) {
        broadcast(Map.of(
                "type", "eviction",
                "policy", policyLabel,
                "key", key,
                "reason", reason
        ));
    }

    private void broadcast(Object event) {
        try {
            String json = mapper.writeValueAsString(event);
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to broadcast WebSocket event: {}", e.getMessage());
        }
    }
}
