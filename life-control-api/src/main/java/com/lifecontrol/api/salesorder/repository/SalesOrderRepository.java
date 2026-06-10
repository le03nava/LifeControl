package com.lifecontrol.api.salesorder.repository;

import com.lifecontrol.api.salesorder.model.SalesOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {

    Optional<SalesOrder> findByOrderNumber(String orderNumber);

    List<SalesOrder> findByCustomerId(UUID customerId);

    List<SalesOrder> findByStatusId(UUID statusId);

    List<SalesOrder> findByShiftId(UUID shiftId);

    Page<SalesOrder> findByEnabledTrueOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
        SELECT so FROM SalesOrder so
        WHERE so.enabled = true
          AND LOWER(so.orderNumber) LIKE LOWER(CONCAT('%', :search, '%'))
        ORDER BY so.createdAt DESC
        """)
    Page<SalesOrder> findBySearchTerm(@Param("search") String search, Pageable pageable);

    Optional<SalesOrder> findTopByOrderNumberStartingWithOrderByOrderNumberDesc(String orderNumberPrefix);
}
