package com.lifecontrol.api.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.company.dto.CompanyZoneResponse;
import com.lifecontrol.api.country.dto.CountryResponse;
import com.lifecontrol.api.measureunit.dto.MeasureUnitResponse;
import com.lifecontrol.api.status.dto.StatusResponse;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

/**
 * Tests for the cache value serializer.
 * <p>
 * Guards the regression where cached DTOs are Java records (not {@link java.io.Serializable})
 * and the JDK serializer used by {@code RedisCacheConfiguration.defaultCacheConfig()}
 * would fail at runtime. Each cached payload must survive a serialize/deserialize round-trip
 * through the same serializer configured in {@link CacheConfig}.
 */
@DisplayName("Cache value serializer Tests")
class CacheValueSerializerTest {

    private final GenericJackson2JsonRedisSerializer serializer = CacheConfig.valueSerializer();

    @Test
    @DisplayName("round-trips a single cached record (CountryResponse)")
    void roundTrip_CountryResponse() {
        var country = new CountryResponse(
                UUID.randomUUID(),
                "MX",
                "México",
                true,
                LocalDateTime.of(2026, 9, 14, 10, 30),
                LocalDateTime.of(2026, 9, 14, 11, 0));

        var restored = roundTrip(country);

        assertThat(restored).isEqualTo(country);
    }

    @Test
    @DisplayName("round-trips an immutable list of cached records")
    void roundTrip_ImmutableListOfRecords() {
        var countries = List.of(
                new CountryResponse(UUID.randomUUID(), "MX", "México", true, LocalDateTime.now(), LocalDateTime.now()),
                new CountryResponse(
                        UUID.randomUUID(), "US", "United States", false, LocalDateTime.now(), LocalDateTime.now()));

        var restored = roundTrip(countries);

        assertThat(restored)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyElementsOf(countries);
    }

    @Test
    @DisplayName("round-trips a mutable ArrayList of cached records")
    void roundTrip_ArrayListOfRecords() {
        var statuses = Arrays.asList(new StatusResponse(
                UUID.randomUUID(),
                "Draft",
                UUID.randomUUID(),
                "SALES_ORDER",
                true,
                LocalDateTime.now(),
                LocalDateTime.now()));

        var restored = roundTrip(statuses);

        assertThat(restored)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyElementsOf(statuses);
    }

    @Test
    @DisplayName("round-trips other cached record types")
    void roundTrip_OtherCachedRecords() {
        var measureUnit = new MeasureUnitResponse(
                UUID.randomUUID(),
                "Kilogram",
                "kg",
                "WEIGHT",
                "KGM",
                "Peso",
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
        var zone = new CompanyZoneResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Z-01",
                "Centro",
                "desc",
                1,
                true,
                LocalDateTime.now(),
                LocalDateTime.now());

        assertThat(roundTrip(measureUnit)).isEqualTo(measureUnit);
        assertThat(roundTrip(zone)).isEqualTo(zone);
    }

    private Object roundTrip(Object value) {
        var bytes = serializer.serialize(value);
        assertThat(bytes).as("serializer must produce a non-empty payload").isNotEmpty();
        return serializer.deserialize(bytes);
    }
}
