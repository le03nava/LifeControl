package com.lifecontrol.api.scheduling.repository;

import com.lifecontrol.api.scheduling.model.SchedulingSlot;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * The calendar's slot read (D35, read 1): one row per materialized slot of the store whose
     * {@code start_at} falls in {@code [from, to)}, ordered by slot start then activity name so the
     * grid has a deterministic order without a client-supplied sort.
     *
     * <p>The read deliberately does <b>not</b> filter by the activity's {@code enabled} flag (D37):
     * deleting an activity is a soft delete that only flips that flag and deletes no slot, so a
     * booked slot — and an empty one — survives its activity's soft delete. Filtering on the flag
     * would hide exactly the rows whose {@code booked}/{@code available} numbers the calendar
     * renders. The activity's flag travels in the projection instead, so the client decides what to
     * draw or to offer for booking.</p>
     *
     * <p>The activity name and flag travel in the same projection through a JPQL constructor
     * expression, so the slot read does not become one query per row. The read never materializes a
     * slot and never writes anything (D33): a range nobody has asked {@code GET /slots} for yet
     * renders as fewer or no slots, the declared consequence <b>G19</b>.</p>
     */
    @Query("""
            SELECT new com.lifecontrol.api.scheduling.repository.SchedulingCalendarSlotProjection(
                s.id, s.activityId, a.activityName, a.enabled, s.startAt, s.endAt, s.capacity, s.booked, s.status)
            FROM SchedulingSlot s
            JOIN SchedulingActivity a ON a.id = s.activityId
            WHERE a.companyStoreId = :storeId
              AND s.startAt >= :from
              AND s.startAt < :to
            ORDER BY s.startAt ASC, a.activityName ASC
            """)
    List<SchedulingCalendarSlotProjection> findCalendarSlots(
            @Param("storeId") UUID storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * The calendar's slot read narrowed to one activity. The service chooses this finder over
     * {@link #findCalendarSlots(UUID, LocalDateTime, LocalDateTime)} when the optional
     * {@code activityId} is present, so the predicate is never a null-bound parameter.
     *
     * <p>Like the unfiltered read it never filters by the activity's {@code enabled} flag (D37), so
     * an explicit {@code activityId} still returns the booked slots of a soft-deleted activity.</p>
     */
    @Query("""
            SELECT new com.lifecontrol.api.scheduling.repository.SchedulingCalendarSlotProjection(
                s.id, s.activityId, a.activityName, a.enabled, s.startAt, s.endAt, s.capacity, s.booked, s.status)
            FROM SchedulingSlot s
            JOIN SchedulingActivity a ON a.id = s.activityId
            WHERE a.companyStoreId = :storeId
              AND a.id = :activityId
              AND s.startAt >= :from
              AND s.startAt < :to
            ORDER BY s.startAt ASC, a.activityName ASC
            """)
    List<SchedulingCalendarSlotProjection> findCalendarSlotsByActivityId(
            @Param("storeId") UUID storeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activityId") UUID activityId);

    /**
     * Pessimistic write lock on the slot, the serialization point of every booking. The booking path
     * takes it as its first database interaction (D28), reads {@code booked}/{@code capacity} and
     * raises the room guard inside it, so two concurrent bookings of the last seat queue here instead
     * of both reading the same free count. It is a lock of the documented {@code appointment -> slot}
     * order; a reschedule holds the appointment lock first and then takes both slots in ascending
     * {@code (start_at, id)} order, so the global order is cycle-free.
     *
     * <p>Deliberately does not filter by {@code enabled}: a disabled slot is found so the caller can
     * answer with an actionable not-bookable 409 instead of a not-found.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SchedulingSlot s WHERE s.id = :id")
    Optional<SchedulingSlot> findByIdForUpdate(@Param("id") UUID id);

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
