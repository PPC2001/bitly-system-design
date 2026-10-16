package com.bitly.clone.dto;

import java.time.LocalDateTime;

public record ShortenUrlResponse(
        String shortUrl,
        String shortCode,
        String originalUrl,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {}