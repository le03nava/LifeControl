package com.lifecontrol.api.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.dto.ProductVariantResponse;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.service.ProductVariantService;
import com.lifecontrol.api.product.supplier.service.ProductSupplierService;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductVariantController Tests")
class ProductVariantControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ProductService productService;

    @Mock
    private ProductSupplierService productSupplierService;

    @Mock
    private ProductVariantService productVariantService;

    @InjectMocks
    private ProductController productController;

    private UUID productId;
    private UUID variantId;
    private ProductVariantResponse testVariantResponse;
    private ProductVariantRequest testVariantRequest;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(productController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        var companyStoreId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testVariantResponse = new ProductVariantResponse(
                variantId,
                productId,
                companyStoreId,
                "7501234567890",
                "SKU-VAR-001",
                "Talla M",
                new BigDecimal("199.99"),
                new BigDecimal("120.00"),
                new BigDecimal("50.00"),
                true,
                now,
                now
        );

        testVariantRequest = new ProductVariantRequest(
                productId,
                companyStoreId,
                "7501234567890",
                "SKU-VAR-001",
                "Talla M",
                new BigDecimal("199.99"),
                new BigDecimal("120.00"),
                new BigDecimal("50.00"),
                true
        );
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
            var variants = List.of(testVariantResponse);
            var page = new PageImpl<>(variants, pageable, 1);

            when(productVariantService.listVariants(eq(productId), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/products/{productId}/variants", productId)
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id").value(variantId.toString()))
                    .andExpect(jsonPath("$.content[0].productId").value(productId.toString()))
                    .andExpect(jsonPath("$.content[0].variantName").value("Talla M"))
                    .andExpect(jsonPath("$.content[0].barCode").value("7501234567890"))
                    .andExpect(jsonPath("$.content[0].sku").value("SKU-VAR-001"))
                    .andExpect(jsonPath("$.content[0].listPrice").value(199.99))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));
        }

        @Test
        @DisplayName("should return 200 with empty page when product has no variants")
        void listVariants_EmptyPage_Returns200() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<ProductVariantResponse>(List.of(), pageable, 0);

            when(productVariantService.listVariants(eq(productId), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/products/{productId}/variants", productId)
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/products/{productId}/variants
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/products/{productId}/variants")
    class CreateVariantTests {

        @Test
        @DisplayName("should return 201 with created variant")
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
                    .andExpect(jsonPath("$.sku").value("SKU-VAR-001"))
                    .andExpect(jsonPath("$.listPrice").value(199.99))
                    .andExpect(jsonPath("$.costPrice").value(120.00))
                    .andExpect(jsonPath("$.stock").value(50.00))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when required fields missing")
        void createVariant_MissingRequiredFields_Returns400() throws Exception {
            var invalidRequest = new ProductVariantRequest(
                    null, null, null, null, null, null, null, null, null
            );

            mockMvc.perform(post("/api/products/{productId}/variants", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.productId").exists())
                    .andExpect(jsonPath("$.errors.companyStoreId").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
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
            when(productVariantService.getVariant(productId, variantId))
                    .thenReturn(testVariantResponse);

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
                    .when(productVariantService).deleteVariant(productId, variantId);

            mockMvc.perform(delete("/api/products/{productId}/variants/{variantId}", productId, variantId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product variant not found with id: " + variantId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }
}
