package com.bitly.clone.dto;

public record CacheStatsResponse(
        long hitCount,
        long missCount,
        double hitRate,
        long evictionCount,
        long estimatedSize
) {}