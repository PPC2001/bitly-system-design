package com.bitly.clone;

import com.bitly.clone.repository.ClickAnalyticsRepository;
import com.bitly.clone.repository.UrlMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@SpringBootTest
class BitlyApplicationTests {

	@MockBean
	private RedisConnectionFactory redisConnectionFactory;

	@MockBean
	private RedisTemplate<String, String> redisTemplate;

	@MockBean
	private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

	@MockBean
	private UrlMappingRepository urlMappingRepository;

	@MockBean
	private ClickAnalyticsRepository clickAnalyticsRepository;

	@Test
	void contextLoads() {
	}

}
