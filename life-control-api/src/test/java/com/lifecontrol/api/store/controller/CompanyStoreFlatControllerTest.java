package com.lifecontrol.api.store.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.service.CompanyStoreService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyStoreFlatController Tests")
class CompanyStoreFlatControllerTest {

    private static final String BASE_URL = "/api/stores";

    private MockMvc mockMvc;

    @Mock
    private CompanyStoreService companyStoreService;

    @InjectMocks
    private CompanyStoreFlatController companyStoreFlatController;

    private UUID storeId;
    private CompanyStoreResponse storeResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(companyStoreFlatController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        storeId = UUID.randomUUID();
        storeResponse = new CompanyStoreResponse(
                storeId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Tienda Principal",
                "tienda@example.com",
                "555-1234",
                null,
                true,
                LocalDateTime.now(),
                LocalDateTime.now(),
                0L);
    }

    @Nested
    @DisplayName("GET /api/stores/{storeId}")
    class GetStoreByIdTests {

        @Test
        @DisplayName("returns the store and its chain for a caller that holds the id alone (W3/T20)")
        void returnsTheStoreForACallerThatHoldsOnlyTheId() throws Exception {
            when(companyStoreService.getStoreById(storeId)).thenReturn(storeResponse);

            mockMvc.perform(get(BASE_URL + "/{storeId}", storeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(storeId.toString()))
                    .andExpect(jsonPath("$.storeName").value("Tienda Principal"))
                    .andExpect(jsonPath("$.companyId").exists())
                    .andExpect(jsonPath("$.companyCountryId").exists())
                    .andExpect(jsonPath("$.regionId").exists())
                    .andExpect(jsonPath("$.zoneId").exists());
        }

        @Test
        @DisplayName("answers 404 for a store that does not exist or is not reachable")
        void answersNotFoundForAnUnknownStore() throws Exception {
            when(companyStoreService.getStoreById(storeId)).thenThrow(new CompanyStoreNotFoundException(storeId));

            mockMvc.perform(get(BASE_URL + "/{storeId}", storeId)).andExpect(status().isNotFound());
        }
    }
}
