package com.bitly.clone.service;

import com.bitly.clone.dto.ShortenUrlRequest;
import com.bitly.clone.dto.ShortenUrlResponse;
import com.bitly.clone.dto.UrlStatsResponse;
import com.bitly.clone.entity.ClickAnalytics;
import com.bitly.clone.entity.UrlMapping;
import com.bitly.clone.exception.InvalidUrlException;
import com.bitly.clone.exception.UrlNotFoundException;
import com.bitly.clone.repository.ClickAnalyticsRepository;
import com.bitly.clone.repository.UrlMappingRepository;
import com.bitly.clone.util.SnowflakeIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlShortenerService {

    private final UrlMappingRepository urlMappingRepository;
    private final ClickAnalyticsRepository clickAnalyticsRepository;
    private final UrlCacheService urlCacheService;
    private final SnowflakeIdGenerator idGenerator;
    private final Base62EncoderService base62Encoder;
    private final HttpServletRequest request;

    @Value("${app.base-url}")
    private String baseUrl;

    @Transactional
    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request) {
        log.info("Received shorten request for URL: {}", request.originalUrl());

        String shortCode = (request.customAlias() != null && !request.customAlias().isBlank())
                ? createCustomAlias(request.customAlias())
                : createGeneratedCode();

        LocalDateTime expiresAt = request.expiresInDays() != null
                ? LocalDateTime.now().plusDays(request.expiresInDays())
                : null;

        UrlMapping mapping = UrlMapping.builder()
                .id(idGenerator.nextId())
                .shortCode(shortCode)
                .originalUrl(request.originalUrl())
                .customAlias(request.customAlias() != null)
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.now())
                .clickCount(0L)
                .active(true)
                .build();

        urlMappingRepository.save(mapping);
        urlCacheService.put(shortCode, request.originalUrl());

        log.info("Successfully created short URL '{}' -> '{}' (expiresAt={})",
                shortCode, request.originalUrl(), expiresAt);

        return new ShortenUrlResponse(
                baseUrl + "/" + shortCode,
                shortCode,
                request.originalUrl(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt()
        );
    }

    private String createCustomAlias(String alias) {
        log.debug("Validating custom alias availability: {}", alias);
        if (urlMappingRepository.existsByShortCode(alias)) {
            log.warn("Custom alias already in use: {}", alias);
            throw new InvalidUrlException("Alias already in use: " + alias);
        }
        return alias;
    }

    private String createGeneratedCode() {
        long id = idGenerator.nextId();
        String code = base62Encoder.encode(id);

        int attempts = 0;
        while (urlMappingRepository.existsByShortCode(code) && attempts < 3) {
            log.warn("Short code collision detected for '{}', regenerating (attempt {})", code, attempts + 1);
            id = idGenerator.nextId();
            code = base62Encoder.encode(id);
            attempts++;
        }
        return code;
    }


    @Transactional
    public String resolveUrl(String shortCode) {
        String clientIp = extractClientIp(request);
        Optional<String> cached = urlCacheService.get(shortCode);

        String originalUrl;
        if (cached.isPresent()) {
            String value = cached.get();
            if (urlCacheService.isTombstone(value)) {
                log.warn("Short code '{}' matched tombstone cache entry", shortCode);
                throw new UrlNotFoundException(shortCode);
            }
            originalUrl = value;
        } else {
            log.debug("Full cache miss (L1+L2) for short code: {} — querying database", shortCode);

            UrlMapping mapping = urlMappingRepository.findByShortCodeAndActiveTrue(shortCode)
                    .filter(m -> !m.isExpired())
                    .orElseGet(() -> {
                        log.warn("Short code '{}' not found or expired — caching tombstone", shortCode);
                        urlCacheService.putTombstone(shortCode);
                        throw new UrlNotFoundException(shortCode);
                    });

            originalUrl = mapping.getOriginalUrl();
            urlCacheService.put(shortCode, originalUrl);
            log.debug("Cache warmed (L1+L2) for short code: {}", shortCode);
        }

        trackClickAsync(shortCode, clientIp, request.getHeader("User-Agent"), request.getHeader("Referer")
        );
        return originalUrl;
    }

    @Async
    protected void trackClickAsync(String shortCode, String ipAddress, String userAgent, String referrer) {
        try {
            urlMappingRepository.incrementClickCount(shortCode);
            ClickAnalytics analytics = ClickAnalytics.builder()
                    .shortCode(shortCode)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .referrer(referrer)
                    .build();
            clickAnalyticsRepository.save(analytics);
            log.debug("Click tracked asynchronously for short code: {} (ip={})", shortCode, ipAddress);
        } catch (Exception e) {
            log.error("Failed to track click for short code '{}': {}", shortCode, e.getMessage(), e);
        }
    }


    public UrlStatsResponse getStats(String shortCode) {
        log.debug("Fetching stats for short code: {}", shortCode);

        UrlMapping mapping = urlMappingRepository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> {
                    log.warn("Stats requested for unknown short code: {}", shortCode);
                    return new UrlNotFoundException(shortCode);
                });

        return new UrlStatsResponse(
                mapping.getShortCode(),
                mapping.getOriginalUrl(),
                mapping.getClickCount(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt(),
                mapping.isActive()
        );
    }

    private String extractClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }
}
