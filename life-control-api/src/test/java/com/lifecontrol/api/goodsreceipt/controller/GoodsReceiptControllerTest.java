package com.lifecontrol.api.goodsreceipt.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptLineRequest;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptLineResponse;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptRequest;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptResponse;
import com.lifecontrol.api.goodsreceipt.exception.GoodsReceiptNotFoundException;
import com.lifecontrol.api.goodsreceipt.service.GoodsReceiptService;
import java.math.BigDecimal;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Controller-level verification of the goods receipt endpoints: paths, serialized contract and errors. */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoodsReceiptController Tests")
class GoodsReceiptControllerTest {

    private static final String BASE_URL = "/api/goods-receipts";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private GoodsReceiptService goodsReceiptService;

    @InjectMocks
    private GoodsReceiptController controller;

    private UUID receiptId;
    private UUID purchaseOrderId;
    private UUID detailId;
    private UUID variantId;
    private UUID storeId;
    private UUID locationId;
    private UUID statusId;
    private GoodsReceiptResponse receiptResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        receiptId = UUID.randomUUID();
        purchaseOrderId = UUID.randomUUID();
        detailId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        statusId = UUID.randomUUID();

        var line = new GoodsReceiptLineResponse(detailId, detailId, variantId, new BigDecimal("3.00"), "Received line");
        receiptResponse = new GoodsReceiptResponse(
                receiptId,
                "GR-PO-20260603-00001-01",
                purchaseOrderId,
                "PO-20260603-00001",
                storeId,
                locationId,
                statusId,
                "Registered",
                "receiver",
                LocalDateTime.now(),
                "Reception",
                true,
                List.of(line));
    }

    private GoodsReceiptRequest validRequest() {
        return new GoodsReceiptRequest(
                purchaseOrderId,
                locationId,
                "Reception",
                List.of(new GoodsReceiptLineRequest(detailId, BigDecimal.ONE, null)));
    }

    // ─── POST create ────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateReceiptTests {

        @Test
        @DisplayName("should return 201 Created with the created receipt")
        void returns201() throws Exception {
            when(goodsReceiptService.createReceipt(any(GoodsReceiptRequest.class)))
                    .thenReturn(receiptResponse);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(receiptId.toString()))
                    .andExpect(jsonPath("$.receiptNumber").value("GR-PO-20260603-00001-01"))
                    .andExpect(jsonPath("$.orderNumber").value("PO-20260603-00001"))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.statusName").value("Registered"))
                    .andExpect(jsonPath("$.lines[0].quantityReceived").value(3.0));
        }

        @Test
        @DisplayName("should return 400 when purchaseOrderId is missing")
        void returns400WhenPurchaseOrderIdMissing() throws Exception {
            var payload = "{\"comments\":\"Reception\",\"lines\":[{\"purchaseOrderDetailId\":\"" + detailId
                    + "\",\"quantityReceived\":1}]}";

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when lines is empty")
        void returns400WhenLinesEmpty() throws Exception {
            var payload = "{\"purchaseOrderId\":\"" + purchaseOrderId + "\",\"lines\":[]}";

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when a line quantity is not positive")
        void returns400WhenQuantityNotPositive() throws Exception {
            var payload = "{\"purchaseOrderId\":\"" + purchaseOrderId + "\",\"lines\":[{\"purchaseOrderDetailId\":\""
                    + detailId + "\",\"quantityReceived\":0}]}";

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─── GET list ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllReceiptsTests {

        @Test
        @DisplayName("should return 200 with the paged response shape")
        void returns200WithPage() throws Exception {
            var page = new PageImpl<>(List.of(receiptResponse), PageRequest.of(0, 12), 1);
            when(goodsReceiptService.getAllReceipts(any(), eq(null))).thenReturn(page);

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].receiptNumber").value("GR-PO-20260603-00001-01"))
                    .andExpect(jsonPath("$.content[0].orderNumber").value("PO-20260603-00001"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));
        }

        @Test
        @DisplayName("should forward the search parameter")
        void forwardsSearch() throws Exception {
            var page = new PageImpl<>(List.of(receiptResponse), PageRequest.of(0, 12), 1);
            when(goodsReceiptService.getAllReceipts(any(), eq("PO-2026"))).thenReturn(page);

            mockMvc.perform(get(BASE_URL).param("search", "PO-2026"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].receiptNumber").value("GR-PO-20260603-00001-01"));
        }
    }

    // ─── GET by id ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetReceiptByIdTests {

        @Test
        @DisplayName("should return 200 with the receipt and its lines")
        void returns200() throws Exception {
            when(goodsReceiptService.getReceipt(receiptId)).thenReturn(receiptResponse);

            mockMvc.perform(get(BASE_URL + "/{id}", receiptId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(receiptId.toString()))
                    .andExpect(jsonPath("$.lines[0].purchaseOrderDetailId").value(detailId.toString()))
                    .andExpect(jsonPath("$.lines[0].productVariantId").value(variantId.toString()));
        }

        @Test
        @DisplayName("should return 404 when the receipt is unknown")
        void returns404() throws Exception {
            when(goodsReceiptService.getReceipt(receiptId)).thenThrow(new GoodsReceiptNotFoundException(receiptId));

            mockMvc.perform(get(BASE_URL + "/{id}", receiptId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Goods receipt not found with id: " + receiptId));
        }
    }
}
