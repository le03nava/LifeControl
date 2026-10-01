package com.lifecontrol.api.hr.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Global reference catalog of seniority levels, mapped to the {@code seniority_levels} table
 * created by {@code V19__hr_org_structure.sql}.
 *
 * <p>Seniority levels are <b>global</b> reference data, not company-scoped (decision D1), so this
 * entity carries no company association. {@code enabled} is the only state column and the
 * table has no {@code version} column, so there is no optimistic-locking precondition here.</p>
 */
@Entity
@Table(name = "seniority_levels")
public class SeniorityLevel extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "level_code", length = 10, nullable = false, unique = true)
    private String levelCode;

    @Column(name = "level_name", length = 50, nullable = false, unique = true)
    private String levelName;

    @Column(name = "rank", nullable = false, unique = true)
    private Integer rank;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public SeniorityLevel() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getLevelCode() {
        return levelCode;
    }

    public String getLevelName() {
        return levelName;
    }

    public Integer getRank() {
        return rank;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setLevelCode(String levelCode) {
        this.levelCode = levelCode;
    }

    public void setLevelName(String levelName) {
        this.levelName = levelName;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SeniorityLevel seniorityLevel = new SeniorityLevel();

        public Builder id(UUID id) {
            seniorityLevel.id = id;
            return this;
        }

        public Builder levelCode(String levelCode) {
            seniorityLevel.levelCode = levelCode;
            return this;
        }

        public Builder levelName(String levelName) {
            seniorityLevel.levelName = levelName;
            return this;
        }

        public Builder rank(Integer rank) {
            seniorityLevel.rank = rank;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            seniorityLevel.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            seniorityLevel.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            seniorityLevel.setUpdatedAt(updatedAt);
            return this;
        }

        public SeniorityLevel build() {
            return seniorityLevel;
        }
    }
}
