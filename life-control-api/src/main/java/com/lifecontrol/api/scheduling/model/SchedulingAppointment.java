package com.lifecontrol.api.scheduling.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * One booking of a slot: who attends, for which customer, and how far the appointment got.
 *
 * <p>The table is {@code scheduling_appointments} (V18). {@code slotId}, {@code activityId},
 * {@code companyStoreId}, {@code customerId} and {@code statusId} are plain {@link UUID} foreign
 * keys, not {@code @ManyToOne} associations, matching the rest of the scheduling domain: the service
 * loads each one explicitly, so no lazy association is reachable from the entity and the booking
 * lock order stays obvious.</p>
 *
 * <p>{@code activityId} and {@code companyStoreId} are denormalized: the booking path derives both
 * from the locked slot's activity, so the appointment carries its own store and activity without a
 * join. {@code userId} is the Keycloak {@code sub} of the attendee and is nullable — an omitted
 * value stays unassigned (D30) and is never defaulted to the caller.</p>
 *
 * <p>{@code booked} on the referenced slot is the number of appointments whose status holds
 * capacity; this entity does not own that counter, the service moves it on the two lifecycle edges
 * (D25).</p>
 */
@Entity
@Table(name = "scheduling_appointments")
public class SchedulingAppointment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "slot_id", nullable = false)
    private UUID slotId;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "status_id", nullable = false)
    private UUID statusId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Default constructor for JPA
    public SchedulingAppointment() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getSlotId() {
        return slotId;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public String getUserId() {
        return userId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getStatusId() {
        return statusId;
    }

    public String getNotes() {
        return notes;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public long getVersion() {
        return version;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setSlotId(UUID slotId) {
        this.slotId = slotId;
    }

    public void setActivityId(UUID activityId) {
        this.activityId = activityId;
    }

    public void setCompanyStoreId(UUID companyStoreId) {
        this.companyStoreId = companyStoreId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public void setStatusId(UUID statusId) {
        this.statusId = statusId;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SchedulingAppointment appointment = new SchedulingAppointment();

        public Builder id(UUID id) {
            appointment.id = id;
            return this;
        }

        public Builder slotId(UUID slotId) {
            appointment.slotId = slotId;
            return this;
        }

        public Builder activityId(UUID activityId) {
            appointment.activityId = activityId;
            return this;
        }

        public Builder companyStoreId(UUID companyStoreId) {
            appointment.companyStoreId = companyStoreId;
            return this;
        }

        public Builder userId(String userId) {
            appointment.userId = userId;
            return this;
        }

        public Builder customerId(UUID customerId) {
            appointment.customerId = customerId;
            return this;
        }

        public Builder statusId(UUID statusId) {
            appointment.statusId = statusId;
            return this;
        }

        public Builder notes(String notes) {
            appointment.notes = notes;
            return this;
        }

        public Builder enabled(boolean enabled) {
            appointment.enabled = enabled;
            return this;
        }

        public Builder createdAt(java.time.LocalDateTime createdAt) {
            appointment.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(java.time.LocalDateTime updatedAt) {
            appointment.setUpdatedAt(updatedAt);
            return this;
        }

        public SchedulingAppointment build() {
            return appointment;
        }
    }
}
