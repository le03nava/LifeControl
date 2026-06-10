package com.lifecontrol.api.promotion.service;

import com.lifecontrol.api.promotion.dto.PromotionRequest;
import com.lifecontrol.api.promotion.dto.PromotionResponse;
import com.lifecontrol.api.promotion.exception.PromotionNotFoundException;
import com.lifecontrol.api.promotion.model.Promotion;
import com.lifecontrol.api.promotion.repository.PromotionRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PromotionService Tests")
class PromotionServiceTest {

    @Mock
    private PromotionRepository promotionRepository;

    @InjectMocks
    private PromotionService promotionService;

    private Promotion testPromotion;
    private PromotionRequest testPromotionRequest;
    private UUID testPromotionId;

    @BeforeEach
    void setUp() {
        testPromotionId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testPromotion = Promotion.builder()
                .id(testPromotionId)
                .promotionName("Summer Sale")
                .discountType("PERCENTAGE")
                .discountValue(new BigDecimal("15.00"))
                .couponCode("SUMMER15")
                .startDate(now.minusDays(1))
                .endDate(now.plusDays(30))
                .salesChannel("TIENDA")
                .minimumPurchaseAmount(new BigDecimal("100.00"))
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

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
    // getAllPromotions
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getAllPromotions")
    class GetAllPromotionsTests {

        @Test
        @DisplayName("should return paginated enabled promotions")
        void getAllPromotions_Paginated() {
            var pageable = PageRequest.of(0, 12);
            var promotions = List.of(testPromotion);
            var expectedPage = new PageImpl<>(promotions, pageable, 1);

            when(promotionRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable)).thenReturn(expectedPage);

            Page<PromotionResponse> result = promotionService.getAllPromotions(pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).id()).isEqualTo(testPromotionId);
            assertThat(result.getContent().get(0).promotionName()).isEqualTo("Summer Sale");
            assertThat(result.getContent().get(0).discountType()).isEqualTo("PERCENTAGE");
            assertThat(result.getContent().get(0).discountValue()).isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(result.getContent().get(0).couponCode()).isEqualTo("SUMMER15");
            assertThat(result.getContent().get(0).salesChannel()).isEqualTo("TIENDA");
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getTotalPages()).isEqualTo(1);
            verify(promotionRepository).findByEnabledTrueOrderByCreatedAtDesc(pageable);
        }

        @Test
        @DisplayName("should return empty page when no promotions exist")
        void getAllPromotions_EmptyPage() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<Promotion>(List.of(), pageable, 0);

            when(promotionRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable)).thenReturn(expectedPage);

            Page<PromotionResponse> result = promotionService.getAllPromotions(pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    // ─────────────────────────────────────────────
    // getPromotionById
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getPromotionById")
    class GetPromotionByIdTests {

        @Test
        @DisplayName("should return promotion when found")
        void getPromotionById_Found() {
            when(promotionRepository.findById(testPromotionId)).thenReturn(Optional.of(testPromotion));

            PromotionResponse result = promotionService.getPromotionById(testPromotionId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(testPromotionId);
            assertThat(result.promotionName()).isEqualTo("Summer Sale");
            assertThat(result.discountType()).isEqualTo("PERCENTAGE");
            assertThat(result.discountValue()).isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(result.couponCode()).isEqualTo("SUMMER15");
            assertThat(result.salesChannel()).isEqualTo("TIENDA");
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw PromotionNotFoundException when not found")
        void getPromotionById_NotFound_ThrowsException() {
            when(promotionRepository.findById(testPromotionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> promotionService.getPromotionById(testPromotionId))
                    .isInstanceOf(PromotionNotFoundException.class)
                    .hasMessageContaining("Promotion not found with id");
        }
    }

    // ─────────────────────────────────────────────
    // findActivePromotions
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("findActivePromotions")
    class FindActivePromotionsTests {

        @Test
        @DisplayName("should return active promotions for given channel and current date")
        void findActivePromotions_ByChannel() {
            when(promotionRepository.findActiveByChannelAndDate(eq("TIENDA"), any(LocalDateTime.class)))
                    .thenReturn(List.of(testPromotion));

            List<PromotionResponse> result = promotionService.findActivePromotions("TIENDA");

            assertThat(result).isNotNull();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(testPromotionId);
            assertThat(result.get(0).promotionName()).isEqualTo("Summer Sale");
            assertThat(result.get(0).couponCode()).isEqualTo("SUMMER15");
            assertThat(result.get(0).salesChannel()).isEqualTo("TIENDA");
            verify(promotionRepository).findActiveByChannelAndDate(eq("TIENDA"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("should return empty list when no active promotions match channel")
        void findActivePromotions_Empty() {
            when(promotionRepository.findActiveByChannelAndDate(eq("ONLINE"), any(LocalDateTime.class)))
                    .thenReturn(List.of());

            List<PromotionResponse> result = promotionService.findActivePromotions("ONLINE");

            assertThat(result).isNotNull();
            assertThat(result).isEmpty();
        }
    }

    // ─────────────────────────────────────────────
    // createPromotion
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("createPromotion")
    class CreatePromotionTests {

        @Test
        @DisplayName("should create promotion and return response with generated ID")
        void createPromotion_Success() {
            when(promotionRepository.save(any(Promotion.class))).thenReturn(testPromotion);

            PromotionResponse result = promotionService.createPromotion(testPromotionRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(testPromotionId);
            assertThat(result.promotionName()).isEqualTo("Summer Sale");
            assertThat(result.discountType()).isEqualTo("PERCENTAGE");
            assertThat(result.discountValue()).isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(result.couponCode()).isEqualTo("SUMMER15");
            assertThat(result.salesChannel()).isEqualTo("TIENDA");
            assertThat(result.minimumPurchaseAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(result.enabled()).isTrue();
            verify(promotionRepository).save(any(Promotion.class));
        }

        @Test
        @DisplayName("should default enabled to true when not provided")
        void createPromotion_DefaultsEnabledToTrue() {
            var now = LocalDateTime.now();
            var requestWithoutEnabled = new PromotionRequest(
                    "Flash Deal",
                    "FIXED",
                    new BigDecimal("50.00"),
                    "FLASH50",
                    now,
                    now.plusDays(1),
                    "ONLINE",
                    null,
                    null
            );
            var savedPromotion = Promotion.builder()
                    .id(UUID.randomUUID())
                    .promotionName("Flash Deal")
                    .discountType("FIXED")
                    .discountValue(new BigDecimal("50.00"))
                    .couponCode("FLASH50")
                    .salesChannel("ONLINE")
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(promotionRepository.save(any(Promotion.class))).thenReturn(savedPromotion);

            PromotionResponse result = promotionService.createPromotion(requestWithoutEnabled);

            assertThat(result).isNotNull();
            assertThat(result.promotionName()).isEqualTo("Flash Deal");
            assertThat(result.enabled()).isTrue();
            verify(promotionRepository).save(any(Promotion.class));
        }
    }

    // ─────────────────────────────────────────────
    // updatePromotion
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updatePromotion")
    class UpdatePromotionTests {

        @Test
        @DisplayName("should update promotion fields and return response")
        void updatePromotion_Success() {
            var updateRequest = new PromotionRequest(
                    "Winter Sale",
                    "FIXED",
                    new BigDecimal("25.00"),
                    "WINTER25",
                    LocalDateTime.now().minusDays(5),
                    LocalDateTime.now().plusDays(20),
                    "ONLINE",
                    new BigDecimal("200.00"),
                    false
            );

            when(promotionRepository.findById(testPromotionId)).thenReturn(Optional.of(testPromotion));
            when(promotionRepository.save(any(Promotion.class))).thenAnswer(inv -> inv.getArgument(0));

            PromotionResponse result = promotionService.updatePromotion(testPromotionId, updateRequest);

            assertThat(result).isNotNull();
            assertThat(result.promotionName()).isEqualTo("Winter Sale");
            assertThat(result.discountType()).isEqualTo("FIXED");
            assertThat(result.discountValue()).isEqualByComparingTo(new BigDecimal("25.00"));
            assertThat(result.couponCode()).isEqualTo("WINTER25");
            assertThat(result.salesChannel()).isEqualTo("ONLINE");
            assertThat(result.minimumPurchaseAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
            assertThat(result.enabled()).isFalse();
            verify(promotionRepository).save(any(Promotion.class));
        }

        @Test
        @DisplayName("should throw PromotionNotFoundException when promotion not found")
        void updatePromotion_NotFound_ThrowsException() {
            var nonExistentId = UUID.randomUUID();
            when(promotionRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> promotionService.updatePromotion(nonExistentId, testPromotionRequest))
                    .isInstanceOf(PromotionNotFoundException.class)
                    .hasMessageContaining("Promotion not found with id");

            verify(promotionRepository, never()).save(any(Promotion.class));
        }
    }

    // ─────────────────────────────────────────────
    // deletePromotion
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deletePromotion")
    class DeletePromotionTests {

        @Test
        @DisplayName("should soft-delete promotion by setting enabled to false")
        void deletePromotion_Success() {
            when(promotionRepository.findById(testPromotionId)).thenReturn(Optional.of(testPromotion));
            when(promotionRepository.save(any(Promotion.class))).thenAnswer(inv -> inv.getArgument(0));

            promotionService.deletePromotion(testPromotionId);

            verify(promotionRepository).findById(testPromotionId);
            verify(promotionRepository).save(any(Promotion.class));
        }

        @Test
        @DisplayName("should throw PromotionNotFoundException when promotion not found")
        void deletePromotion_NotFound_ThrowsException() {
            var nonExistentId = UUID.randomUUID();
            when(promotionRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> promotionService.deletePromotion(nonExistentId))
                    .isInstanceOf(PromotionNotFoundException.class)
                    .hasMessageContaining("Promotion not found with id");

            verify(promotionRepository, never()).save(any(Promotion.class));
        }
    }
}
