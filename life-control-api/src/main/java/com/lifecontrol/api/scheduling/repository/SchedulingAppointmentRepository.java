package com.lifecontrol.api.scheduling.repository;

import com.lifecontrol.api.scheduling.model.SchedulingAppointment;
import jakarta.persistence.LockModeType;
import java.util.Collection;
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
}
