package com.lifecontrol.api.scheduling.repository;

import com.lifecontrol.api.scheduling.model.SchedulingAppointment;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence of scheduling appointments.
 *
 * <p>Every write addressed by an existing appointment id starts at {@link #findByIdForUpdate(UUID)}:
 * it takes a PESSIMISTIC_WRITE lock on the appointment row, which is the first lock of the documented
 * {@code appointment -> slot} order. Booking ({@code POST}) has no appointment yet, so it locks the
 * slot directly; the other writes hold this lock while they move the slot counter, and two concurrent
 * status changes on the same appointment queue here instead of both releasing capacity.</p>
 */
@Repository
public interface SchedulingAppointmentRepository extends JpaRepository<SchedulingAppointment, UUID> {

    /**
     * Pessimistic write lock on the appointment. Deliberately does not filter by {@code enabled}:
     * a soft-deleted appointment is found so the caller can answer precisely instead of confusing a
     * delete with a not-found.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SchedulingAppointment a WHERE a.id = :id")
    Optional<SchedulingAppointment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Counts the appointments of a slot whose status is one of {@code statusIds}. The capacity
     * invariant is "the number of appointments in a holding status equals {@code slot.booked}", and
     * this is how the integration test asserts it from the data.
     */
    long countBySlotIdAndStatusIdIn(UUID slotId, Collection<UUID> statusIds);

    /**
     * The filtered appointment list (W4b): one appointment per row, ranging on the <b>slot's</b>
     * {@code start_at} in {@code [from, to)} and ordered by that start, so the list is deterministic
     * without a client-supplied sort tied to the appointment itself.
     *
     * <p>Soft-deleted rows are <b>included</b> on purpose: {@code enabled = false} is a soft delete,
     * not a deletion, and the projected record carries the flag so a consumer can filter. It also
     * keeps this read consistent with the calendar (D36), where a soft-deleted {@code Completed}
     * appointment is still counted by {@code booked}.</p>
     */
    @Query("""
            SELECT a FROM SchedulingAppointment a
            JOIN SchedulingSlot s ON s.id = a.slotId
            WHERE a.companyStoreId = :storeId
              AND s.startAt >= :from
              AND s.startAt < :to
            ORDER BY s.startAt ASC, a.id ASC
            """)
    List<SchedulingAppointment> findInRangeByStore(
            @Param("storeId") UUID storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * The filtered appointment list narrowed to one {@code userId}. The service chooses this finder
     * over {@link #findInRangeByStore(UUID, LocalDateTime, LocalDateTime)} when the optional
     * {@code userId} is present, so the predicate is never a null-bound parameter.
     */
    @Query("""
            SELECT a FROM SchedulingAppointment a
            JOIN SchedulingSlot s ON s.id = a.slotId
            WHERE a.companyStoreId = :storeId
              AND a.userId = :userId
              AND s.startAt >= :from
              AND s.startAt < :to
            ORDER BY s.startAt ASC, a.id ASC
            """)
    List<SchedulingAppointment> findInRangeByStoreAndUserId(
            @Param("storeId") UUID storeId,
            @Param("userId") String userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** D35's second batched read: every appointment of every returned slot in one query. */
    List<SchedulingAppointment> findBySlotIdInOrderByIdAsc(Collection<UUID> slotIds);

    /**
     * D35's second batched read narrowed to one {@code userId}. Chosen by the service when the
     * optional calendar filter is present, so the predicate is never a null-bound parameter.
     */
    List<SchedulingAppointment> findBySlotIdInAndUserIdOrderByIdAsc(Collection<UUID> slotIds, String userId);
}
