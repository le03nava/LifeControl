package com.lifecontrol.api.config.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Set;

/**
 * Configures Spring Cache abstraction for the application.
 * <p>
 * <b>Primary cache (Redis):</b> When Redis is available (via {@link RedisConnectionFactory}),
 * a {@link RedisCacheManager} is configured with a 1-hour TTL for all cache regions.
 * <p>
 * <b>Serialization:</b> Cached values are serialized as JSON via
 * {@link GenericJackson2JsonRedisSerializer}. This is required because the cached
 * payloads are immutable Java records ({@code *Response} DTOs) that do <b>not</b>
 * implement {@link java.io.Serializable} — the JDK serialization used by
 * {@link RedisCacheConfiguration#defaultCacheConfig()} would fail at runtime with a
 * {@code SerializationException}. Default typing is enabled so polymorphic payloads
 * (e.g. {@code List<CountryResponse>}) can be reconstructed on read.
 * <p>
 * <b>Fallback (Simple):</b> When no {@link RedisConnectionFactory} is present, a
 * {@link SimpleCacheManager} is used as an in-memory fallback. The application continues
 * without Redis, querying the database directly on every request.
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

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /**
     * Configures Redis-based caching with a 1-hour TTL and JSON value serialization.
     * Active only when a {@link RedisConnectionFactory} bean is available.
     * <p>
     * {@link RedisCacheConfiguration} set via {@code cacheDefaults} applies to every
     * cache region requested through {@code @Cacheable}, so no per-region entries are needed.
     */
    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(CacheManager.class)
    @Primary
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        log.info("Initializing Redis cache manager with 1-hour TTL and JSON serialization");

        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(CACHE_TTL)
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .build();
    }

    /**
     * Builds the JSON serializer used for cache values.
     * <p>
     * Registers {@link JavaTimeModule} so {@link java.time.LocalDateTime} fields
     * serialize as ISO-8601 strings, and enables default typing so deserialization
     * can restore concrete types (including final records) without prior knowledge
     * of the cached payload's declared type.
     */
    static GenericJackson2JsonRedisSerializer valueSerializer() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
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
                new ConcurrentMapCache("companyZones"),
                new ConcurrentMapCache("statusTypes"),
                new ConcurrentMapCache("statuses"),
                new ConcurrentMapCache("measureUnits"),
                new ConcurrentMapCache("paymentMethods")
        ));
        return cacheManager;
    }
}
