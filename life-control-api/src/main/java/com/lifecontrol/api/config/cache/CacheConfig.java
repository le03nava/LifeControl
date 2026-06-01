package com.lifecontrol.api.config.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.Set;

import java.time.Duration;

/**
 * Configures Spring Cache abstraction for the application.
 * <p>
 * <b>Primary cache (Redis):</b> When Redis is available (via {@link RedisConnectionFactory}),
 * a {@link RedisCacheManager} is configured with a 1-hour TTL for the {@code "countries"} cache.
 * <p>
 * <b>Fallback (Simple):</b> When no {@link RedisConnectionFactory} is present, a
 * {@link SimpleCacheManager} is used as a no-op fallback. The application continues
 * without caching, querying the database directly on every request.
 * <p>
 * To explicitly control the cache type, set {@code spring.cache.type} in application properties:
 * <ul>
 *   <li>{@code redis} — force Redis cache</li>
 *   <li>{@code simple} — use in-memory {@code ConcurrentHashMap} cache (good for tests/dev without Redis)</li>
 *   <li>{@code none} — disable caching entirely</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * Configures Redis-based caching with a 1-hour TTL for the "countries" cache.
     * Active only when a {@link RedisConnectionFactory} bean is available.
     */
    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(CacheManager.class)
    @Primary
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        log.info("Initializing Redis cache manager with 1-hour TTL for 'countries' cache");

        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("countries",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofHours(1)))
                .withCacheConfiguration("companyRegions",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofHours(1)))
                .withCacheConfiguration("companyZones",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofHours(1)))
                .build();
    }

    /**
     * Fallback cache manager used when Redis is not available.
     * Uses a simple in-memory {@link java.util.concurrent.ConcurrentHashMap} backing
     * with pre-registered cache regions.
     * Cached entries live for the duration of the application process.
     */
    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public CacheManager simpleCacheManager() {
        log.info("Redis not available — using SimpleCacheManager fallback (in-memory, no TTL)");
        var cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(Set.of(
                new ConcurrentMapCache("countries"),
                new ConcurrentMapCache("companyRegions"),
                new ConcurrentMapCache("companyZones")
        ));
        return cacheManager;
    }
}
