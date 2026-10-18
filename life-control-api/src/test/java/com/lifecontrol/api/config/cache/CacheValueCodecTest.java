package com.lifecontrol.api.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.country.dto.CountryResponse;
import com.lifecontrol.api.hr.dto.SeniorityLevelResponse;
import com.lifecontrol.api.measureunit.dto.MeasureUnitResponse;
import com.lifecontrol.api.paymentmethod.dto.PaymentMethodResponse;
import com.lifecontrol.api.status.dto.StatusResponse;
import com.lifecontrol.api.status.dto.StatusTypeResponse;
import com.lifecontrol.api.status.service.StatusTypePage;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

/**
 * Guards every value the application's Redis cache can hold.
 *
 * <p>The Redis cache serializes values with Jackson default typing and reads them back with no
 * knowledge of their declared type, so a value that cannot be reconstructed is a permanent endpoint
 * failure for the entry's whole TTL, not a cache miss. {@code Page<StatusTypeResponse>} was exactly
 * that defect: {@code PageImpl} has no Jackson creator, so the second call to
 * {@code GET /api/status-types} answered 500. The in-memory manager hid it because a hit never had
 * to serialize.
 *
 * <p>Two tests close that class of defect:
 * <ul>
 *   <li>{@link #everyCachedReturnTypeRoundTrips()} round-trips one representative of each cached
 *       return type through the production {@link CacheConfig#valueSerializer()}.</li>
 *   <li>{@link #everyCacheableReturnTypeHasARepresentative()} scans the {@code com.lifecontrol.api}
 *       class path for {@code @Cacheable} methods and fails when a return type has no registered
 *       representative, so the next unrepresentable cached type fails here instead of in an
 *       endpoint.</li>
 * </ul>
 */
@DisplayName("Cache value codec coverage")
class CacheValueCodecTest {

    private final GenericJacksonJsonRedisSerializer serializer = CacheConfig.valueSerializer();

    @Test
    @DisplayName("every cached return type round-trips through the production value serializer")
    void everyCachedReturnTypeRoundTrips() {
        assertThat(REPRESENTATIVES).isNotEmpty();

        REPRESENTATIVES.forEach((typeName, value) -> {
            byte[] bytes = serializer.serialize(value);
            assertThat(bytes)
                    .as("%s must serialize to a non-empty payload", typeName)
                    .isNotEmpty();
            assertThat(serializer.deserialize(bytes))
                    .as("%s must be reconstructible by the production serializer", typeName)
                    .isEqualTo(value);
        });
    }

    @Test
    @DisplayName("the list shapes production actually produces round-trip (Stream.toList and Page.map)")
    void productionRuntimeListShapesRoundTrip() {
        var country =
                new CountryResponse(UUID.randomUUID(), "MX", "México", true, LocalDateTime.now(), LocalDateTime.now());

        // CountryService.getAllCountries caches Stream#toList's result, which is a ListN; List.of
        // produces a different class, so the representative table alone would not have proven this.
        assertThat(serializer.deserialize(
                        serializer.serialize(Stream.of(country).map(x -> x).toList())))
                .isEqualTo(List.of(country));

        // StatusTypeCatalog caches Page#map(...).getContent(), an unmodifiable random-access list.
        var statusType =
                new StatusTypeResponse(UUID.randomUUID(), "ORDER", true, LocalDateTime.now(), LocalDateTime.now());
        var content = new PageImpl<>(List.of("x"), PageRequest.of(0, 12), 1)
                .map(x -> statusType)
                .getContent();
        assertThat(content.getClass().getName()).contains("Unmodifiable");
        assertThat(serializer.deserialize(serializer.serialize(new StatusTypePage(content, 1L))))
                .isEqualTo(new StatusTypePage(content, 1L));
    }

    @Test
    @DisplayName("every @Cacheable return type has a registered representative")
    void everyCacheableReturnTypeHasARepresentative() {
        List<CachedMethod> cachedMethods = scanCacheableMethods();
        assertThat(cachedMethods)
                .as("the class-path scan must find the @Cacheable methods")
                .isNotEmpty();

        List<String> missing = new ArrayList<>();
        Set<String> regions = new LinkedHashSet<>();
        for (CachedMethod cached : cachedMethods) {
            String typeName = cached.method().getGenericReturnType().getTypeName();
            if (!REPRESENTATIVES.containsKey(typeName)) {
                missing.add(
                        cached.declaringType().getName() + "#" + cached.method().getName() + " -> " + typeName);
            }
            Cacheable cacheable = AnnotatedElementUtils.findMergedAnnotation(cached.method(), Cacheable.class);
            regions.addAll(List.of(cacheable.value()));
        }

        assertThat(regions)
                .as("the scan must reach every cached region, or it is silently passing")
                .contains("countries", "statusTypes", "statuses", "measureUnits", "paymentMethods", "seniorityLevels");
        assertThat(missing)
                .as("register a representative in REPRESENTATIVES for every @Cacheable return type")
                .isEmpty();
    }

    private static List<CachedMethod> scanCacheableMethods() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);

        List<CachedMethod> methods = new ArrayList<>();
        for (var candidate : provider.findCandidateComponents("com.lifecontrol.api")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName(), false, CacheValueCodecTest.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError ignored) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, Cacheable.class)) {
                    methods.add(new CachedMethod(type, method));
                }
            }
        }
        return methods;
    }

    private record CachedMethod(Class<?> declaringType, Method method) {}

    private static final Map<String, Object> REPRESENTATIVES = representatives();

    private static Map<String, Object> representatives() {
        Map<String, Object> values = new LinkedHashMap<>();

        var country = new CountryResponse(
                UUID.randomUUID(),
                "MX",
                "México",
                true,
                LocalDateTime.of(2026, 9, 14, 10, 30),
                LocalDateTime.of(2026, 9, 14, 11, 0));
        values.put(CountryResponse.class.getName(), country);
        values.put(listOf(CountryResponse.class), List.of(country));

        var statusType =
                new StatusTypeResponse(UUID.randomUUID(), "ORDER", true, LocalDateTime.now(), LocalDateTime.now());
        values.put(StatusTypeResponse.class.getName(), statusType);
        values.put(StatusTypePage.class.getName(), new StatusTypePage(List.of(statusType), 1L));

        var status = new StatusResponse(
                UUID.randomUUID(),
                "Draft",
                UUID.randomUUID(),
                "SALES_ORDER",
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
        values.put(StatusResponse.class.getName(), status);
        values.put(listOf(StatusResponse.class), List.of(status));

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
        values.put(MeasureUnitResponse.class.getName(), measureUnit);
        values.put(listOf(MeasureUnitResponse.class), List.of(measureUnit));

        var seniorityLevel = new SeniorityLevelResponse(
                UUID.randomUUID(), "JUN", "Junior", 1, true, LocalDateTime.now(), LocalDateTime.now());
        values.put(SeniorityLevelResponse.class.getName(), seniorityLevel);
        values.put(listOf(SeniorityLevelResponse.class), List.of(seniorityLevel));

        var paymentMethod = new PaymentMethodResponse(
                UUID.randomUUID(), "Cash", "CASH", true, LocalDateTime.now(), LocalDateTime.now());
        values.put(PaymentMethodResponse.class.getName(), paymentMethod);
        values.put(listOf(PaymentMethodResponse.class), List.of(paymentMethod));

        return Map.copyOf(values);
    }

    private static String listOf(Class<?> element) {
        return "java.util.List<" + element.getName() + ">";
    }
}
