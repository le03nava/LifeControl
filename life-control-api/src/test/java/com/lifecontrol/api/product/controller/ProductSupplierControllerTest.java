package com.lifecontrol.api.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierRequest;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierResponse;
import com.lifecontrol.api.product.supplier.exception.DuplicateProductSupplierException;
import com.lifecontrol.api.product.supplier.exception.ProductSupplierNotFoundException;
import com.lifecontrol.api.product.supplier.service.ProductSupplierService;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
@DisplayName("ProductSupplierController Tests")
class ProductSupplierControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ProductService productService;

    @Mock
    private ProductSupplierService productSupplierService;

    @InjectMocks
    private ProductController productController;

    private UUID productId;
    private UUID relationId;
    private UUID supplierId;
    private ProductSupplierResponse testResponse;
    private ProductSupplierRequest testRequest;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(productController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        productId = UUID.randomUUID();
        relationId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        now = LocalDateTime.now();

        testResponse = new ProductSupplierResponse(
                relationId,
                productId,
                supplierId,
                "Test Supplier Co",
                new BigDecimal("150.00"),
                true,
                true,
                now,
                now
        );

        testRequest = new ProductSupplierRequest(
                supplierId,
                new BigDecimal("150.00"),
                true,
                true
        );
    }

    // ─────────────────────────────────────────────
    // GET /api/products/{productId}/suppliers
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/products/{productId}/suppliers")
    class GetProductSuppliersTests {

        @Test
        @DisplayName("should return 200 with supplier list when product has suppliers")
        void getProductSuppliers_Success() throws Exception {
            when(productSupplierService.listSuppliersByProductId(productId))
                    .thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/products/{productId}/suppliers", productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].id").value(relationId.toString()))
                    .andExpect(jsonPath("$[0].productId").value(productId.toString()))
                    .andExpect(jsonPath("$[0].supplierId").value(supplierId.toString()))
                    .andExpect(jsonPath("$[0].supplierName").value("Test Supplier Co"))
                    .andExpect(jsonPath("$[0].purchaseCost").value(150.0))
                    .andExpect(jsonPath("$[0].main").value(true))
                    .andExpect(jsonPath("$[0].enabled").value(true))
                    .andExpect(jsonPath("$[0].createdAt").exists())
                    .andExpect(jsonPath("$[0].updatedAt").exists());
        }

        @Test
        @DisplayName("should return 200 with empty list when product has no suppliers")
        void getProductSuppliers_EmptyList() throws Exception {
            when(productSupplierService.listSuppliersByProductId(productId))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/products/{productId}/suppliers", productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return 404 when product not found")
        void getProductSuppliers_ProductNotFound() throws Exception {
            when(productSupplierService.listSuppliersByProductId(productId))
                    .thenThrow(new ProductNotFoundException(productId));

            mockMvc.perform(get("/api/products/{productId}/suppliers", productId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product not found with id: " + productId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/products/{productId}/suppliers
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/products/{productId}/suppliers")
    class AddProductSupplierTests {

        @Test
        @DisplayName("should return 201 with created relation when valid request")
        void addProductSupplier_Success() throws Exception {
            when(productSupplierService.addSupplierToProduct(eq(productId), any(ProductSupplierRequest.class)))
                    .thenReturn(testResponse);

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(relationId.toString()))
                    .andExpect(jsonPath("$.supplierName").value("Test Supplier Co"))
                    .andExpect(jsonPath("$.purchaseCost").value(150.0))
                    .andExpect(jsonPath("$.main").value(true));
        }

        @Test
        @DisplayName("should return 201 when creating relation without optional fields")
        void addProductSupplier_WithDefaults() throws Exception {
            var minimalRequest = new ProductSupplierRequest(supplierId, null, null, null);
            var minimalResponse = new ProductSupplierResponse(
                    relationId, productId, supplierId, "Test Supplier Co",
                    null, false, true, now, now
            );
            when(productSupplierService.addSupplierToProduct(eq(productId), any(ProductSupplierRequest.class)))
                    .thenReturn(minimalResponse);

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(minimalRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.main").value(false))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when product not found")
        void addProductSupplier_ProductNotFound() throws Exception {
            when(productSupplierService.addSupplierToProduct(eq(productId), any(ProductSupplierRequest.class)))
                    .thenThrow(new ProductNotFoundException(productId));

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("should return 404 when supplier not found")
        void addProductSupplier_SupplierNotFound() throws Exception {
            when(productSupplierService.addSupplierToProduct(eq(productId), any(ProductSupplierRequest.class)))
                    .thenThrow(new SupplierNotFoundException(supplierId));

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("should return 409 when duplicate product-supplier pair")
        void addProductSupplier_Duplicate() throws Exception {
            when(productSupplierService.addSupplierToProduct(eq(productId), any(ProductSupplierRequest.class)))
                    .thenThrow(new DuplicateProductSupplierException("Test Supplier Co"));

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("The product already has a relationship with supplier: Test Supplier Co"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 400 when supplierId is missing")
        void addProductSupplier_MissingSupplierId() throws Exception {
            var invalidRequest = new ProductSupplierRequest(null, new BigDecimal("50.00"), false, true);

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.supplierId").value("supplierId es requerido"));
        }

        @Test
        @DisplayName("should return 400 when purchaseCost is negative")
        void addProductSupplier_NegativeCost() throws Exception {
            var invalidRequest = new ProductSupplierRequest(supplierId, new BigDecimal("-10.00"), false, true);

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.purchaseCost").value("purchaseCost no puede ser negativo"));
        }
    }

    // ─────────────────────────────────────────────
    // PUT /api/products/{productId}/suppliers/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/products/{productId}/suppliers/{id}")
    class UpdateProductSupplierTests {

        @Test
        @DisplayName("should return 200 with updated relation when valid request")
        void updateProductSupplier_Success() throws Exception {
            var updatedResponse = new ProductSupplierResponse(
                    relationId, productId, supplierId, "Test Supplier Co",
                    new BigDecimal("200.00"), true, true, now, now
            );
            when(productSupplierService.updateSupplier(eq(productId), eq(relationId), any(ProductSupplierRequest.class)))
                    .thenReturn(updatedResponse);

            var updateRequest = new ProductSupplierRequest(supplierId, new BigDecimal("200.00"), true, true);

            mockMvc.perform(put("/api/products/{productId}/suppliers/{id}", productId, relationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(relationId.toString()))
                    .andExpect(jsonPath("$.purchaseCost").value(200.0))
                    .andExpect(jsonPath("$.main").value(true));
        }

        @Test
        @DisplayName("should return 404 when relation not found")
        void updateProductSupplier_NotFound() throws Exception {
            when(productSupplierService.updateSupplier(eq(productId), eq(relationId), any(ProductSupplierRequest.class)))
                    .thenThrow(new ProductSupplierNotFoundException(relationId));

            mockMvc.perform(put("/api/products/{productId}/suppliers/{id}", productId, relationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product-supplier relation not found with id: " + relationId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // DELETE /api/products/{productId}/suppliers/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/products/{productId}/suppliers/{id}")
    class RemoveProductSupplierTests {

        @Test
        @DisplayName("should return 204 when relation deleted successfully")
        void removeProductSupplier_Success() throws Exception {
            mockMvc.perform(delete("/api/products/{productId}/suppliers/{id}", productId, relationId))
                    .andExpect(status().isNoContent());

            verify(productSupplierService).removeSupplierFromProduct(productId, relationId);
        }

        @Test
        @DisplayName("should return 404 when relation not found")
        void removeProductSupplier_NotFound() throws Exception {
            doThrow(new ProductSupplierNotFoundException(relationId))
                    .when(productSupplierService).removeSupplierFromProduct(productId, relationId);

            mockMvc.perform(delete("/api/products/{productId}/suppliers/{id}", productId, relationId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product-supplier relation not found with id: " + relationId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // Error Response Contract Verification
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("Error Response Contract")
    class ErrorResponseContractTests {

        @Test
        @DisplayName("should include correlationId, path, timestamp in 404 responses")
        void error404_ContainsContractFields() throws Exception {
            when(productSupplierService.listSuppliersByProductId(productId))
                    .thenThrow(new ProductSupplierNotFoundException(relationId));

            mockMvc.perform(get("/api/products/{productId}/suppliers", productId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.path").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        @DisplayName("should include correlationId, path, timestamp in 409 responses")
        void error409_ContainsContractFields() throws Exception {
            when(productSupplierService.addSupplierToProduct(eq(productId), any(ProductSupplierRequest.class)))
                    .thenThrow(new DuplicateProductSupplierException("Test Supplier Co"));

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.path").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }
    }
}
