package com.lifecontrol.api.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentRescheduleRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentStatusRequest;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingRangeException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.exception.SchedulingAppointmentNotFoundException;
import com.lifecontrol.api.scheduling.exception.SchedulingAppointmentNotModifiableException;
import com.lifecontrol.api.scheduling.exception.SchedulingSlotNotBookableException;
import com.lifecontrol.api.scheduling.exception.SchedulingSlotNotFoundException;
import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import com.lifecontrol.api.scheduling.model.SchedulingAppointment;
import com.lifecontrol.api.scheduling.model.SchedulingSlot;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAppointmentRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit coverage of {@link SchedulingAppointmentService}: the booking path and its room guard, the
 * capacity invariant on the two lifecycle edges, the transition map, and the reschedule lock order.
 * The real pessimistic locking and the schema live in the PostgreSQL integration test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchedulingAppointmentService Tests")
class SchedulingAppointmentServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID COMPANY_COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a6");
    private static final UUID SLOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a7");
    private static final UUID TARGET_SLOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a8");
    private static final UUID APPOINTMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID STATUS_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    private static final UUID SCHEDULED_STATUS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    private static final UUID TARGET_STATUS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ad");

    private static final LocalDateTime SLOT_START = LocalDateTime.of(2026, 9, 28, 9, 0);
    private static final LocalDateTime TARGET_SLOT_START = LocalDateTime.of(2026, 9, 28, 8, 0);

    @Mock
    private SchedulingAppointmentRepository schedulingAppointmentRepository;

    @Mock
    private SchedulingSlotRepository schedulingSlotRepository;

    @Mock
    private SchedulingActivityRepository schedulingActivityRepository;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    private SchedulingAppointmentService service;
    private SchedulingActivity activity;
    private SchedulingSlot slot;

    @BeforeEach
    void setUp() {
        service = new SchedulingAppointmentService(
                schedulingAppointmentRepository,
                schedulingSlotRepository,
                schedulingActivityRepository,
                statusRepository,
                customerRepository,
                companyStoreRepository,
                currentUserContext);

        var company = Company.builder().id(COMPANY_ID).companyKey("SC-APT").build();
        var companyCountry =
                CompanyCountry.builder().id(COMPANY_COUNTRY_ID).company(company).build();
        var companyRegion = CompanyRegion.builder()
                .id(REGION_ID)
                .companyCountry(companyCountry)
                .build();
        var companyZone =
                CompanyZone.builder().id(ZONE_ID).companyRegion(companyRegion).build();
        var store = CompanyStore.builder()
                .id(STORE_ID)
                .companyZone(companyZone)
                .storeName("Store")
                .enabled(true)
                .build();

        activity = SchedulingActivity.builder()
                .id(ACTIVITY_ID)
                .companyStoreId(STORE_ID)
                .activityName("Yoga")
                .durationMinutes(60)
                .capacityPerSlot(1)
                .enabled(true)
                .build();
        slot = slot(SLOT_ID, ACTIVITY_ID, SLOT_START, 1, 0, "Available", true);

        when(companyStoreRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
        when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
        when(schedulingAppointmentRepository.saveAndFlush(any(SchedulingAppointment.class)))
                .thenAnswer(invocation -> {
                    SchedulingAppointment saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId(APPOINTMENT_ID);
                    }
                    return saved;
                });
    }

    // ── Fixtures and stubs ──────────────────────────────────────────────

    private static SchedulingSlot slot(
            UUID id, UUID activityId, LocalDateTime startAt, int capacity, int booked, String status, boolean enabled) {
        return SchedulingSlot.builder()
                .id(id)
                .activityId(activityId)
                .startAt(startAt)
                .endAt(startAt.plusMinutes(60))
                .capacity(capacity)
                .booked(booked)
                .status(status)
                .enabled(enabled)
                .build();
    }

    private static SchedulingAppointment appointment(UUID slotId, String statusId, boolean enabled) {
        return SchedulingAppointment.builder()
                .id(APPOINTMENT_ID)
                .slotId(slotId)
                .activityId(ACTIVITY_ID)
                .companyStoreId(STORE_ID)
                .userId("employee-1")
                .statusId(UUID.fromString(statusId))
                .enabled(enabled)
                .build();
    }

    private static Status status(String name) {
        return Status.builder()
                .id(UUID.nameUUIDFromBytes(name.getBytes()))
                .statusName(name)
                .statusType(StatusType.builder()
                        .id(STATUS_TYPE_ID)
                        .statusTypeName("APPOINTMENT")
                        .build())
                .enabled(true)
                .build();
    }

    private void stubScheduledStatus() {
        var scheduled = Status.builder()
                .id(SCHEDULED_STATUS_ID)
                .statusName("Scheduled")
                .statusType(StatusType.builder()
                        .id(STATUS_TYPE_ID)
                        .statusTypeName("APPOINTMENT")
                        .build())
                .enabled(true)
                .build();
        when(statusRepository.findByTypeNameAndStatusName("APPOINTMENT", "Scheduled"))
                .thenReturn(Optional.of(scheduled));
    }

    private void denyStoreAccess() {
        doThrow(new AccessDeniedException("denied"))
                .when(currentUserContext)
                .verifyCompanyStoreAccess(any(), any(), any(), any(), any());
    }

    // ── Booking ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("booking")
    class BookingTests {

        @Test
        @DisplayName("should book, increment booked, flip the slot to Full at capacity and map the window")
        void bookingIncrementsAndFills() {
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));
            stubScheduledStatus();
            when(customerRepository.existsById(CUSTOMER_ID)).thenReturn(true);

            var response =
                    service.create(new SchedulingAppointmentRequest(SLOT_ID, "employee-1", CUSTOMER_ID, "first visit"));

            assertThat(slot.getBooked()).isEqualTo(1);
            assertThat(slot.getStatus()).isEqualTo("Full");

            assertThat(response.id()).isEqualTo(APPOINTMENT_ID);
            assertThat(response.slotId()).isEqualTo(SLOT_ID);
            assertThat(response.startAt()).isEqualTo(SLOT_START);
            assertThat(response.endAt()).isEqualTo(SLOT_START.plusMinutes(60));
            assertThat(response.activityId()).isEqualTo(ACTIVITY_ID);
            assertThat(response.companyStoreId()).isEqualTo(STORE_ID);
            assertThat(response.userId()).isEqualTo("employee-1");
            assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(response.statusId()).isEqualTo(SCHEDULED_STATUS_ID);
            assertThat(response.statusName()).isEqualTo("Scheduled");
            assertThat(response.notes()).isEqualTo("first visit");
            assertThat(response.enabled()).isTrue();

            verify(customerRepository).existsById(CUSTOMER_ID);
            verify(schedulingSlotRepository).save(slot);
        }

        @Test
        @DisplayName("should stay Available when the booking does not fill a capacity-2 slot")
        void bookingBelowCapacityStaysAvailable() {
            slot.setCapacity(2);
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));
            stubScheduledStatus();

            service.create(new SchedulingAppointmentRequest(SLOT_ID, null, null, null));

            assertThat(slot.getBooked()).isEqualTo(1);
            assertThat(slot.getStatus()).isEqualTo("Available");
        }

        @Test
        @DisplayName("should leave userId unassigned when the request omits it, never defaulting to the caller")
        void omittedUserStaysUnassigned() {
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));
            stubScheduledStatus();

            var response = service.create(new SchedulingAppointmentRequest(SLOT_ID, null, null, null));

            assertThat(response.userId()).isNull();
        }

        @Test
        @DisplayName("should return 404 for an unknown slot")
        void unknownSlotIsNotFound() {
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(new SchedulingAppointmentRequest(SLOT_ID, null, null, null)))
                    .isInstanceOf(SchedulingSlotNotFoundException.class);

            verifyNoInteractions(schedulingAppointmentRepository);
        }

        @Test
        @DisplayName("should return 404 when the slot's activity is gone")
        void unknownActivityIsNotFound() {
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(new SchedulingAppointmentRequest(SLOT_ID, null, null, null)))
                    .isInstanceOf(SchedulingActivityNotFoundException.class);
        }

        @Test
        @DisplayName("should raise AccessDenied and write nothing when the store scope is denied")
        void deniedStoreScopeWritesNothing() {
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));
            denyStoreAccess();

            assertThatThrownBy(() -> service.create(new SchedulingAppointmentRequest(SLOT_ID, null, null, null)))
                    .isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(schedulingAppointmentRepository);
        }

        @Test
        @DisplayName("should return 409 for a disabled slot")
        void disabledSlotIsConflict() {
            slot.setEnabled(false);
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));

            assertThatThrownBy(() -> service.create(new SchedulingAppointmentRequest(SLOT_ID, null, null, null)))
                    .isInstanceOf(SchedulingSlotNotBookableException.class)
                    .hasMessageContaining("disabled");

            verifyNoInteractions(schedulingAppointmentRepository);
        }

        @Test
        @DisplayName("should return 409 for a full slot")
        void fullSlotIsConflict() {
            slot.setBooked(1);
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));

            assertThatThrownBy(() -> service.create(new SchedulingAppointmentRequest(SLOT_ID, null, null, null)))
                    .isInstanceOf(SchedulingSlotNotBookableException.class)
                    .hasMessageContaining("full");

            verifyNoInteractions(schedulingAppointmentRepository);
        }

        @Test
        @DisplayName("should return 404 when a supplied customer id does not exist")
        void absentCustomerIsNotFound() {
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));
            when(customerRepository.existsById(CUSTOMER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.create(new SchedulingAppointmentRequest(SLOT_ID, null, CUSTOMER_ID, null)))
                    .isInstanceOf(CustomerNotFoundException.class);

            verifyNoInteractions(schedulingAppointmentRepository);
        }

        @Test
        @DisplayName("should not check customer existence when the request omits the customer")
        void omittedCustomerSkipsTheCheck() {
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));
            stubScheduledStatus();

            service.create(new SchedulingAppointmentRequest(SLOT_ID, null, null, null));

            verify(customerRepository, never()).existsById(any());
        }
    }

    // ── Transition map ──────────────────────────────────────────────────

    @Nested
    @DisplayName("transition map")
    class TransitionTests {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "Scheduled,Confirmed,false",
            "Scheduled,Completed,false",
            "Scheduled,Cancelled,true",
            "Scheduled,NoShow,true",
            "Confirmed,Completed,false",
            "Confirmed,Cancelled,true",
            "Confirmed,NoShow,true"
        })
        @DisplayName("applies a valid edge and releases only on the move out of the holding set")
        void validEdges(String from, String to, boolean releases) {
            var current = status(from);
            var target = status(to);
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);
            var sourceSlot = slot(SLOT_ID, ACTIVITY_ID, SLOT_START, 2, 1, "Available", true);

            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));
            when(statusRepository.findById(target.getId())).thenReturn(Optional.of(target));
            // Asymmetric on purpose: the releasing edges must take the slot under the pessimistic
            // lock, so they stub ONLY findByIdForUpdate. Stubbing the read-only findById as well
            // would hide an implementation that released through the non-locking finder.
            if (releases) {
                when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(sourceSlot));
            } else {
                when(schedulingSlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(sourceSlot));
            }

            var response = service.updateStatus(APPOINTMENT_ID, new SchedulingAppointmentStatusRequest(target.getId()));

            assertThat(response.statusName()).isEqualTo(to);
            assertThat(appointment.getStatusId()).isEqualTo(target.getId());
            if (releases) {
                assertThat(sourceSlot.getBooked()).isZero();
                assertThat(sourceSlot.getStatus()).isEqualTo("Available");
            } else {
                assertThat(sourceSlot.getBooked()).isEqualTo(1);
            }
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "Scheduled,Scheduled",
            "Confirmed,Scheduled",
            "Completed,Confirmed",
            "Completed,Cancelled",
            "Completed,NoShow",
            "Cancelled,Scheduled",
            "Cancelled,Confirmed",
            "NoShow,Scheduled",
            "NoShow,Confirmed",
            "Unknown,Confirmed"
        })
        @DisplayName("answers 409 for an invalid edge and for every terminal or unknown source")
        void invalidEdges(String from, String to) {
            var current = status(from);
            var target = status(to);
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);

            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));
            when(statusRepository.findById(target.getId())).thenReturn(Optional.of(target));

            assertThatThrownBy(() -> service.updateStatus(
                            APPOINTMENT_ID, new SchedulingAppointmentStatusRequest(target.getId())))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        @DisplayName("should reject a target status from a different type via the real StatusValidator")
        void foreignStatusTypeIsBadRequest() {
            var current = status("Scheduled");
            // A real SALES_ORDER status, so the rejection comes from StatusValidator.requireStatusOfType
            // and not from a hand-made stub: the 400 path must be exercised against the shared checker.
            var foreignType = Status.builder()
                    .id(TARGET_STATUS_ID)
                    .statusName("Draft")
                    .statusType(StatusType.builder()
                            .id(UUID.randomUUID())
                            .statusTypeName("SALES_ORDER")
                            .build())
                    .enabled(true)
                    .build();
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);

            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));
            when(statusRepository.findById(TARGET_STATUS_ID)).thenReturn(Optional.of(foreignType));

            assertThatThrownBy(() -> service.updateStatus(
                            APPOINTMENT_ID, new SchedulingAppointmentStatusRequest(TARGET_STATUS_ID)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("APPOINTMENT");
        }

        @Test
        @DisplayName("should return 404 when the appointment's own status row is missing")
        void missingCurrentStatusIsNotFound() {
            var appointment = appointment(SLOT_ID, SCHEDULED_STATUS_ID.toString(), true);
            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(SCHEDULED_STATUS_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateStatus(
                            APPOINTMENT_ID, new SchedulingAppointmentStatusRequest(TARGET_STATUS_ID)))
                    .isInstanceOf(com.lifecontrol.api.status.exception.StatusNotFoundException.class);
        }
    }

    // ── Release and delete ──────────────────────────────────────────────

    @Nested
    @DisplayName("release and delete")
    class ReleaseTests {

        @Test
        @DisplayName("should decrement exactly once and flip Full back to Available")
        void releaseDecrementsAndFlipsStatus() {
            var current = status("Confirmed");
            var target = status("Cancelled");
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);
            var sourceSlot = slot(SLOT_ID, ACTIVITY_ID, SLOT_START, 1, 1, "Full", true);

            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));
            when(statusRepository.findById(target.getId())).thenReturn(Optional.of(target));
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(sourceSlot));

            service.updateStatus(APPOINTMENT_ID, new SchedulingAppointmentStatusRequest(target.getId()));

            assertThat(sourceSlot.getBooked()).isZero();
            assertThat(sourceSlot.getStatus()).isEqualTo("Available");
            verify(schedulingSlotRepository).save(sourceSlot);
        }

        @ParameterizedTest(name = "DELETE {0}")
        @CsvSource({"Scheduled", "Confirmed"})
        @DisplayName("DELETE should release a Scheduled or Confirmed appointment exactly once and cancel it")
        void deleteReleasesAndSoftDeletes(String from) {
            var current = status(from);
            var cancelled = status("Cancelled");
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);
            var sourceSlot = slot(SLOT_ID, ACTIVITY_ID, SLOT_START, 1, 1, "Full", true);

            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));
            when(statusRepository.findByTypeNameAndStatusName("APPOINTMENT", "Cancelled"))
                    .thenReturn(Optional.of(cancelled));
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(sourceSlot));

            service.delete(APPOINTMENT_ID);

            assertThat(sourceSlot.getBooked()).isZero();
            assertThat(sourceSlot.getStatus()).isEqualTo("Available");
            assertThat(appointment.getStatusId()).isEqualTo(cancelled.getId());
            assertThat(appointment.getEnabled()).isFalse();
            verify(schedulingSlotRepository).save(sourceSlot);
        }

        @Test
        @DisplayName("DELETE on a terminal Completed appointment must only soft-delete it")
        void deleteCompletedOnlySoftDeletes() {
            var current = status("Completed");
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);
            var originalStatusId = appointment.getStatusId();
            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));

            service.delete(APPOINTMENT_ID);

            assertThat(appointment.getEnabled()).isFalse();
            assertThat(appointment.getStatusId()).isEqualTo(originalStatusId);
            verify(schedulingSlotRepository, never()).save(any());
            verify(schedulingSlotRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("DELETE on an already-released NoShow appointment must not decrement again")
        void deleteNoShowDoesNotDecrement() {
            var current = status("NoShow");
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);
            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));

            service.delete(APPOINTMENT_ID);

            assertThat(appointment.getEnabled()).isFalse();
            verify(schedulingSlotRepository, never()).save(any());
            verify(schedulingSlotRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("DELETE on an already-released appointment must not decrement again")
        void deleteIsIdempotentOnAReleasedAppointment() {
            var current = status("Cancelled");
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);
            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));

            service.delete(APPOINTMENT_ID);

            assertThat(appointment.getEnabled()).isFalse();
            verify(schedulingSlotRepository, never()).save(any());
            verify(schedulingSlotRepository, never()).findByIdForUpdate(any());
        }
    }

    // ── Reschedule ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("reschedule")
    class RescheduleTests {

        @ParameterizedTest(name = "non-terminal status {0}")
        @CsvSource({"Scheduled", "Confirmed"})
        @DisplayName("should release the source, take the target and lock the two slots in ascending order")
        void rescheduleReleasesSourceAndTakesTargetInOrder(String currentStatusName) {
            var current = status(currentStatusName);
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);

            // The target starts BEFORE the source, so the ascending (start_at, id) order locks the
            // target first even though it is the destination.
            var sourceProbe = slot(SLOT_ID, ACTIVITY_ID, SLOT_START, 1, 1, "Full", true);
            var targetProbe = slot(TARGET_SLOT_ID, ACTIVITY_ID, TARGET_SLOT_START, 2, 0, "Available", true);

            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));
            when(schedulingSlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(sourceProbe));
            when(schedulingSlotRepository.findById(TARGET_SLOT_ID)).thenReturn(Optional.of(targetProbe));
            when(schedulingSlotRepository.findByIdForUpdate(TARGET_SLOT_ID)).thenReturn(Optional.of(targetProbe));
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(sourceProbe));

            var response =
                    service.reschedule(APPOINTMENT_ID, new SchedulingAppointmentRescheduleRequest(TARGET_SLOT_ID));

            InOrder inOrder = inOrder(schedulingSlotRepository);
            inOrder.verify(schedulingSlotRepository).findByIdForUpdate(TARGET_SLOT_ID);
            inOrder.verify(schedulingSlotRepository).findByIdForUpdate(SLOT_ID);

            assertThat(sourceProbe.getBooked()).isZero();
            assertThat(sourceProbe.getStatus()).isEqualTo("Available");
            assertThat(targetProbe.getBooked()).isEqualTo(1);
            assertThat(targetProbe.getStatus()).isEqualTo("Available");
            assertThat(appointment.getSlotId()).isEqualTo(TARGET_SLOT_ID);
            assertThat(response.slotId()).isEqualTo(TARGET_SLOT_ID);
            assertThat(response.startAt()).isEqualTo(TARGET_SLOT_START);
            assertThat(response.statusName()).isEqualTo(currentStatusName);
        }

        @Test
        @DisplayName("should 409 when the target slot belongs to another activity")
        void rescheduleToForeignActivityIsConflict() {
            var current = status("Scheduled");
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);
            var otherActivityId = UUID.randomUUID();
            var sourceProbe = slot(SLOT_ID, ACTIVITY_ID, SLOT_START, 1, 1, "Full", true);
            var targetProbe = slot(TARGET_SLOT_ID, otherActivityId, TARGET_SLOT_START, 2, 0, "Available", true);

            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));
            when(schedulingSlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(sourceProbe));
            when(schedulingSlotRepository.findById(TARGET_SLOT_ID)).thenReturn(Optional.of(targetProbe));
            when(schedulingSlotRepository.findByIdForUpdate(TARGET_SLOT_ID)).thenReturn(Optional.of(targetProbe));
            when(schedulingSlotRepository.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(sourceProbe));

            assertThatThrownBy(() -> service.reschedule(
                            APPOINTMENT_ID, new SchedulingAppointmentRescheduleRequest(TARGET_SLOT_ID)))
                    .isInstanceOf(SchedulingSlotNotBookableException.class)
                    .hasMessageContaining("different activity");
        }

        @ParameterizedTest(name = "terminal status {0}")
        @CsvSource({"Cancelled", "NoShow", "Completed"})
        @DisplayName("should 409 when rescheduling a terminal appointment")
        void rescheduleTerminalIsConflict(String currentStatusName) {
            // Completed is the status that separates terminality from capacity: it still holds its
            // seat (D25) but is terminal in the transition map (D24), so the guard must read the map.
            var current = status(currentStatusName);
            var appointment = appointment(SLOT_ID, current.getId().toString(), true);
            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.of(appointment));
            when(statusRepository.findById(current.getId())).thenReturn(Optional.of(current));

            assertThatThrownBy(() -> service.reschedule(
                            APPOINTMENT_ID, new SchedulingAppointmentRescheduleRequest(TARGET_SLOT_ID)))
                    .isInstanceOf(SchedulingAppointmentNotModifiableException.class);
        }

        @Test
        @DisplayName("should return 404 for an unknown appointment")
        void rescheduleUnknownAppointmentIsNotFound() {
            when(schedulingAppointmentRepository.findByIdForUpdate(APPOINTMENT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reschedule(
                            APPOINTMENT_ID, new SchedulingAppointmentRescheduleRequest(TARGET_SLOT_ID)))
                    .isInstanceOf(SchedulingAppointmentNotFoundException.class);
        }
    }

    // ── Appointment list read ────────────────────────────────────────────

    @Nested
    @DisplayName("appointment list read")
    class ReadTests {

        private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 28, 0, 0);
        private static final LocalDateTime TO = LocalDateTime.of(2026, 9, 29, 0, 0);
        private static final UUID SECOND_SLOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ae");

        private SchedulingAppointment appointmentAt(UUID id, UUID slotId, String userId, boolean enabled) {
            return SchedulingAppointment.builder()
                    .id(id)
                    .slotId(slotId)
                    .activityId(ACTIVITY_ID)
                    .companyStoreId(STORE_ID)
                    .userId(userId)
                    .statusId(SCHEDULED_STATUS_ID)
                    .notes("notes")
                    .enabled(enabled)
                    .build();
        }

        private Status scheduledStatus() {
            return Status.builder()
                    .id(SCHEDULED_STATUS_ID)
                    .statusName("Scheduled")
                    .statusType(StatusType.builder()
                            .id(STATUS_TYPE_ID)
                            .statusTypeName("APPOINTMENT")
                            .build())
                    .enabled(true)
                    .build();
        }

        @Test
        @DisplayName("should map the slot window and the resolved status name in one status batch")
        void mapsWindowAndResolvesStatusOnce() {
            var appointment = appointmentAt(APPOINTMENT_ID, SLOT_ID, "employee-1", true);
            when(schedulingAppointmentRepository.findInRangeByStore(STORE_ID, FROM, TO))
                    .thenReturn(List.of(appointment));
            when(schedulingSlotRepository.findAllById(any())).thenReturn(List.of(slot));
            when(statusRepository.findAllById(any())).thenReturn(List.of(scheduledStatus()));

            var result = service.getAppointments(STORE_ID, FROM, TO, null);

            assertThat(result).hasSize(1);
            var response = result.getFirst();
            assertThat(response.id()).isEqualTo(APPOINTMENT_ID);
            assertThat(response.startAt()).isEqualTo(SLOT_START);
            assertThat(response.endAt()).isEqualTo(SLOT_START.plusMinutes(60));
            assertThat(response.statusName()).isEqualTo("Scheduled");
            // The slot and the status names are each resolved with one batched read.
            verify(schedulingSlotRepository).findAllById(any());
            verify(statusRepository).findAllById(any());
        }

        @Test
        @DisplayName("should keep a soft-deleted appointment in the list and expose its enabled flag")
        void softDeletedAppointmentStaysInTheList() {
            var appointment = appointmentAt(APPOINTMENT_ID, SLOT_ID, "employee-1", false);
            when(schedulingAppointmentRepository.findInRangeByStore(STORE_ID, FROM, TO))
                    .thenReturn(List.of(appointment));
            when(schedulingSlotRepository.findAllById(any())).thenReturn(List.of(slot));
            when(statusRepository.findAllById(any())).thenReturn(List.of(scheduledStatus()));

            var result = service.getAppointments(STORE_ID, FROM, TO, null);

            assertThat(result)
                    .singleElement()
                    .satisfies(response -> assertThat(response.enabled()).isFalse());
        }

        @Test
        @DisplayName("should omit an appointment whose slot vanished and keep the rest, instead of a 404")
        void orphanedSlotAppointmentIsOmitted() {
            var orphaned = appointmentAt(APPOINTMENT_ID, SLOT_ID, "employee-1", true);
            var kept = appointmentAt(UUID.randomUUID(), SECOND_SLOT_ID, "employee-2", true);
            when(schedulingAppointmentRepository.findInRangeByStore(STORE_ID, FROM, TO))
                    .thenReturn(List.of(orphaned, kept));
            // The batch returns only the kept slot: the orphaned appointment's slot was deleted
            // between the range query and the batch (G20). The read drops the orphan, never throws.
            when(schedulingSlotRepository.findAllById(any()))
                    .thenReturn(List.of(
                            slot(SECOND_SLOT_ID, ACTIVITY_ID, SLOT_START.plusHours(1), 1, 0, "Available", true)));
            when(statusRepository.findAllById(any())).thenReturn(List.of(scheduledStatus()));

            var result = service.getAppointments(STORE_ID, FROM, TO, null);

            assertThat(result)
                    .singleElement()
                    .satisfies(response -> assertThat(response.id()).isEqualTo(kept.getId()));
        }

        @Test
        @DisplayName("should narrow with userId and never call the unfiltered finder")
        void userIdFilterIsApplied() {
            when(schedulingAppointmentRepository.findInRangeByStoreAndUserId(STORE_ID, "employee-1", FROM, TO))
                    .thenReturn(List.of(appointmentAt(APPOINTMENT_ID, SLOT_ID, "employee-1", true)));
            when(schedulingSlotRepository.findAllById(any())).thenReturn(List.of(slot));
            when(statusRepository.findAllById(any())).thenReturn(List.of(scheduledStatus()));

            service.getAppointments(STORE_ID, FROM, TO, "employee-1");

            verify(schedulingAppointmentRepository).findInRangeByStoreAndUserId(STORE_ID, "employee-1", FROM, TO);
            verify(schedulingAppointmentRepository, never()).findInRangeByStore(any(), any(), any());
        }

        @Test
        @DisplayName("should use the unfiltered finder when userId is absent")
        void noUserUsesTheUnfilteredFinder() {
            when(schedulingAppointmentRepository.findInRangeByStore(STORE_ID, FROM, TO))
                    .thenReturn(List.of());

            service.getAppointments(STORE_ID, FROM, TO, null);

            verify(schedulingAppointmentRepository).findInRangeByStore(STORE_ID, FROM, TO);
            verify(schedulingAppointmentRepository, never()).findInRangeByStoreAndUserId(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should resolve the statuses of several appointments in one batch, not one per row")
        void statusResolutionIsBatchedAcrossRows() {
            when(schedulingAppointmentRepository.findInRangeByStore(STORE_ID, FROM, TO))
                    .thenReturn(List.of(
                            appointmentAt(APPOINTMENT_ID, SLOT_ID, "employee-1", true),
                            appointmentAt(UUID.randomUUID(), SECOND_SLOT_ID, "employee-2", true)));
            when(schedulingSlotRepository.findAllById(any()))
                    .thenReturn(List.of(
                            slot, slot(SECOND_SLOT_ID, ACTIVITY_ID, SLOT_START.plusHours(1), 1, 0, "Available", true)));
            when(statusRepository.findAllById(any())).thenReturn(List.of(scheduledStatus()));

            service.getAppointments(STORE_ID, FROM, TO, null);

            verify(statusRepository).findAllById(any());
        }

        @Test
        @DisplayName("should reject an inverted range with 400 before reading any appointment")
        void invertedRangeIsRejected() {
            assertThatThrownBy(() -> service.getAppointments(STORE_ID, TO, FROM, null))
                    .isInstanceOf(InvalidSchedulingRangeException.class)
                    .hasMessageContaining("to must be after from");

            verifyNoInteractions(schedulingAppointmentRepository);
        }

        @Test
        @DisplayName("should reject a span wider than 90 days with 400")
        void tooWideRangeIsRejected() {
            assertThatThrownBy(() -> service.getAppointments(STORE_ID, FROM, FROM.plusDays(91), null))
                    .isInstanceOf(InvalidSchedulingRangeException.class)
                    .hasMessageContaining("90");

            verifyNoInteractions(schedulingAppointmentRepository);
        }

        @Test
        @DisplayName("should accept a range of exactly 90 days")
        void exactlyNinetyDaysIsAccepted() {
            when(schedulingAppointmentRepository.findInRangeByStore(STORE_ID, FROM, FROM.plusDays(90)))
                    .thenReturn(List.of());

            assertThatCode(() -> service.getAppointments(STORE_ID, FROM, FROM.plusDays(90), null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should raise AccessDenied and read no appointment when the store scope is denied")
        void deniedStoreScopeReadsNothing() {
            denyStoreAccess();

            assertThatThrownBy(() -> service.getAppointments(STORE_ID, FROM, TO, null))
                    .isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(schedulingAppointmentRepository);
        }
    }
}
