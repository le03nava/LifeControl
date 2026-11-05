package com.lifecontrol.api.currency.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * The currency catalog's authorization boundary: {@code isAuthenticated()} and nothing narrower.
 *
 * <p><b>Why "any authenticated role" is the assertion and not a role name.</b> Reading the catalog grants
 * nothing — it is the same reference data {@code /api/countries} and {@code /api/measure-units} serve — so
 * a role gate here would refuse the very caller the company form exists for. The 401 is the other half:
 * with no token there is no principal at all.</p>
 *
 * <p>The shape copies {@code MeasureUnitControllerSecurityTest}: a minimal {@code @TestConfiguration} that
 * enables method security without JWT/OAuth2 infrastructure, and {@code @WithMockUser} stating the
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
            when(currencyService.getAllCurrencies(false))
                    .thenReturn(List.of(new CurrencyResponse("MXN", (short) 2, "$", true)));

            mockMvc.perform(get("/api/currencies")).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for an administrator as well, with no role of its own")
        void administratorCanRead() throws Exception {
            when(currencyService.getAllCurrencies(true))
                    .thenReturn(List.of(new CurrencyResponse("MXN", (short) 2, "$", true)));

            mockMvc.perform(get("/api/currencies?includeDisabled=true")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/currencies")).andExpect(status().isUnauthorized());
        }
    }
}
