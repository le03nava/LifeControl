package com.lifecontrol.api.scheduling.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One availability window of an activity: a weekday, a wall-clock range and the calendar range the
 * window is valid for.
 *
 * <p>The table is {@code scheduling_availability} (V17). {@code activity_id} is a plain {@link UUID}
 * foreign key, not a {@code @ManyToOne}, matching {@code SchedulingActivity} and the newer
 * sales/purchase-order habit: the service loads the activity explicitly, so no lazy association is
 * reachable from the entity.</p>
 *
 * <p>{@code dayOfWeek} is ISO-8601 — {@code 1} is Monday and {@code 7} is Sunday — the convention the
 * V17 CHECK enforces. It is a primitive {@code short} to match the {@code SMALLINT} column;
 * {@code ddl-auto=validate} only asserts the column exists, so the value round-trip in the PostgreSQL
 * integration test is what proves this mapping.</p>
 *
 * <p>{@code startTime}/{@code endTime} and {@code validFrom}/{@code validTo} are store-local
 * wall-clock dates and times with no time-zone conversion (the same {@code LocalDateTime} +
 * {@code TIMESTAMP} gap the whole repo carries).</p>
 */
@Entity
@Table(name = "scheduling_availability")
public class SchedulingAvailability extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Default constructor for JPA
    public SchedulingAvailability() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public short getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
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

    public void setDayOfWeek(short dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SchedulingAvailability availability = new SchedulingAvailability();

        public Builder id(UUID id) {
            availability.id = id;
            return this;
        }

        public Builder activityId(UUID activityId) {
            availability.activityId = activityId;
            return this;
        }

        public Builder dayOfWeek(short dayOfWeek) {
            availability.dayOfWeek = dayOfWeek;
            return this;
        }

        public Builder startTime(LocalTime startTime) {
            availability.startTime = startTime;
            return this;
        }

        public Builder endTime(LocalTime endTime) {
            availability.endTime = endTime;
            return this;
        }

        public Builder validFrom(LocalDate validFrom) {
            availability.validFrom = validFrom;
            return this;
        }

        public Builder validTo(LocalDate validTo) {
            availability.validTo = validTo;
            return this;
        }

        public Builder enabled(boolean enabled) {
            availability.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            availability.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            availability.setUpdatedAt(updatedAt);
            return this;
        }

        public SchedulingAvailability build() {
            return availability;
        }
    }
}
