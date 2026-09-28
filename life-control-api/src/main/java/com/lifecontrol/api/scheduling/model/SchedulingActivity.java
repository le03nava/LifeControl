package com.lifecontrol.api.scheduling.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A bookable activity of a store: the service name, how long it lasts and how many appointments fit
 * in one slot.
 *
 * <p>The table is {@code scheduling_activities} (V16). {@code company_store_id} is a plain
 * {@link UUID} foreign key, not a {@code @ManyToOne}, matching the sales/purchase-order habit: the
 * service derives the company &rarr; country &rarr; region &rarr; zone chain from the store it loads
 * explicitly, so no lazy association is reachable from the entity.</p>
 *
 * <p>{@code user_id} is the Keycloak {@code sub} of the employee who attends the activity, nullable
 * because a catalogue entry can exist before someone is assigned — the same convention
 * {@code shifts.user_id} uses.</p>
 *
 * <p>{@code activityName} is unique per store ({@code UNIQUE(company_store_id, activity_name)}), a
 * rule the service checks before writing so the client gets a 409 instead of a raw constraint
 * violation.</p>
 */
@Entity
@Table(name = "scheduling_activities")
public class SchedulingActivity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "activity_name", nullable = false, length = 150)
    private String activityName;

    @Column(name = "description")
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "capacity_per_slot", nullable = false)
    private Integer capacityPerSlot;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Default constructor for JPA
    public SchedulingActivity() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public String getUserId() {
        return userId;
    }

    public String getActivityName() {
        return activityName;
    }

    public String getDescription() {
        return description;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public Integer getCapacityPerSlot() {
        return capacityPerSlot;
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

    public void setCompanyStoreId(UUID companyStoreId) {
        this.companyStoreId = companyStoreId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setActivityName(String activityName) {
        this.activityName = activityName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public void setCapacityPerSlot(Integer capacityPerSlot) {
        this.capacityPerSlot = capacityPerSlot;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SchedulingActivity activity = new SchedulingActivity();

        public Builder id(UUID id) {
            activity.id = id;
            return this;
        }

        public Builder companyStoreId(UUID companyStoreId) {
            activity.companyStoreId = companyStoreId;
            return this;
        }

        public Builder userId(String userId) {
            activity.userId = userId;
            return this;
        }

        public Builder activityName(String activityName) {
            activity.activityName = activityName;
            return this;
        }

        public Builder description(String description) {
            activity.description = description;
            return this;
        }

        public Builder durationMinutes(Integer durationMinutes) {
            activity.durationMinutes = durationMinutes;
            return this;
        }

        public Builder capacityPerSlot(Integer capacityPerSlot) {
            activity.capacityPerSlot = capacityPerSlot;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            activity.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            activity.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            activity.setUpdatedAt(updatedAt);
            return this;
        }

        public SchedulingActivity build() {
            return activity;
        }
    }
}
