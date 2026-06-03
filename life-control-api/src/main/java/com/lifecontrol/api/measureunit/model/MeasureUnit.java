package com.lifecontrol.api.measureunit.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "measure_units")
public class MeasureUnit extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 100, nullable = false)
    private String measureUnitName;

    @Column(length = 10, nullable = false)
    private String measureUnitShortName;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private MeasureUnitType unitType;

    @Column(length = 5, nullable = false, unique = true)
    private String satCode;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public MeasureUnit() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getMeasureUnitName() {
        return measureUnitName;
    }

    public String getMeasureUnitShortName() {
        return measureUnitShortName;
    }

    public MeasureUnitType getUnitType() {
        return unitType;
    }

    public String getSatCode() {
        return satCode;
    }

    public String getDescription() {
        return description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setMeasureUnitName(String measureUnitName) {
        this.measureUnitName = measureUnitName;
    }

    public void setMeasureUnitShortName(String measureUnitShortName) {
        this.measureUnitShortName = measureUnitShortName;
    }

    public void setUnitType(MeasureUnitType unitType) {
        this.unitType = unitType;
    }

    public void setSatCode(String satCode) {
        this.satCode = satCode;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final MeasureUnit measureUnit = new MeasureUnit();

        public Builder id(UUID id) {
            measureUnit.id = id;
            return this;
        }

        public Builder measureUnitName(String measureUnitName) {
            measureUnit.measureUnitName = measureUnitName;
            return this;
        }

        public Builder measureUnitShortName(String measureUnitShortName) {
            measureUnit.measureUnitShortName = measureUnitShortName;
            return this;
        }

        public Builder unitType(MeasureUnitType unitType) {
            measureUnit.unitType = unitType;
            return this;
        }

        public Builder satCode(String satCode) {
            measureUnit.satCode = satCode;
            return this;
        }

        public Builder description(String description) {
            measureUnit.description = description;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            measureUnit.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            measureUnit.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            measureUnit.setUpdatedAt(updatedAt);
            return this;
        }

        public MeasureUnit build() {
            return measureUnit;
        }
    }
}
