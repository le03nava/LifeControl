package com.lifecontrol.api.scheduling.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.customer.model.Customer;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.scheduling.dto.SchedulingCalendarAppointmentResponse;
import com.lifecontrol.api.scheduling.dto.SchedulingCalendarSlotResponse;
import com.lifecontrol.api.scheduling.model.SchedulingAppointment;
import com.lifecontrol.api.scheduling.repository.SchedulingAppointmentRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingCalendarSlotProjection;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The calendar projection (D13): one entry per materialized slot of the store whose {@code start_at}
 * falls in {@code [from, to)}, each carrying its own appointment list (D34).
 *
 * <h2>It never materializes (D33)</h2>
 * <p>This read only projects rows that already exist: no insert, no upsert and no call into the
 * materialization path. The consequence is declared as gap <b>G19</b>: a range nobody has asked
 * {@code GET /api/scheduling/slots} for yet renders as fewer or no slots.</p>
 *
 * <h2>Three batched reads, never one per row (D35)</h2>
 * <ol>
 *   <li>the slots in range, joined to their activity names and flags in the same projection;</li>
 *   <li>every appointment of every returned slot in one query;</li>
 *   <li>the status names and the customer names, each by distinct id in one query.</li>
 * </ol>
 * <p>There is deliberately no per-row lookup: the single-read cost of a week view is what a naive
 * projection would turn into N+1 on the endpoint a UI calls most.</p>
 *
 * <h2>Soft-deleted appointments stay visible (D36)</h2>
 * <p>A soft-deleted appointment is still a row, and a soft-deleted {@code Completed} appointment
 * still holds capacity and is still counted by {@code booked}, so it is included with its
 * {@code enabled} flag rather than filtered out. The projection's {@code booked} is authoritative;
 * the client decides which rows to draw.</p>
 *
 * <h2>Soft-deleted activities stay visible too (D37)</h2>
 * <p>The read never filters by the activity's {@code enabled} flag. Deleting an activity is a soft
 * delete that only flips that flag and deletes no slot, so a booked slot — and an empty one —
 * survives its activity's soft delete. Filtering on the flag would hide exactly the rows whose
 * {@code booked}/{@code available} numbers the calendar renders. The entry exposes
 * {@code activityEnabled} instead, and the client decides what to draw or to offer for booking.</p>
 */
