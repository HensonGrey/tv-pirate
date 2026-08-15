package com.tvpirate.backend.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;

import com.github.benmanes.caffeine.cache.Caffeine;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plumbing for the TMDB proxy: the HTTP client that talks to TMDB, and the
 * caches that keep us from talking to it too often.
 */
@Configuration
@EnableCaching
public class TmdbConfig {

    /** The HTTP client for TMDB: Bearer auth and timeouts applied here once.
     * Default timeouts would let a stalled upstream hang threads forever. */
    @Bean
    RestClient tmdbRestClient(@Value("${tmdb.base-url}") String baseUrl,
                              @Value("${tmdb.read-access-token}") String readAccessToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        // Jackson 3 errors on null → primitive mapping, and a hand-built
        // mapper doesn't inherit Boot's snake_case naming — both set here.
        // vault:tmdb-deep-dive#jackson
        JsonMapper.Builder mapperBuilder = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        // Boot's default Jackson converter stays registered; ours goes in front.
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .messageConverters(converters -> converters.add(0,
                        new JacksonJsonHttpMessageConverter(mapperBuilder)))
                .defaultHeader("Authorization", "Bearer " + readAccessToken)
                .build();
    }

    /** One cache per data kind with its own TTL: lists 10 min, details and
     * genre tables 24 h, image config 7 days. maximumSize keeps memory bounded. */
    @Bean
    CaffeineCacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        register(manager, "trending", 10, 100);
        register(manager, "discover", 10, 100);
        register(manager, "search", 10, 100);
        register(manager, "tmdb-detail", 1440, 1000);
        register(manager, "tmdb-genres", 1440, 2);
        register(manager, "tmdb-image-config", 10080, 1);
        return manager;
    }

    private static void register(CaffeineCacheManager manager, String name, int ttlMinutes, long maxSize) {
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .maximumSize(maxSize);
        manager.registerCustomCache(name, caffeine.build());
    }
}
