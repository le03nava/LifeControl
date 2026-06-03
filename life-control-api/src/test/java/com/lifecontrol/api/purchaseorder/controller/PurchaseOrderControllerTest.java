package com.lifecontrol.api.purchaseorder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailResponse;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderResponse;
import com.lifecontrol.api.purchaseorder.dto.UpdatePurchaseOrderStatusRequest;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderDetailNotFoundException;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderNotFoundException;
import com.lifecontrol.api.purchaseorder.service.PurchaseOrderService;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseOrderController Tests")
class PurchaseOrderControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock private PurchaseOrderService purchaseOrderService;
    @InjectMocks private PurchaseOrderController controller;

    private UUID poId, detailId, supplierId, productId;
    private PurchaseOrderResponse poResponse;
    private PurchaseOrderDetailResponse detailResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        poId = UUID.randomUUID();
        detailId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        productId = UUID.randomUUID();

        detailResponse = new PurchaseOrderDetailResponse(
                detailId, poId, productId, "Test Product",
                5, new BigDecimal("100.00"), new BigDecimal("500.00"),
                0, "Note", UUID.randomUUID(), "Pending",
                LocalDateTime.now(), LocalDateTime.now()
        );

        poResponse = new PurchaseOrderResponse(
                poId, "PO-20260603-00001",
                supplierId, "Test Supplier",
                UUID.randomUUID(), "Test Store",
                UUID.randomUUID(), "Transfer",
                UUID.randomUUID(), "Draft",
                "Comments", true,
                LocalDateTime.now(), LocalDateTime.now(),
                List.of(detailResponse)
        );
    }

    // ─── GET list ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/purchase-orders")
    class GetAllPurchaseOrdersTests {

        @Test
        @DisplayName("should return 200 with paginated results")
        void returns200WithPage() throws Exception {
            var page = new PageImpl<>(List.of(poResponse), PageRequest.of(0, 10), 1);
            when(purchaseOrderService.getAllPurchaseOrders(any(), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/purchase-orders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].orderNumber").value("PO-20260603-00001"))
                    .andExpect(jsonPath("$.content[0].supplierName").value("Test Supplier"));
        }

        @Test
        @DisplayName("should support search parameter")
        void supportsSearch() throws Exception {
            var page = new PageImpl<>(List.of(poResponse), PageRequest.of(0, 10), 1);
            when(purchaseOrderService.getAllPurchaseOrders(any(), eq("test"))).thenReturn(page);

            mockMvc.perform(get("/api/purchase-orders").param("search", "test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].orderNumber").value("PO-20260603-00001"));
        }
    }

    // ─── GET by ID ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/purchase-orders/{id}")
    class GetPurchaseOrderByIdTests {

        @Test
        @DisplayName("should return 200 with PO")
        void returns200() throws Exception {
            when(purchaseOrderService.getPurchaseOrderById(poId)).thenReturn(poResponse);

            mockMvc.perform(get("/api/purchase-orders/{id}", poId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(poId.toString()))
                    .andExpect(jsonPath("$.supplierName").value("Test Supplier"));
        }

        @Test
        @DisplayName("should return 404 when not found")
        void returns404() throws Exception {
            when(purchaseOrderService.getPurchaseOrderById(poId))
                    .thenThrow(new PurchaseOrderNotFoundException(poId));

            mockMvc.perform(get("/api/purchase-orders/{id}", poId))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── POST create ────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/purchase-orders")
    class CreatePurchaseOrderTests {

        @Test
        @DisplayName("should return 201 Created")
        void returns201() throws Exception {
            var request = new PurchaseOrderRequest(
                    supplierId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "Comments", List.of()
            );
            when(purchaseOrderService.createPurchaseOrder(any(PurchaseOrderRequest.class)))
                    .thenReturn(poResponse);

            mockMvc.perform(post("/api/purchase-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderNumber").value("PO-20260603-00001"));
        }

        @Test
        @DisplayName("should return 400 on validation error")
        void returns400OnValidationError() throws Exception {
            var invalidRequest = new PurchaseOrderRequest(
                    null, null, null, null, null, null
            );

            mockMvc.perform(post("/api/purchase-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when supplier missing")
        void returns404WhenSupplierMissing() throws Exception {
            var request = new PurchaseOrderRequest(
                    supplierId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    null, List.of()
            );
            when(purchaseOrderService.createPurchaseOrder(any(PurchaseOrderRequest.class)))
                    .thenThrow(new SupplierNotFoundException(supplierId));

            mockMvc.perform(post("/api/purchase-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── PUT update ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/purchase-orders/{id}")
    class UpdatePurchaseOrderTests {

        @Test
        @DisplayName("should return 200 OK")
        void returns200() throws Exception {
            var request = new PurchaseOrderRequest(
                    supplierId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "Updated", List.of()
            );
            when(purchaseOrderService.updatePurchaseOrder(eq(poId), any(PurchaseOrderRequest.class)))
                    .thenReturn(poResponse);

            mockMvc.perform(put("/api/purchase-orders/{id}", poId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 404 when PO not found")
        void returns404() throws Exception {
            var request = new PurchaseOrderRequest(
                    supplierId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    null, List.of()
            );
            when(purchaseOrderService.updatePurchaseOrder(eq(poId), any(PurchaseOrderRequest.class)))
                    .thenThrow(new PurchaseOrderNotFoundException(poId));

            mockMvc.perform(put("/api/purchase-orders/{id}", poId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── PATCH status ───────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/purchase-orders/{id}/status")
    class UpdatePurchaseOrderStatusTests {

        @Test
        @DisplayName("should return 200 OK")
        void returns200() throws Exception {
            var statusReq = new UpdatePurchaseOrderStatusRequest(UUID.randomUUID());
            when(purchaseOrderService.updatePurchaseOrderStatus(eq(poId), any()))
                    .thenReturn(poResponse);

            mockMvc.perform(patch("/api/purchase-orders/{id}/status", poId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusReq)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 409 on invalid transition")
        void returns409() throws Exception {
            var statusReq = new UpdatePurchaseOrderStatusRequest(UUID.randomUUID());
            when(purchaseOrderService.updatePurchaseOrderStatus(eq(poId), any()))
                    .thenThrow(new InvalidStatusTransitionException("Sent", "Draft"));

            mockMvc.perform(patch("/api/purchase-orders/{id}/status", poId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusReq)))
                    .andExpect(status().isConflict());
        }
    }

    // ─── DELETE ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/purchase-orders/{id}")
    class DeletePurchaseOrderTests {

        @Test
        @DisplayName("should return 204 No Content")
        void returns204() throws Exception {
            mockMvc.perform(delete("/api/purchase-orders/{id}", poId))
                    .andExpect(status().isNoContent());
        }
    }

    // ─── GET details ────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/purchase-orders/{id}/details")
    class GetDetailsTests {

        @Test
        @DisplayName("should return 200 with details")
        void returns200() throws Exception {
            when(purchaseOrderService.getPurchaseOrderDetails(poId)).thenReturn(List.of(detailResponse));

            mockMvc.perform(get("/api/purchase-orders/{id}/details", poId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].productName").value("Test Product"));
        }
    }

    // ─── POST detail ────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/purchase-orders/{id}/details")
    class AddDetailTests {

        @Test
        @DisplayName("should return 201 Created")
        void returns201() throws Exception {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId, 5, new BigDecimal("100.00"), "Note", UUID.randomUUID()
            );
            when(purchaseOrderService.addPurchaseOrderDetail(eq(poId), any()))
                    .thenReturn(detailResponse);

            mockMvc.perform(post("/api/purchase-orders/{id}/details", poId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(detailReq)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.productName").value("Test Product"));
        }
    }

    // ─── PUT detail ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/purchase-orders/{id}/details/{did}")
    class UpdateDetailTests {

        @Test
        @DisplayName("should return 200 OK")
        void returns200() throws Exception {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId, 10, new BigDecimal("50.00"), "Updated", UUID.randomUUID()
            );
            when(purchaseOrderService.updatePurchaseOrderDetail(eq(poId), eq(detailId), any()))
                    .thenReturn(detailResponse);

            mockMvc.perform(put("/api/purchase-orders/{id}/details/{did}", poId, detailId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(detailReq)))
                    .andExpect(status().isOk());
        }
    }

    // ─── DELETE detail ──────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/purchase-orders/{id}/details/{did}")
    class DeleteDetailTests {

        @Test
        @DisplayName("should return 204 No Content")
        void returns204() throws Exception {
            mockMvc.perform(delete("/api/purchase-orders/{id}/details/{did}", poId, detailId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when detail not found")
        void returns404() throws Exception {
            doThrow(new PurchaseOrderDetailNotFoundException(detailId))
                    .when(purchaseOrderService).deletePurchaseOrderDetail(poId, detailId);

            mockMvc.perform(delete("/api/purchase-orders/{id}/details/{did}", poId, detailId))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── PATCH detail status ────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/purchase-orders/{id}/details/{did}/status")
    class UpdateDetailStatusTests {

        @Test
        @DisplayName("should return 200 OK")
        void returns200() throws Exception {
            var statusReq = new UpdatePurchaseOrderStatusRequest(UUID.randomUUID());
            when(purchaseOrderService.updatePurchaseOrderDetailStatus(eq(poId), eq(detailId), any()))
                    .thenReturn(detailResponse);

            mockMvc.perform(patch("/api/purchase-orders/{id}/details/{did}/status", poId, detailId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusReq)))
                    .andExpect(status().isOk());
        }
    }
}
