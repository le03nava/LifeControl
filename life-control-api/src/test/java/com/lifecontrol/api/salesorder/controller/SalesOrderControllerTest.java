package com.lifecontrol.api.salesorder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.salesorder.dto.ChargeSalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemResponse;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderResponse;
import com.lifecontrol.api.salesorder.dto.UpdateSalesOrderStatusRequest;
import com.lifecontrol.api.salesorder.exception.InvalidSalesOrderChargeException;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.salesorder.exception.InsufficientStockException;
import com.lifecontrol.api.salesorder.exception.SalesOrderAlreadyFinalizedException;
import com.lifecontrol.api.salesorder.exception.SalesOrderItemNotFoundException;
import com.lifecontrol.api.salesorder.exception.SalesOrderNotFoundException;
import com.lifecontrol.api.salesorder.service.SalesOrderService;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("SalesOrderController Tests")
class SalesOrderControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private SalesOrderService salesOrderService;

    @InjectMocks
    private SalesOrderController salesOrderController;

    private SalesOrderResponse testOrderResponse;
    private SalesOrderRequest testOrderRequest;
    private SalesOrderItemResponse testItemResponse;
    private SalesOrderItemRequest testItemRequest;
    private UpdateSalesOrderStatusRequest testStatusRequest;
    private UUID testOrderId;
    private UUID testItemId;
    private UUID testStatusId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(salesOrderController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testOrderId = UUID.randomUUID();
        testItemId = UUID.randomUUID();
        testStatusId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testItemResponse = new SalesOrderItemResponse(
                testItemId,
                testOrderId,
                UUID.randomUUID(),
                new BigDecimal("2.00"),
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                new BigDecimal("90.00"),
                null,
                UUID.randomUUID(),
                "Pending",
                now,
                now
        );

        testOrderResponse = new SalesOrderResponse(
                testOrderId,
                "SO-20260610-00001",
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user123",
                now,
                testStatusId,
                "Draft",
                BigDecimal.ZERO,
                null,
                true,
                now,
                now,
                List.of()
        );

        testOrderRequest = new SalesOrderRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user123",
                null
        );

        testItemRequest = new SalesOrderItemRequest(
                null,
                UUID.randomUUID(),
                new BigDecimal("2.00"),
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                null
        );

        testStatusRequest = new UpdateSalesOrderStatusRequest(testStatusId);
    }

    // ─────────────────────────────────────────────
    // GET /api/sales-orders
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/sales-orders")
    class GetAllSalesOrdersTests {

        @Test
        @DisplayName("should return 200 with paginated sales orders without search")
        void getAllSalesOrders_Paginated() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var orders = List.of(testOrderResponse);
            var page = new PageImpl<>(orders, pageable, 1);

            when(salesOrderService.getAllSalesOrders(any(Pageable.class), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/sales-orders")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id").value(testOrderId.toString()))
                    .andExpect(jsonPath("$.content[0].orderNumber").value("SO-20260610-00001"))
                    .andExpect(jsonPath("$.content[0].statusName").value("Draft"))
                    .andExpect(jsonPath("$.content[0].totalAmount").value(0))
                    .andExpect(jsonPath("$.content[0].enabled").value(true))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));
        }

        @Test
        @DisplayName("should return 200 and filter by search term on orderNumber")
        void getAllSalesOrders_WithSearch() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var orders = List.of(testOrderResponse);
            var page = new PageImpl<>(orders, pageable, 1);

            when(salesOrderService.getAllSalesOrders(any(Pageable.class), eq("SO-20260610"))).thenReturn(page);

            mockMvc.perform(get("/api/sales-orders")
                            .param("page", "0")
                            .param("size", "12")
                            .param("search", "SO-20260610"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].orderNumber").value("SO-20260610-00001"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("should return 200 with empty page when no sales orders match")
        void getAllSalesOrders_EmptyPage() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<SalesOrderResponse>(List.of(), pageable, 0);

            when(salesOrderService.getAllSalesOrders(any(Pageable.class), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/sales-orders")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/sales-orders/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/sales-orders/{id}")
    class GetSalesOrderByIdTests {

        @Test
        @DisplayName("should return 200 with sales order when found")
        void getSalesOrderById_Found_Returns200() throws Exception {
            var responseWithItems = new SalesOrderResponse(
                    testOrderId,
                    "SO-20260610-00001",
                    testOrderResponse.customerId(),
                    testOrderResponse.companyStoreId(),
                    testOrderResponse.shiftId(),
                    "user123",
                    testOrderResponse.orderDate(),
                    testStatusId,
                    "Draft",
                    new BigDecimal("180.00"),
                    null,
                    true,
                    testOrderResponse.createdAt(),
                    testOrderResponse.updatedAt(),
                    List.of(testItemResponse)
            );

            when(salesOrderService.getSalesOrderById(testOrderId)).thenReturn(responseWithItems);

            mockMvc.perform(get("/api/sales-orders/{id}", testOrderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testOrderId.toString()))
                    .andExpect(jsonPath("$.orderNumber").value("SO-20260610-00001"))
                    .andExpect(jsonPath("$.statusName").value("Draft"))
                    .andExpect(jsonPath("$.totalAmount").value(180.00))
                    .andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(testItemId.toString()))
                    .andExpect(jsonPath("$.items[0].finalPrice").value(90.00))
                    .andExpect(jsonPath("$.items[0].statusName").value("Pending"));
        }

        @Test
        @DisplayName("should return 404 when sales order not found")
        void getSalesOrderById_NotFound_Returns404() throws Exception {
            when(salesOrderService.getSalesOrderById(testOrderId))
                    .thenThrow(new SalesOrderNotFoundException(testOrderId));

            mockMvc.perform(get("/api/sales-orders/{id}", testOrderId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Sales order not found with id: " + testOrderId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/sales-orders
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/sales-orders")
    class CreateSalesOrderTests {

        @Test
        @DisplayName("should return 201 with created sales order when valid request")
        void createSalesOrder_ValidRequest_Returns201() throws Exception {
            when(salesOrderService.createSalesOrder(any(SalesOrderRequest.class)))
                    .thenReturn(testOrderResponse);

            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testOrderRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(testOrderId.toString()))
                    .andExpect(jsonPath("$.orderNumber").value("SO-20260610-00001"))
                    .andExpect(jsonPath("$.statusName").value("Draft"))
                    .andExpect(jsonPath("$.totalAmount").value(0))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when required companyStoreId is missing")
        void createSalesOrder_MissingRequiredFields_Returns400() throws Exception {
            var invalidRequest = new SalesOrderRequest(null, null, null, null, null);

            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.companyStoreId").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 404 when referenced FK entity not found")
        void createSalesOrder_FkNotFound_Returns404() throws Exception {
            when(salesOrderService.createSalesOrder(any(SalesOrderRequest.class)))
                    .thenThrow(new SalesOrderNotFoundException(testOrderId));

            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testOrderRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Sales order not found with id: " + testOrderId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PUT /api/sales-orders/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/sales-orders/{id}")
    class UpdateSalesOrderTests {

        @Test
        @DisplayName("should return 200 with updated sales order")
        void updateSalesOrder_Success_Returns200() throws Exception {
            when(salesOrderService.updateSalesOrder(eq(testOrderId), any(SalesOrderRequest.class)))
                    .thenReturn(testOrderResponse);

            mockMvc.perform(put("/api/sales-orders/{id}", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testOrderRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testOrderId.toString()))
                    .andExpect(jsonPath("$.orderNumber").value("SO-20260610-00001"))
                    .andExpect(jsonPath("$.statusName").value("Draft"));
        }

        @Test
        @DisplayName("should return 404 when sales order not found")
        void updateSalesOrder_NotFound_Returns404() throws Exception {
            when(salesOrderService.updateSalesOrder(eq(testOrderId), any(SalesOrderRequest.class)))
                    .thenThrow(new SalesOrderNotFoundException(testOrderId));

            mockMvc.perform(put("/api/sales-orders/{id}", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testOrderRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Sales order not found with id: " + testOrderId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PATCH /api/sales-orders/{id}/enable
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PATCH /api/sales-orders/{id}/enable")
    class EnableSalesOrderTests {

        @Test
        @DisplayName("should return 200 with re-enabled sales order")
        void enableSalesOrder_Success_Returns200() throws Exception {
            when(salesOrderService.enableSalesOrder(testOrderId)).thenReturn(testOrderResponse);

            mockMvc.perform(patch("/api/sales-orders/{id}/enable", testOrderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testOrderId.toString()))
                    .andExpect(jsonPath("$.enabled").value(true));
        }
    }

    // ─────────────────────────────────────────────
    // PATCH /api/sales-orders/{id}/status
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PATCH /api/sales-orders/{id}/status (Draft → Active → Pending → Completed/Cancelled)")
    class UpdateSalesOrderStatusTests {

        @Test
        @DisplayName("should return 200 on valid status transition")
        void updateStatus_ValidTransition_Returns200() throws Exception {
            var updatedResponse = new SalesOrderResponse(
                    testOrderId,
                    "SO-20260610-00001",
                    testOrderResponse.customerId(),
                    testOrderResponse.companyStoreId(),
                    testOrderResponse.shiftId(),
                    "user123",
                    testOrderResponse.orderDate(),
                    testStatusId,
                    "Pending",
                    BigDecimal.ZERO,
                    null,
                    true,
                    testOrderResponse.createdAt(),
                    testOrderResponse.updatedAt(),
                    List.of()
            );

            when(salesOrderService.updateSalesOrderStatus(eq(testOrderId), any(UpdateSalesOrderStatusRequest.class)))
                    .thenReturn(updatedResponse);

            mockMvc.perform(patch("/api/sales-orders/{id}/status", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testStatusRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testOrderId.toString()))
                    .andExpect(jsonPath("$.statusName").value("Pending"));
        }

        @Test
        @DisplayName("should return 409 on invalid status transition")
        void updateStatus_InvalidTransition_Returns409() throws Exception {
            when(salesOrderService.updateSalesOrderStatus(eq(testOrderId), any(UpdateSalesOrderStatusRequest.class)))
                    .thenThrow(new InvalidStatusTransitionException("Completed", "Draft"));

            mockMvc.perform(patch("/api/sales-orders/{id}/status", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testStatusRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Transición de estado inválida: Completed → Draft"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PATCH /api/sales-orders/{id}/charge
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PATCH /{id}/charge (Active auto-promotes to Pending)")
    class ChargeSalesOrderTests {

        private ChargeSalesOrderRequest chargeRequest;
        private UUID paymentMethodId;

        @BeforeEach
        void setUpCharge() {
            paymentMethodId = UUID.randomUUID();
            chargeRequest = new ChargeSalesOrderRequest(paymentMethodId);
        }

        @Test
        @DisplayName("should return 200 when charge is successful")
        void chargeSalesOrder_Success_Returns200() throws Exception {
            var chargedResponse = new SalesOrderResponse(
                    testOrderId,
                    "SO-20260610-00001",
                    testOrderResponse.customerId(),
                    testOrderResponse.companyStoreId(),
                    testOrderResponse.shiftId(),
                    "user123",
                    testOrderResponse.orderDate(),
                    testStatusId,
                    "Completed",
                    BigDecimal.ZERO,
                    paymentMethodId,
                    true,
                    testOrderResponse.createdAt(),
                    testOrderResponse.updatedAt(),
                    List.of()
            );

            when(salesOrderService.chargeSalesOrder(eq(testOrderId), any(ChargeSalesOrderRequest.class)))
                    .thenReturn(chargedResponse);

            mockMvc.perform(patch("/api/sales-orders/{id}/charge", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(chargeRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testOrderId.toString()))
                    .andExpect(jsonPath("$.statusName").value("Completed"))
                    .andExpect(jsonPath("$.paymentMethodId").value(paymentMethodId.toString()));
        }

        @Test
        @DisplayName("should return 200 when charging an Active order (auto-promotes to Pending)")
        void chargeSalesOrder_ActiveOrder_Returns200() throws Exception {
            var chargedResponse = new SalesOrderResponse(
                    testOrderId,
                    "SO-20260610-00001",
                    testOrderResponse.customerId(),
                    testOrderResponse.companyStoreId(),
                    testOrderResponse.shiftId(),
                    "user123",
                    testOrderResponse.orderDate(),
                    testStatusId,
                    "Completed",
                    BigDecimal.ZERO,
                    paymentMethodId,
                    true,
                    testOrderResponse.createdAt(),
                    testOrderResponse.updatedAt(),
                    List.of()
            );

            when(salesOrderService.chargeSalesOrder(eq(testOrderId), any(ChargeSalesOrderRequest.class)))
                    .thenReturn(chargedResponse);

            mockMvc.perform(patch("/api/sales-orders/{id}/charge", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(chargeRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testOrderId.toString()))
                    .andExpect(jsonPath("$.statusName").value("Completed"))
                    .andExpect(jsonPath("$.paymentMethodId").value(paymentMethodId.toString()));
        }

        @Test
        @DisplayName("should return 400 when order is not in Pending or Active status")
        void chargeSalesOrder_NotPendingOrActive_Returns400() throws Exception {
            when(salesOrderService.chargeSalesOrder(eq(testOrderId), any(ChargeSalesOrderRequest.class)))
                    .thenThrow(new InvalidSalesOrderChargeException(testOrderId, "Draft"));

            mockMvc.perform(patch("/api/sales-orders/{id}/charge", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(chargeRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message")
                            .value("Cannot charge sales order " + testOrderId + ": current status is Draft, expected Pending"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 404 when payment method is not found")
        void chargeSalesOrder_InvalidPaymentMethod_Returns404() throws Exception {
            when(salesOrderService.chargeSalesOrder(eq(testOrderId), any(ChargeSalesOrderRequest.class)))
                    .thenThrow(new PaymentMethodNotFoundException(paymentMethodId));

            mockMvc.perform(patch("/api/sales-orders/{id}/charge", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(chargeRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Payment method not found with id: " + paymentMethodId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // DELETE /api/sales-orders/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/sales-orders/{id}")
    class DeleteSalesOrderTests {

        @Test
        @DisplayName("should return 204 on successful soft delete")
        void deleteSalesOrder_Success_Returns204() throws Exception {
            doNothing().when(salesOrderService).deleteSalesOrder(testOrderId);

            mockMvc.perform(delete("/api/sales-orders/{id}", testOrderId))
                    .andExpect(status().isNoContent());
            verify(salesOrderService).deleteSalesOrder(testOrderId);
        }

        @Test
        @DisplayName("should return 404 when sales order not found")
        void deleteSalesOrder_NotFound_Returns404() throws Exception {
            doThrow(new SalesOrderNotFoundException(testOrderId))
                    .when(salesOrderService).deleteSalesOrder(testOrderId);

            mockMvc.perform(delete("/api/sales-orders/{id}", testOrderId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Sales order not found with id: " + testOrderId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/sales-orders/{id}/items
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/sales-orders/{id}/items")
    class GetSalesOrderItemsTests {

        @Test
        @DisplayName("should return 200 with items list")
        void getSalesOrderItems_Returns200() throws Exception {
            var items = List.of(testItemResponse);
            when(salesOrderService.getSalesOrderItems(testOrderId)).thenReturn(items);

            mockMvc.perform(get("/api/sales-orders/{id}/items", testOrderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].id").value(testItemId.toString()))
                    .andExpect(jsonPath("$[0].finalPrice").value(90.00))
                    .andExpect(jsonPath("$[0].quantity").value(2.00))
                    .andExpect(jsonPath("$[0].statusName").value("Pending"));
        }

        @Test
        @DisplayName("should return 404 when sales order not found")
        void getSalesOrderItems_OrderNotFound_Returns404() throws Exception {
            when(salesOrderService.getSalesOrderItems(testOrderId))
                    .thenThrow(new SalesOrderNotFoundException(testOrderId));

            mockMvc.perform(get("/api/sales-orders/{id}/items", testOrderId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Sales order not found with id: " + testOrderId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/sales-orders/{id}/items
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/sales-orders/{id}/items (allowed in Draft and Active)")
    class AddSalesOrderItemTests {

        @Test
        @DisplayName("should return 201 with created item when valid request")
        void addItem_ValidRequest_Returns201() throws Exception {
            when(salesOrderService.addSalesOrderItem(eq(testOrderId), any(SalesOrderItemRequest.class)))
                    .thenReturn(testItemResponse);

            mockMvc.perform(post("/api/sales-orders/{id}/items", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testItemRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(testItemId.toString()))
                    .andExpect(jsonPath("$.finalPrice").value(90.00))
                    .andExpect(jsonPath("$.quantity").value(2.00))
                    .andExpect(jsonPath("$.statusName").value("Pending"));
        }

        @Test
        @DisplayName("should return 400 when required fields are missing")
        void addItem_MissingRequiredFields_Returns400() throws Exception {
            var invalidRequest = new SalesOrderItemRequest(null, null, null, null, null, null);

            mockMvc.perform(post("/api/sales-orders/{id}/items", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.productVariantId").exists())
                    .andExpect(jsonPath("$.errors.quantity").exists())
                    .andExpect(jsonPath("$.errors.listPrice").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 404 when sales order not found")
        void addItem_OrderNotFound_Returns404() throws Exception {
            when(salesOrderService.addSalesOrderItem(eq(testOrderId), any(SalesOrderItemRequest.class)))
                    .thenThrow(new SalesOrderNotFoundException(testOrderId));

            mockMvc.perform(post("/api/sales-orders/{id}/items", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testItemRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Sales order not found with id: " + testOrderId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 409 when sales order is not in Draft or Active status")
        void addItem_NotDraftOrActive_Returns409() throws Exception {
            when(salesOrderService.addSalesOrderItem(eq(testOrderId), any(SalesOrderItemRequest.class)))
                    .thenThrow(new SalesOrderAlreadyFinalizedException(testOrderId, "Completed"));

            mockMvc.perform(post("/api/sales-orders/{id}/items", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testItemRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(
                            "Sales order " + testOrderId + " is already Completed and cannot be modified"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 409 when product variant has insufficient stock")
        void addItem_InsufficientStock_Returns409() throws Exception {
            var variantId = UUID.randomUUID();
            var requested = new BigDecimal("5.00");
            var available = new BigDecimal("3.00");

            when(salesOrderService.addSalesOrderItem(eq(testOrderId), any(SalesOrderItemRequest.class)))
                    .thenThrow(new InsufficientStockException(variantId, requested, available));

            mockMvc.perform(post("/api/sales-orders/{id}/items", testOrderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testItemRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(
                            "Insufficient stock for variant %s: requested 5.00, available 3.00"
                                    .formatted(variantId)))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PUT /api/sales-orders/{id}/items/{itemId}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/sales-orders/{id}/items/{itemId} (allowed in Draft and Active)")
    class UpdateSalesOrderItemTests {

        @Test
        @DisplayName("should return 200 with updated item")
        void updateItem_Success_Returns200() throws Exception {
            var updatedItem = new SalesOrderItemResponse(
                    testItemId,
                    testOrderId,
                    testItemResponse.productVariantId(),
                    new BigDecimal("5.00"),
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    new BigDecimal("100.00"),
                    null,
                    testItemResponse.statusId(),
                    "Pending",
                    testItemResponse.createdAt(),
                    LocalDateTime.now()
            );

            when(salesOrderService.updateSalesOrderItem(eq(testOrderId), eq(testItemId), any(SalesOrderItemRequest.class)))
                    .thenReturn(updatedItem);

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", testOrderId, testItemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testItemRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testItemId.toString()))
                    .andExpect(jsonPath("$.finalPrice").value(100.00))
                    .andExpect(jsonPath("$.quantity").value(5.00));
        }

        @Test
        @DisplayName("should return 404 when item not found")
        void updateItem_NotFound_Returns404() throws Exception {
            when(salesOrderService.updateSalesOrderItem(eq(testOrderId), eq(testItemId), any(SalesOrderItemRequest.class)))
                    .thenThrow(new SalesOrderItemNotFoundException(testItemId));

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", testOrderId, testItemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testItemRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Sales order item not found with id: " + testItemId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // DELETE /api/sales-orders/{id}/items/{itemId}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/sales-orders/{id}/items/{itemId} (allowed in Draft and Active)")
    class DeleteSalesOrderItemTests {

        @Test
        @DisplayName("should return 204 on successful soft delete")
        void deleteItem_Success_Returns204() throws Exception {
            doNothing().when(salesOrderService).deleteSalesOrderItem(testOrderId, testItemId);

            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", testOrderId, testItemId))
                    .andExpect(status().isNoContent());
            verify(salesOrderService).deleteSalesOrderItem(testOrderId, testItemId);
        }

        @Test
        @DisplayName("should return 404 when item not found")
        void deleteItem_NotFound_Returns404() throws Exception {
            doThrow(new SalesOrderItemNotFoundException(testItemId))
                    .when(salesOrderService).deleteSalesOrderItem(testOrderId, testItemId);

            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", testOrderId, testItemId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Sales order item not found with id: " + testItemId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PATCH /api/sales-orders/{id}/items/{itemId}/status
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PATCH /api/sales-orders/{id}/items/{itemId}/status")
    class UpdateSalesOrderItemStatusTests {

        @Test
        @DisplayName("should return 200 on valid status transition")
        void updateItemStatus_ValidTransition_Returns200() throws Exception {
            var updatedItem = new SalesOrderItemResponse(
                    testItemId,
                    testOrderId,
                    testItemResponse.productVariantId(),
                    testItemResponse.quantity(),
                    testItemResponse.listPrice(),
                    testItemResponse.discountApplied(),
                    testItemResponse.finalPrice(),
                    null,
                    UUID.randomUUID(),
                    "Added",
                    testItemResponse.createdAt(),
                    LocalDateTime.now()
            );

            when(salesOrderService.updateSalesOrderItemStatus(
                    eq(testOrderId), eq(testItemId), any(UpdateSalesOrderStatusRequest.class)))
                    .thenReturn(updatedItem);

            mockMvc.perform(patch("/api/sales-orders/{id}/items/{itemId}/status", testOrderId, testItemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testStatusRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testItemId.toString()))
                    .andExpect(jsonPath("$.statusName").value("Added"));
        }

        @Test
        @DisplayName("should return 409 on invalid status transition")
        void updateItemStatus_InvalidTransition_Returns409() throws Exception {
            when(salesOrderService.updateSalesOrderItemStatus(
                    eq(testOrderId), eq(testItemId), any(UpdateSalesOrderStatusRequest.class)))
                    .thenThrow(new InvalidStatusTransitionException("Cancelled", "Pending"));

            mockMvc.perform(patch("/api/sales-orders/{id}/items/{itemId}/status", testOrderId, testItemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testStatusRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Transición de estado inválida: Cancelled → Pending"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }
}
