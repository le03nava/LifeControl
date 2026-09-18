package com.lifecontrol.api.store.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * Level 2 of the store location tree (Store &rarr; Area &rarr; Zone &rarr; Location).
 *
 * <p>An area belongs to a single {@link CompanyStore} and is uniquely identified inside that
 * store by its {@code areaCode}. Soft deletion uses {@code enabled = false}.</p>
 */
@Entity
@Table(name = "store_areas")
public class StoreArea extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_store_id", nullable = false)
    private CompanyStore companyStore;

    @Column(name = "area_code", nullable = false, length = 10)
    private String areaCode;

    @Column(name = "area_name", nullable = false, length = 100)
    private String areaName;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Default constructor for JPA
    public StoreArea() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public CompanyStore getCompanyStore() {
        return companyStore;
    }

    public String getAreaCode() {
        return areaCode;
    }

    public String getAreaName() {
        return areaName;
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

    public long getVersion() {
        return version;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompanyStore(CompanyStore companyStore) {
        this.companyStore = companyStore;
    }

    public void setAreaCode(String areaCode) {
        this.areaCode = areaCode;
    }

    public void setAreaName(String areaName) {
        this.areaName = areaName;
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
        private final StoreArea area = new StoreArea();

        public Builder id(UUID id) {
            area.id = id;
            return this;
        }

        public Builder companyStore(CompanyStore companyStore) {
            area.companyStore = companyStore;
            return this;
        }

        public Builder areaCode(String areaCode) {
            area.areaCode = areaCode;
            return this;
        }

        public Builder areaName(String areaName) {
            area.areaName = areaName;
            return this;
        }

        public Builder description(String description) {
            area.description = description;
            return this;
        }

        public Builder displayOrder(Integer displayOrder) {
            area.displayOrder = displayOrder;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            area.enabled = enabled;
            return this;
        }

        public StoreArea build() {
            return area;
        }
    }
}
