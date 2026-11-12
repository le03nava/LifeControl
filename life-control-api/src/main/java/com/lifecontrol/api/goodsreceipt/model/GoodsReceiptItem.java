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

    /**
     * The purchase price these units were received at: {@code purchase_order_details.unit_price}, the base of
     * the line and never its {@code total}.
     *
     * <p><b>Written once, and the mapping is what says so.</b> A receipt is immutable — nothing in this tree
     * edits or deletes one — so {@code updatable = false} states the rule Hibernate would otherwise leave to a
     * convention: an update that touches the other columns cannot rewrite the price the goods came in at. The
     * same number is what the {@code RECEIPT} movement this line books carries in
     * {@code stock_movement.unit_cost}, and it is the record a later costing model reads instead of re-deriving
     * what the purchase paid.
     *
     * <p><b>Nullable, because history is.</b> Rows written before {@code V96} received their units at a price
     * nobody recorded; the line's current {@code unit_price} is not that price (it can be edited after the
     * receipt), so null is the honest value and no reader treats it as a zero.
     */
    @Column(name = "unit_cost", precision = 19, scale = 4, updatable = false)
    private BigDecimal unitCost;

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

    public BigDecimal getUnitCost() {
        return unitCost;
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

        public Builder unitCost(BigDecimal unitCost) {
            item.unitCost = unitCost;
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
