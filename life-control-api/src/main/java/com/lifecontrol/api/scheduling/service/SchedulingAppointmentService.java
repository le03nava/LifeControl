package com.lifecontrol.api.scheduling.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentRescheduleRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentResponse;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentStatusRequest;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingRangeException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.exception.SchedulingAppointmentNotFoundException;
import com.lifecontrol.api.scheduling.exception.SchedulingAppointmentNotModifiableException;
import com.lifecontrol.api.scheduling.exception.SchedulingSlotNotBookableException;
import com.lifecontrol.api.scheduling.exception.SchedulingSlotNotFoundException;
import com.lifecontrol.api.scheduling.model.SchedulingAppointment;
import com.lifecontrol.api.scheduling.model.SchedulingSlot;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAppointmentRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.service.StatusValidator;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking lifecycle of an appointment: book it, move it, cancel it.
 *
 * <h2>Lock order of the slice</h2>
 * <p>{@code appointment -> slot}, and two slots in ascending {@code (start_at, id)} order. Booking
 * ({@link #create}) has no appointment yet, so it locks the slot first; every other write locks the
 * appointment row first, then the slot it mutates. A reschedule touches two slots, so it locks them
 * in ascending {@code (start_at, id)} order before releasing the source and taking the target. The
 * order is cycle-free because no path ever locks a slot before the appointment that owns it (booking
 * creates a brand-new appointment, so there is nothing to lock yet) and no path locks a later slot
 * before an earlier one.</p>
 *
 * <h2>The capacity invariant</h2>
 * <p>{@code slot.booked} is the number of appointments whose status holds capacity (D25). The only
 * writers of the counter are this service's two edges: {@code +1} on booking, and {@code -1} only
 * when a transition moves an appointment out of a holding status into {@code Cancelled} or
 * {@code NoShow}. {@code DELETE} applies that same release plus a soft delete only when the
 * transition map allows the {@code -> Cancelled} edge; a terminal appointment such as
 * {@code Completed} is only soft-deleted and keeps its seat, and an appointment already in a
 * releasing status is not decremented again, so {@code DELETE} is idempotent. The slot's own
 * {@code status} is written here too, as {@code 'Full'} exactly when {@code booked == capacity} and
 * {@code 'Available'} otherwise (D26).</p>
 *
 * <p>Every operation authorizes the caller against the appointment's store before mutating: the
 * endpoints are flat, so {@code @PreAuthorize} alone can only prove the caller holds some
 * scheduling-scoped role. The store the booking path uses is derived from the locked slot's activity,
 * which is what keeps a client from booking into a store it does not own.</p>
 */
@Service
public class SchedulingAppointmentService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulingAppointmentService.class);

    /** Status family of an appointment. */
    private static final String APPOINTMENT_STATUS_TYPE = "APPOINTMENT";

    /** The status every booking is created with (D23). */
    private static final String SCHEDULED_STATUS_NAME = "Scheduled";

    /**
     * The status {@code DELETE} moves a {@code Scheduled} or {@code Confirmed} appointment to. A
     * terminal appointment has no {@code -> Cancelled} edge, so it skips this target.
     */
    private static final String CANCELLED_STATUS_NAME = "Cancelled";

    /** Slot status strings, written only by this booking path (D26). */
    private static final String SLOT_STATUS_AVAILABLE = "Available";

    private static final String SLOT_STATUS_FULL = "Full";

    /**
     * Statuses that hold capacity: an appointment in one of these counts toward {@code slot.booked}
     * (D23). The complement, {@code Cancelled} and {@code NoShow}, has released its seat.
     */
    private static final Set<String> HOLDING_STATUS_NAMES = Set.of("Scheduled", "Confirmed", "Completed");

    /**
     * The appointment transition map, keyed by current status NAME (D24). A missing key or an empty
     * set means terminal, so an unknown status name is terminal too. Only a move from a holding status
     * into {@code Cancelled} or {@code NoShow} releases capacity.
     */
    private static final Map<String, Set<String>> APPOINTMENT_TRANSITIONS = Map.ofEntries(
            Map.entry("Scheduled", Set.of("Confirmed", "Completed", "Cancelled", "NoShow")),
            Map.entry("Confirmed", Set.of("Completed", "Cancelled", "NoShow")),
            Map.entry("Completed", Set.of()),
            Map.entry("Cancelled", Set.of()),
            Map.entry("NoShow", Set.of()));

    private final SchedulingAppointmentRepository schedulingAppointmentRepository;
    private final SchedulingSlotRepository schedulingSlotRepository;
    private final SchedulingActivityRepository schedulingActivityRepository;
    private final StatusRepository statusRepository;
    private final CustomerRepository customerRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CurrentUserContext currentUserContext;

    public SchedulingAppointmentService(
            SchedulingAppointmentRepository schedulingAppointmentRepository,
            SchedulingSlotRepository schedulingSlotRepository,
            SchedulingActivityRepository schedulingActivityRepository,
            StatusRepository statusRepository,
            CustomerRepository customerRepository,
            CompanyStoreRepository companyStoreRepository,
            CurrentUserContext currentUserContext) {
        this.schedulingAppointmentRepository = schedulingAppointmentRepository;
        this.schedulingSlotRepository = schedulingSlotRepository;
        this.schedulingActivityRepository = schedulingActivityRepository;
        this.statusRepository = statusRepository;
        this.customerRepository = customerRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Books an appointment into the slot named by the request.
     *
     * <p>The store is derived from the slot's activity, never from the request: the slot is locked
     * first, the activity it points at supplies the store the guard checks, and the appointment is
     * written with that store and that activity. The status is always {@code Scheduled}; the request
     * carries none.</p>
     *
     * @throws SchedulingSlotNotFoundException when the slot does not exist
     * @throws SchedulingActivityNotFoundException when the slot's activity does not exist
     * @throws CustomerNotFoundException when a customer id is supplied but does not exist
     * @throws SchedulingSlotNotBookableException when the slot is disabled or full (409)
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot access
     *     the slot's store
     * @throws StatusNotFoundException when the {@code APPOINTMENT} / {@code Scheduled} status is
     *     missing
     */
    @Transactional
    public SchedulingAppointmentResponse create(SchedulingAppointmentRequest request) {
        // findByIdForUpdate is this transaction's FIRST database interaction (D28): it holds a
        // PESSIMISTIC_WRITE lock on the slot for the whole transaction, which is what makes the room
        // guard and the counter increment atomic against a concurrent booking of the same slot.
        var slot = schedulingSlotRepository
                .findByIdForUpdate(request.slotId())
                .orElseThrow(() -> new SchedulingSlotNotFoundException(request.slotId()));

        var activity = schedulingActivityRepository
                .findById(slot.getActivityId())
                .orElseThrow(() -> new SchedulingActivityNotFoundException(slot.getActivityId()));
        verifyStoreAccess(activity.getCompanyStoreId());

        if (request.customerId() != null && !customerRepository.existsById(request.customerId())) {
            throw new CustomerNotFoundException(request.customerId());
        }

        // The room guard runs inside the lock, after the tenant check so a foreign slot answers 403
        // rather than a bookability detail it should not reveal.
        requireBookable(slot);

        var scheduled = statusRepository
                .findByTypeNameAndStatusName(APPOINTMENT_STATUS_TYPE, SCHEDULED_STATUS_NAME)
                .orElseThrow(() -> new StatusNotFoundException(
                        "Status '" + SCHEDULED_STATUS_NAME + "' not found for " + APPOINTMENT_STATUS_TYPE + " type"));

        var appointment = SchedulingAppointment.builder()
                .slotId(slot.getId())
                .activityId(activity.getId())
                .companyStoreId(activity.getCompanyStoreId())
                .userId(request.userId())
                .customerId(request.customerId())
                .statusId(scheduled.getId())
                .notes(request.notes())
                .enabled(true)
                .build();

        // Flush so the response carries the real @Version and the non-null auditable timestamps.
        var saved = schedulingAppointmentRepository.saveAndFlush(appointment);
        occupy(slot);
        logger.info(
                "SchedulingAppointment created: id={}, slotId={}, activityId={}, storeId={}",
                saved.getId(),
                slot.getId(),
                activity.getId(),
                activity.getCompanyStoreId());
        return toResponse(saved, slot, scheduled.getStatusName());
    }

    /**
     * Reads one appointment with its slot's window.
     *
     * @throws SchedulingAppointmentNotFoundException when the appointment does not exist
     * @throws SchedulingSlotNotFoundException when the appointment's slot does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot access
     *     the appointment's store
     * @throws StatusNotFoundException when the appointment's status no longer exists
     */
    @Transactional(readOnly = true)
    public SchedulingAppointmentResponse getById(UUID id) {
        var appointment = schedulingAppointmentRepository
                .findById(id)
                .orElseThrow(() -> new SchedulingAppointmentNotFoundException(id));
        verifyStoreAccess(appointment.getCompanyStoreId());

        var slot = schedulingSlotRepository
                .findById(appointment.getSlotId())
                .orElseThrow(() -> new SchedulingSlotNotFoundException(appointment.getSlotId()));
        var statusNames = resolveStatusNames(Set.of(appointment.getStatusId()));
        return toResponse(appointment, slot, requireName(statusNames, appointment.getStatusId()));
    }

    /**
     * Reads the store's appointments whose slot's {@code start_at} falls in {@code [from, to)},
     * ordered by that start, optionally narrowed to one {@code userId}.
     *
     * <p>The range is interpreted on the <b>slot's</b> start, the same half-open convention
     * {@code GET /slots} uses, and the slots are loaded in one batch to fill each response's window
     * so the read never issues one query per row.</p>
     *
     * <p><b>An appointment whose slot row is gone is dropped, not fatal.</b> The range query
     * inner-joins the slot, so every row it returns has a slot at query time, but the follow-up
     * {@code findAllById} is a <b>second statement</b>: under {@code READ COMMITTED} a concurrent
     * {@code PUT} of the activity's availability can commit the deletion of a {@code booked = 0} slot
     * between the two. An appointment whose slot is missing from that batch is omitted from the
     * result rather than raised as a 404, because the appointment's time <b>is</b> its slot and it is
     * no longer findable by either range read (G20). One orphaned row must not fail the whole read.
     * The calendar path degrades the same way; neither read materializes or repairs the missing row.</p>
     *
     * <p><b>Soft-deleted appointments are included.</b> An appointment the store cancelled is a row
     * with {@code enabled = false}, not a deleted one, and this read deliberately does not filter it
     * out: the response carries the {@code enabled} flag so a consumer can choose, which keeps the
     * flat list consistent with the calendar projection (D36), where a soft-deleted {@code Completed}
     * appointment still holds capacity and is still counted by {@code booked}. Consumers that want
     * only live appointments filter on {@code enabled}.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws AccessDeniedException when the caller cannot access the store's scope
     * @throws InvalidSchedulingRangeException when {@code to} is not strictly after {@code from} or
     *     the span exceeds 90 days (400)
     * @throws StatusNotFoundException when an appointment's status no longer exists
     */
    @Transactional(readOnly = true)
    public List<SchedulingAppointmentResponse> getAppointments(
            UUID storeId, LocalDateTime from, LocalDateTime to, String userId) {
        verifyStoreAccess(storeId);
        SchedulingRangeGuard.validate(from, to);

        var appointments = userId == null
                ? schedulingAppointmentRepository.findInRangeByStore(storeId, from, to)
                : schedulingAppointmentRepository.findInRangeByStoreAndUserId(storeId, userId, from, to);
        if (appointments.isEmpty()) {
            return List.of();
        }

        var slotIds =
                appointments.stream().map(SchedulingAppointment::getSlotId).collect(Collectors.toSet());
        var slotsById = schedulingSlotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(SchedulingSlot::getId, slot -> slot));
        var statusNames = resolveStatusNames(
                appointments.stream().map(SchedulingAppointment::getStatusId).collect(Collectors.toSet()));

        // An appointment whose slot vanished between the range query and this batch is filtered out
        // here: G20 drops it from the read instead of turning one orphaned row into a 404 for the
        // whole store.
        return appointments.stream()
                .filter(appointment -> slotsById.containsKey(appointment.getSlotId()))
                .map(appointment -> toResponse(
                        appointment,
                        slotsById.get(appointment.getSlotId()),
                        requireName(statusNames, appointment.getStatusId())))
                .toList();
    }

    /**
     * Moves an appointment to another slot of the same activity, releasing the source and taking room
     * on the target under the same guard. The status is kept (D29).
     *
     * <p>A reschedule is allowed only while the appointment's status is non-terminal in the
     * transition map ({@code Scheduled} or {@code Confirmed}); a terminal status ({@code Completed},
     * {@code Cancelled} or {@code NoShow}) answers 409. Changability is <em>terminality</em>, not
     * capacity. Moving a {@code Completed} appointment would rewrite what already happened and
     * double-count the seat: it would take room in a new slot while the completed one still occupies
     * its own. A {@code Cancelled} or {@code NoShow} appointment already released its seat, so moving
     * it would take room it does not hold. {@code Completed} is the one status where terminality and
     * holding differ (it holds capacity and is terminal), which is why the guard reads the transition
     * map and not the holding set. The two slots are locked in ascending {@code (start_at, id)} order
     * before either is mutated, which is the slice's lock-order contract (D27).</p>
     *
     * @throws SchedulingAppointmentNotFoundException when the appointment does not exist
     * @throws SchedulingSlotNotFoundException when the source or target slot does not exist
     * @throws SchedulingAppointmentNotModifiableException when the appointment is terminal (409)
     * @throws SchedulingSlotNotBookableException when the target slot is disabled, full, or belongs
     *     to another activity (409)
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot access
     *     the appointment's store
     * @throws StatusNotFoundException when the appointment's status no longer exists
     */
    @Transactional
    public SchedulingAppointmentResponse reschedule(UUID id, SchedulingAppointmentRescheduleRequest request) {
        var appointment = schedulingAppointmentRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new SchedulingAppointmentNotFoundException(id));
        verifyStoreAccess(appointment.getCompanyStoreId());

        var currentStatus = statusRepository
                .findById(appointment.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(appointment.getStatusId()));
        // Terminality, not capacity, gates the move: Completed is terminal in the map yet still
        // holds its seat, so the holding set would wrongly allow moving it (D29).
        if (isTerminal(currentStatus.getStatusName())) {
            throw new SchedulingAppointmentNotModifiableException(id, currentStatus.getStatusName());
        }

        var sourceId = appointment.getSlotId();
        var targetId = request.slotId();

        // Same slot: nothing to release or retake, so the counter must not move. Re-read the slot for
        // the response window without disturbing it.
        if (sourceId.equals(targetId)) {
            var slot = schedulingSlotRepository
                    .findById(sourceId)
                    .orElseThrow(() -> new SchedulingSlotNotFoundException(sourceId));
            return toResponse(appointment, slot, currentStatus.getStatusName());
        }

        // Probe both slots read-only to know their (start_at, id) order; start_at is immutable once a
        // slot exists, so the probe cannot reorder the pair frames later. Then take the two
        // pessimistic locks strictly in that order (D27).
        var sourceProbe = schedulingSlotRepository
                .findById(sourceId)
                .orElseThrow(() -> new SchedulingSlotNotFoundException(sourceId));
        var targetProbe = schedulingSlotRepository
                .findById(targetId)
                .orElseThrow(() -> new SchedulingSlotNotFoundException(targetId));

        SchedulingSlot source;
        SchedulingSlot target;
        if (comesBefore(sourceProbe, targetProbe)) {
            source = lockSlot(sourceId);
            target = lockSlot(targetId);
        } else {
            target = lockSlot(targetId);
            source = lockSlot(sourceId);
        }

        // An appointment is a booking of one activity; a reschedule changes the time window only.
        if (!target.getActivityId().equals(appointment.getActivityId())) {
            throw SchedulingSlotNotBookableException.foreignActivity(targetId);
        }
        requireBookable(target);

        release(source);
        occupy(target);

        appointment.setSlotId(targetId);
        var saved = schedulingAppointmentRepository.saveAndFlush(appointment);
        logger.info("SchedulingAppointment rescheduled: id={}, fromSlotId={}, toSlotId={}", id, sourceId, targetId);
        return toResponse(saved, target, currentStatus.getStatusName());
    }

    /**
     * Applies a status transition to an appointment. The target id must name an {@code APPOINTMENT}
     * status, and the edge must exist in the transition map (D24); an edge that moves a holding
     * appointment into {@code Cancelled} or {@code NoShow} releases its seat.
     *
     * @throws SchedulingAppointmentNotFoundException when the appointment does not exist
     * @throws StatusNotFoundException when the appointment's current status or the target id does not
     *     exist
     * @throws IllegalArgumentException when the target status does not belong to the
     *     {@code APPOINTMENT} type (400)
     * @throws com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException when the
     *     edge is not allowed (409)
     * @throws SchedulingSlotNotFoundException when the released slot does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot access
     *     the appointment's store
     */
    @Transactional
    public SchedulingAppointmentResponse updateStatus(UUID id, SchedulingAppointmentStatusRequest request) {
        var appointment = schedulingAppointmentRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new SchedulingAppointmentNotFoundException(id));
        verifyStoreAccess(appointment.getCompanyStoreId());

        var currentStatus = statusRepository
                .findById(appointment.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(appointment.getStatusId()));
        var targetStatus =
                StatusValidator.requireStatusOfType(statusRepository, request.statusId(), APPOINTMENT_STATUS_TYPE);
        validateTransition(currentStatus, targetStatus);

        SchedulingSlot slot;
        if (releasesCapacity(currentStatus.getStatusName(), targetStatus.getStatusName())) {
            slot = lockSlot(appointment.getSlotId());
            release(slot);
        } else {
            slot = schedulingSlotRepository
                    .findById(appointment.getSlotId())
                    .orElseThrow(() -> new SchedulingSlotNotFoundException(appointment.getSlotId()));
        }

        appointment.setStatusId(targetStatus.getId());
        var saved = schedulingAppointmentRepository.saveAndFlush(appointment);
        logger.info(
                "SchedulingAppointment status changed: id={}, from={}, to={}",
                id,
                currentStatus.getStatusName(),
                targetStatus.getStatusName());
        return toResponse(saved, slot, targetStatus.getStatusName());
    }

    /**
     * Cancels an appointment: it soft-deletes the row ({@code enabled = false}) and applies the
     * {@code -> Cancelled} transition only when the transition map allows that edge. The transition
     * map is the single authority here, not the holding-status set.
     *
     * <p>{@code Scheduled} and {@code Confirmed} have the {@code -> Cancelled} edge, so their seat is
     * released (decrement {@code booked}, back to {@code 'Available'}) and their status becomes
     * {@code Cancelled}. A terminal appointment has no outgoing edge, so a {@code Completed} row is
     * <em>only</em> soft-deleted: its {@code statusId}, its slot and the slot's {@code booked} counter
     * are left exactly as they were. The same no-op applies to {@code Cancelled} and {@code NoShow},
     * which already released their seat, so they are never decremented twice (D25). The invariant
     * "appointments in a holding status equal {@code slot.booked}" still holds for a terminal
     * appointment because a soft-deleted {@code Completed} row remains a holding row and keeps
     * counting toward {@code booked}.</p>
     *
     * <p>{@code DELETE} never answers 409: a missing edge simply means no release to apply.</p>
     *
     * @throws SchedulingAppointmentNotFoundException when the appointment does not exist
     * @throws StatusNotFoundException when the appointment's status or the {@code Cancelled} status is
     *     missing
     * @throws SchedulingSlotNotFoundException when the released slot does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot access
     *     the appointment's store
     */
    @Transactional
    public void delete(UUID id) {
        var appointment = schedulingAppointmentRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new SchedulingAppointmentNotFoundException(id));
        verifyStoreAccess(appointment.getCompanyStoreId());

        var currentStatus = statusRepository
                .findById(appointment.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(appointment.getStatusId()));

        if (allowsTransition(currentStatus.getStatusName(), CANCELLED_STATUS_NAME)) {
            release(lockSlot(appointment.getSlotId()));
            var cancelled = statusRepository
                    .findByTypeNameAndStatusName(APPOINTMENT_STATUS_TYPE, CANCELLED_STATUS_NAME)
                    .orElseThrow(() -> new StatusNotFoundException("Status '" + CANCELLED_STATUS_NAME
                            + "' not found for " + APPOINTMENT_STATUS_TYPE + " type"));
            appointment.setStatusId(cancelled.getId());
        }

        appointment.setEnabled(false);
        schedulingAppointmentRepository.saveAndFlush(appointment);
        logger.info("SchedulingAppointment cancelled and soft-deleted: id={}", id);
    }

    // ── Capacity invariant ──────────────────────────────────────────────

    /**
     * Rejects a slot that exists but takes no booking: disabled, or already at capacity (D28). Both
     * answer 409 through {@link SchedulingSlotNotBookableException}. The caller must already hold the
     * slot's pessimistic lock, so the check and the counter move are one atomic step.
     */
    private void requireBookable(SchedulingSlot slot) {
        if (!Boolean.TRUE.equals(slot.getEnabled())) {
            throw SchedulingSlotNotBookableException.disabled(slot.getId());
        }
        if (slot.getBooked() >= slot.getCapacity()) {
            throw SchedulingSlotNotBookableException.full(slot.getId(), slot.getBooked(), slot.getCapacity());
        }
    }

    /** {@code booked += 1}, and {@code 'Full'} exactly when the slot reached its capacity (D26). */
    private void occupy(SchedulingSlot slot) {
        slot.setBooked(slot.getBooked() + 1);
        slot.setStatus(slot.getBooked() == slot.getCapacity() ? SLOT_STATUS_FULL : SLOT_STATUS_AVAILABLE);
        schedulingSlotRepository.save(slot);
    }

    /**
     * {@code booked -= 1}, back to {@code 'Available'} once the slot drops below capacity (D25, D26).
     * Only ever called for a slot the appointment held, so {@code booked} is at least one and the
     * counter never goes negative.
     */
    private void release(SchedulingSlot slot) {
        slot.setBooked(slot.getBooked() - 1);
        slot.setStatus(slot.getBooked() == slot.getCapacity() ? SLOT_STATUS_FULL : SLOT_STATUS_AVAILABLE);
        schedulingSlotRepository.save(slot);
    }

    private SchedulingSlot lockSlot(UUID slotId) {
        return schedulingSlotRepository
                .findByIdForUpdate(slotId)
                .orElseThrow(() -> new SchedulingSlotNotFoundException(slotId));
    }

    private boolean comesBefore(SchedulingSlot first, SchedulingSlot second) {
        var byStart = first.getStartAt().compareTo(second.getStartAt());
        return byStart < 0 || (byStart == 0 && first.getId().compareTo(second.getId()) < 0);
    }

    // ── Status transitions ──────────────────────────────────────────────

    /**
     * The transition map is the single authority on which edges exist (D24). A missing key or a
     * missing target means the edge does not exist, so an unknown current status is terminal.
     */
    private static boolean allowsTransition(String currentName, String targetName) {
        return APPOINTMENT_TRANSITIONS.getOrDefault(currentName, Set.of()).contains(targetName);
    }

    /**
     * Terminality is the map's "no outgoing edge" property: a missing key or an empty set means the
     * status is terminal, so an unknown status name is terminal too. Reads the same map as
     * {@link #allowsTransition}, which remains the edge authority for the whole class.
     */
    private static boolean isTerminal(String currentName) {
        return APPOINTMENT_TRANSITIONS.getOrDefault(currentName, Set.of()).isEmpty();
    }

    private void validateTransition(Status current, Status target) {
        if (!allowsTransition(current.getStatusName(), target.getStatusName())) {
            throw new InvalidStatusTransitionException(current.getStatusName(), target.getStatusName());
        }
    }

    /**
     * Capacity is released exactly when the appointment was holding it and the target status does
     * not, which by the map is a move into {@code Cancelled} or {@code NoShow}. Every other edge is
     * within the holding set, or out of a terminal status, which the map already forbids.
     */
    private static boolean releasesCapacity(String currentName, String targetName) {
        return HOLDING_STATUS_NAMES.contains(currentName) && !HOLDING_STATUS_NAMES.contains(targetName);
    }

    // ── Mapping and scope ───────────────────────────────────────────────

    private SchedulingAppointmentResponse toResponse(
            SchedulingAppointment appointment, SchedulingSlot slot, String statusName) {
        return new SchedulingAppointmentResponse(
                appointment.getId(),
                appointment.getSlotId(),
                slot.getStartAt(),
                slot.getEndAt(),
                appointment.getActivityId(),
                appointment.getCompanyStoreId(),
                appointment.getUserId(),
                appointment.getCustomerId(),
                appointment.getStatusId(),
                statusName,
                appointment.getNotes(),
                appointment.getEnabled(),
                appointment.getVersion(),
                appointment.getCreatedAt(),
                appointment.getUpdatedAt());
    }

    /**
     * Resolves status names for a set of ids with a single {@code findAllById}, so a page read never
     * issues one query per row (D22). A status id that resolves to nothing is reported as missing by
     * {@link #requireName}.
     */
    private Map<UUID, String> resolveStatusNames(Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) {
            return Map.of();
        }
        return statusRepository.findAllById(statusIds).stream()
                .collect(Collectors.toMap(Status::getId, Status::getStatusName));
    }

    private static String requireName(Map<UUID, String> names, UUID statusId) {
        var name = names.get(statusId);
        if (name == null) {
            throw new StatusNotFoundException(statusId);
        }
        return name;
    }

    /**
     * Loads the store and authorizes the caller for its whole scope.
     *
     * <p>A faithful copy of the other three scheduling services' check, deliberately not extracted
     * into a shared helper: extracting it is its own work unit, and a ~10-line read-only scope check
     * is cheaper duplicated than coupling the scheduling services through a new abstraction. The
     * company &rarr; country &rarr; region &rarr; zone &rarr; store chain is derived from the store
     * itself because the flat endpoints carry only a slot or appointment id.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws AccessDeniedException when the caller holds no grant for the store's scope
     */
    private void verifyStoreAccess(UUID companyStoreId) {
        var store = companyStoreRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new CompanyStoreNotFoundException(companyStoreId));

        var zone = store.getCompanyZone();
        var region = zone.getCompanyRegion();
        var country = region.getCompanyCountry();
        currentUserContext.verifyCompanyStoreAccess(
                country.getCompany().getId(), country.getId(), region.getId(), zone.getId(), store.getId());
    }
}
