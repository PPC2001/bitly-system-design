package com.bitly.clone.controller;

import com.bitly.clone.dto.ShortenUrlRequest;
import com.bitly.clone.dto.ShortenUrlResponse;
import com.bitly.clone.dto.UrlStatsResponse;
import com.bitly.clone.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
@Tag(name = "URL Shortener API")
public class UrlShortenerController {

    private final UrlShortenerService urlShortenerService;

    @PostMapping
    @Operation(summary = "Create a shortened URL")
    public ResponseEntity<ShortenUrlResponse> shortenUrl(@Valid @RequestBody ShortenUrlRequest request) {
        ShortenUrlResponse response = urlShortenerService.shortenUrl(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{shortCode}/stats")
    @Operation(summary = "Get analytics for a short URL")
    public ResponseEntity<UrlStatsResponse> getStats(@PathVariable String shortCode) {
        return ResponseEntity.ok(urlShortenerService.getStats(shortCode));
    }
}
