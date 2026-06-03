package com.lifecontrol.api.paymentmethod.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.paymentmethod.dto.PaymentMethodRequest;
import com.lifecontrol.api.paymentmethod.dto.PaymentMethodResponse;
import com.lifecontrol.api.paymentmethod.exception.DuplicatePaymentMethodException;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.service.PaymentMethodService;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentMethodController Tests")
class PaymentMethodControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private PaymentMethodService paymentMethodService;

    @InjectMocks
    private PaymentMethodController paymentMethodController;

    private PaymentMethodResponse testPaymentMethodResponse;
    private UUID testPaymentMethodId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentMethodController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testPaymentMethodId = UUID.randomUUID();
        testPaymentMethodResponse = new PaymentMethodResponse(
                testPaymentMethodId, "Efectivo", "EFECTIVO", true,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("GET /api/payment-methods")
    class GetAllPaymentMethodsTests {

        @Test
        @DisplayName("should return 200 with list of payment methods")
        void getAllPaymentMethods_Returns200() throws Exception {
            when(paymentMethodService.getAllPaymentMethods())
                    .thenReturn(List.of(testPaymentMethodResponse));

            mockMvc.perform(get("/api/payment-methods"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].paymentMethodName").value("Efectivo"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("should return 200 with empty list when no payment methods")
        void getAllPaymentMethods_EmptyList_Returns200() throws Exception {
            when(paymentMethodService.getAllPaymentMethods())
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/payment-methods"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /api/payment-methods/{id}")
    class GetPaymentMethodByIdTests {

        @Test
        @DisplayName("should return 200 when payment method exists")
        void getPaymentMethodById_Returns200() throws Exception {
            when(paymentMethodService.getPaymentMethodById(testPaymentMethodId))
                    .thenReturn(testPaymentMethodResponse);

            mockMvc.perform(get("/api/payment-methods/{id}", testPaymentMethodId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentMethodName").value("Efectivo"));
        }

        @Test
        @DisplayName("should return 404 when payment method not found")
        void getPaymentMethodById_NotFound_Returns404() throws Exception {
            when(paymentMethodService.getPaymentMethodById(testPaymentMethodId))
                    .thenThrow(new PaymentMethodNotFoundException(testPaymentMethodId));

            mockMvc.perform(get("/api/payment-methods/{id}", testPaymentMethodId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/payment-methods")
    class CreatePaymentMethodTests {

        @Test
        @DisplayName("should return 201 when created successfully")
        void createPaymentMethod_Returns201() throws Exception {
            var request = new PaymentMethodRequest("Efectivo", "EFECTIVO", true);
            when(paymentMethodService.createPaymentMethod(any(PaymentMethodRequest.class)))
                    .thenReturn(testPaymentMethodResponse);

            mockMvc.perform(post("/api/payment-methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.paymentMethodName").value("Efectivo"));
        }

        @Test
        @DisplayName("should return 400 when request body is invalid")
        void createPaymentMethod_InvalidBody_Returns400() throws Exception {
            var invalidRequest = new PaymentMethodRequest("", "", true);

            mockMvc.perform(post("/api/payment-methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 409 when name already exists")
        void createPaymentMethod_DuplicateName_Returns409() throws Exception {
            var request = new PaymentMethodRequest("Efectivo", "EFECTIVO", true);
            when(paymentMethodService.createPaymentMethod(any(PaymentMethodRequest.class)))
                    .thenThrow(new DuplicatePaymentMethodException("Efectivo"));

            mockMvc.perform(post("/api/payment-methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/payment-methods/{id}")
    class UpdatePaymentMethodTests {

        @Test
        @DisplayName("should return 200 when updated successfully")
        void updatePaymentMethod_Returns200() throws Exception {
            var request = new PaymentMethodRequest("Transferencia", "TRANSFERENCIA", true);
            var updatedResponse = new PaymentMethodResponse(
                    testPaymentMethodId, "Transferencia", "TRANSFERENCIA", true,
                    LocalDateTime.now(), LocalDateTime.now());
            when(paymentMethodService.updatePaymentMethod(eq(testPaymentMethodId), any(PaymentMethodRequest.class)))
                    .thenReturn(updatedResponse);

            mockMvc.perform(put("/api/payment-methods/{id}", testPaymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentMethodName").value("Transferencia"));
        }

        @Test
        @DisplayName("should return 404 when payment method not found")
        void updatePaymentMethod_NotFound_Returns404() throws Exception {
            var request = new PaymentMethodRequest("Efectivo", "EFECTIVO", true);
            when(paymentMethodService.updatePaymentMethod(eq(testPaymentMethodId), any(PaymentMethodRequest.class)))
                    .thenThrow(new PaymentMethodNotFoundException(testPaymentMethodId));

            mockMvc.perform(put("/api/payment-methods/{id}", testPaymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when name conflicts with another payment method")
        void updatePaymentMethod_DuplicateName_Returns409() throws Exception {
            var request = new PaymentMethodRequest("Transferencia", "TRANSFERENCIA", true);
            when(paymentMethodService.updatePaymentMethod(eq(testPaymentMethodId), any(PaymentMethodRequest.class)))
                    .thenThrow(new DuplicatePaymentMethodException("Transferencia"));

            mockMvc.perform(put("/api/payment-methods/{id}", testPaymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("DELETE /api/payment-methods/{id}")
    class DeletePaymentMethodTests {

        @Test
        @DisplayName("should return 204 when deleted successfully")
        void deletePaymentMethod_Returns204() throws Exception {
            mockMvc.perform(delete("/api/payment-methods/{id}", testPaymentMethodId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when payment method not found")
        void deletePaymentMethod_NotFound_Returns404() throws Exception {
            doThrow(new PaymentMethodNotFoundException(testPaymentMethodId))
                    .when(paymentMethodService).deletePaymentMethod(testPaymentMethodId);

            mockMvc.perform(delete("/api/payment-methods/{id}", testPaymentMethodId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/payment-methods/{id}/enable")
    class SetPaymentMethodEnabledTests {

        @Test
        @DisplayName("should return 200 when enabled successfully")
        void setPaymentMethodEnabled_Returns200() throws Exception {
            when(paymentMethodService.setPaymentMethodEnabled(eq(testPaymentMethodId), eq(true)))
                    .thenReturn(testPaymentMethodResponse);

            mockMvc.perform(patch("/api/payment-methods/{id}/enable", testPaymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentMethodName").value("Efectivo"));
        }

        @Test
        @DisplayName("should return 200 when disabled successfully")
        void setPaymentMethodEnabled_Disable_Returns200() throws Exception {
            var disabledResponse = new PaymentMethodResponse(
                    testPaymentMethodId, "Efectivo", "EFECTIVO", false,
                    LocalDateTime.now(), LocalDateTime.now());
            when(paymentMethodService.setPaymentMethodEnabled(eq(testPaymentMethodId), eq(false)))
                    .thenReturn(disabledResponse);

            mockMvc.perform(patch("/api/payment-methods/{id}/enable", testPaymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));
        }

        @Test
        @DisplayName("should return 404 when payment method not found")
        void setPaymentMethodEnabled_NotFound_Returns404() throws Exception {
            when(paymentMethodService.setPaymentMethodEnabled(eq(testPaymentMethodId), eq(true)))
                    .thenThrow(new PaymentMethodNotFoundException(testPaymentMethodId));

            mockMvc.perform(patch("/api/payment-methods/{id}/enable", testPaymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isNotFound());
        }
    }
}
