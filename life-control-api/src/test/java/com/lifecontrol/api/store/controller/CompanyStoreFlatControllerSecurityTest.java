package com.lifecontrol.api.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.service.CompanyStoreService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Security matrix of the flat, id-addressed store lookup.
 *
 * <p>It exists because that endpoint's widening was unasserted: the nested
 * {@link CompanyStoreController} has its own {@code @WebMvcTest} matrix and {@code
 * CompanyStoreFlatControllerTest} is a standalone {@code MockMvc} setup without method security, so
 * before this class nothing would have failed if the flat {@code GET} lost a role or gained one. The
 * flat path is not a second gate — the service resolves the store's chain and authorizes it through
 * {@code verifyCompanyStoreAccess} — but the annotation is what decides who ever reaches that
 * service, so it needs the same pin as its siblings.</p>
 *
 * <p>Read-only: the controller exposes one {@code GET}, so a role absent from the list is refused
 * with 403 rather than filtered, and the two negative cases keep a store-adjacent role
 * ({@code lc-sales}) and an anonymous caller out.</p>
 */
@WebMvcTest(CompanyStoreFlatController.class)
@Import({GlobalExceptionHandler.class, CompanyStoreFlatControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("CompanyStoreFlatController Security — @PreAuthorize authorization")
class CompanyStoreFlatControllerSecurityTest {

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

    private static final String BASE_URL = "/api/stores";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompanyStoreService companyStoreService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID storeId = UUID.randomUUID();

    private CompanyStoreResponse buildStoreResponse() {
        return new CompanyStoreResponse(
                storeId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Tienda Test",
                "tienda@test.com",
                "555-0001",
                null,
                true,
                LocalDateTime.now(),
                LocalDateTime.now(),
                0L);
    }

    @Nested
    @DisplayName("GET /api/stores/{storeId}")
    class GetStoreById {

        @ParameterizedTest(name = "{0} can read the flat store lookup")
        @ValueSource(
                strings = {
                    "lc-admin",
                    "lc-company",
                    "lc-company-country",
                    "lc-company-region",
                    "lc-company-zone",
                    "lc-company-store",
                    "lc-company-store-read",
                    "lc-company-read",
                    "lc-company-country-read",
                    "lc-company-region-read",
                    "lc-company-zone-read"
                })
        @WithMockUser()
        @DisplayName("every admitted role, including the ancestor read roles, returns 200 OK")
        void admittedRolesCanReadTheStore(String role) throws Exception {
            when(companyStoreService.getStoreById(any())).thenReturn(buildStoreResponse());

            mockMvc.perform(get(BASE_URL + "/{storeId}", storeId)
                            .with(user("subject").roles(role)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-sales"})
        @DisplayName("a store-adjacent role outside the list returns 403 Forbidden")
        void storeAdjacentRoleIsRefused() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{storeId}", storeId)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{storeId}", storeId)).andExpect(status().isUnauthorized());
        }
    }
}
