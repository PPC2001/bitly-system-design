package com.bitly.clone.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationPublisher {

    public static final String INVALIDATION_CHANNEL = "cache:invalidate:url";

    private final RedisTemplate<String, String> redisTemplate;

    public void publishInvalidation(String shortCode) {
        redisTemplate.convertAndSend(INVALIDATION_CHANNEL, shortCode);
        log.debug("Published L1 cache invalidation event for short code: {}", shortCode);
    }
}