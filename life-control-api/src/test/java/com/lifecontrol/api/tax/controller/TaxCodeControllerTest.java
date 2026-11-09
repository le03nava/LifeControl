package com.lifecontrol.api.tax.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.tax.dto.TaxCodeResponse;
import com.lifecontrol.api.tax.service.TaxCodeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /api/tax-codes} at the HTTP boundary: the three catalog fields come back, and
 * {@code includeDisabled} decides whether a retired code is in the answer.
 *
 * <p>Standalone {@code MockMvc} with a mocked {@link TaxCodeService}, the shape
 * {@code CurrencyControllerTest} has: the endpoint's own job is the request parameter and the serialized
 * shape, and the filter behind {@code includeDisabled} is the service's.</p>
 *
 * <p><b>Why the authorization is pinned from the annotation.</b> A standalone {@code MockMvc} applies no
 * method security, so no request here can prove the gate exists. The reflection test below reads
 * {@code @PreAuthorize} from the handler itself and pins its exact expression, which fails both when the
 * annotation disappears and when someone narrows {@code isAuthenticated()} into a role list — the read
 * grants nothing, so a role gate would refuse the very caller the product form exists for.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaxCodeController Tests")
class TaxCodeControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TaxCodeService taxCodeService;

    @InjectMocks
    private TaxCodeController taxCodeController;

    /** The code {@code V81:87} defaults every pre-existing line to, so the catalog cannot omit it. */
    private static final TaxCodeResponse NA = new TaxCodeResponse("NA", "No aplica", true);

    /** The taxed bucket: how much is not stated here, it is resolved from {@code tax_rate}. */
    private static final TaxCodeResponse IVA = new TaxCodeResponse("IVA", "IVA", true);

    /** A retired code: it stays referenceable by history, and the default answer must not offer it. */
    private static final TaxCodeResponse RETIRED = new TaxCodeResponse("RETIRED", "Retirado", false);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(taxCodeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("GET /api/tax-codes")
    class GetAllTaxCodesTests {

        @Test
        @DisplayName("returns 200 with every field of the catalog row")
        void getAllTaxCodes_ReturnsTheCatalogFields() throws Exception {
            when(taxCodeService.getAllTaxCodes(false)).thenReturn(List.of(NA));

            mockMvc.perform(get("/api/tax-codes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].code").value("NA"))
                    .andExpect(jsonPath("$[0].label").value("No aplica"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("serves the enabled codes only when includeDisabled is absent")
        void getAllTaxCodes_Default_ExcludesTheRetiredCode() throws Exception {
            when(taxCodeService.getAllTaxCodes(false)).thenReturn(List.of(NA, IVA));

            mockMvc.perform(get("/api/tax-codes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].code").value("NA"))
                    .andExpect(jsonPath("$[1].code").value("IVA"));

            verify(taxCodeService).getAllTaxCodes(false);
        }

        @Test
        @DisplayName("serves the retired codes too when ?includeDisabled=true")
        void getAllTaxCodes_IncludeDisabled_IncludesTheRetiredCode() throws Exception {
            when(taxCodeService.getAllTaxCodes(true)).thenReturn(List.of(NA, IVA, RETIRED));

            mockMvc.perform(get("/api/tax-codes?includeDisabled=true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[2].code").value("RETIRED"))
                    .andExpect(jsonPath("$[2].enabled").value(false));

            verify(taxCodeService).getAllTaxCodes(true);
        }

        @Test
        @DisplayName("declares exactly isAuthenticated() and no narrower role list")
        void getAllTaxCodes_IsGatedByIsAuthenticatedOnly() throws Exception {
            var method = TaxCodeController.class.getMethod("getAllTaxCodes", boolean.class);
            var annotation = method.getAnnotation(PreAuthorize.class);

            assertThat(annotation)
                    .as("the endpoint must declare its own @PreAuthorize, not inherit one")
                    .isNotNull();
            assertThat(annotation.value())
                    .as("reading the catalog grants nothing, so the gate is authentication and not a role")
                    .isEqualTo("isAuthenticated()");
        }
    }
}
