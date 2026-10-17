package com.lifecontrol.api.config.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;
import java.util.Set;
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
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.jsontype.impl.DefaultTypeResolverBuilder;

/**
 * Configures Spring Cache abstraction for the application.
 * <p>
 * <b>Primary cache (Redis):</b> When Redis is available (via {@link RedisConnectionFactory}),
 * a {@link RedisCacheManager} is configured with a 1-hour TTL for all cache regions.
 * <p>
 * <b>Serialization:</b> Cached values are serialized as JSON via
 * {@link GenericJacksonJsonRedisSerializer}, Spring Data Redis 4's Jackson 3 serializer.
 * This is required because the cached payloads are immutable Java records
 * ({@code *Response} DTOs) that do <b>not</b> implement {@link java.io.Serializable} — the JDK
 * serialization used by {@link RedisCacheConfiguration#defaultCacheConfig()} would fail at
 * runtime with a {@code SerializationException}. Default typing is enabled so polymorphic
 * payloads (e.g. {@code List<CountryResponse>}) can be reconstructed on read.
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
     * Jackson 3 has JSR-310 support built in, so no date module is registered:
     * {@link java.time.LocalDateTime} fields serialize as ISO-8601 strings on their own.
     * {@link DateTimeFeature#WRITE_DATES_AS_TIMESTAMPS} already defaults to {@code false} in
     * Jackson 3 and is still set explicitly because these bytes are persisted for up to
     * {@link #CACHE_TTL}, so the representation must not depend on a library default.
     * <p>
     * Default typing (a {@code @class} property carrying the type id) is required because values
     * are read back without any knowledge of the declared type. Jackson 3 removed
     * {@code DefaultTyping.EVERYTHING} (databind#4160) and <b>no remaining
     * {@link DefaultTyping} constant is equivalent</b>: they all omit the type id for a final
     * JDK class such as {@code java.util.ImmutableCollections$ListN}, which is exactly the shape
     * {@link java.util.stream.Stream#toList()} produces — the resulting payload is written but
     * cannot be read back. {@link CacheValueTypeResolver} restores the previous behaviour.
     */
    static GenericJacksonJsonRedisSerializer valueSerializer() {
        var typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();

        var mapper = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setDefaultTyping(new CacheValueTypeResolver(typeValidator))
                .build();

        return new GenericJacksonJsonRedisSerializer(mapper);
    }

    /**
     * Type resolver equivalent to Jackson 2's removed {@code DefaultTyping.EVERYTHING}: every
     * non-primitive value carries its type id (scalar types are handled by Jackson's own scalar
     * serializers, which ignore type ids). This is what lets final records and the final JDK
     * collection classes returned by {@code Stream#toList()} round-trip, and it keeps the
     * on-wire format byte-identical to the entries written before the Jackson 3 migration.
     */
    private static final class CacheValueTypeResolver extends DefaultTypeResolverBuilder {

        private static final long serialVersionUID = 1L;

        private CacheValueTypeResolver(PolymorphicTypeValidator typeValidator) {
            // The DefaultTyping argument is required but inert: useForType is overridden below.
            super(typeValidator, DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        }

        @Override
        public boolean useForType(JavaType type) {
            return !type.isPrimitive();
        }

        @Override
        public DefaultTypeResolverBuilder withDefaultImpl(Class<?> defaultImpl) {
            // Defensive mirror of Jackson 2's EVERYTHING builder, whose withDefaultImpl also
            // returned this. Measured: MapperBuilder.setDefaultTyping stores this builder and does
            // not call withDefaultImpl, so the override is inert in this configuration; it stays
            // because delegating to the base implementation would return a plain
            // DefaultTypeResolverBuilder and silently drop useForType above, were any path to call
            // it (Spring Data Redis does the same for its own resolver).
            return this;
        }
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
                new ConcurrentMapCache("paymentMethods"),
                new ConcurrentMapCache("seniorityLevels")));
        return cacheManager;
    }
}
