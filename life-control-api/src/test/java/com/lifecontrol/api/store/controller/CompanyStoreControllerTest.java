package com.lifecontrol.api.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.common.address.dto.AddressResponse;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.dto.CreateCompanyStoreRequest;
import com.lifecontrol.api.store.dto.UpdateCompanyStoreRequest;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DuplicateCompanyStoreException;
import com.lifecontrol.api.store.service.CompanyStoreService;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores";

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
                new AddressResponse(
                        UUID.randomUUID(),
                        "Calle Principal",
                        "123",
                        null,
                        "Centro",
                        "12345",
                        "Ciudad de México",
                        "CDMX",
                        UUID.randomUUID()),
                true,
                now,
                now,
                0L);
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllStoresTests {

        @Test
        @DisplayName("should return 200 with list of stores (default without disabled)")
        void getAllStores_Success() throws Exception {
            // Arrange
            when(companyStoreService.getAllStores(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, false))
                    .thenReturn(List.of(testStoreResponse));

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].storeName").value("Tienda Principal"))
                    .andExpect(jsonPath("$[0].enabled").value(true))
                    .andExpect(jsonPath("$[0].address.street").value("Calle Principal"))
                    .andExpect(jsonPath("$[0].address.city").value("Ciudad de México"));
        }

        @Test
        @DisplayName("should support includeDisabled=true parameter")
        void getAllStores_WithIncludeDisabled() throws Exception {
            // Arrange
            when(companyStoreService.getAllStores(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, true))
                    .thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk());
            verify(companyStoreService)
                    .getAllStores(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, true);
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
            mockMvc.perform(get(
                            BASE_URL + "/{id}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeName").value("Tienda Principal"))
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.address.street").value("Calle Principal"));
        }

        @Test
        @DisplayName("should return 404 when store not found")
        void getStoreById_NotFound() throws Exception {
            // Arrange
            when(companyStoreService.getStoreById(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .thenThrow(new CompanyStoreNotFoundException(testStoreId));

            // Act & Assert
            mockMvc.perform(get(
                            BASE_URL + "/{id}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId))
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
                    "Tienda Nueva",
                    "nueva@example.com",
                    "555-5678",
                    new AddressRequest(
                            "Calle", "123", null, "Colonia", "12345", "Ciudad", "Estado", UUID.randomUUID()));
            when(companyStoreService.createStore(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            any(CreateCompanyStoreRequest.class)))
                    .thenReturn(testStoreResponse);

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.storeName").value("Tienda Principal"))
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.address.street").value("Calle Principal"));
        }

        @Test
        @DisplayName("should return 400 when validation fails (missing storeName)")
        void createStore_ValidationError() throws Exception {
            // Arrange
            var invalidRequest = new CreateCompanyStoreRequest("", "nueva@example.com", "555-5678", null);

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"));
        }

        @Test
        @DisplayName("should return 400 when the nested address street exceeds 255 characters")
        void createStore_OverLengthAddressStreetReturns400() throws Exception {
            // Arrange: 256 chars in the nested address. Without @Valid on the address component the
            // @Size(max = 255) in AddressRequest never runs and this request would reach the service.
            var request = new CreateCompanyStoreRequest(
                    "Tienda Nueva",
                    "nueva@example.com",
                    "555-5678",
                    new AddressRequest("a".repeat(256), null, null, null, null, null, null, UUID.randomUUID()));

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$['errors']['address.street']").exists());

            verifyNoInteractions(companyStoreService);
        }

        @Test
        @DisplayName("should return 409 when duplicate")
        void createStore_Duplicate() throws Exception {
            // Arrange
            var request = new CreateCompanyStoreRequest("Tienda Existente", "dupe@example.com", "555-0000", null);
            when(companyStoreService.createStore(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            any(CreateCompanyStoreRequest.class)))
                    .thenThrow(new DuplicateCompanyStoreException(
                            "Store with name 'Tienda Existente' already exists in this zone"));

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value("Store with name 'Tienda Existente' already exists in this zone"));
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class UpdateStoreTests {

        @Test
        @DisplayName("should return 200 with updated store")
        void updateStore_Success() throws Exception {
            // Arrange
            var request =
                    new UpdateCompanyStoreRequest("Tienda Actualizada", "actualizada@example.com", "555-9999", null);
            var updatedResponse = new CompanyStoreResponse(
                    testStoreId,
                    testCompanyId,
                    testCompanyCountryId,
                    testRegionId,
                    testZoneId,
                    "Tienda Actualizada",
                    "actualizada@example.com",
                    "555-9999",
                    null,
                    true,
                    now,
                    now,
                    0L);
            when(companyStoreService.updateStore(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(updatedResponse);

            // Act & Assert
            mockMvc.perform(put(
                                    BASE_URL + "/{id}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeName").value("Tienda Actualizada"))
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.address").doesNotExist());
        }

        @Test
        @DisplayName("should return 400 when storeName is blank")
        void updateStore_BlankStoreNameReturns400() throws Exception {
            // Arrange: null means "leave unchanged", but a blank string is a validation error.
            var request = new UpdateCompanyStoreRequest("", null, null, null);

            // Act & Assert
            mockMvc.perform(put(
                                    BASE_URL + "/{id}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.storeName").exists());

            verifyNoInteractions(companyStoreService);
        }

        @Test
        @DisplayName("should return 400 when the nested address street exceeds 255 characters")
        void updateStore_OverLengthAddressStreetReturns400() throws Exception {
            // Arrange
            var request = new UpdateCompanyStoreRequest(
                    null,
                    null,
                    null,
                    new AddressRequest("a".repeat(256), null, null, null, null, null, null, UUID.randomUUID()));

            // Act & Assert
            mockMvc.perform(put(
                                    BASE_URL + "/{id}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$['errors']['address.street']").exists());

            verifyNoInteractions(companyStoreService);
        }

        @Test
        @DisplayName("should accept a null storeName meaning unchanged")
        void updateStore_NullStoreNameIsUnchanged() throws Exception {
            // Arrange: positive boundary — null passes validation and reaches the service.
            var request = new UpdateCompanyStoreRequest(null, "nueva@example.com", null, null);
            when(companyStoreService.updateStore(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(testStoreResponse);

            // Act & Assert
            mockMvc.perform(put(
                                    BASE_URL + "/{id}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 409 with the conflict message when the version precondition fails")
        void updateStore_VersionConflictReturns409() throws Exception {
            // Arrange
            var request = new UpdateCompanyStoreRequest("Tienda Actualizada", null, null, null, 5L);
            when(companyStoreService.updateStore(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            any(UpdateCompanyStoreRequest.class)))
                    .thenThrow(new ConflictException(
                            "The company store conflicts with the current server state; reload and try again"));

            // Act & Assert
            mockMvc.perform(put(
                                    BASE_URL + "/{id}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message")
                            .value("The company store conflicts with the current server state; reload and try again"));
        }

        @Test
        @DisplayName("should return 404 when store not found")
        void updateStore_NotFound() throws Exception {
            // Arrange
            var request = new UpdateCompanyStoreRequest("Tienda Actualizada", null, null, null);
            when(companyStoreService.updateStore(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            any(UpdateCompanyStoreRequest.class)))
                    .thenThrow(new CompanyStoreNotFoundException(testStoreId));

            // Act & Assert
            mockMvc.perform(put(
                                    BASE_URL + "/{id}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId)
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
            doNothing()
                    .when(companyStoreService)
                    .deleteStore(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId);

            // Act & Assert
            mockMvc.perform(delete(
                            BASE_URL + "/{id}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId))
                    .andExpect(status().isNoContent());
            verify(companyStoreService)
                    .deleteStore(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId);
        }

        @Test
        @DisplayName("should return 404 when store not found on delete")
        void deleteStore_NotFound() throws Exception {
            // Arrange
            doThrow(new CompanyStoreNotFoundException(testStoreId))
                    .when(companyStoreService)
                    .deleteStore(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId);

            // Act & Assert
            mockMvc.perform(delete(
                            BASE_URL + "/{id}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId))
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
                    testStoreId,
                    testCompanyId,
                    testCompanyCountryId,
                    testRegionId,
                    testZoneId,
                    "Tienda Principal",
                    "tienda@example.com",
                    "555-1234",
                    null,
                    true,
                    now,
                    now,
                    0L);
            when(companyStoreService.enableStore(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .thenReturn(enabledResponse);

            // Act & Assert
            mockMvc.perform(patch(
                            BASE_URL + "/{id}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId))
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
            mockMvc.perform(patch(
                            BASE_URL + "/{id}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store not found with id: " + testStoreId));
        }
    }
}
