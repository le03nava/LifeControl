package com.lifecontrol.api.scheduling.repository;

import com.lifecontrol.api.scheduling.model.SchedulingSlot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence of the bookable slot instances materialized from an activity's availability template.
 *
 * <p>The ordered finder reproduces the {@code idx_scheduling_slots_activity_id_start_at} order, so a
 * range read is deterministic without the caller supplying a sort. The materialization and cleanup
 * statements are the write side of W3b: the first inserts only rows that do not exist yet, the second
 * removes only rows no appointment depends on.</p>
 */
@Repository
public interface SchedulingSlotRepository extends JpaRepository<SchedulingSlot, UUID> {

    /**
     * Inserts the slot for the activity's start instant only when no equal slot exists, relying on
     * {@code UNIQUE(activity_id, start_at)} and {@code ON CONFLICT DO NOTHING} instead of catching the
     * unique violation: a failed insert aborts the PostgreSQL transaction, so a caught
     * {@code DataIntegrityViolationException} could not be recovered from inside the same
     * transaction.
     *
     * <p>{@code id}, {@code booked}, {@code status}, {@code enabled}, {@code version},
     * {@code created_at} and {@code updated_at} are deliberately absent from the column list and take
     * their column defaults. That is the idempotency contract: a conflicting row is left exactly as
     * it was, so {@code booked} is never reset by a re-materialization of the same range (D10, D11).
     * The method carries its own read-write transaction so a direct call cannot land in the read-only
     * default transaction of Spring Data query methods.</p>
     *
     * @return the number of rows actually inserted, so the caller can log what was created
     */
    @Modifying
    @Transactional
    @Query(value = """
                    INSERT INTO scheduling_slots (activity_id, start_at, end_at, capacity)
                    VALUES (:activityId, :startAt, :endAt, :capacity)
                    ON CONFLICT (activity_id, start_at) DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("activityId") UUID activityId,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("capacity") int capacity);

    List<SchedulingSlot> findByActivityIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
            UUID activityId, LocalDateTime from, LocalDateTime to);

    /**
     * Removes the activity's unbooked slots, the reconciliation half of a template replacement.
     *
     * <p>{@code booked = 0} is the exact boundary between a slot a re-materialization may discard and
     * one it must not: a positive {@code booked} means an appointment holds that row, and deleting it
     * would either orphan the appointment or cascade it away, so the row wins over the template edit.
     * An unbooked row carries no state beyond the template that just changed, so re-materializing the
     * new template reproduces it if the new windows still call for it.</p>
     *
     * @return the number of rows deleted
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SchedulingSlot slot WHERE slot.activityId = :activityId AND slot.booked = 0")
    int deleteUnbookedByActivityId(@Param("activityId") UUID activityId);
}
