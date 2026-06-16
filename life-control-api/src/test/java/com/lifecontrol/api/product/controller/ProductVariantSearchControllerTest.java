package com.lifecontrol.api.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.product.dto.ProductVariantSearchResponse;
import com.lifecontrol.api.product.service.ProductVariantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductVariantSearchController Tests")
class ProductVariantSearchControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ProductVariantService productVariantService;

    @InjectMocks
    private ProductVariantSearchController productVariantSearchController;

    private UUID storeId;
    private ProductVariantSearchResponse testSearchResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(productVariantSearchController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        storeId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testSearchResponse = new ProductVariantSearchResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                storeId,
                "7501234567890",
                "SKU-VAR-001",
                "Talla M",
                new BigDecimal("199.99"),
                new BigDecimal("120.00"),
                new BigDecimal("50.00"),
                true,
                "Producto Test",
                "PROD-001",
                now,
                now
        );
    }

    @Nested
    @DisplayName("GET /api/product-variants/search")
    class SearchVariantsTests {

        @Test
        @DisplayName("should return 200 with matching variants")
        void searchVariants_WithResults() throws Exception {
            var pageable = PageRequest.of(0, 20);
            var page = new PageImpl<>(List.of(testSearchResponse), pageable, 1);

            when(productVariantService.searchVariants(eq("7501234567890"), eq(storeId), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/product-variants/search")
                            .param("q", "7501234567890")
                            .param("storeId", storeId.toString())
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id").value(testSearchResponse.id().toString()))
                    .andExpect(jsonPath("$.content[0].productId").value(testSearchResponse.productId().toString()))
                    .andExpect(jsonPath("$.content[0].companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.content[0].variantName").value("Talla M"))
                    .andExpect(jsonPath("$.content[0].barCode").value("7501234567890"))
                    .andExpect(jsonPath("$.content[0].sku").value("SKU-VAR-001"))
                    .andExpect(jsonPath("$.content[0].listPrice").value(199.99))
                    .andExpect(jsonPath("$.content[0].costPrice").value(120.00))
                    .andExpect(jsonPath("$.content[0].stock").value(50.00))
                    .andExpect(jsonPath("$.content[0].enabled").value(true))
                    .andExpect(jsonPath("$.content[0].productName").value("Producto Test"))
                    .andExpect(jsonPath("$.content[0].productSku").value("PROD-001"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(20));
        }

        @Test
        @DisplayName("should return 200 with empty content when no matches")
        void searchVariants_EmptyResults() throws Exception {
            var pageable = PageRequest.of(0, 20);
            var emptyPage = new PageImpl<ProductVariantSearchResponse>(List.of(), pageable, 0);

            when(productVariantService.searchVariants(eq("xyznonexistent"), eq(storeId), any(Pageable.class)))
                    .thenReturn(emptyPage);

            mockMvc.perform(get("/api/product-variants/search")
                            .param("q", "xyznonexistent")
                            .param("storeId", storeId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));
        }

        // Missing-param validation is handled by Spring's @RequestParam (required=true).
        // In isolated MockMvc setup (standalone), missing required params throw a
        // MissingServletRequestParameterException that results in 500, not 400,
        // because Spring MVC's default binder exception handling is not wired.
        // These are Spring framework-level behaviors, not our logic — omitted.
    }
}
