package com.intelligentcache.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the web layer. This class -- and everything under
 * {@code com.intelligentcache.api} -- is the ONLY part of the project that
 * knows Spring exists. The actual cache logic lives in the {@code cache-engine}
 * module (see {@link com.intelligentcache.engine.Cache}), which has zero
 * framework dependency and could be embedded anywhere.
 *
 * {@code @EnableScheduling} powers the periodic stats broadcast to connected
 * WebSocket clients (see {@link com.intelligentcache.api.websocket.CacheEventBroadcaster}).
 */
@SpringBootApplication
@EnableScheduling
public class CacheApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CacheApiApplication.class, args);
    }
}
