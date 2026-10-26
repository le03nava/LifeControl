package com.lifecontrol.api.product.category.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.product.category.dto.ProductCategoryRequest;
import com.lifecontrol.api.product.category.dto.ProductCategoryResponse;
import com.lifecontrol.api.product.category.exception.DisabledParentCategoryException;
import com.lifecontrol.api.product.category.exception.DuplicateProductCategoryException;
import com.lifecontrol.api.product.category.exception.ProductCategoryDepthExceededException;
import com.lifecontrol.api.product.category.exception.ProductCategoryHierarchyCycleException;
import com.lifecontrol.api.product.category.exception.ProductCategoryNotFoundException;
import com.lifecontrol.api.product.category.service.ProductCategoryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP contract of the category tree: the wire shape of every response and the status code every
 * domain rule maps to.
 *
 * <p>The mapping matters as much as the happy path: the tree's refusals are 409 (a well-formed
 * request that conflicts with the tree's state) and never 400, and a category of another company is a
 * 404 rather than a 403 so the endpoint does not disclose that the row exists.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCategoryController Tests")
class ProductCategoryControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ProductCategoryService productCategoryService;

    @InjectMocks
    private ProductCategoryController productCategoryController;

    private UUID companyId;
    private UUID categoryId;
    private UUID parentId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(productCategoryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = JsonMapper.builder().build();

        companyId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        parentId = UUID.randomUUID();
    }

    private ProductCategoryResponse response(UUID id, UUID parent, int depth, boolean enabled) {
        return response(id, parent, depth, enabled, null);
    }

    private ProductCategoryResponse response(UUID id, UUID parent, int depth, boolean enabled, String satCode) {
        return new ProductCategoryResponse(
                id,
                companyId,
                parent,
                "Gaseosas",
                "Refrescos con gas",
                satCode,
                2,
                depth,
                enabled,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private ProductCategoryRequest request(UUID parent, String name) {
        return new ProductCategoryRequest(parent, name, "descripcion", null, 2, true);
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/product-categories")
    class ListTests {

        @Test
        @DisplayName("returns the depth-first list with depth and parentCategoryId")
        void returnsList() throws Exception {
            when(productCategoryService.getCategories(companyId, false))
                    .thenReturn(List.of(response(parentId, null, 1, true), response(categoryId, parentId, 2, true)));

            mockMvc.perform(get("/api/companies/{companyId}/product-categories", companyId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].depth").value(1))
                    .andExpect(jsonPath("$[0].parentCategoryId").doesNotExist())
                    .andExpect(jsonPath("$[1].depth").value(2))
                    .andExpect(jsonPath("$[1].parentCategoryId").value(parentId.toString()));
        }

        @Test
        @DisplayName("forwards includeDisabled to the service")
        void forwardsIncludeDisabled() throws Exception {
            when(productCategoryService.getCategories(companyId, true)).thenReturn(List.of());

            mockMvc.perform(get("/api/companies/{companyId}/product-categories", companyId)
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk());

            verify(productCategoryService).getCategories(companyId, true);
        }
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/product-categories/{id}")
    class GetByIdTests {

        @Test
        @DisplayName("returns 200 with the category")
        void returnsCategory() throws Exception {
            when(productCategoryService.getCategoryById(companyId, categoryId))
                    .thenReturn(response(categoryId, parentId, 2, true));

            mockMvc.perform(get("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(categoryId.toString()))
                    .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.categoryName").value("Gaseosas"));
        }

        @Test
        @DisplayName("returns 404 when the category does not belong to the company")
        void returns404() throws Exception {
            when(productCategoryService.getCategoryById(companyId, categoryId))
                    .thenThrow(new ProductCategoryNotFoundException(categoryId));

            mockMvc.perform(get("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/companies/{companyId}/product-categories")
    class CreateTests {

        @Test
        @DisplayName("returns 201 with the created category")
        void returns201() throws Exception {
            when(productCategoryService.createCategory(eq(companyId), any(ProductCategoryRequest.class)))
                    .thenReturn(response(categoryId, parentId, 2, true));

            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(parentId, "Gaseosas"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(categoryId.toString()));
        }

        @Test
        @DisplayName("returns 400 when categoryName is blank")
        void blankNameIs400() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(null, "  "))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when displayOrder is negative")
        void negativeDisplayOrderIs400() throws Exception {
            var body = """
                    {"categoryName": "Bebidas", "displayOrder": -1}
                    """;

            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("carries satCode from the payload to the service and back in the response")
        void carriesSatCode() throws Exception {
            when(productCategoryService.createCategory(eq(companyId), any(ProductCategoryRequest.class)))
                    .thenReturn(response(categoryId, parentId, 2, true, "27111600"));

            var body = """
                    {"parentCategoryId": "%s", "categoryName": "Herramientas Manuales", "satCode": "27111600"}
                    """.formatted(parentId);

            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.satCode").value("27111600"));

            var captor = ArgumentCaptor.forClass(ProductCategoryRequest.class);
            verify(productCategoryService).createCategory(eq(companyId), captor.capture());
            assertThat(captor.getValue().satCode()).isEqualTo("27111600");
        }

        @Test
        @DisplayName("normalizes a whitespace-only satCode to null: the key is optional, not blank")
        void blankSatCodeBecomesNull() throws Exception {
            when(productCategoryService.createCategory(eq(companyId), any(ProductCategoryRequest.class)))
                    .thenReturn(response(categoryId, null, 1, true));

            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"categoryName": "Bebidas", "satCode": "   "}
                                    """))
                    .andExpect(status().isCreated());

            var captor = ArgumentCaptor.forClass(ProductCategoryRequest.class);
            verify(productCategoryService).createCategory(eq(companyId), captor.capture());
            assertThat(captor.getValue().satCode()).isNull();
        }

        @Test
        @DisplayName("returns 400 when satCode is not eight digits")
        void malformedSatCodeIs400() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"categoryName": "Bebidas", "satCode": "27-11000"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when satCode carries letters or is too short")
        void nonNumericSatCodeIs400() throws Exception {
            for (var satCode : new String[] {"ABCDEFGH", "2711000"}) {
                mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"categoryName": "Bebidas", "satCode": "%s"}
                                        """.formatted(satCode)))
                        .andExpect(status().isBadRequest());
            }

            verifyNoInteractions(productCategoryService);
        }

        @Test
        @DisplayName("returns 404 when the parent category does not exist in the company")
        void missingParentIs404() throws Exception {
            when(productCategoryService.createCategory(eq(companyId), any(ProductCategoryRequest.class)))
                    .thenThrow(new ProductCategoryNotFoundException(parentId));

            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(parentId, "Gaseosas"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 409 when a sibling already carries the name")
        void duplicateIs409() throws Exception {
            when(productCategoryService.createCategory(eq(companyId), any(ProductCategoryRequest.class)))
                    .thenThrow(new DuplicateProductCategoryException("Gaseosas", null));

            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(null, "Gaseosas"))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("returns 409 for a fourth level")
        void depthExceededIs409() throws Exception {
            when(productCategoryService.createCategory(eq(companyId), any(ProductCategoryRequest.class)))
                    .thenThrow(new ProductCategoryDepthExceededException(4, 3));

            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(parentId, "Colas"))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("returns 409 when the parent is disabled")
        void disabledParentIs409() throws Exception {
            when(productCategoryService.createCategory(eq(companyId), any(ProductCategoryRequest.class)))
                    .thenThrow(new DisabledParentCategoryException("parent is disabled"));

            mockMvc.perform(post("/api/companies/{companyId}/product-categories", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(parentId, "Colas"))))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/product-categories/{id}")
    class UpdateTests {

        @Test
        @DisplayName("returns 200 with the updated category")
        void returns200() throws Exception {
            when(productCategoryService.updateCategory(
                            eq(companyId), eq(categoryId), any(ProductCategoryRequest.class)))
                    .thenReturn(response(categoryId, null, 1, true));

            mockMvc.perform(put("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(null, "Bebidas"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.depth").value(1));
        }

        @Test
        @DisplayName("returns 409 for a cycle")
        void cycleIs409() throws Exception {
            when(productCategoryService.updateCategory(
                            eq(companyId), eq(categoryId), any(ProductCategoryRequest.class)))
                    .thenThrow(ProductCategoryHierarchyCycleException.descendantParent());

            mockMvc.perform(put("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(parentId, "Bebidas"))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("forwards enabled=false instead of dropping the flag on the floor")
        void forwardsDisableFlag() throws Exception {
            when(productCategoryService.updateCategory(
                            eq(companyId), eq(categoryId), any(ProductCategoryRequest.class)))
                    .thenReturn(response(categoryId, null, 1, false));

            mockMvc.perform(put("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new ProductCategoryRequest(null, "Bebidas", null, null, 1, false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));

            var captor = ArgumentCaptor.forClass(ProductCategoryRequest.class);
            verify(productCategoryService).updateCategory(eq(companyId), eq(categoryId), captor.capture());
            assertThat(captor.getValue().enabled())
                    .as("the flag that cascades to the subtree reaches the service")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("DELETE /api/companies/{companyId}/product-categories/{id}")
    class DeleteTests {

        @Test
        @DisplayName("returns 204")
        void returns204() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("returns 404 for an unknown category")
        void returns404() throws Exception {
            doThrow(new ProductCategoryNotFoundException(categoryId))
                    .when(productCategoryService)
                    .deleteCategory(companyId, categoryId);

            mockMvc.perform(delete("/api/companies/{companyId}/product-categories/{id}", companyId, categoryId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/product-categories/{id}/enable")
    class EnableTests {

        @Test
        @DisplayName("disables a category when the body says so")
        void disables() throws Exception {
            when(productCategoryService.setCategoryEnabled(companyId, categoryId, false))
                    .thenReturn(response(categoryId, null, 1, false));

            mockMvc.perform(patch("/api/companies/{companyId}/product-categories/{id}/enable", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));
        }

        @Test
        @DisplayName("defaults to enabling when the body omits the flag")
        void defaultsToEnabled() throws Exception {
            when(productCategoryService.setCategoryEnabled(companyId, categoryId, true))
                    .thenReturn(response(categoryId, null, 1, true));

            mockMvc.perform(patch("/api/companies/{companyId}/product-categories/{id}/enable", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            verify(productCategoryService).setCategoryEnabled(companyId, categoryId, true);
        }

        @Test
        @DisplayName("returns 409 when an ancestor is disabled")
        void disabledAncestorIs409() throws Exception {
            when(productCategoryService.setCategoryEnabled(companyId, categoryId, true))
                    .thenThrow(new DisabledParentCategoryException("ancestor is disabled"));

            mockMvc.perform(patch("/api/companies/{companyId}/product-categories/{id}/enable", companyId, categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isConflict());
        }
    }
}
