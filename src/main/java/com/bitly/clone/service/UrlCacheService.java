package com.bitly.clone.service;

import com.bitly.clone.dto.CacheStatsResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Encapsulates the two-tier (L1 Caffeine + L2 Redis) caching strategy
 * for short-code -> original-URL lookups.
 *
 * Read path:  L1 -> L2 -> (caller falls back to DB on full miss)
 * Write path: populates both tiers simultaneously
 * Evict path: clears local L1 + shared L2, then broadcasts so every
 *             other instance's L1 stays consistent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlCacheService {

    public static final String TOMBSTONE = "__NULL__";
    private static final String REDIS_PREFIX = "url:";

    private final Cache<String, String> urlL1Cache;
    private final RedisTemplate<String, String> redisTemplate;
    private final CacheInvalidationPublisher invalidationPublisher;

    @Value("${app.cache.l2.ttl-seconds:86400}")
    private long l2TtlSeconds;

    @Value("${app.cache.negative-ttl-seconds:60}")
    private long negativeTtlSeconds;

    /**
     * Resolves a short code through L1 then L2.
     * Returns Optional.empty() only on a full miss (caller must then check the DB).
     * A returned value may itself be the TOMBSTONE sentinel — callers should
     * check {@link #isTombstone(String)} before treating it as a valid URL.
     */
    public Optional<String> get(String shortCode) {
        String l1Value = urlL1Cache.getIfPresent(shortCode);
        if (l1Value != null) {
            log.debug("L1 cache HIT for short code: {}", shortCode);
            return Optional.of(l1Value);
        }

        String l2Value = redisTemplate.opsForValue().get(REDIS_PREFIX + shortCode);
        if (l2Value != null) {
            log.debug("L1 MISS / L2 HIT for short code: {} — promoting to L1", shortCode);
            urlL1Cache.put(shortCode, l2Value);
            return Optional.of(l2Value);
        }

        log.debug("Cache MISS at both L1 and L2 for short code: {}", shortCode);
        return Optional.empty();
    }

    /** Populates both cache tiers — called after a DB write or a DB read on cache miss. */
    public void put(String shortCode, String originalUrl) {
        urlL1Cache.put(shortCode, originalUrl);
        redisTemplate.opsForValue().set(
                REDIS_PREFIX + shortCode, originalUrl, l2TtlSeconds, TimeUnit.SECONDS);
        log.debug("Cached short code '{}' in L1 + L2 (L2 TTL={}s)", shortCode, l2TtlSeconds);
    }

    /** Caches a negative result in both tiers to prevent cache penetration attacks. */
    public void putTombstone(String shortCode) {
        urlL1Cache.put(shortCode, TOMBSTONE);
        redisTemplate.opsForValue().set(
                REDIS_PREFIX + shortCode, TOMBSTONE, negativeTtlSeconds, TimeUnit.SECONDS);
        log.warn("Cached tombstone for short code '{}' in L1 + L2 (TTL={}s)", shortCode, negativeTtlSeconds);
    }

    /**
     * Full eviction: clears local L1, clears shared L2, and broadcasts
     * an invalidation event so every other instance clears its own L1 too.
     * Use this when a URL is deactivated/deleted/updated by an admin action.
     */
    public void evict(String shortCode) {
        urlL1Cache.invalidate(shortCode);
        redisTemplate.delete(REDIS_PREFIX + shortCode);
        invalidationPublisher.publishInvalidation(shortCode);
        log.info("Evicted short code '{}' from L1 + L2, broadcast sent", shortCode);
    }

    /** Invoked by the Pub/Sub listener when another instance evicts a key — local L1 only. */
    public void evictLocalOnly(String shortCode) {
        urlL1Cache.invalidate(shortCode);
        log.debug("Evicted short code '{}' from local L1 due to remote invalidation", shortCode);
    }

    public boolean isTombstone(String value) {
        return TOMBSTONE.equals(value);
    }

    public CacheStatsResponse getL1Diagnostics() {
        CacheStats stats = urlL1Cache.stats();
        return new CacheStatsResponse(
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate(),
                stats.evictionCount(),
                urlL1Cache.estimatedSize()
        );
    }
}
