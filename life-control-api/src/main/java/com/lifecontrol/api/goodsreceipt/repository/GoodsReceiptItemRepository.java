package com.lifecontrol.api.goodsreceipt.repository;

import com.lifecontrol.api.goodsreceipt.model.GoodsReceiptItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GoodsReceiptItemRepository extends JpaRepository<GoodsReceiptItem, UUID> {

    /** Lines of one receipt. Used to read a persisted document back without walking its collection. */
    List<GoodsReceiptItem> findByGoodsReceiptId(UUID goodsReceiptId);
}
