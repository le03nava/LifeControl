package com.lifecontrol.api.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.dto.ProductVariantResponse;
import com.lifecontrol.api.product.dto.ProductVariantSearchResponse;
import com.lifecontrol.api.product.dto.ProductVariantStoreStockRequest;
import com.lifecontrol.api.product.dto.ProductVariantStoreStockResponse;
import com.lifecontrol.api.product.exception.DuplicateProductVariantException;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.service.ProductVariantService;
import com.lifecontrol.api.product.supplier.service.ProductSupplierService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductVariantController Tests")
class ProductVariantControllerTest {

    private MockMvc mockMvc;
    private MockMvc searchMockMvc;
    private MockMvc storeMockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ProductService productService;

    @Mock
    private ProductSupplierService productSupplierService;

    @Mock
    private ProductVariantService productVariantService;

    @InjectMocks
    private ProductController productController;

    @InjectMocks
    private ProductVariantSearchController productVariantSearchController;

    @InjectMocks
    private ProductVariantStoreController productVariantStoreController;

    private UUID productId;
    private UUID variantId;
    private UUID storeId;
    private ProductVariantResponse testVariantResponse;
    private ProductVariantRequest testVariantRequest;
    private ProductVariantSearchResponse testSearchResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(productController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        searchMockMvc = MockMvcBuilders.standaloneSetup(productVariantSearchController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        storeMockMvc = MockMvcBuilders.standaloneSetup(productVariantStoreController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testVariantResponse = new ProductVariantResponse(
                variantId, productId, null, "7501234567890", "PROD-001", "Talla M", null, null, null, true, now, now);

        testVariantRequest = new ProductVariantRequest("7501234567890", "Talla M");

        testSearchResponse = new ProductVariantSearchResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                storeId,
                "7501234567890",
                "PROD-001",
                "Talla M",
                new BigDecimal("199.99"),
                new BigDecimal("120.00"),
                new BigDecimal("50.00"),
                true,
                "Producto Test",
                "PROD-001",
                now,
                now);
    }

    // ─────────────────────────────────────────────
    // GET /api/products/{productId}/variants
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/products/{productId}/variants")
    class ListVariantsTests {

        @Test
        @DisplayName("should return 200 with paginated variants")
        void listVariants_Paginated_Returns200() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<>(List.of(testVariantResponse), pageable, 1);

            when(productVariantService.listVariants(eq(productId), isNull(), eq(false), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/products/{productId}/variants", productId)
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id").value(variantId.toString()))
                    .andExpect(jsonPath("$.content[0].productId").value(productId.toString()))
                    .andExpect(jsonPath("$.content[0].variantName").value("Talla M"))
                    .andExpect(jsonPath("$.content[0].barCode").value("7501234567890"))
                    .andExpect(jsonPath("$.content[0].sku").value("PROD-001"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));

            verify(productVariantService).listVariants(eq(productId), isNull(), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("should default includeDisabled to false when the param is absent")
        void listVariants_IncludeDisabledAbsent_DefaultsFalse() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<>(List.of(testVariantResponse), pageable, 1);

            when(productVariantService.listVariants(eq(productId), isNull(), eq(false), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/products/{productId}/variants", productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));

            verify(productVariantService).listVariants(eq(productId), isNull(), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("should forward includeDisabled=true to the service")
        void listVariants_IncludeDisabledTrue_ForwardsTrue() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<>(List.of(testVariantResponse), pageable, 1);

            when(productVariantService.listVariants(eq(productId), isNull(), eq(true), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/products/{productId}/variants", productId).param("includeDisabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));

            verify(productVariantService).listVariants(eq(productId), isNull(), eq(true), any(Pageable.class));
        }

        @Test
        @DisplayName("should forward the storeId param to the service")
        void listVariants_WithStoreId_ForwardsStoreIdToService() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<>(List.of(testVariantResponse), pageable, 1);

            when(productVariantService.listVariants(eq(productId), eq(storeId), eq(false), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/products/{productId}/variants", productId).param("storeId", storeId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(variantId.toString()))
                    .andExpect(jsonPath("$.totalElements").value(1));

            verify(productVariantService).listVariants(eq(productId), eq(storeId), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("should return 403 when the caller holds no grant for the store")
        void listVariants_StoreOutsideCallerScope_Returns403() throws Exception {
            when(productVariantService.listVariants(eq(productId), eq(storeId), eq(false), any(Pageable.class)))
                    .thenThrow(new AccessDeniedException("Access denied"));

            mockMvc.perform(get("/api/products/{productId}/variants", productId).param("storeId", storeId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("should return 200 with empty page when product has no variants")
        void listVariants_EmptyPage_Returns200() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<ProductVariantResponse>(List.of(), pageable, 0);

            when(productVariantService.listVariants(eq(productId), isNull(), eq(false), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/products/{productId}/variants", productId)
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));

            verify(productVariantService).listVariants(eq(productId), isNull(), eq(false), any(Pageable.class));
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/products/{productId}/variants
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/products/{productId}/variants")
    class CreateVariantTests {

        @Test
        @DisplayName("should return 201 with the created definition")
        void createVariant_ValidRequest_Returns201() throws Exception {
            when(productVariantService.createVariant(eq(productId), any(ProductVariantRequest.class)))
                    .thenReturn(testVariantResponse);

            mockMvc.perform(post("/api/products/{productId}/variants", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testVariantRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(variantId.toString()))
                    .andExpect(jsonPath("$.productId").value(productId.toString()))
                    .andExpect(jsonPath("$.variantName").value("Talla M"))
                    .andExpect(jsonPath("$.barCode").value("7501234567890"))
                    .andExpect(jsonPath("$.sku").value("PROD-001"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when required fields are missing")
        void createVariant_MissingRequiredFields_Returns400() throws Exception {
            var invalidRequest = new ProductVariantRequest(null, null);

            mockMvc.perform(post("/api/products/{productId}/variants", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.barCode").exists())
                    .andExpect(jsonPath("$.errors.variantName").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 409 when the barCode is duplicated")
        void createVariant_DuplicateBarCode_Returns409() throws Exception {
            when(productVariantService.createVariant(eq(productId), any(ProductVariantRequest.class)))
                    .thenThrow(new DuplicateProductVariantException(
                            "Product variant with barCode already exists: 7501234567890"));

            mockMvc.perform(post("/api/products/{productId}/variants", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testVariantRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(
                            jsonPath("$.message").value("Product variant with barCode already exists: 7501234567890"));
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/products/{productId}/variants/{variantId}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/products/{productId}/variants/{variantId}")
    class GetVariantTests {

        @Test
        @DisplayName("should return 200 with variant when found")
        void getVariant_Found_Returns200() throws Exception {
            when(productVariantService.getVariant(productId, variantId)).thenReturn(testVariantResponse);

            mockMvc.perform(get("/api/products/{productId}/variants/{variantId}", productId, variantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(variantId.toString()))
                    .andExpect(jsonPath("$.productId").value(productId.toString()))
                    .andExpect(jsonPath("$.variantName").value("Talla M"))
                    .andExpect(jsonPath("$.barCode").value("7501234567890"));
        }

        @Test
        @DisplayName("should return 404 when variant not found")
        void getVariant_NotFound_Returns404() throws Exception {
            when(productVariantService.getVariant(productId, variantId))
                    .thenThrow(new ProductVariantNotFoundException(variantId));

            mockMvc.perform(get("/api/products/{productId}/variants/{variantId}", productId, variantId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product variant not found with id: " + variantId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PUT /api/products/{productId}/variants/{variantId}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/products/{productId}/variants/{variantId}")
    class UpdateVariantTests {

        @Test
        @DisplayName("should return 200 with updated variant")
        void updateVariant_Success_Returns200() throws Exception {
            when(productVariantService.updateVariant(eq(productId), eq(variantId), any(ProductVariantRequest.class)))
                    .thenReturn(testVariantResponse);

            mockMvc.perform(put("/api/products/{productId}/variants/{variantId}", productId, variantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testVariantRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(variantId.toString()))
                    .andExpect(jsonPath("$.variantName").value("Talla M"));
        }

        @Test
        @DisplayName("should return 404 when variant not found")
        void updateVariant_NotFound_Returns404() throws Exception {
            when(productVariantService.updateVariant(eq(productId), eq(variantId), any(ProductVariantRequest.class)))
                    .thenThrow(new ProductVariantNotFoundException(variantId));

            mockMvc.perform(put("/api/products/{productId}/variants/{variantId}", productId, variantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testVariantRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product variant not found with id: " + variantId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // DELETE /api/products/{productId}/variants/{variantId}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/products/{productId}/variants/{variantId}")
    class DeleteVariantTests {

        @Test
        @DisplayName("should return 204 on successful soft delete")
        void deleteVariant_Success_Returns204() throws Exception {
            mockMvc.perform(delete("/api/products/{productId}/variants/{variantId}", productId, variantId))
                    .andExpect(status().isNoContent());

            verify(productVariantService).deleteVariant(productId, variantId);
        }

        @Test
        @DisplayName("should return 404 when variant not found")
        void deleteVariant_NotFound_Returns404() throws Exception {
            doThrow(new ProductVariantNotFoundException(variantId))
                    .when(productVariantService)
                    .deleteVariant(productId, variantId);

            mockMvc.perform(delete("/api/products/{productId}/variants/{variantId}", productId, variantId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product variant not found with id: " + variantId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PATCH /api/products/{productId}/variants/{variantId}/enable
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PATCH /api/products/{productId}/variants/{variantId}/enable")
    class EnableVariantTests {

        @Test
        @DisplayName("should return 200 with the re-enabled variant")
        void enableVariant_Success_Returns200() throws Exception {
            when(productVariantService.enableVariant(productId, variantId)).thenReturn(testVariantResponse);

            mockMvc.perform(patch("/api/products/{productId}/variants/{variantId}/enable", productId, variantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(variantId.toString()))
                    .andExpect(jsonPath("$.enabled").value(true));

            verify(productVariantService).enableVariant(productId, variantId);
        }

        @Test
        @DisplayName("should return 404 when variant not found")
        void enableVariant_NotFound_Returns404() throws Exception {
            when(productVariantService.enableVariant(productId, variantId))
                    .thenThrow(new ProductVariantNotFoundException(variantId));

            mockMvc.perform(patch("/api/products/{productId}/variants/{variantId}/enable", productId, variantId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product variant not found with id: " + variantId));
        }
    }

    // ─────────────────────────────────────────────
    // PUT /api/variants/{variantId}/stores/{storeId}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/variants/{variantId}/stores/{storeId}")
    class UpsertStoreStockTests {

        @Test
        @DisplayName("should return 200 with the upserted store row")
        void upsertStoreStock_ValidRequest_Returns200() throws Exception {
            var request = new ProductVariantStoreStockRequest(
                    new BigDecimal("199.99"), new BigDecimal("120.00"), new BigDecimal("50.00"));
            var response = new ProductVariantStoreStockResponse(
                    storeId, new BigDecimal("199.99"), new BigDecimal("120.00"), new BigDecimal("50.00"));

            when(productVariantService.upsertStoreStock(
                            eq(variantId), eq(storeId), any(ProductVariantStoreStockRequest.class)))
                    .thenReturn(response);

            storeMockMvc
                    .perform(put("/api/variants/{variantId}/stores/{storeId}", variantId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.listPrice").value(199.99))
                    .andExpect(jsonPath("$.costPrice").value(120.00))
                    .andExpect(jsonPath("$.stock").value(50.00));

            verify(productVariantService)
                    .upsertStoreStock(eq(variantId), eq(storeId), any(ProductVariantStoreStockRequest.class));
        }

        @Test
        @DisplayName("should return 404 when the variant does not exist")
        void upsertStoreStock_UnknownVariant_Returns404() throws Exception {
            var request = new ProductVariantStoreStockRequest(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

            when(productVariantService.upsertStoreStock(
                            eq(variantId), eq(storeId), any(ProductVariantStoreStockRequest.class)))
                    .thenThrow(new ProductVariantNotFoundException(variantId));

            storeMockMvc
                    .perform(put("/api/variants/{variantId}/stores/{storeId}", variantId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("should return 400 when a price is negative")
        void upsertStoreStock_NegativePrice_Returns400() throws Exception {
            var request = new ProductVariantStoreStockRequest(new BigDecimal("-1.00"), null, null);

            storeMockMvc
                    .perform(put("/api/variants/{variantId}/stores/{storeId}", variantId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.listPrice").exists());
        }

        @Test
        @DisplayName("should return 403 when the caller holds no grant for the store")
        void upsertStoreStock_StoreOutsideCallerScope_Returns403() throws Exception {
            var request = new ProductVariantStoreStockRequest(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

            when(productVariantService.upsertStoreStock(
                            eq(variantId), eq(storeId), any(ProductVariantStoreStockRequest.class)))
                    .thenThrow(new AccessDeniedException("Access denied"));

            storeMockMvc
                    .perform(put("/api/variants/{variantId}/stores/{storeId}", variantId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/product-variants/search
    // ─────────────────────────────────────────────
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

            searchMockMvc
                    .perform(get("/api/product-variants/search")
                            .param("q", "7501234567890")
                            .param("storeId", storeId.toString())
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id")
                            .value(testSearchResponse.id().toString()))
                    .andExpect(jsonPath("$.content[0].productId")
                            .value(testSearchResponse.productId().toString()))
                    .andExpect(jsonPath("$.content[0].companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.content[0].variantName").value("Talla M"))
                    .andExpect(jsonPath("$.content[0].barCode").value("7501234567890"))
                    .andExpect(jsonPath("$.content[0].sku").value("PROD-001"))
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

            searchMockMvc
                    .perform(get("/api/product-variants/search")
                            .param("q", "xyznonexistent")
                            .param("storeId", storeId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));
        }

        @Test
        @DisplayName("should return 403 when the caller holds no grant for the store")
        void searchVariants_StoreOutsideCallerScope_Returns403() throws Exception {
            when(productVariantService.searchVariants(eq("7501234567890"), eq(storeId), any(Pageable.class)))
                    .thenThrow(new AccessDeniedException("Access denied"));

            searchMockMvc
                    .perform(get("/api/product-variants/search")
                            .param("q", "7501234567890")
                            .param("storeId", storeId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }
}
