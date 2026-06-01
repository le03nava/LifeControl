package com.lifecontrol.api.store.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.dto.CreateCompanyStoreRequest;
import com.lifecontrol.api.store.dto.UpdateCompanyStoreRequest;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DuplicateCompanyStoreException;
import com.lifecontrol.api.store.service.CompanyStoreService;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyStoreController Tests")
class CompanyStoreControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private CompanyStoreService companyStoreService;

    @InjectMocks
    private CompanyStoreController companyStoreController;

    private UUID testCompanyId;
    private UUID testCompanyCountryId;
    private UUID testRegionId;
    private UUID testZoneId;
    private UUID testStoreId;
    private CompanyStoreResponse testStoreResponse;
    private LocalDateTime now;

    private static final String BASE_URL = "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(companyStoreController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testCompanyId = UUID.randomUUID();
        testCompanyCountryId = UUID.randomUUID();
        testRegionId = UUID.randomUUID();
        testZoneId = UUID.randomUUID();
        testStoreId = UUID.randomUUID();
        now = LocalDateTime.now();

        testStoreResponse = new CompanyStoreResponse(
                testStoreId,
                testCompanyId,
                testCompanyCountryId,
                testRegionId,
                testZoneId,
                "Tienda Principal",
                "tienda@example.com",
                "555-1234",
                UUID.randomUUID(),
                "Calle Principal",
                "123",
                null,
                "Centro",
                "12345",
                "Ciudad de México",
                "CDMX",
                UUID.randomUUID(),
                true,
                now,
                now
        );
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllStoresTests {

        @Test
        @DisplayName("should return 200 with list of stores (default without disabled)")
        void getAllStores_Success() throws Exception {
            // Arrange
            when(companyStoreService.getAllStores(
                    testCompanyId, testCompanyCountryId, testRegionId, testZoneId, false))
                    .thenReturn(List.of(testStoreResponse));

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].storeName").value("Tienda Principal"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("should support includeDisabled=true parameter")
        void getAllStores_WithIncludeDisabled() throws Exception {
            // Arrange
            when(companyStoreService.getAllStores(
                    testCompanyId, testCompanyCountryId, testRegionId, testZoneId, true))
                    .thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk());
            verify(companyStoreService).getAllStores(
                    testCompanyId, testCompanyCountryId, testRegionId, testZoneId, true);
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetStoreByIdTests {

        @Test
        @DisplayName("should return 200 with store when found")
        void getStoreById_Success() throws Exception {
            // Arrange
            when(companyStoreService.getStoreById(
                    testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .thenReturn(testStoreResponse);

            // Act & Assert
            mockMvc.perform(get(BASE_URL + "/{id}",
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeName").value("Tienda Principal"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when store not found")
        void getStoreById_NotFound() throws Exception {
            // Arrange
            when(companyStoreService.getStoreById(
                    testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .thenThrow(new CompanyStoreNotFoundException(testStoreId));

            // Act & Assert
            mockMvc.perform(get(BASE_URL + "/{id}",
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store not found with id: " + testStoreId));
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateStoreTests {

        @Test
        @DisplayName("should return 201 with created store")
        void createStore_Success() throws Exception {
            // Arrange
            var request = new CreateCompanyStoreRequest(
                    "Tienda Nueva", "nueva@example.com", "555-5678",
                    "Calle", "123", null, "Colonia", "12345",
                    "Ciudad", "Estado", UUID.randomUUID());
            when(companyStoreService.createStore(
                    eq(testCompanyId), eq(testCompanyCountryId), eq(testRegionId),
                    eq(testZoneId), any(CreateCompanyStoreRequest.class)))
                    .thenReturn(testStoreResponse);

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.storeName").value("Tienda Principal"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when validation fails (missing storeName)")
        void createStore_ValidationError() throws Exception {
            // Arrange
            var invalidRequest = new CreateCompanyStoreRequest(
                    "", "nueva@example.com", "555-5678",
                    null, null, null, null, null,
                    null, null, null);

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"));
        }

        @Test
        @DisplayName("should return 409 when duplicate")
        void createStore_Duplicate() throws Exception {
            // Arrange
            var request = new CreateCompanyStoreRequest(
                    "Tienda Existente", "dupe@example.com", "555-0000",
                    null, null, null, null, null,
                    null, null, null);
            when(companyStoreService.createStore(
                    eq(testCompanyId), eq(testCompanyCountryId), eq(testRegionId),
                    eq(testZoneId), any(CreateCompanyStoreRequest.class)))
                    .thenThrow(new DuplicateCompanyStoreException(
                            "Store with name 'Tienda Existente' already exists in this zone"));

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            "Store with name 'Tienda Existente' already exists in this zone"));
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class UpdateStoreTests {

        @Test
        @DisplayName("should return 200 with updated store")
        void updateStore_Success() throws Exception {
            // Arrange
            var request = new UpdateCompanyStoreRequest(
                    "Tienda Actualizada", "actualizada@example.com", "555-9999",
                    null, null, null, null, null,
                    null, null, null);
            var updatedResponse = new CompanyStoreResponse(
                    testStoreId, testCompanyId, testCompanyCountryId, testRegionId, testZoneId,
                    "Tienda Actualizada", "actualizada@example.com", "555-9999",
                    null, null, null, null, null, null,
                    null, null, null,
                    true, now, now);
            when(companyStoreService.updateStore(
                    eq(testCompanyId), eq(testCompanyCountryId), eq(testRegionId),
                    eq(testZoneId), eq(testStoreId), any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(updatedResponse);

            // Act & Assert
            mockMvc.perform(put(BASE_URL + "/{id}",
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeName").value("Tienda Actualizada"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when store not found")
        void updateStore_NotFound() throws Exception {
            // Arrange
            var request = new UpdateCompanyStoreRequest(
                    "Tienda Actualizada", null, null,
                    null, null, null, null, null,
                    null, null, null);
            when(companyStoreService.updateStore(
                    eq(testCompanyId), eq(testCompanyCountryId), eq(testRegionId),
                    eq(testZoneId), eq(testStoreId), any(UpdateCompanyStoreRequest.class)))
                    .thenThrow(new CompanyStoreNotFoundException(testStoreId));

            // Act & Assert
            mockMvc.perform(put(BASE_URL + "/{id}",
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store not found with id: " + testStoreId));
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteStoreTests {

        @Test
        @DisplayName("should return 204 on successful soft-delete")
        void deleteStore_Success() throws Exception {
            // Arrange
            doNothing().when(companyStoreService).deleteStore(
                    testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId);

            // Act & Assert
            mockMvc.perform(delete(BASE_URL + "/{id}",
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .andExpect(status().isNoContent());
            verify(companyStoreService).deleteStore(
                    testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId);
        }

        @Test
        @DisplayName("should return 404 when store not found on delete")
        void deleteStore_NotFound() throws Exception {
            // Arrange
            doThrow(new CompanyStoreNotFoundException(testStoreId))
                    .when(companyStoreService).deleteStore(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId);

            // Act & Assert
            mockMvc.perform(delete(BASE_URL + "/{id}",
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store not found with id: " + testStoreId));
        }
    }

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{id}")
    class EnableStoreTests {

        @Test
        @DisplayName("should return 200 on re-enable")
        void enableStore_Success() throws Exception {
            // Arrange
            var enabledResponse = new CompanyStoreResponse(
                    testStoreId, testCompanyId, testCompanyCountryId, testRegionId, testZoneId,
                    "Tienda Principal", "tienda@example.com", "555-1234",
                    null, null, null, null, null, null,
                    null, null, null,
                    true, now, now);
            when(companyStoreService.enableStore(
                    testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .thenReturn(enabledResponse);

            // Act & Assert
            mockMvc.perform(patch(BASE_URL + "/{id}",
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeName").value("Tienda Principal"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when store not found on enable")
        void enableStore_NotFound() throws Exception {
            // Arrange
            when(companyStoreService.enableStore(
                    testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .thenThrow(new CompanyStoreNotFoundException(testStoreId));

            // Act & Assert
            mockMvc.perform(patch(BASE_URL + "/{id}",
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store not found with id: " + testStoreId));
        }
    }
}
