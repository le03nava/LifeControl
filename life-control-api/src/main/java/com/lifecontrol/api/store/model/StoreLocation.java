package com.lifecontrol.api.store.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * Level 4 of the store location tree (Store &rarr; Area &rarr; Zone &rarr; Location).
 *
 * <p>A store location belongs to a single {@link StoreZone} and is uniquely identified inside
 * that zone by its {@code locationCode}. Soft deletion uses {@code enabled = false}. It is the
 * leaf of the tree and the deepest node a scoped store role can reach.</p>
 */
@Entity
@Table(name = "store_locations")
public class StoreLocation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_zone_id", nullable = false)
    private StoreZone storeZone;

    @Column(name = "location_code", nullable = false, length = 10)
    private String locationCode;

    @Column(name = "location_name", nullable = false, length = 100)
    private String locationName;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public StoreLocation() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public StoreZone getStoreZone() {
        return storeZone;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public String getLocationName() {
        return locationName;
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

    public void setStoreZone(StoreZone storeZone) {
        this.storeZone = storeZone;
    }

    public void setLocationCode(String locationCode) {
        this.locationCode = locationCode;
    }

    public void setLocationName(String locationName) {
        this.locationName = locationName;
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
        private final StoreLocation location = new StoreLocation();

        public Builder id(UUID id) {
            location.id = id;
            return this;
        }

        public Builder storeZone(StoreZone storeZone) {
            location.storeZone = storeZone;
            return this;
        }

        public Builder locationCode(String locationCode) {
            location.locationCode = locationCode;
            return this;
        }

        public Builder locationName(String locationName) {
            location.locationName = locationName;
            return this;
        }

        public Builder description(String description) {
            location.description = description;
            return this;
        }

        public Builder displayOrder(Integer displayOrder) {
            location.displayOrder = displayOrder;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            location.enabled = enabled;
            return this;
        }

        public StoreLocation build() {
            return location;
        }
    }
}
