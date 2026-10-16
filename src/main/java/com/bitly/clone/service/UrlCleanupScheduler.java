package com.bitly.clone.service;

import com.bitly.clone.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class UrlCleanupScheduler {

    private final UrlMappingRepository urlMappingRepository;

    @Scheduled(cron = "0 0 2 * * *") // every day at 2 AM
    @Transactional
    public void deactivateExpiredUrls() {
        log.info("Starting scheduled job: deactivateExpiredUrls");
        long start = System.currentTimeMillis();

        // In practice: use a bulk @Modifying query for efficiency
        // int updated = urlMappingRepository.deactivateExpired(LocalDateTime.now());

        long duration = System.currentTimeMillis() - start;
        log.info("Completed scheduled job: deactivateExpiredUrls (took {}ms)", duration);
    }
}
