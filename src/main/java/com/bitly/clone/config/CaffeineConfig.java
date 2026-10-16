package com.bitly.clone.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configures the L1 in-process cache (Caffeine).
 *
 * Design choices:
 * - maximumSize: bounds heap usage — without this, a long-running instance
 *   could accumulate unbounded entries and trigger OOM under memory pressure.
 * - expireAfterWrite (not expireAfterAccess): ensures stale URLs eventually
 *   fall through to L2/DB even under constant traffic, bounding staleness
 *   to a known worst case regardless of request volume.
 * - recordStats + Micrometer binding: exposes hit rate, eviction count, etc.
 *   via /actuator/metrics — critical for diagnosing cache effectiveness in prod.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CaffeineConfig {

    private final MeterRegistry meterRegistry;

    @Value("${app.cache.l1.max-size:10000}")
    private long maxSize;

    @Value("${app.cache.l1.ttl-seconds:300}")
    private long ttlSeconds;

    @Bean
    public Cache<String, String> urlL1Cache() {
        log.info("Initializing Caffeine L1 cache: maxSize={}, ttlSeconds={}", maxSize, ttlSeconds);

        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();

        CaffeineCacheMetrics.monitor(meterRegistry, cache, "url_l1_cache");
        log.info("Registered Caffeine L1 cache metrics with Micrometer as 'url_l1_cache'");

        return cache;
    }
}