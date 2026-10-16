package com.bitly.clone.controller;

import com.bitly.clone.dto.CacheStatsResponse;
import com.bitly.clone.service.UrlCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Cache Diagnostics")
public class CacheStatsController {

    private final UrlCacheService urlCacheService;

    @GetMapping("/api/v1/cache/l1-stats")
    @Operation(summary = "Get Caffeine L1 cache statistics (hit rate, evictions, size)")
    public CacheStatsResponse getL1CacheStats() {
        CacheStatsResponse stats = urlCacheService.getL1Diagnostics();
        log.info("L1 cache stats requested: hitRate={}, hits={}, misses={}",
                stats.hitRate(), stats.hitCount(), stats.missCount());
        return stats;
    }
}
