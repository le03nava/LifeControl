package com.lifecontrol.api.goodsreceipt.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptLineRequest;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptLineResponse;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptRequest;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptResponse;
import com.lifecontrol.api.goodsreceipt.service.GoodsReceiptService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
 * Pins the {@code @PreAuthorize} role sets of the goods receipt endpoints: the reads add
 * {@code lc-company-store-read}, the create does not. No receiving role exists yet, so none is used.
 */
@WebMvcTest(GoodsReceiptController.class)
@Import({GlobalExceptionHandler.class, GoodsReceiptControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("GoodsReceiptController Security — @PreAuthorize method-level authorization")
class GoodsReceiptControllerSecurityTest {

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

    private static final String BASE_URL = "/api/goods-receipts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GoodsReceiptService goodsReceiptService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private GoodsReceiptRequest request;
    private GoodsReceiptResponse response;

    @BeforeEach
    void setUp() {
        var purchaseOrderId = UUID.randomUUID();
        var detailId = UUID.randomUUID();
        var variantId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();

        request = new GoodsReceiptRequest(
                purchaseOrderId,
                UUID.randomUUID(),
                "Reception",
                List.of(new GoodsReceiptLineRequest(detailId, BigDecimal.ONE, null)));
        response = new GoodsReceiptResponse(
                receiptId,
                "GR-PO-20260603-00001-01",
                purchaseOrderId,
                "PO-20260603-00001",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Registered",
                "receiver",
                LocalDateTime.now(),
                "Reception",
                true,
                List.of(new GoodsReceiptLineResponse(detailId, detailId, variantId, new BigDecimal("1.00"), null)));

        when(goodsReceiptService.createReceipt(any(GoodsReceiptRequest.class))).thenReturn(response);
        when(goodsReceiptService.getAllReceipts(any(), any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 12), 1));
        when(goodsReceiptService.getReceipt(any())).thenReturn(response);
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateReceipt {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for the read-only store role")
        void readOnlyStoreRoleCannotWrite() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 201 for an authorized store role")
        void storeRoleCanWrite() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 for lc-admin")
        void adminCanWrite() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllReceipts {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 for the read-only store role")
        void readOnlyStoreRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 for an authorized store role")
        void storeRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL)).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetReceiptById {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", UUID.randomUUID())).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 for the read-only store role")
        void readOnlyStoreRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", UUID.randomUUID())).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 for an authorized store role")
        void storeRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", UUID.randomUUID())).andExpect(status().isOk());
        }
    }
}
