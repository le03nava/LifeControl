package com.lifecontrol.api.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.model.Currency;
import com.lifecontrol.api.currency.repository.CurrencyRepository;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins that the two shapes of {@link CurrencyService#getAllCurrencies(boolean)} cannot be served for each
 * other.
 *
 * <p><b>Why this test exists at all.</b> The catalog answer depends on {@code includeDisabled}, and the
 * annotation carries that flag only in its key expression ({@code "'all-' + #includeDisabled"}). The two
 * neighbouring parity tests do not cover it: {@code CacheManagerSelectionTest} pins the <b>region names</b>
 * and {@code CacheValueCodecTest} pins the <b>return type</b>, so a refactor that dropped the flag from the
 * key would keep both green while every caller that asked for the enabled set received the retired codes —
 * and every caller that asked for the full catalog received a shorter list. Neither of those is visible
 * from the endpoint's own test, which mocks the service.</p>
 *
 * <p><b>What is asserted, and how.</b> Only what a caller can observe: the codes that come back from the
 * public method. Each case calls the two shapes in one order against a repository stubbed to answer them
 * differently, so a shared entry makes the second answer wrong instead of merely slower. The third case
 * pins that one shape alone is still cached, which is what proves the flag splits <b>entries</b> inside one
 * region rather than disabling the cache.</p>
 *
 * <p>Runs against the {@code test} profile, where {@code spring.cache.type=simple} selects the in-memory
 * {@link CacheManager} and Spring's AOP proxy applies the {@code @Cacheable} annotation for real — a unit
 * test with a hand-built service would assert nothing about the key.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("CurrencyService cache key integration tests")
class CurrencyServiceCacheTest {

    private static final Currency MXN = new Currency("MXN", (short) 2, "$", true);
    private static final Currency USD = new Currency("USD", (short) 2, "US$", false);

    @Autowired
    private CurrencyService currencyService;

    @MockitoBean
    private CurrencyRepository currencyRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().stream()
                .map(cacheManager::getCache)
                .filter(Objects::nonNull)
                .forEach(cache -> cache.clear());
    }

    private static List<String> codes(List<CurrencyResponse> currencies) {
        return currencies.stream().map(CurrencyResponse::code).toList();
    }

    @Nested
    @DisplayName("One region, one entry per shape")
    class KeyCarriesTheFlag {

        @Test
        @DisplayName("the enabled answer is not served to a caller that asked for the retired codes too")
        void enabledThenAll_SecondCallerStillReceivesTheRetiredCode() {
            when(currencyRepository.findByEnabledTrue()).thenReturn(List.of(MXN));
            when(currencyRepository.findAll()).thenReturn(List.of(MXN, USD));

            assertThat(codes(currencyService.getAllCurrencies(false))).containsExactly("MXN");

            assertThat(codes(currencyService.getAllCurrencies(true))).containsExactly("MXN", "USD");
        }

        @Test
        @DisplayName("the full answer is not served to a caller that asked for the enabled set only")
        void allThenEnabled_SecondCallerDoesNotReceiveTheRetiredCode() {
            when(currencyRepository.findByEnabledTrue()).thenReturn(List.of(MXN));
            when(currencyRepository.findAll()).thenReturn(List.of(MXN, USD));

            assertThat(codes(currencyService.getAllCurrencies(true))).containsExactly("MXN", "USD");

            assertThat(codes(currencyService.getAllCurrencies(false))).containsExactly("MXN");
        }
    }

    @Nested
    @DisplayName("Each shape is still cached")
    class EachShapeIsCached {

        @Test
        @DisplayName("a second read of the same shape does not reach the repository again")
        void repeatedReadOfOneShape_HitsTheRepositoryOnce() {
            when(currencyRepository.findByEnabledTrue()).thenReturn(List.of(MXN));

            var first = codes(currencyService.getAllCurrencies(false));

            when(currencyRepository.findByEnabledTrue()).thenThrow(new RuntimeException("the cache should have answered"));

            var second = codes(currencyService.getAllCurrencies(false));

            assertThat(second).isEqualTo(first).containsExactly("MXN");
        }
    }
}
