package com.lifecontrol.api.inventory.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One immutable entry of the append-only inventory ledger.
 *
 * <p>A movement is a fact: it is inserted once by {@code InventoryService} and never updated or
 * deleted. The entity therefore has no {@code updated_at} column and no setters, and
 * {@link #occurredAt} is assigned once by the JPA pre-persist callback (the
 * {@code ActivityLog} precedent).</p>
 *
 * <p>Every stock mutation writes a movement, so the ledger is the complete explanation of each
 * balance: {@link MovementType#RECEIPT} (goods receipt),
 * {@link MovementType#SALE}/{@link MovementType#SALE_REVERSAL} (a sale and its exact restoration)
 * and {@link MovementType#ADJUSTMENT_INCREASE}/{@link MovementType#ADJUSTMENT_DECREASE} (a manual
 * per-store edit), all with a positive quantity (W3-D7).</p>
 */
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_variant_id", nullable = false)
    private UUID productVariantId;

    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    @Column(name = "store_location_id", nullable = false)
    private UUID storeLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private MovementType movementType;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    // Default constructor for JPA
    public InventoryMovement() {}

    @PrePersist
    protected void onCreate() {
        occurredAt = LocalDateTime.now();
    }

    // Getters (no setters — a movement is immutable once written)
    public UUID getId() {
        return id;
    }

    public UUID getProductVariantId() {
        return productVariantId;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public UUID getStoreLocationId() {
        return storeLocationId;
    }

    public MovementType getMovementType() {
        return movementType;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final InventoryMovement movement = new InventoryMovement();

        public Builder id(UUID id) {
            movement.id = id;
            return this;
        }

        public Builder productVariantId(UUID productVariantId) {
            movement.productVariantId = productVariantId;
            return this;
        }

        public Builder companyStoreId(UUID companyStoreId) {
            movement.companyStoreId = companyStoreId;
            return this;
        }

        public Builder storeLocationId(UUID storeLocationId) {
            movement.storeLocationId = storeLocationId;
            return this;
        }

        public Builder movementType(MovementType movementType) {
            movement.movementType = movementType;
            return this;
        }

        public Builder quantity(BigDecimal quantity) {
            movement.quantity = quantity;
            return this;
        }

        public Builder referenceType(String referenceType) {
            movement.referenceType = referenceType;
            return this;
        }

        public Builder referenceId(UUID referenceId) {
            movement.referenceId = referenceId;
            return this;
        }

        public Builder createdBy(String createdBy) {
            movement.createdBy = createdBy;
            return this;
        }

        public InventoryMovement build() {
            return movement;
        }
    }
}
