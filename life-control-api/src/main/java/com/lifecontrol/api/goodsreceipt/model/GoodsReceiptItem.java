package com.lifecontrol.api.goodsreceipt.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One received line of a {@link GoodsReceipt}.
 *
 * <p>It links the received quantity to the purchase-order line it settles and to the exact product
 * variant that entered stock. {@code quantity_received} is strictly positive: the database CHECK
 * ({@code quantity_received > 0}) is the backstop, and the service owns the human-facing rejection.
 * {@code product_variant_id} is required here (unlike the legacy nullable variant link on
 * {@code purchase_order_details}): a reception must name the variant it moves.</p>
 */
@Entity
@Table(name = "goods_receipt_items")
public class GoodsReceiptItem extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_receipt_id", nullable = false)
    private GoodsReceipt goodsReceipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_detail_id", nullable = false)
    private PurchaseOrderDetail purchaseOrderDetail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_variant_id", nullable = false)
    private ProductVariant productVariant;

    @Column(name = "quantity_received", precision = 12, scale = 2, nullable = false)
    private BigDecimal quantityReceived;

    @Column(length = 500)
    private String comments;

    // Default constructor for JPA
    public GoodsReceiptItem() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public GoodsReceipt getGoodsReceipt() {
        return goodsReceipt;
    }

    public PurchaseOrderDetail getPurchaseOrderDetail() {
        return purchaseOrderDetail;
    }

    public ProductVariant getProductVariant() {
        return productVariant;
    }

    public BigDecimal getQuantityReceived() {
        return quantityReceived;
    }

    public String getComments() {
        return comments;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setGoodsReceipt(GoodsReceipt goodsReceipt) {
        this.goodsReceipt = goodsReceipt;
    }

    public void setPurchaseOrderDetail(PurchaseOrderDetail purchaseOrderDetail) {
        this.purchaseOrderDetail = purchaseOrderDetail;
    }

    public void setProductVariant(ProductVariant productVariant) {
        this.productVariant = productVariant;
    }

    public void setQuantityReceived(BigDecimal quantityReceived) {
        this.quantityReceived = quantityReceived;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final GoodsReceiptItem item = new GoodsReceiptItem();

        public Builder id(UUID id) {
            item.id = id;
            return this;
        }

        public Builder goodsReceipt(GoodsReceipt goodsReceipt) {
            item.goodsReceipt = goodsReceipt;
            return this;
        }

        public Builder purchaseOrderDetail(PurchaseOrderDetail purchaseOrderDetail) {
            item.purchaseOrderDetail = purchaseOrderDetail;
            return this;
        }

        public Builder productVariant(ProductVariant productVariant) {
            item.productVariant = productVariant;
            return this;
        }

        public Builder quantityReceived(BigDecimal quantityReceived) {
            item.quantityReceived = quantityReceived;
            return this;
        }

        public Builder comments(String comments) {
            item.comments = comments;
            return this;
        }

        public GoodsReceiptItem build() {
            return item;
        }
    }
}
