package com.lifecontrol.api.promotion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.promotion.dto.PromotionRequest;
import com.lifecontrol.api.promotion.dto.PromotionResponse;
import com.lifecontrol.api.promotion.exception.PromotionNotFoundException;
import com.lifecontrol.api.promotion.service.PromotionService;
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
@DisplayName("PromotionController Tests")
class PromotionControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private PromotionService promotionService;

    @InjectMocks
    private PromotionController promotionController;

    private PromotionResponse testPromotionResponse;
    private PromotionRequest testPromotionRequest;
    private UUID testPromotionId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(promotionController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testPromotionId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testPromotionResponse = new PromotionResponse(
                testPromotionId,
                "Summer Sale",
                "PERCENTAGE",
                new BigDecimal("15.00"),
                "SUMMER15",
                now.minusDays(1),
                now.plusDays(30),
                "TIENDA",
                new BigDecimal("100.00"),
                true,
                now,
                now
        );

        testPromotionRequest = new PromotionRequest(
                "Summer Sale",
                "PERCENTAGE",
                new BigDecimal("15.00"),
                "SUMMER15",
                now.minusDays(1),
                now.plusDays(30),
                "TIENDA",
                new BigDecimal("100.00"),
                true
        );
    }

    // ─────────────────────────────────────────────
    // GET /api/promotions
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/promotions")
    class GetAllPromotionsTests {

        @Test
        @DisplayName("should return 200 with paginated promotions")
        void getAllPromotions_Paginated() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var promotions = List.of(testPromotionResponse);
            var page = new PageImpl<>(promotions, pageable, 1);

            when(promotionService.getAllPromotions(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/promotions")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id").value(testPromotionId.toString()))
                    .andExpect(jsonPath("$.content[0].promotionName").value("Summer Sale"))
                    .andExpect(jsonPath("$.content[0].discountType").value("PERCENTAGE"))
                    .andExpect(jsonPath("$.content[0].discountValue").value(15.00))
                    .andExpect(jsonPath("$.content[0].couponCode").value("SUMMER15"))
                    .andExpect(jsonPath("$.content[0].salesChannel").value("TIENDA"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));
        }

        @Test
        @DisplayName("should return 200 with empty page when no promotions exist")
        void getAllPromotions_EmptyPage() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<PromotionResponse>(List.of(), pageable, 0);

            when(promotionService.getAllPromotions(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/promotions")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/promotions/active
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/promotions/active")
    class GetActivePromotionsTests {

        @Test
        @DisplayName("should return 200 with active promotions for channel")
        void getActivePromotions_WithChannel() throws Exception {
            var activePromotions = List.of(testPromotionResponse);

            when(promotionService.findActivePromotions("TIENDA")).thenReturn(activePromotions);

            mockMvc.perform(get("/api/promotions/active")
                            .param("channel", "TIENDA"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].id").value(testPromotionId.toString()))
                    .andExpect(jsonPath("$[0].promotionName").value("Summer Sale"))
                    .andExpect(jsonPath("$[0].couponCode").value("SUMMER15"))
                    .andExpect(jsonPath("$[0].salesChannel").value("TIENDA"));
        }

        @Test
        @DisplayName("should return 200 with empty list when no active promotions")
        void getActivePromotions_EmptyList() throws Exception {
            when(promotionService.findActivePromotions("ONLINE")).thenReturn(List.of());

            mockMvc.perform(get("/api/promotions/active")
                            .param("channel", "ONLINE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/promotions/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/promotions/{id}")
    class GetPromotionByIdTests {

        @Test
        @DisplayName("should return 200 with promotion when found")
        void getPromotionById_Found_Returns200() throws Exception {
            when(promotionService.getPromotionById(testPromotionId)).thenReturn(testPromotionResponse);

            mockMvc.perform(get("/api/promotions/{id}", testPromotionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testPromotionId.toString()))
                    .andExpect(jsonPath("$.promotionName").value("Summer Sale"))
                    .andExpect(jsonPath("$.discountType").value("PERCENTAGE"))
                    .andExpect(jsonPath("$.discountValue").value(15.00))
                    .andExpect(jsonPath("$.couponCode").value("SUMMER15"))
                    .andExpect(jsonPath("$.salesChannel").value("TIENDA"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when promotion not found")
        void getPromotionById_NotFound_Returns404() throws Exception {
            when(promotionService.getPromotionById(testPromotionId))
                    .thenThrow(new PromotionNotFoundException(testPromotionId));

            mockMvc.perform(get("/api/promotions/{id}", testPromotionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Promotion not found with id: " + testPromotionId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/promotions
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/promotions")
    class CreatePromotionTests {

        @Test
        @DisplayName("should return 201 with created promotion when valid request")
        void createPromotion_ValidRequest_Returns201() throws Exception {
            when(promotionService.createPromotion(any(PromotionRequest.class)))
                    .thenReturn(testPromotionResponse);

            mockMvc.perform(post("/api/promotions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testPromotionRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(testPromotionId.toString()))
                    .andExpect(jsonPath("$.promotionName").value("Summer Sale"))
                    .andExpect(jsonPath("$.discountType").value("PERCENTAGE"))
                    .andExpect(jsonPath("$.discountValue").value(15.00))
                    .andExpect(jsonPath("$.couponCode").value("SUMMER15"))
                    .andExpect(jsonPath("$.salesChannel").value("TIENDA"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when required fields are missing")
        void createPromotion_MissingRequiredFields_Returns400() throws Exception {
            var invalidRequest = new PromotionRequest("", "", null, null, null, null, null, null, null);

            mockMvc.perform(post("/api/promotions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.promotionName").exists())
                    .andExpect(jsonPath("$.errors.discountType").exists())
                    .andExpect(jsonPath("$.errors.discountValue").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PUT /api/promotions/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/promotions/{id}")
    class UpdatePromotionTests {

        @Test
        @DisplayName("should return 200 with updated promotion")
        void updatePromotion_Success_Returns200() throws Exception {
            when(promotionService.updatePromotion(eq(testPromotionId), any(PromotionRequest.class)))
                    .thenReturn(testPromotionResponse);

            mockMvc.perform(put("/api/promotions/{id}", testPromotionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testPromotionRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testPromotionId.toString()))
                    .andExpect(jsonPath("$.promotionName").value("Summer Sale"));
        }

        @Test
        @DisplayName("should return 404 when promotion not found")
        void updatePromotion_NotFound_Returns404() throws Exception {
            when(promotionService.updatePromotion(eq(testPromotionId), any(PromotionRequest.class)))
                    .thenThrow(new PromotionNotFoundException(testPromotionId));

            mockMvc.perform(put("/api/promotions/{id}", testPromotionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testPromotionRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Promotion not found with id: " + testPromotionId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // DELETE /api/promotions/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/promotions/{id}")
    class DeletePromotionTests {

        @Test
        @DisplayName("should return 204 on successful soft delete")
        void deletePromotion_Success_Returns204() throws Exception {
            mockMvc.perform(delete("/api/promotions/{id}", testPromotionId))
                    .andExpect(status().isNoContent());

            verify(promotionService).deletePromotion(testPromotionId);
        }

        @Test
        @DisplayName("should return 404 when promotion not found")
        void deletePromotion_NotFound_Returns404() throws Exception {
            doThrow(new PromotionNotFoundException(testPromotionId))
                    .when(promotionService).deletePromotion(testPromotionId);

            mockMvc.perform(delete("/api/promotions/{id}", testPromotionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Promotion not found with id: " + testPromotionId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

}
