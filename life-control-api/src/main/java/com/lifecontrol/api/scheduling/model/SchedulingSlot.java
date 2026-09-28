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
 * One bookable instance of an activity: a concrete date-time range materialized from the activity's
 * availability template.
 *
 * <p>The table is {@code scheduling_slots} (V17). {@code activity_id} is a plain {@link UUID} foreign
 * key, not a {@code @ManyToOne}, matching {@code SchedulingActivity} and {@link SchedulingAvailability}:
 * the service loads the activity explicitly, so no lazy association is reachable from the entity.</p>
 *
 * <p>{@code startAt}/{@code endAt} are store-local wall-clock date-times with no time-zone conversion
 * (D12), the same {@code LocalDateTime} + {@code TIMESTAMP} mapping the whole repo carries.</p>
 *
 * <p>{@code booked} is the count of appointments taken against the slot, owned by the booking path
 * (W4). W3 only ever inserts it with its column default of {@code 0} and never updates it, so a slot
 * an appointment depends on is never rewritten by materialization (D10, D11). {@code status} is the
 * booking path's too; W3 leaves the {@code 'Available'} default.</p>
 */
@Entity
@Table(name = "scheduling_slots")
public class SchedulingSlot extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "booked", nullable = false)
    private int booked;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Default constructor for JPA
    public SchedulingSlot() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getBooked() {
        return booked;
    }

    public String getStatus() {
        return status;
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

    public void setActivityId(UUID activityId) {
        this.activityId = activityId;
    }

    public void setStartAt(LocalDateTime startAt) {
        this.startAt = startAt;
    }

    public void setEndAt(LocalDateTime endAt) {
        this.endAt = endAt;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public void setBooked(int booked) {
        this.booked = booked;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SchedulingSlot slot = new SchedulingSlot();

        public Builder id(UUID id) {
            slot.id = id;
            return this;
        }

        public Builder activityId(UUID activityId) {
            slot.activityId = activityId;
            return this;
        }

        public Builder startAt(LocalDateTime startAt) {
            slot.startAt = startAt;
            return this;
        }

        public Builder endAt(LocalDateTime endAt) {
            slot.endAt = endAt;
            return this;
        }

        public Builder capacity(int capacity) {
            slot.capacity = capacity;
            return this;
        }

        public Builder booked(int booked) {
            slot.booked = booked;
            return this;
        }

        public Builder status(String status) {
            slot.status = status;
            return this;
        }

        public Builder enabled(boolean enabled) {
            slot.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            slot.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            slot.setUpdatedAt(updatedAt);
            return this;
        }

        public SchedulingSlot build() {
            return slot;
        }
    }
}
