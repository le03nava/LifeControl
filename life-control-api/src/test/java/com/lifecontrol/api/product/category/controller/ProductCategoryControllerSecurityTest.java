package com.lifecontrol.api.product.category.controller;

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
import com.lifecontrol.api.product.category.dto.ProductCategoryRequest;
import com.lifecontrol.api.product.category.dto.ProductCategoryResponse;
import com.lifecontrol.api.product.category.service.ProductCategoryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The role matrix of the category tree: reads are open to any authenticated caller, writes are
 * {@code lc-admin} only.
 *
 * <p>The write gate is deliberately narrower than the department catalog's, and this suite is where
 * that decision is pinned: the products ABM is admin-only ({@code ProductController}), the category
 * tree is its classification, and no {@code lc-product-category} role exists — introducing one would
 * widen the frozen {@code GrantablePositionRoles} allowlist, a security boundary its own test defines
 * as a deliberate decision (record L5). A holder of an unrelated role must therefore get 403 and not
 * a 404, so the refusal reads as "not yours to write" rather than "does not exist".</p>
 */
@WebMvcTest(ProductCategoryController.class)
@Import(ProductCategoryControllerSecurityTest.TestSecurityConfig.class)
@DisplayName("ProductCategory Controller Security — @PreAuthorize method-level authorization")
class ProductCategoryControllerSecurityTest {

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

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductCategoryService productCategoryService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    private ProductCategoryRequest buildRequest() {
        return new ProductCategoryRequest(null, "Bebidas", "Bebidas y refrescos", 1, true);
    }

    private ProductCategoryResponse buildResponse() {
        return new ProductCategoryResponse(
                categoryId,
                companyId,
                null,
                "Bebidas",
                "Bebidas y refrescos",
                1,
                1,
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    // ─── Reads: any authenticated user ────────────────────────────

    @Nested
    @DisplayName("GET /api/companies/{companyId}/product-categories")
    class ReadSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 for any authenticated user (reads are isAuthenticated)")
        void anyAuthenticatedUserCanRead() throws Exception {
            when(productCategoryService.getCategories(companyId, false)).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/companies/{companyId}/product-categories", companyId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/companies/{companyId}/product-categories", companyId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"lc-sales"})
        @DisplayName("returns 200 for a store-scoped role on GET /{id}")
        void salesRoleCanReadById() throws Exception {
            when(productCategoryService.getCategoryById(companyId, categoryId)).thenReturn(buildResponse());

            mockMvc.perform(get("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId))
                    .andExpect(status().isOk());
        }
    }

    // ─── Writes: lc-admin only ────────────────────────────────────

    @Nested
    @DisplayName("POST /api/companies/{companyId}/product-categories")
    class CreateSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 for lc-admin")
        void adminCanCreate() throws Exception {
            when(productCategoryService.createCategory(eq(companyId), any(ProductCategoryRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-department"})
        @DisplayName("returns 403 for a catalog write role that is not lc-admin")
        void otherCatalogRoleCannotCreate() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/product-categories/{id}")
    class UpdateSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin")
        void adminCanUpdate() throws Exception {
            when(productCategoryService.updateCategory(
                            eq(companyId), eq(categoryId), any(ProductCategoryRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-sales"})
        @DisplayName("returns 403 for a store-scoped role")
        void salesRoleCannotUpdate() throws Exception {
            mockMvc.perform(put("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("DELETE and PATCH enable")
    class DeleteAndEnableSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 for lc-admin")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for DELETE with an unrelated role")
        void unrelatedRoleCannotDelete() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for PATCH enable with lc-admin")
        void adminCanToggle() throws Exception {
            when(productCategoryService.setCategoryEnabled(companyId, categoryId, false))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/companies/{companyId}/product-categories/{id}/enable", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": false}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for PATCH enable with an unrelated role")
        void unrelatedRoleCannotToggle() throws Exception {
            mockMvc.perform(patch("/api/companies/{companyId}/product-categories/{id}/enable", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": false}"))
                    .andExpect(status().isForbidden());
        }
    }
}
