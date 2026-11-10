package com.lifecontrol.api.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.currency.dto.CurrencyRequest;
import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.model.Currency;
import com.lifecontrol.api.currency.repository.CurrencyRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * <p><b>The write half of the pin (S5).</b> The same region is what a write has to evict, and the failure
 * without eviction is invisible from a status code: {@code GET /api/currencies} keeps answering with a code
 * the service just retired, because the cached list is never re-read. The {@code WritesEvictTheRegion} case
 * drives the real proxy for both annotations — a read that populates the entry, a write through
 * {@link CurrencyService}, and a second read that must reach the repository again — so dropping
 * {@code @CacheEvict} reddens it instead of leaving a stale catalog that only a restart repairs.</p>
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

            when(currencyRepository.findByEnabledTrue())
                    .thenThrow(new RuntimeException("the cache should have answered"));

            var second = codes(currencyService.getAllCurrencies(false));

            assertThat(second).isEqualTo(first).containsExactly("MXN");
        }
    }

    @Nested
    @DisplayName("A write evicts the region")
    class WritesEvictTheRegion {

        /** The catalog as one mutable list, so a write through the service is visible to the next read. */
        private List<Currency> catalog;

        @BeforeEach
        void startFromTheSeededCode() {
            catalog = new ArrayList<>(List.of(new Currency("MXN", (short) 2, "$", true)));
        }

        /** Serves the repository from {@link #catalog}, the way the table would answer after each write. */
        private void serveCatalogFromMemory() {
            when(currencyRepository.findByEnabledTrue())
                    .thenAnswer(invocation -> catalog.stream()
                            .filter(currency -> Boolean.TRUE.equals(currency.getEnabled()))
                            .toList());
            lenient().when(currencyRepository.findAll()).thenAnswer(invocation -> List.copyOf(catalog));
            when(currencyRepository.save(any(Currency.class))).thenAnswer(invocation -> {
                Currency saved = invocation.getArgument(0);
                if (!catalog.contains(saved)) {
                    catalog.add(saved);
                }
                return saved;
            });
        }

        @Test
        @DisplayName("a code retired through the service disappears from the very next read")
        void retireThroughTheService_IsVisibleToTheNextRead() {
            serveCatalogFromMemory();
            when(currencyRepository.findById("MXN")).thenReturn(Optional.of(catalog.get(0)));

            assertThat(codes(currencyService.getAllCurrencies(false))).containsExactly("MXN");

            currencyService.setCurrencyEnabled("MXN", false);

            assertThat(codes(currencyService.getAllCurrencies(false))).isEmpty();
        }

        @Test
        @DisplayName("a code added through the service appears in the very next read")
        void createThroughTheService_IsVisibleToTheNextRead() {
            serveCatalogFromMemory();

            assertThat(codes(currencyService.getAllCurrencies(false))).containsExactly("MXN");

            currencyService.createCurrency(new CurrencyRequest("USD", (short) 2, "US$", null));

            assertThat(codes(currencyService.getAllCurrencies(false))).containsExactly("MXN", "USD");
        }

        @Test
        @DisplayName("a retire also refreshes the includeDisabled shape of the same region")
        void retireThroughTheService_IsVisibleToTheFullCatalogRead() {
            serveCatalogFromMemory();
            when(currencyRepository.findById("MXN")).thenReturn(Optional.of(catalog.get(0)));

            assertThat(currencyService.getAllCurrencies(true))
                    .extracting(CurrencyResponse::enabled)
                    .containsExactly(true);

            currencyService.setCurrencyEnabled("MXN", false);

            assertThat(currencyService.getAllCurrencies(true))
                    .extracting(CurrencyResponse::enabled)
                    .containsExactly(false);
        }
    }
}
