package com.bitly.clone.dto;

import java.time.LocalDateTime;

public record UrlStatsResponse(
        String shortCode,
        String originalUrl,
        Long clickCount,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        boolean active
) {}