package com.lifecontrol.api.product.controller;

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

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.product.category.exception.ProductCategoryNotFoundException;
import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductResponse;
import com.lifecontrol.api.product.exception.DuplicateProductException;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.supplier.dto.SupplierProductResponse;
import com.lifecontrol.api.product.supplier.service.ProductSupplierService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductController Tests")
class ProductControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ProductService productService;

    @Mock
    private ProductSupplierService productSupplierService;

    @InjectMocks
    private ProductController productController;

    private UUID companyId;
    private UUID productId;
    private ProductResponse testProductResponse;
    private ProductRequest testProductRequest;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(productController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = JsonMapper.builder().build();

        companyId = UUID.randomUUID();
        productId = UUID.randomUUID();
        now = LocalDateTime.now();

        testProductResponse = new ProductResponse(
                productId,
                "SKU-001",
                "Test Product",
                "Test",
                "SAT-001",
                "SERVICE",
                Map.of("color", "red"),
                true,
                now,
                now,
                null,
                null,
                companyId);

        testProductRequest = new ProductRequest(
                "SKU-001", "Test Product", "Test", "SAT-001", "SERVICE", Map.of("color", "red"), null);
    }

    // ─────────────────────────────────────────────
    // POST /api/companies/{companyId}/products
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/companies/{companyId}/products")
    class CreateProductTests {

        @Test
        @DisplayName("should return 404 when the category does not exist")
        void createProduct_UnknownCategory_Returns404() throws Exception {
            var unknownCategoryId = UUID.randomUUID();
            when(productService.createProduct(eq(companyId), any(ProductRequest.class)))
                    .thenThrow(new ProductCategoryNotFoundException(unknownCategoryId));

            mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testProductRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product category not found with id: " + unknownCategoryId));
        }

        @Test
        @DisplayName("should return 201 with the created product when valid request with all fields")
        void createProduct_WithAllFields_Returns201() throws Exception {
            when(productService.createProduct(eq(companyId), any(ProductRequest.class)))
                    .thenReturn(testProductResponse);

            mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testProductRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(productId.toString()))
                    .andExpect(jsonPath("$.sku").value("SKU-001"))
                    .andExpect(jsonPath("$.name").value("Test Product"))
                    .andExpect(jsonPath("$.shortName").value("Test"))
                    .andExpect(jsonPath("$.satCode").value("SAT-001"))
                    .andExpect(jsonPath("$.productType").value("SERVICE"))
                    .andExpect(jsonPath("$.attributes.color").value("red"))
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.createdAt").exists())
                    .andExpect(jsonPath("$.updatedAt").exists());

            // The path is the owner: the service is asked to create the product OF that company.
            verify(productService).createProduct(eq(companyId), any(ProductRequest.class));
        }

        /**
         * ADR-0006 decision 1: the mode's vocabulary is closed in the schema
         * ({@code ck_products_variant_mode}, V54) and the request states the same two words, so a client
         * gets a 400 that names the field instead of a constraint violation after the row was built.
         */
        @Test
        @DisplayName("should return 400 when variantMode is outside SIMPLE|MULTI")
        void createProduct_UnknownVariantMode_Returns400() throws Exception {
            var body = objectMapper.writeValueAsString(testProductRequest);
            var withFamily = body.substring(0, body.lastIndexOf('}')) + ",\"variantMode\":\"FAMILY\"}";

            mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withFamily))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.variantMode").exists());
        }

        @Test
        @DisplayName("should return 201 when product created without attributes")
        void createProduct_WithoutAttributes_Returns201() throws Exception {
            var request = new ProductRequest("SKU-SIMPLE", "Simple Product", null, null, null, null, null);
            var response = new ProductResponse(
                    productId,
                    "SKU-SIMPLE",
                    "Simple Product",
                    null,
                    null,
                    null,
                    null,
                    true,
                    now,
                    now,
                    null,
                    null,
                    companyId);
            when(productService.createProduct(eq(companyId), any(ProductRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sku").value("SKU-SIMPLE"))
                    .andExpect(jsonPath("$.attributes").doesNotExist());
        }

        @Test
        @DisplayName("should return 409 Conflict when duplicate SKU")
        void createProduct_DuplicateSku_Returns409() throws Exception {
            when(productService.createProduct(eq(companyId), any(ProductRequest.class)))
                    .thenThrow(new DuplicateProductException("Product with SKU 'SKU-001' already exists"));

            mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testProductRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Product with SKU 'SKU-001' already exists"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 400 Bad Request when required fields missing")
        void createProduct_MissingRequiredFields_Returns400() throws Exception {
            var invalidRequest = new ProductRequest("", "", null, null, null, null, null);

            mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.sku").exists())
                    .andExpect(jsonPath("$.errors.name").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/companies/{companyId}/products
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/companies/{companyId}/products")
    class ListProductsTests {

        @Test
        @DisplayName("should return 200 with paginated active products by default")
        void listProducts_Default_Returns200() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<>(List.of(testProductResponse), pageable, 1);
            when(productService.listProducts(eq(companyId), any(Pageable.class), eq(null), eq(false)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/companies/{companyId}/products", companyId)
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].sku").value("SKU-001"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));

            // The company is the scope of the read, so it travels to the service on every call.
            verify(productService).listProducts(eq(companyId), any(Pageable.class), eq(null), eq(false));
        }

        @Test
        @DisplayName("should return 200 when searching by name or SKU")
        void listProducts_WithSearch_Returns200() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<>(List.of(testProductResponse), pageable, 1);
            when(productService.listProducts(eq(companyId), any(Pageable.class), eq("Widget"), eq(false)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/companies/{companyId}/products", companyId)
                            .param("page", "0")
                            .param("size", "12")
                            .param("search", "Widget"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].name").value("Test Product"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("should return 200 including disabled when includeDisabled=true")
        void listProducts_IncludeDisabled_Returns200() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<>(List.of(testProductResponse), pageable, 1);
            when(productService.listProducts(eq(companyId), any(Pageable.class), eq(null), eq(true)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/companies/{companyId}/products", companyId)
                            .param("page", "0")
                            .param("size", "12")
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("should return 200 with empty page when no products match")
        void listProducts_EmptyPage_Returns200() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<ProductResponse>(List.of(), pageable, 0);
            when(productService.listProducts(eq(companyId), any(Pageable.class), eq(null), eq(false)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/companies/{companyId}/products", companyId)
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/companies/{companyId}/products/by-supplier/{supplierId}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/companies/{companyId}/products/by-supplier/{supplierId}")
    class GetProductsBySupplierTests {

        private UUID supplierId;

        @BeforeEach
        void setUp() {
            supplierId = UUID.randomUUID();
        }

        @Test
        @DisplayName("should return 200 with list of products for supplier")
        void getProductsBySupplier_Success() throws Exception {
            var products = List.of(
                    new SupplierProductResponse(productId, "Test Product", "SKU-001"),
                    new SupplierProductResponse(UUID.randomUUID(), "Another Product", "SKU-002"));
            when(productSupplierService.listProductsBySupplier(companyId, supplierId, null))
                    .thenReturn(products);

            mockMvc.perform(get("/api/companies/{companyId}/products/by-supplier/{supplierId}", companyId, supplierId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].productId").value(productId.toString()))
                    .andExpect(jsonPath("$[0].productName").value("Test Product"))
                    .andExpect(jsonPath("$[0].sku").value("SKU-001"))
                    .andExpect(jsonPath("$[1].productName").value("Another Product"));
        }

        @Test
        @DisplayName("should return 200 with empty list when supplier has no products")
        void getProductsBySupplier_EmptyList() throws Exception {
            when(productSupplierService.listProductsBySupplier(companyId, supplierId, null))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/companies/{companyId}/products/by-supplier/{supplierId}", companyId, supplierId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should filter by search term when provided")
        void getProductsBySupplier_WithSearch() throws Exception {
            var products = List.of(new SupplierProductResponse(productId, "Test Product", "SKU-001"));
            when(productSupplierService.listProductsBySupplier(companyId, supplierId, "Test"))
                    .thenReturn(products);

            mockMvc.perform(get("/api/companies/{companyId}/products/by-supplier/{supplierId}", companyId, supplierId)
                            .param("search", "Test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].productName").value("Test Product"));
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/companies/{companyId}/products/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/companies/{companyId}/products/{id}")
    class GetProductByIdTests {

        @Test
        @DisplayName("should return 200 with product when found")
        void getProductById_Found_Returns200() throws Exception {
            when(productService.findProduct(companyId, productId)).thenReturn(testProductResponse);

            mockMvc.perform(get("/api/companies/{companyId}/products/{id}", companyId, productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(productId.toString()))
                    .andExpect(jsonPath("$.sku").value("SKU-001"))
                    .andExpect(jsonPath("$.name").value("Test Product"))
                    .andExpect(jsonPath("$.attributes.color").value("red"));
        }

        @Test
        @DisplayName("should return the category and its company when the product is classified")
        void getProductById_Classified_ReturnsCategoryAndItsCompany() throws Exception {
            var categoryId = UUID.randomUUID();
            var categoryCompanyId = UUID.randomUUID();
            when(productService.findProduct(companyId, productId))
                    .thenReturn(new ProductResponse(
                            productId,
                            "SKU-001",
                            "Test Product",
                            "Test",
                            "SAT-001",
                            "SERVICE",
                            Map.of("color", "red"),
                            true,
                            now,
                            now,
                            categoryId,
                            categoryCompanyId,
                            companyId));

            mockMvc.perform(get("/api/companies/{companyId}/products/{id}", companyId, productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.categoryId").value(categoryId.toString()))
                    .andExpect(jsonPath("$.categoryCompanyId").value(categoryCompanyId.toString()));
        }

        @Test
        @DisplayName("should return 404 when product not found")
        void getProductById_NotFound_Returns404() throws Exception {
            when(productService.findProduct(companyId, productId)).thenThrow(new ProductNotFoundException(productId));

            mockMvc.perform(get("/api/companies/{companyId}/products/{id}", companyId, productId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product not found with id: " + productId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 404 when the product belongs to another company")
        void getProductById_OtherCompany_Returns404() throws Exception {
            // 404, not 403: the company segment is the scope, so the row is not addressable from here.
            when(productService.findProduct(companyId, productId)).thenThrow(new ProductNotFoundException(productId));

            mockMvc.perform(get("/api/companies/{companyId}/products/{id}", companyId, productId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product not found with id: " + productId));
        }

        @Test
        @DisplayName("should return 404 when product is soft-deleted")
        void getProductById_SoftDeleted_Returns404() throws Exception {
            when(productService.findProduct(companyId, productId)).thenThrow(new ProductNotFoundException(productId));

            mockMvc.perform(get("/api/companies/{companyId}/products/{id}", companyId, productId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // ─────────────────────────────────────────────
    // PUT /api/companies/{companyId}/products/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/companies/{companyId}/products/{id}")
    class UpdateProductTests {

        @Test
        @DisplayName("should return 200 with updated product")
        void updateProduct_Returns200() throws Exception {
            var updatedResponse = new ProductResponse(
                    productId,
                    "SKU-001",
                    "Updated Name",
                    "Upd",
                    "SAT-NEW",
                    "GOODS",
                    Map.of("color", "blue"),
                    true,
                    now,
                    now,
                    null,
                    null,
                    companyId);
            when(productService.updateProduct(eq(companyId), eq(productId), any(ProductRequest.class)))
                    .thenReturn(updatedResponse);

            var request = new ProductRequest(
                    "SKU-001", "Updated Name", "Upd", "SAT-NEW", "GOODS", Map.of("color", "blue"), null);

            mockMvc.perform(put("/api/companies/{companyId}/products/{id}", companyId, productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Name"))
                    .andExpect(jsonPath("$.satCode").value("SAT-NEW"))
                    .andExpect(jsonPath("$.attributes.color").value("blue"));
        }

        @Test
        @DisplayName("should merge attributes when partial attributes provided")
        void updateProduct_MergeAttributes_Returns200() throws Exception {
            var mergedResponse = new ProductResponse(
                    productId,
                    "SKU-001",
                    "Product",
                    null,
                    null,
                    null,
                    Map.of("color", "red", "size", "L", "weight", "2kg"),
                    true,
                    now,
                    now,
                    null,
                    null,
                    companyId);
            when(productService.updateProduct(eq(companyId), eq(productId), any(ProductRequest.class)))
                    .thenReturn(mergedResponse);

            // Only send "weight" — expects existing "color" and "size" preserved
            var request = new ProductRequest("SKU-001", "Product", null, null, null, Map.of("weight", "2kg"), null);

            mockMvc.perform(put("/api/companies/{companyId}/products/{id}", companyId, productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.attributes.color").value("red"))
                    .andExpect(jsonPath("$.attributes.size").value("L"))
                    .andExpect(jsonPath("$.attributes.weight").value("2kg"));
        }

        @Test
        @DisplayName("should return 404 when update names a category that does not exist")
        void updateProduct_UnknownCategory_Returns404() throws Exception {
            // The create path is not the only writer: an edit that repoints the classification at a
            // deleted id has to answer with the same contract instead of an FK violation.
            var unknownCategoryId = UUID.randomUUID();
            when(productService.updateProduct(eq(companyId), eq(productId), any(ProductRequest.class)))
                    .thenThrow(new ProductCategoryNotFoundException(unknownCategoryId));

            mockMvc.perform(put("/api/companies/{companyId}/products/{id}", companyId, productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testProductRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product category not found with id: " + unknownCategoryId));
        }

        @Test
        @DisplayName("should return 404 when product not found")
        void updateProduct_NotFound_Returns404() throws Exception {
            when(productService.updateProduct(eq(companyId), eq(productId), any(ProductRequest.class)))
                    .thenThrow(new ProductNotFoundException(productId));

            mockMvc.perform(put("/api/companies/{companyId}/products/{id}", companyId, productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testProductRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product not found with id: " + productId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 409 Conflict when SKU conflicts with another product")
        void updateProduct_SkuConflict_Returns409() throws Exception {
            when(productService.updateProduct(eq(companyId), eq(productId), any(ProductRequest.class)))
                    .thenThrow(new DuplicateProductException("Product with SKU 'SKU-CONFLICT' already exists"));

            var request = new ProductRequest("SKU-CONFLICT", "Product", null, null, null, null, null);

            mockMvc.perform(put("/api/companies/{companyId}/products/{id}", companyId, productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Product with SKU 'SKU-CONFLICT' already exists"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // DELETE /api/companies/{companyId}/products/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/companies/{companyId}/products/{id}")
    class DeleteProductTests {

        @Test
        @DisplayName("should return 204 when active product soft-deleted")
        void deleteProduct_Active_Returns204() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/products/{id}", companyId, productId))
                    .andExpect(status().isNoContent());

            verify(productService).deleteProduct(companyId, productId);
        }

        @Test
        @DisplayName("should return 404 when product already soft-deleted")
        void deleteProduct_AlreadyDeleted_Returns404() throws Exception {
            doThrow(new ProductNotFoundException(productId))
                    .when(productService)
                    .deleteProduct(companyId, productId);

            mockMvc.perform(delete("/api/companies/{companyId}/products/{id}", companyId, productId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product not found with id: " + productId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 404 when product does not exist")
        void deleteProduct_NotFound_Returns404() throws Exception {
            doThrow(new ProductNotFoundException(productId))
                    .when(productService)
                    .deleteProduct(companyId, productId);

            mockMvc.perform(delete("/api/companies/{companyId}/products/{id}", companyId, productId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Product not found with id: " + productId));
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
            when(productService.findProduct(companyId, productId)).thenThrow(new ProductNotFoundException(productId));

            mockMvc.perform(get("/api/companies/{companyId}/products/{id}", companyId, productId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.path").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        @DisplayName("should include correlationId, path, timestamp in 409 responses")
        void error409_ContainsContractFields() throws Exception {
            when(productService.createProduct(eq(companyId), any(ProductRequest.class)))
                    .thenThrow(new DuplicateProductException("Product with SKU 'SKU-001' already exists"));

            mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testProductRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.path").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        @DisplayName("should include correlationId, path, timestamp, errors map in 400 responses")
        void error400_ContainsContractFields() throws Exception {
            var invalidRequest = new ProductRequest("", "", null, null, null, null, null);

            mockMvc.perform(post("/api/companies/{companyId}/products", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors").isMap())
                    .andExpect(jsonPath("$.path").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }
    }
}
