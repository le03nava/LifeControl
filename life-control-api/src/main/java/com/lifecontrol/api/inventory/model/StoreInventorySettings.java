package com.lifecontrol.api.inventory.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * Per-store inventory settings: the store location where goods are received and the one sales
 * deducts from.
 *
 * <p>The store is the identity, so {@code company_store_id} is the assigned primary key and there
 * is exactly one row per store. {@code receivingLocationId} is consumed by the goods receipt (W2c).
 * {@code salesLocationId} is read by both stock movers of a store: the sale deduction draws from it
 * first and the manual stock increase credits it ({@code InventoryService.applySaleDeduction},
 * {@code applyStockAdjustment}).</p>
 *
 * <p>The two location columns are plain foreign keys, which only prove that the location exists.
 * "This location belongs to this store" follows from the {@code NOT NULL} foreign-key chain
 * ({@code store_locations -> store_zones -> store_areas -> company_stores}) and is enforced by
 * {@code StoreInventorySettingsService}, not by the schema.</p>
 */
@Entity
@Table(name = "store_inventory_settings")
public class StoreInventorySettings extends Auditable {

    @Id
    @Column(name = "company_store_id", nullable = false, updatable = false)
    private UUID companyStoreId;

    @Column(name = "receiving_location_id", nullable = false)
    private UUID receivingLocationId;

    @Column(name = "sales_location_id", nullable = false)
    private UUID salesLocationId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Default constructor for JPA
    public StoreInventorySettings() {}

    // Getters
    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public UUID getReceivingLocationId() {
        return receivingLocationId;
    }

    public UUID getSalesLocationId() {
        return salesLocationId;
    }

    public long getVersion() {
        return version;
    }

    // Setters
    public void setCompanyStoreId(UUID companyStoreId) {
        this.companyStoreId = companyStoreId;
    }

    public void setReceivingLocationId(UUID receivingLocationId) {
        this.receivingLocationId = receivingLocationId;
    }

    public void setSalesLocationId(UUID salesLocationId) {
        this.salesLocationId = salesLocationId;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final StoreInventorySettings settings = new StoreInventorySettings();

        public Builder companyStoreId(UUID companyStoreId) {
            settings.companyStoreId = companyStoreId;
            return this;
        }

        public Builder receivingLocationId(UUID receivingLocationId) {
            settings.receivingLocationId = receivingLocationId;
            return this;
        }

        public Builder salesLocationId(UUID salesLocationId) {
            settings.salesLocationId = salesLocationId;
            return this;
        }

        public StoreInventorySettings build() {
            return settings;
        }
    }
}
