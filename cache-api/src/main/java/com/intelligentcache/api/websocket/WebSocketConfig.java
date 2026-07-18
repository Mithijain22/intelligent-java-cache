package com.intelligentcache.api.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers {@link CacheEventBroadcaster} at {@code /ws/stats}. CORS is
 * wide open ({@code setAllowedOrigins("*")}) because this is a local
 * dev/demo project -- the React dev server on :3000 (or :5173 for Vite)
 * talks to the Spring Boot server on :8080, and locking down origins here
 * would only get in the way of running this on your own laptop. Tighten
 * this before ever deploying it anywhere with real traffic.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CacheEventBroadcaster broadcaster;

    public WebSocketConfig(CacheEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(broadcaster, "/ws/stats")
                .setAllowedOrigins("*");
    }
}
