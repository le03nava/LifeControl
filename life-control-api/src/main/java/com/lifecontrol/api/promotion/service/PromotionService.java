package com.lifecontrol.api.promotion.service;

import com.lifecontrol.api.promotion.dto.PromotionRequest;
import com.lifecontrol.api.promotion.dto.PromotionResponse;
import com.lifecontrol.api.promotion.exception.PromotionNotFoundException;
import com.lifecontrol.api.promotion.model.Promotion;
import com.lifecontrol.api.promotion.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PromotionService {

    private static final Logger logger = LoggerFactory.getLogger(PromotionService.class);

    private final PromotionRepository promotionRepository;

    public PromotionService(PromotionRepository promotionRepository) {
        this.promotionRepository = promotionRepository;
    }

    @Transactional(readOnly = true)
    public Page<PromotionResponse> getAllPromotions(Pageable pageable) {
        var promotions = promotionRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable);
        return promotions.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PromotionResponse getPromotionById(UUID id) {
        var promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new PromotionNotFoundException(id));
        return toResponse(promotion);
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> findActivePromotions(String channel) {
        var now = LocalDateTime.now();
        var activePromotions = promotionRepository.findActiveByChannelAndDate(channel, now);
        return activePromotions.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PromotionResponse createPromotion(PromotionRequest request) {
        logger.info("Creating promotion: name={}, discountType={}", request.promotionName(), request.discountType());

        var promotion = Promotion.builder()
                .promotionName(request.promotionName())
                .discountType(request.discountType())
                .discountValue(request.discountValue())
                .couponCode(request.couponCode())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .salesChannel(request.salesChannel())
                .minimumPurchaseAmount(request.minimumPurchaseAmount())
                .enabled(request.enabled())
                .build();

        var saved = promotionRepository.save(promotion);
        logger.info("Promotion created: id={}, name={}", saved.getId(), saved.getPromotionName());

        return toResponse(saved);
    }

    @Transactional
    public PromotionResponse updatePromotion(UUID id, PromotionRequest request) {
        logger.info("Updating promotion: id={}", id);

        var promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new PromotionNotFoundException(id));

        promotion.setPromotionName(request.promotionName());
        promotion.setDiscountType(request.discountType());
        promotion.setDiscountValue(request.discountValue());
        promotion.setCouponCode(request.couponCode());
        promotion.setStartDate(request.startDate());
        promotion.setEndDate(request.endDate());
        promotion.setSalesChannel(request.salesChannel());
        promotion.setMinimumPurchaseAmount(request.minimumPurchaseAmount());
        promotion.setEnabled(request.enabled());

        var updated = promotionRepository.save(promotion);
        logger.info("Promotion updated: id={}", id);

        return toResponse(updated);
    }

    @Transactional
    public void deletePromotion(UUID id) {
        logger.info("Soft-deleting promotion: id={}", id);

        var promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new PromotionNotFoundException(id));

        promotion.setEnabled(false);
        promotionRepository.save(promotion);
        logger.info("Promotion soft-deleted: id={}", id);
    }

    @Transactional
    public PromotionResponse enablePromotion(UUID id) {
        logger.info("Re-enabling promotion: id={}", id);

        var promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new PromotionNotFoundException(id));

        promotion.setEnabled(true);
        var saved = promotionRepository.save(promotion);

        return toResponse(saved);
    }

    // ─── Response Mapper ─────────────────────────────────────────────────

    private PromotionResponse toResponse(Promotion promotion) {
        return new PromotionResponse(
                promotion.getId(),
                promotion.getPromotionName(),
                promotion.getDiscountType(),
                promotion.getDiscountValue(),
                promotion.getCouponCode(),
                promotion.getStartDate(),
                promotion.getEndDate(),
                promotion.getSalesChannel(),
                promotion.getMinimumPurchaseAmount(),
                promotion.getEnabled(),
                promotion.getCreatedAt(),
                promotion.getUpdatedAt()
        );
    }
}
