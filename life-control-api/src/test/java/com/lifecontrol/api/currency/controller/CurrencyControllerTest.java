package com.lifecontrol.api.currency.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.service.CurrencyService;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /api/currencies} at the HTTP boundary: the four catalog fields come back, and
 * {@code includeDisabled} decides whether a retired code is in the answer.
 *
 * <p>Standalone {@code MockMvc} with a mocked {@link CurrencyService}, the shape
 * {@code MeasureUnitControllerTest} has: the endpoint's own job is the request parameter and the
 * serialized shape, and the filter behind {@code includeDisabled} is the service's.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CurrencyController Tests")
class CurrencyControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CurrencyService currencyService;

    @InjectMocks
    private CurrencyController currencyController;

    /** The enabled code {@code V57__company_currency.sql} seeds. */
    private static final CurrencyResponse MXN = new CurrencyResponse("MXN", (short) 2, "$", true);

    /** A retired code: it stays referenceable by history, and the default answer must not offer it. */
    private static final CurrencyResponse RETIRED = new CurrencyResponse("VES", (short) 2, "Bs.", false);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(currencyController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("GET /api/currencies")
    class GetAllCurrenciesTests {

        @Test
        @DisplayName("returns 200 with every field of the catalog row")
        void getAllCurrencies_ReturnsTheCatalogFields() throws Exception {
            when(currencyService.getAllCurrencies(false)).thenReturn(List.of(MXN));

            mockMvc.perform(get("/api/currencies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].code").value("MXN"))
                    .andExpect(jsonPath("$[0].minorUnits").value(2))
                    .andExpect(jsonPath("$[0].symbol").value("$"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("serves the enabled codes only when includeDisabled is absent")
        void getAllCurrencies_Default_ExcludesTheRetiredCode() throws Exception {
            when(currencyService.getAllCurrencies(false)).thenReturn(List.of(MXN));

            mockMvc.perform(get("/api/currencies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].code").value("MXN"));

            verify(currencyService).getAllCurrencies(false);
        }

        @Test
        @DisplayName("serves the retired codes too when ?includeDisabled=true")
        void getAllCurrencies_IncludeDisabled_IncludesTheRetiredCode() throws Exception {
            when(currencyService.getAllCurrencies(true)).thenReturn(List.of(MXN, RETIRED));

            mockMvc.perform(get("/api/currencies?includeDisabled=true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[1].code").value("VES"))
                    .andExpect(jsonPath("$[1].enabled").value(false));

            verify(currencyService).getAllCurrencies(true);
        }
    }
}
