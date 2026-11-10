package com.lifecontrol.api.currency.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.service.CurrencyService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The currency catalog's authorization boundary: reads are {@code isAuthenticated()}, writes are
 * {@code hasAnyRole('lc-admin','lc-currency')}.
 *
 * <p><b>Why "any authenticated role" is the read assertion and not a role name.</b> Reading the catalog
 * grants nothing — it is the same reference data {@code /api/countries} and {@code /api/measure-units}
 * serve — so a role gate here would refuse the very caller the company form exists for. The 401 is the
 * other half: with no token there is no principal at all.</p>
 *
 * <p><b>Why every verb is asserted against five principals.</b> A write to this catalog is a privilege —
 * a retired code stops being assignable to every company — so the boundary is the whole question this
 * endpoint set answers. {@code lc-admin} and {@code lc-currency} are the two admitted roles; a
 * <b>wrong {@code lc-*} role</b> is the case a copy-paste of the annotation from another catalog would
 * get wrong silently, and a principal with no roles is the case where {@code hasAnyRole} must deny
 * instead of falling through to "authenticated".</p>
 *
 * <p>The shape copies {@code PaymentMethodControllerSecurityTest}: a minimal {@code @TestConfiguration}
 * that enables method security without JWT/OAuth2 infrastructure, and {@code @WithMockUser} stating the
 * principal directly.</p>
 */
@WebMvcTest(CurrencyController.class)
@Import(CurrencyControllerSecurityTest.TestSecurityConfig.class)
@DisplayName("Currency Controller Security — @PreAuthorize method-level authorization")
class CurrencyControllerSecurityTest {

    /**
     * Minimal security configuration that enables method-level security without requiring JWT/OAuth2
     * infrastructure. @WithMockUser sets up the SecurityContext directly, bypassing authentication filters.
     */
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(basic -> {})
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .build();
        }
    }

    private static final String CREATE_BODY = "{\"code\":\"USD\",\"minorUnits\":2,\"symbol\":\"US$\"}";
    private static final String UPDATE_BODY = "{\"code\":\"MXN\",\"minorUnits\":2,\"symbol\":\"$\"}";
    private static final String ENABLE_BODY = "{\"enabled\": false}";

    private static final CurrencyResponse MXN = new CurrencyResponse("MXN", (short) 2, "$", true);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrencyService currencyService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    @Nested
    @DisplayName("GET /api/currencies")
    class GetCurrenciesSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user (reads are isAuthenticated)")
        void anyAuthenticatedUserCanRead() throws Exception {
            when(currencyService.getAllCurrencies(false)).thenReturn(List.of(MXN));

            mockMvc.perform(get("/api/currencies")).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for an administrator as well, with no role of its own")
        void administratorCanRead() throws Exception {
            when(currencyService.getAllCurrencies(true)).thenReturn(List.of(MXN));

            mockMvc.perform(get("/api/currencies?includeDisabled=true")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/currencies")).andExpect(status().isUnauthorized());
        }
    }

    // ─── POST /api/currencies ─────────────────────────────────────

    @Nested
    @DisplayName("POST /api/currencies")
    class CreateCurrencySecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for a user with the lc-admin role")
        void adminCanCreate() throws Exception {
            when(currencyService.createCurrency(any())).thenReturn(MXN);

            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-currency"})
        @DisplayName("returns 201 Created for a user with the lc-currency role")
        void domainRoleCanCreate() throws Exception {
            when(currencyService.createCurrency(any())).thenReturn(MXN);

            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-payment-method"})
        @DisplayName("returns 403 Forbidden for a user holding a different catalog's role")
        void otherCatalogRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for an authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/currencies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── PUT /api/currencies/{code} ───────────────────────────────

    @Nested
    @DisplayName("PUT /api/currencies/{code}")
    class UpdateCurrencySecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for a user with the lc-admin role")
        void adminCanUpdate() throws Exception {
            when(currencyService.updateCurrency(eq("MXN"), any())).thenReturn(MXN);

            mockMvc.perform(put("/api/currencies/{code}", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-currency"})
        @DisplayName("returns 200 OK for a user with the lc-currency role")
        void domainRoleCanUpdate() throws Exception {
            when(currencyService.updateCurrency(eq("MXN"), any())).thenReturn(MXN);

            mockMvc.perform(put("/api/currencies/{code}", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-measure-unit"})
        @DisplayName("returns 403 Forbidden for a user holding a different catalog's role")
        void otherCatalogRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/currencies/{code}", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for an authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/currencies/{code}", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/currencies/{code}", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── DELETE /api/currencies/{code} ────────────────────────────

    @Nested
    @DisplayName("DELETE /api/currencies/{code}")
    class DeleteCurrencySecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for a user with the lc-admin role")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/currencies/{code}", "MXN")).andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-currency"})
        @DisplayName("returns 204 No Content for a user with the lc-currency role")
        void domainRoleCanDelete() throws Exception {
            mockMvc.perform(delete("/api/currencies/{code}", "MXN")).andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-country"})
        @DisplayName("returns 403 Forbidden for a user holding a different catalog's role")
        void otherCatalogRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/currencies/{code}", "MXN")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for an authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/currencies/{code}", "MXN")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(delete("/api/currencies/{code}", "MXN")).andExpect(status().isUnauthorized());
        }
    }

    // ─── PATCH /api/currencies/{code}/enable ──────────────────────

    @Nested
    @DisplayName("PATCH /api/currencies/{code}/enable")
    class SetCurrencyEnabledSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for a user with the lc-admin role")
        void adminCanRetire() throws Exception {
            when(currencyService.setCurrencyEnabled("MXN", false)).thenReturn(MXN);

            mockMvc.perform(patch("/api/currencies/{code}/enable", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENABLE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-currency"})
        @DisplayName("returns 200 OK for a user with the lc-currency role")
        void domainRoleCanRetire() throws Exception {
            when(currencyService.setCurrencyEnabled("MXN", false)).thenReturn(MXN);

            mockMvc.perform(patch("/api/currencies/{code}/enable", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENABLE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-status"})
        @DisplayName("returns 403 Forbidden for a user holding a different catalog's role")
        void otherCatalogRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch("/api/currencies/{code}/enable", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENABLE_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for an authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(patch("/api/currencies/{code}/enable", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENABLE_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(patch("/api/currencies/{code}/enable", "MXN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENABLE_BODY))
                    .andExpect(status().isUnauthorized());
        }
    }
}
