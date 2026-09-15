package com.lifecontrol.api.salesorder.repository;

import com.lifecontrol.api.salesorder.model.SalesOrderItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SalesOrderItemRepository extends JpaRepository<SalesOrderItem, UUID> {

    List<SalesOrderItem> findBySalesOrderId(UUID salesOrderId);

    List<SalesOrderItem> findBySalesOrderIdAndEnabledTrue(UUID salesOrderId);

    List<SalesOrderItem> findByProductVariantId(UUID productVariantId);
}
