package com.lifecontrol.api.promotion.repository;

import com.lifecontrol.api.promotion.model.Promotion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    List<Promotion> findByEnabledTrue();

    Page<Promotion> findByEnabledTrueOrderByCreatedAtDesc(Pageable pageable);

    List<Promotion> findBySalesChannel(String salesChannel);

    Optional<Promotion> findByCouponCode(String couponCode);

    @Query("""
        SELECT p FROM Promotion p
        WHERE p.enabled = true
          AND p.salesChannel = :channel
          AND p.startDate <= :now
          AND (p.endDate IS NULL OR p.endDate >= :now)
        ORDER BY p.createdAt DESC
        """)
    List<Promotion> findActiveByChannelAndDate(@Param("channel") String channel, @Param("now") LocalDateTime now);
}