@Service
public class SchedulingCalendarService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulingCalendarService.class);

    private final SchedulingSlotRepository schedulingSlotRepository;
    private final SchedulingAppointmentRepository schedulingAppointmentRepository;
    private final StatusRepository statusRepository;
    private final CustomerRepository customerRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CurrentUserContext currentUserContext;

    public SchedulingCalendarService(
            SchedulingSlotRepository schedulingSlotRepository,
            SchedulingAppointmentRepository schedulingAppointmentRepository,
            StatusRepository statusRepository,
            CustomerRepository customerRepository,
            CompanyStoreRepository companyStoreRepository,
            CurrentUserContext currentUserContext) {
        this.schedulingSlotRepository = schedulingSlotRepository;
        this.schedulingAppointmentRepository = schedulingAppointmentRepository;
        this.statusRepository = statusRepository;
        this.customerRepository = customerRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Projects the store's calendar for {@code [from, to)}, one entry per materialized slot.
     *
     * @param storeId    the store whose slots and appointments are projected
     * @param from       inclusive start of the range, read on the slot's {@code start_at}
     * @param to         exclusive end of the range
     * @param userId     optional filter applied to the appointments, not to the slots
     * @param activityId optional filter that narrows the slots to one activity
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws AccessDeniedException when the caller cannot access the store's scope
     * @throws com.lifecontrol.api.scheduling.exception.InvalidSchedulingRangeException when
     *     {@code to} is not strictly after {@code from} or the span exceeds 90 days (400)
     * @throws StatusNotFoundException when an appointment's status no longer exists
     */
    @Transactional(readOnly = true)
    public List<SchedulingCalendarSlotResponse> getCalendar(
            UUID storeId, LocalDateTime from, LocalDateTime to, String userId, UUID activityId) {
        verifyStoreAccess(storeId);
        SchedulingRangeGuard.validate(from, to);

        // Read 1: the store's materialized slots in range, activity name and flag included. No
        // enabled filter (D37): a booked slot survives its activity's soft delete.
        var rows = activityId == null
                ? schedulingSlotRepository.findCalendarSlots(storeId, from, to)
                : schedulingSlotRepository.findCalendarSlotsByActivityId(storeId, from, to, activityId);
        if (rows.isEmpty()) {
            return List.of();
        }

        var slotIds =
                rows.stream().map(SchedulingCalendarSlotProjection::slotId).collect(Collectors.toSet());

        // Read 2: every appointment of every returned slot in one query.
        var appointments = userId == null
                ? schedulingAppointmentRepository.findBySlotIdInOrderByIdAsc(slotIds)
                : schedulingAppointmentRepository.findBySlotIdInAndUserIdOrderByIdAsc(slotIds, userId);

        // Read 3: the two name lookups, each one query over the distinct ids.
        var statusNames = resolveStatusNames(
                appointments.stream().map(SchedulingAppointment::getStatusId).collect(Collectors.toSet()));
        var customerNames = resolveCustomerNames(appointments.stream()
                .map(SchedulingAppointment::getCustomerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        var appointmentsBySlot = appointments.stream().collect(Collectors.groupingBy(SchedulingAppointment::getSlotId));

        logger.info(
                "SchedulingCalendar read: storeId={}, from={}, to={}, slots={}, appointments={}",
                storeId,
                from,
                to,
                rows.size(),
                appointments.size());

        return rows.stream()
                .map(row -> toResponse(
                        row, appointmentsBySlot.getOrDefault(row.slotId(), List.of()), statusNames, customerNames))
                .toList();
    }

    private SchedulingCalendarSlotResponse toResponse(
            SchedulingCalendarSlotProjection row,
            List<SchedulingAppointment> appointments,
            Map<UUID, String> statusNames,
            Map<UUID, String> customerNames) {
        return new SchedulingCalendarSlotResponse(
                row.slotId(),
                row.activityId(),
                row.activityName(),
                row.activityEnabled(),
                row.startAt(),
                row.endAt(),
                row.capacity(),
                row.booked(),
                row.capacity() - row.booked(),
                row.status(),
                appointments.stream()
                        .map(appointment -> new SchedulingCalendarAppointmentResponse(
                                appointment.getId(),
                                appointment.getUserId(),
                                appointment.getCustomerId(),
                                appointment.getCustomerId() == null
                                        ? null
                                        : customerNames.get(appointment.getCustomerId()),
                                appointment.getStatusId(),
                                requireStatusName(statusNames, appointment.getStatusId()),
                                appointment.getNotes(),
                                appointment.getEnabled()))
                        .toList());
    }

    /**
     * Resolves status names for a set of ids with a single {@code findAllById}, so the projection
     * never issues one query per appointment (D35). A status id that resolves to nothing is reported
     * as missing by {@link #requireStatusName}.
     */
    private Map<UUID, String> resolveStatusNames(Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) {
            return Map.of();
        }
        return statusRepository.findAllById(statusIds).stream()
                .collect(Collectors.toMap(Status::getId, Status::getStatusName));
    }

    /**
     * Resolves customer names for a set of ids with a single {@code findAllById} (D35). A customer id
     * that resolves to nothing yields a {@code null} name rather than failing the whole read: the
     * customer reference on an appointment is optional and the column carries no cascade, so a null
     * name is the honest projection of a missing row.
     */
    private Map<UUID, String> resolveCustomerNames(Collection<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        return customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getName));
    }

    private static String requireStatusName(Map<UUID, String> names, UUID statusId) {
        var name = names.get(statusId);
        if (name == null) {
            throw new StatusNotFoundException(statusId);
        }
        return name;
    }

    /**
     * Loads the store and authorizes the caller for its whole scope.
     *
     * <p>The same faithful copy the other scheduling services carry, deliberately not extracted into
     * a shared helper: extracting it is its own work unit, and a ~10-line read-only scope check is
     * cheaper duplicated than coupling the scheduling services through a new abstraction. The
     * company &rarr; country &rarr; region &rarr; zone &rarr; store chain is derived from the store
     * itself because the flat endpoint carries only a store id.</p>
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
