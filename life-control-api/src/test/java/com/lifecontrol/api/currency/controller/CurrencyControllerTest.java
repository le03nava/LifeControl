package com.lifecontrol.api.currency.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.currency.dto.CurrencyRequest;
import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.exception.CurrencyNotFoundException;
import com.lifecontrol.api.currency.exception.DuplicateCurrencyException;
import com.lifecontrol.api.currency.service.CurrencyService;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The currency catalog at the HTTP boundary: {@code GET}, and the four write verbs with the status each
 * refusal becomes.
 *
 * <p>Standalone {@code MockMvc} with a mocked {@link CurrencyService} and the real
 * {@link GlobalExceptionHandler}, the shape {@code MeasureUnitControllerTest} has: the endpoint's own job
 * is the request body, the path variable and the status the exception becomes, while what a refusal leaves
 * stored is the service's and is pinned in {@code CurrencyServiceTest}.</p>
 *
 * <p><b>Why the three refusals are asserted here.</b> S3 fixes them at the HTTP boundary — 409 on a
 * duplicate code, 404 on an unknown one, 400 on a {@code minorUnits} or {@code symbol} the DDL would
 * refuse — and S4 fixes a fourth: a delete the foreign keys refuse is a 409 and never a 500. A status is
 * the only thing a caller sees, so it is a thing of its own; the last two cases are also the only place
 * where "the handler that already exists covers this type" can be observed at all.</p>
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

    /** A code the catalog does not carry yet, which is what {@code POST} answers with. */
    private static final CurrencyResponse USD = new CurrencyResponse("USD", (short) 2, "US$", true);

    /** {@code MXN} after a retire: the same row, with {@code enabled} flipped. */
    private static final CurrencyResponse RETIRED_MXN = new CurrencyResponse("MXN", (short) 2, "$", false);

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

    // ─── POST /api/currencies ─────────────────────────────────────

    @Nested
    @DisplayName("POST /api/currencies")
    class CreateCurrencyTests {

        @Test
        @DisplayName("returns 201 with the row the catalog now carries")
        void createCurrency_Returns201WithTheStoredRow() throws Exception {
            when(currencyService.createCurrency(any())).thenReturn(USD);

            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"USD\",\"minorUnits\":2,\"symbol\":\"US$\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("USD"))
                    .andExpect(jsonPath("$.minorUnits").value(2))
                    .andExpect(jsonPath("$.symbol").value("US$"))
                    .andExpect(jsonPath("$.enabled").value(true));

            verify(currencyService).createCurrency(any());
        }

        @Test
        @DisplayName("hands the service the whole body, with an omitted enabled defaulting to true")
        void createCurrency_HandsTheStatedStateToTheService() throws Exception {
            when(currencyService.createCurrency(any())).thenReturn(USD);

            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"USD\",\"minorUnits\":2,\"symbol\":\"US$\"}"))
                    .andExpect(status().isCreated());

            var captor = ArgumentCaptor.forClass(CurrencyRequest.class);
            verify(currencyService).createCurrency(captor.capture());
            assertThat(captor.getValue()).isEqualTo(new CurrencyRequest("USD", (short) 2, "US$", true));
        }

        @Test
        @DisplayName("returns 409 and no row when the code already exists")
        void createCurrency_WithAnExistingCode_Returns409() throws Exception {
            when(currencyService.createCurrency(any())).thenThrow(new DuplicateCurrencyException("MXN"));

            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"MXN\",\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Currency with code 'MXN' already exists"))
                    .andExpect(jsonPath("$.code").doesNotExist());
        }

        @Test
        @DisplayName("returns 400 naming minorUnits when it is above the DDL bound and nothing is written")
        void createCurrency_WithMinorUnitsAboveTheBound_Returns400() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"USD\",\"minorUnits\":5,\"symbol\":\"US$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.minorUnits").exists());

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming minorUnits when it is negative and nothing is written")
        void createCurrency_WithNegativeMinorUnits_Returns400() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"USD\",\"minorUnits\":-1,\"symbol\":\"US$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.minorUnits").exists());

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming symbol when it is longer than the column and nothing is written")
        void createCurrency_WithASymbolLongerThanTheColumn_Returns400() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"USD\",\"minorUnits\":2,\"symbol\":\"US$long\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.symbol").exists());

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming code when it is not three characters and nothing is written")
        void createCurrency_WithACodeThatIsNotThreeCharacters_Returns400() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"MX\",\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.code").exists());

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming code when it is blank and nothing is written")
        void createCurrency_WithABlankCode_Returns400() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"\",\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.code").exists());

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming code when it is lowercase, and writes no second spelling of it")
        void createCurrency_WithALowercaseCode_Returns400AndWritesNothing() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"mxn\",\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.code").exists());

            // The refusal is the catalog's invariance: nothing reached the service, so no row exists for the
            // catalog to answer `mxn` with, and the caller's string was not rewritten into `MXN` either.
            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming code when it is three characters but not three letters")
        void createCurrency_WithACodeThatIsNotThreeLetters_Returns400AndWritesNothing() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"MX$\",\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.code").exists());

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming code when it holds a space instead of a letter")
        void createCurrency_WithASpaceInTheCode_Returns400AndWritesNothing() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"M X\",\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.code").exists());

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming code when it is null, as a refusal and never a 500")
        void createCurrency_WithANullCode_Returns400() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":null,\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.code").value("code is required"));

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming code when the body omits it, as a refusal and never a 500")
        void createCurrency_WithAnOmittedCode_Returns400() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.code").value("code is required"));

            verifyNoInteractions(currencyService);
        }
    }

    // ─── PUT /api/currencies/{code} ───────────────────────────────

    @Nested
    @DisplayName("PUT /api/currencies/{code}")
    class UpdateCurrencyTests {

        @Test
        @DisplayName("returns 200 with the updated row")
        void updateCurrency_Returns200WithTheUpdatedRow() throws Exception {
            when(currencyService.updateCurrency(eq("MXN"), any())).thenReturn(MXN);

            mockMvc.perform(put("/api/currencies/{code}", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"MXN\",\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("MXN"))
                    .andExpect(jsonPath("$.minorUnits").value(2))
                    .andExpect(jsonPath("$.enabled").value(true));

            verify(currencyService).updateCurrency(eq("MXN"), any());
        }

        @Test
        @DisplayName("returns 404 when the path names a code the catalog does not carry")
        void updateCurrency_WithAnUnknownCode_Returns404() throws Exception {
            when(currencyService.updateCurrency(eq("ZZZ"), any())).thenThrow(new CurrencyNotFoundException("ZZZ"));

            mockMvc.perform(put("/api/currencies/{code}", "ZZZ")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"ZZZ\",\"minorUnits\":2,\"symbol\":\"Z\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Currency not found with code: ZZZ"));
        }

        @Test
        @DisplayName("returns 400 naming symbol when it is longer than the column and nothing is written")
        void updateCurrency_WithASymbolLongerThanTheColumn_Returns400() throws Exception {
            mockMvc.perform(put("/api/currencies/{code}", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"MXN\",\"minorUnits\":2,\"symbol\":\"US$long\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.symbol").exists());

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming code when the body states a lowercase code, and writes nothing")
        void updateCurrency_WithALowercaseBodyCode_Returns400AndWritesNothing() throws Exception {
            mockMvc.perform(put("/api/currencies/{code}", "mxn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"mxn\",\"minorUnits\":2,\"symbol\":\"$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.code").exists());

            // Same rule on the update body, and the same observable: the service is never reached, so no
            // row is created by the normal-named path and none is written under a lowercase spelling.
            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 naming minorUnits when it is above the DDL bound and nothing is written")
        void updateCurrency_WithMinorUnitsAboveTheBound_Returns400() throws Exception {
            mockMvc.perform(put("/api/currencies/{code}", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"MXN\",\"minorUnits\":5,\"symbol\":\"$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.minorUnits").exists());

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 400 when the body states a code other than the path's")
        void updateCurrency_WithABodyCodeThatDiffersFromThePath_Returns400() throws Exception {
            when(currencyService.updateCurrency(eq("MXN"), any()))
                    .thenThrow(new IllegalArgumentException("a currency's code cannot be renamed: the path names '"
                            + "MXN' and the body states 'USD'"));

            mockMvc.perform(put("/api/currencies/{code}", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"USD\",\"minorUnits\":2,\"symbol\":\"US$\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message")
                            .value("a currency's code cannot be renamed: the path names 'MXN' and the body "
                                    + "states 'USD'"));
        }
    }

    // ─── DELETE /api/currencies/{code} ────────────────────────────

    @Nested
    @DisplayName("DELETE /api/currencies/{code}")
    class DeleteCurrencyTests {

        @Test
        @DisplayName("returns 204 with no body for a row nothing references")
        void deleteCurrency_Returns204() throws Exception {
            mockMvc.perform(delete("/api/currencies/{code}", "MXN")).andExpect(status().isNoContent());

            verify(currencyService).deleteCurrency("MXN");
        }

        @Test
        @DisplayName("returns 404 when the path names a code the catalog does not carry")
        void deleteCurrency_WithAnUnknownCode_Returns404() throws Exception {
            doThrow(new CurrencyNotFoundException("ZZZ")).when(currencyService).deleteCurrency("ZZZ");

            mockMvc.perform(delete("/api/currencies/{code}", "ZZZ"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Currency not found with code: ZZZ"));
        }

        @Test
        @DisplayName("returns 409, never 500, when a foreign key still points at the code")
        void deleteCurrency_WhenAForeignKeyStillPointsAtTheCode_Returns409() throws Exception {
            doThrow(new DataIntegrityViolationException(
                            "update or delete on table \"currency\" violates foreign key constraint "
                                    + "\"companies_currency_fkey\" on table \"companies\""))
                    .when(currencyService)
                    .deleteCurrency("MXN");

            mockMvc.perform(delete("/api/currencies/{code}", "MXN"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message")
                            .value("The operation conflicts with an existing resource or violates a data "
                                    + "constraint"));

            verify(currencyService).deleteCurrency("MXN");
        }
    }

    // ─── PATCH /api/currencies/{code}/enable ──────────────────────

    @Nested
    @DisplayName("PATCH /api/currencies/{code}/enable")
    class SetCurrencyEnabledTests {

        @Test
        @DisplayName("returns 200 with the retired row")
        void setCurrencyEnabled_Returns200WithTheRetiredRow() throws Exception {
            when(currencyService.setCurrencyEnabled("MXN", false)).thenReturn(RETIRED_MXN);

            mockMvc.perform(patch("/api/currencies/{code}/enable", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("MXN"))
                    .andExpect(jsonPath("$.enabled").value(false));

            verify(currencyService).setCurrencyEnabled("MXN", false);
        }

        @Test
        @DisplayName("enables the code when the body omits the flag")
        void setCurrencyEnabled_WithoutTheFlag_EnablesTheCode() throws Exception {
            when(currencyService.setCurrencyEnabled("MXN", true)).thenReturn(MXN);

            mockMvc.perform(patch("/api/currencies/{code}/enable", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            verify(currencyService).setCurrencyEnabled("MXN", true);
        }

        @Test
        @DisplayName("returns 400, never 500, when the body states a null flag")
        void setCurrencyEnabled_WithANullFlag_Returns400() throws Exception {
            mockMvc.perform(patch("/api/currencies/{code}/enable", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": null}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verifyNoInteractions(currencyService);
        }

        @Test
        @DisplayName("returns 404 when the path names a code the catalog does not carry")
        void setCurrencyEnabled_WithAnUnknownCode_Returns404() throws Exception {
            doThrow(new CurrencyNotFoundException("ZZZ")).when(currencyService).setCurrencyEnabled("ZZZ", false);

            mockMvc.perform(patch("/api/currencies/{code}/enable", "ZZZ")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": false}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Currency not found with code: ZZZ"));
        }
    }
}
