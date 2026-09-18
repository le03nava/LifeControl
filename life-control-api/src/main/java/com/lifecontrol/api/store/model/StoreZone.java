package com.lifecontrol.api.store.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * Level 3 of the store location tree (Store &rarr; Area &rarr; Zone &rarr; Location).
 *
 * <p>A store zone belongs to a single {@link StoreArea} and is uniquely identified inside that
 * area by its {@code zoneCode}. Soft deletion uses {@code enabled = false}. It is not the same
 * concept as {@link com.lifecontrol.api.company.model.CompanyZone}, which sits above the store in
 * the company hierarchy.</p>
 */
@Entity
@Table(name = "store_zones")
public class StoreZone extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_area_id", nullable = false)
    private StoreArea storeArea;

    @Column(name = "zone_code", nullable = false, length = 10)
    private String zoneCode;

    @Column(name = "zone_name", nullable = false, length = 100)
    private String zoneName;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public StoreZone() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public StoreArea getStoreArea() {
        return storeArea;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public String getZoneName() {
        return zoneName;
    }

    public String getDescription() {
        return description;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setStoreArea(StoreArea storeArea) {
        this.storeArea = storeArea;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final StoreZone zone = new StoreZone();

        public Builder id(UUID id) {
            zone.id = id;
            return this;
        }

        public Builder storeArea(StoreArea storeArea) {
            zone.storeArea = storeArea;
            return this;
        }

        public Builder zoneCode(String zoneCode) {
            zone.zoneCode = zoneCode;
            return this;
        }

        public Builder zoneName(String zoneName) {
            zone.zoneName = zoneName;
            return this;
        }

        public Builder description(String description) {
            zone.description = description;
            return this;
        }

        public Builder displayOrder(Integer displayOrder) {
            zone.displayOrder = displayOrder;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            zone.enabled = enabled;
            return this;
        }

        public StoreZone build() {
            return zone;
        }
    }
}
