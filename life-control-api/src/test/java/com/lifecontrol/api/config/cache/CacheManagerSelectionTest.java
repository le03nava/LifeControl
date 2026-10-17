package com.lifecontrol.api.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.country.dto.CountryResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;

/**
 * Pins which {@link CacheManager} the application context ends up with, and that the Redis one
 * carries this project's TTL and JSON serializer.
 *
 * <p>Why this needs its own test: the choice is invisible in normal operation. The context either
 * gets a {@link RedisCacheManager} (shared across instances, 1-hour TTL) or an in-memory one
 * (per-JVM, no TTL, no eviction across instances), and both serve {@code @Cacheable} reads
 * successfully — a wrong selection shows up only as cache misses, never as a failure. It was wrong:
 * {@link CacheConfig#redisCacheManager} asked for the Redis manager behind
 * {@code @ConditionalOnBean(RedisConnectionFactory.class)}, and inside a user {@code @Configuration}
 * that condition is evaluated before {@code DataRedisAutoConfiguration} registers the factory, so
 * the in-memory fallback won in every environment. {@code spring.cache.type} was therefore inert in
 * production: nothing in this application reads it. This module has no Spring Boot cache
 * auto-configuration on its classpath (no {@code spring-boot-starter-cache}), so
 * {@link CacheConfig} is the single source of the {@code CacheManager} and these conditions are the
 * only thing that decides.
 *
 * <p>These are context-runner tests rather than {@code @SpringBootTest}: they need no Redis server
 * (the factory is created but never connected, and a {@link RedisCache} reads its configuration
 * without touching the network), no database, and they assert the beans the runner really creates.
 */
@DisplayName("CacheManager selection")
class CacheManagerSelectionTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withUserConfiguration(CacheConfig.class)
                .withPropertyValues("spring.data.redis.host=localhost", "spring.data.redis.port=6379");
    }

    @Test
    @DisplayName("spring.cache.type=redis selects the Redis cache manager (the production setting)")
    void cacheTypeRedis_SelectsRedisCacheManager() {
        runner().withPropertyValues("spring.cache.type=redis")
                .run(context -> assertThat(context.getBean(CacheManager.class)).isInstanceOf(RedisCacheManager.class));
    }

    @Test
    @DisplayName("without spring.cache.type the Redis cache manager is selected, as the deployed default")
    void cacheTypeUnset_SelectsRedisCacheManager() {
        runner().run(context -> assertThat(context.getBean(CacheManager.class)).isInstanceOf(RedisCacheManager.class));
    }

    @Test
    @DisplayName("spring.cache.type=simple selects the in-memory cache manager used by tests")
    void cacheTypeSimple_SelectsInMemoryCacheManager() {
        runner().withPropertyValues("spring.cache.type=simple")
                .run(context -> assertThat(context.getBean(CacheManager.class))
                        .isInstanceOf(SimpleCacheManager.class)
                        .isNotInstanceOf(RedisCacheManager.class));
    }

    @Test
    @DisplayName("spring.cache.type=simple registers the eight cache regions the fallback serves")
    void cacheTypeSimple_RegistersTheCacheRegions() {
        runner().withPropertyValues("spring.cache.type=simple")
                .run(context -> assertThat(context.getBean(CacheManager.class).getCacheNames())
                        .containsExactlyInAnyOrder(
                                "countries",
                                "companyRegions",
                                "companyZones",
                                "statusTypes",
                                "statuses",
                                "measureUnits",
                                "paymentMethods",
                                "seniorityLevels"));
    }

    @Test
    @DisplayName("spring.cache.type=none disables caching instead of leaving the context without a manager")
    void cacheTypeNone_DisablesCaching() {
        runner().withPropertyValues("spring.cache.type=none")
                .run(context -> assertThat(context.getBean(CacheManager.class)).isInstanceOf(NoOpCacheManager.class));
    }

    @Test
    @DisplayName("the Redis cache manager keeps the 1-hour TTL and the Jackson 3 serializer")
    void redisCacheManager_KeepsTtlAndJsonSerializer() {
        runner().withPropertyValues("spring.cache.type=redis").run(context -> {
            var value = cachedValue();
            var cache = context.getBean(CacheManager.class).getCache("countries");

            assertThat(cache).isInstanceOf(RedisCache.class);
            var configuration = ((RedisCache) cache).getCacheConfiguration();

            assertThat(configuration.getValueSerializationPair().read(serialize(configuration, value)))
                    .isEqualTo(value);
            assertThat(configuration.getTtlFunction().getTimeToLive("countries", value))
                    .isEqualTo(Duration.ofHours(1));
        });
    }

    /**
     * Serializes with the pair the Redis cache manager is actually configured with, and returns the
     * bytes for the read-back. The {@code @class} property is asserted here because it is what makes
     * a payload readable without knowing its declared type, and what keeps the on-wire format
     * identical to the entries written before the Jackson 3 migration.
     */
    private static ByteBuffer serialize(RedisCacheConfiguration configuration, CountryResponse value) {
        var written = configuration.getValueSerializationPair().getWriter().write(value);
        var bytes = new byte[written.remaining()];
        written.duplicate().get(bytes);

        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("@class");

        return ByteBuffer.wrap(bytes);
    }

    private static CountryResponse cachedValue() {
        return new CountryResponse(
                UUID.randomUUID(),
                "MX",
                "México",
                true,
                LocalDateTime.of(2026, 9, 14, 10, 30),
                LocalDateTime.of(2026, 9, 14, 11, 0));
    }
}
