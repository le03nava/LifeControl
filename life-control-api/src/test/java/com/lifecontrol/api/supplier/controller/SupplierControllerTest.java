package com.lifecontrol.api.supplier.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.common.address.dto.AddressResponse;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.supplier.dto.SupplierRequest;
import com.lifecontrol.api.supplier.dto.SupplierResponse;
import com.lifecontrol.api.supplier.exception.DuplicateSupplierException;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.service.SupplierService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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
@DisplayName("SupplierController Tests")
class SupplierControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private SupplierService supplierService;

    @InjectMocks
    private SupplierController supplierController;

    private SupplierResponse testSupplierResponse;
    private SupplierRequest testSupplierRequest;
    private UUID testSupplierId;
    private UUID testCountryId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(supplierController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testSupplierId = UUID.randomUUID();
        testCountryId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testSupplierResponse = new SupplierResponse(
                testSupplierId,
                "Test Supplier",
                "Test Razon Social SA de CV",
                "XAXX010101000",
                "test@supplier.com",
                "+1234567890",
                "INT-001",
                new AddressResponse(
                        UUID.randomUUID(),
                        "Calle Principal",
                        "123",
                        null,
                        "Centro",
                        "12345",
                        "Ciudad de Mexico",
                        "CDMX",
                        testCountryId
                ),
                true,
                now,
                now
        );

        testSupplierRequest = new SupplierRequest(
                "Test Supplier",
                "Test Razon Social SA de CV",
                "XAXX010101000",
                "test@supplier.com",
                "+1234567890",
                "INT-001",
                new AddressRequest(
                        "Calle Principal",
                        "123",
                        null,
                        "Centro",
                        "12345",
                        "Ciudad de Mexico",
                        "CDMX",
                        testCountryId
                ),
                true
        );
    }

    @Nested
    @DisplayName("GET /api/suppliers")
    class GetAllSuppliersTests {

        @Test
        @DisplayName("getAllSuppliers - should return paginated suppliers")
        void getAllSuppliers_Paginated() throws Exception {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var suppliers = List.of(testSupplierResponse);
            var page = new PageImpl<>(suppliers, pageable, 1);

            when(supplierService.getAllSuppliers(any(Pageable.class), eq(null))).thenReturn(page);

            // Act & Assert
            mockMvc.perform(get("/api/suppliers")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].supplierName").value("Test Supplier"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));
        }

        @Test
        @DisplayName("getAllSuppliers - should filter by search term")
        void getAllSuppliers_WithSearch() throws Exception {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var suppliers = List.of(testSupplierResponse);
            var page = new PageImpl<>(suppliers, pageable, 1);

            when(supplierService.getAllSuppliers(any(Pageable.class), eq("Test"))).thenReturn(page);

            // Act & Assert
            mockMvc.perform(get("/api/suppliers")
                            .param("page", "0")
                            .param("size", "12")
                            .param("search", "Test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].supplierName").value("Test Supplier"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("getAllSuppliers - should return empty page when no results")
        void getAllSuppliers_EmptyPage() throws Exception {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<SupplierResponse>(List.of(), pageable, 0);

            when(supplierService.getAllSuppliers(any(Pageable.class), eq(null))).thenReturn(page);

            // Act & Assert
            mockMvc.perform(get("/api/suppliers")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));
        }

        @Test
        @DisplayName("getAllSuppliers - should use default page size when not specified")
        void getAllSuppliers_DefaultPageSize() throws Exception {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<>(List.of(testSupplierResponse), pageable, 1);

            when(supplierService.getAllSuppliers(any(Pageable.class), eq(null))).thenReturn(page);

            // Act & Assert
            mockMvc.perform(get("/api/suppliers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(12));
        }
    }

    @Nested
    @DisplayName("GET /api/suppliers/{id}")
    class GetSupplierByIdTests {

        @Test
        @DisplayName("getSupplierById - should return 200 with supplier")
        void getSupplierById_Success() throws Exception {
            // Arrange
            when(supplierService.getSupplierById(testSupplierId)).thenReturn(testSupplierResponse);

            // Act & Assert
            mockMvc.perform(get("/api/suppliers/{id}", testSupplierId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.supplierName").value("Test Supplier"))
                    .andExpect(jsonPath("$.rfc").value("XAXX010101000"))
                    .andExpect(jsonPath("$.address.street").value("Calle Principal"))
                    .andExpect(jsonPath("$.address.city").value("Ciudad de Mexico"));
        }

        @Test
        @DisplayName("getSupplierById - should return 404 when not found")
        void getSupplierById_NotFound() throws Exception {
            // Arrange
            when(supplierService.getSupplierById(testSupplierId))
                    .thenThrow(new SupplierNotFoundException(testSupplierId));

            // Act & Assert
            mockMvc.perform(get("/api/suppliers/{id}", testSupplierId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Supplier not found with id: " + testSupplierId));
        }
    }

    @Nested
    @DisplayName("POST /api/suppliers")
    class CreateSupplierTests {

        @Test
        @DisplayName("createSupplier - should return 201 with created supplier")
        void createSupplier_Success() throws Exception {
            // Arrange
            when(supplierService.createSupplier(any(SupplierRequest.class))).thenReturn(testSupplierResponse);

            // Act & Assert
            mockMvc.perform(post("/api/suppliers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testSupplierRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.supplierName").value("Test Supplier"))
                    .andExpect(jsonPath("$.rfc").value("XAXX010101000"))
                    .andExpect(jsonPath("$.internalNumber").value("INT-001"))
                    .andExpect(jsonPath("$.address.street").value("Calle Principal"));
        }

        @Test
        @DisplayName("createSupplier - should return 400 for invalid input")
        void createSupplier_InvalidInput() throws Exception {
            // Arrange - missing required supplierName
            var invalidRequest = new SupplierRequest(
                    null,  // supplierName is required
                    null, "ZAXX010101000", null, null, null, null,
                    true
            );

            // Act & Assert
            mockMvc.perform(post("/api/suppliers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("createSupplier - should return 409 for duplicate RFC")
        void createSupplier_DuplicateRfc() throws Exception {
            // Arrange
            when(supplierService.createSupplier(any(SupplierRequest.class)))
                    .thenThrow(new DuplicateSupplierException("Ya existe un proveedor con RFC: XAXX010101000"));

            // Act & Assert
            mockMvc.perform(post("/api/suppliers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testSupplierRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Ya existe un proveedor con RFC: XAXX010101000"));
        }
    }

    @Nested
    @DisplayName("PUT /api/suppliers/{id}")
    class UpdateSupplierTests {

        @Test
        @DisplayName("updateSupplier - should return 200 with updated supplier")
        void updateSupplier_Success() throws Exception {
            // Arrange
            when(supplierService.updateSupplier(eq(testSupplierId), any(SupplierRequest.class)))
                    .thenReturn(testSupplierResponse);

            // Act & Assert
            mockMvc.perform(put("/api/suppliers/{id}", testSupplierId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testSupplierRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.supplierName").value("Test Supplier"))
                    .andExpect(jsonPath("$.rfc").value("XAXX010101000"))
                    .andExpect(jsonPath("$.internalNumber").value("INT-001"))
                    .andExpect(jsonPath("$.address.street").value("Calle Principal"));
        }

        @Test
        @DisplayName("updateSupplier - should return 404 when supplier not found")
        void updateSupplier_NotFound() throws Exception {
            // Arrange
            when(supplierService.updateSupplier(eq(testSupplierId), any(SupplierRequest.class)))
                    .thenThrow(new SupplierNotFoundException(testSupplierId));

            // Act & Assert
            mockMvc.perform(put("/api/suppliers/{id}", testSupplierId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testSupplierRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Supplier not found with id: " + testSupplierId));
        }

        @Test
        @DisplayName("updateSupplier - should return 409 for duplicate RFC")
        void updateSupplier_DuplicateRfc() throws Exception {
            // Arrange
            when(supplierService.updateSupplier(eq(testSupplierId), any(SupplierRequest.class)))
                    .thenThrow(new DuplicateSupplierException("Ya existe un proveedor con RFC: XAXX010101000"));

            // Act & Assert
            mockMvc.perform(put("/api/suppliers/{id}", testSupplierId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testSupplierRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Ya existe un proveedor con RFC: XAXX010101000"));
        }

        @Test
        @DisplayName("updateSupplier - should return 400 for invalid input")
        void updateSupplier_InvalidInput() throws Exception {
            // Arrange - missing required supplierName
            var invalidRequest = new SupplierRequest(
                    null, null, "ZAXX010101000", null, null, null, null,
                    true
            );

            // Act & Assert
            mockMvc.perform(put("/api/suppliers/{id}", testSupplierId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("DELETE /api/suppliers/{id}")
    class DeleteSupplierTests {

        @Test
        @DisplayName("deleteSupplier - should return 204 on successful delete")
        void deleteSupplier_Success() throws Exception {
            // Arrange
            doNothing().when(supplierService).deleteSupplier(testSupplierId);

            // Act & Assert
            mockMvc.perform(delete("/api/suppliers/{id}", testSupplierId))
                    .andExpect(status().isNoContent());
            verify(supplierService).deleteSupplier(testSupplierId);
        }

        @Test
        @DisplayName("deleteSupplier - should return 404 when supplier not found")
        void deleteSupplier_NotFound() throws Exception {
            // Arrange
            doThrow(new SupplierNotFoundException(testSupplierId))
                    .when(supplierService).deleteSupplier(testSupplierId);

            // Act & Assert
            mockMvc.perform(delete("/api/suppliers/{id}", testSupplierId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Supplier not found with id: " + testSupplierId));
        }
    }
}
