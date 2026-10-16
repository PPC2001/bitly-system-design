package com.bitly.clone.config;

import com.bitly.clone.service.CacheInvalidationPublisher;
import com.bitly.clone.service.UrlCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CacheInvalidationListenerConfig {

    private final UrlCacheService urlCacheService;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        container.addMessageListener(
                (message, pattern) -> {
                    String shortCode = new String(message.getBody(), StandardCharsets.UTF_8);
                    log.debug("Received remote cache invalidation event for short code: {}", shortCode);
                    urlCacheService.evictLocalOnly(shortCode);
                },
                new ChannelTopic(CacheInvalidationPublisher.INVALIDATION_CHANNEL)
        );

        log.info("Subscribed to Redis invalidation channel: {}",
                CacheInvalidationPublisher.INVALIDATION_CHANNEL);
        return container;
    }
}
