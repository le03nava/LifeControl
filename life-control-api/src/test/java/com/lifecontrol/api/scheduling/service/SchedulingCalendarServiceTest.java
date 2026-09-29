package com.lifecontrol.api.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.customer.model.Customer;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingRangeException;
import com.lifecontrol.api.scheduling.model.SchedulingAppointment;
import com.lifecontrol.api.scheduling.repository.SchedulingAppointmentRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingCalendarSlotProjection;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.status.model.Status;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit coverage of {@link SchedulingCalendarService}: the shared range guard, the store scope, the
 * optional {@code userId}/{@code activityId} filters of D36, D34's slot grouping, D21's derived
 * {@code available}, and D35's three batched reads (the N+1 proof). The real join and the
 * no-materialization guarantee live in the PostgreSQL integration test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchedulingCalendarService Tests")
class SchedulingCalendarServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID COMPANY_COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d5");
    private static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d6");
    private static final UUID OTHER_ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d7");
    private static final UUID FIRST_SLOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d8");
    private static final UUID SECOND_SLOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d9");
    private static final UUID SCHEDULED_STATUS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000da");
    private static final UUID COMPLETED_STATUS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000db");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dc");
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 9, 28, 0, 0);

    @Mock
    private SchedulingSlotRepository schedulingSlotRepository;

    @Mock
    private SchedulingAppointmentRepository schedulingAppointmentRepository;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    private SchedulingCalendarService service;

    @BeforeEach
    void setUp() {
        service = new SchedulingCalendarService(
                schedulingSlotRepository,
                schedulingAppointmentRepository,
                statusRepository,
                customerRepository,
                companyStoreRepository,
                currentUserContext);

        var company = Company.builder().id(COMPANY_ID).companyKey("SC-CAL").build();
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

        when(companyStoreRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static SchedulingCalendarSlotProjection row(
            UUID slotId,
            UUID activityId,
            String activityName,
            LocalDateTime startAt,
            int capacity,
            int booked,
            boolean activityEnabled) {
        return new SchedulingCalendarSlotProjection(
                slotId,
                activityId,
                activityName,
                activityEnabled,
                startAt,
                startAt.plusMinutes(60),
                capacity,
                booked,
                "Available");
    }

    private static SchedulingAppointment appointment(
            UUID id, UUID slotId, String userId, UUID customerId, UUID statusId, boolean enabled) {
        return SchedulingAppointment.builder()
                .id(id)
                .slotId(slotId)
                .activityId(ACTIVITY_ID)
                .companyStoreId(STORE_ID)
                .userId(userId)
                .customerId(customerId)
                .statusId(statusId)
                .notes("notes")
                .enabled(enabled)
                .build();
    }

    private static Status status(UUID id, String name) {
        return Status.builder().id(id).statusName(name).enabled(true).build();
    }

    private static Customer customer(UUID id, String name) {
        return Customer.builder().id(id).name(name).build();
    }

    private void denyStoreAccess() {
        doThrow(new AccessDeniedException("denied"))
                .when(currentUserContext)
                .verifyCompanyStoreAccess(any(), any(), any(), any(), any());
    }

    // ── Range guard ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("range guard")
    class RangeGuardTests {

        @Test
        @DisplayName("should reject an inverted range with 400 before touching the slot table")
        void invertedRangeIsRejected() {
            assertThatThrownBy(
                            () -> service.getCalendar(STORE_ID, MONDAY.plusHours(11), MONDAY.plusHours(9), null, null))
                    .isInstanceOf(InvalidSchedulingRangeException.class)
                    .hasMessageContaining("to must be after from");

            verifyNoInteractions(schedulingSlotRepository);
        }

        @Test
        @DisplayName("should reject a span wider than 90 days with 400 before touching the slot table")
        void tooWideRangeIsRejected() {
            assertThatThrownBy(() -> service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(91), null, null))
                    .isInstanceOf(InvalidSchedulingRangeException.class)
                    .hasMessageContaining("90");

            verifyNoInteractions(schedulingSlotRepository);
        }

        @Test
        @DisplayName("should accept a range of exactly 90 days")
        void exactlyNinetyDaysIsAccepted() {
            when(schedulingSlotRepository.findCalendarSlots(STORE_ID, MONDAY, MONDAY.plusDays(90)))
                    .thenReturn(List.of());

            assertThatCode(() -> service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(90), null, null))
                    .doesNotThrowAnyException();
        }
    }

    // ── Scope ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("store scope")
    class ScopeTests {

        @Test
        @DisplayName("should raise AccessDenied and read no slot when the store scope is denied")
        void accessDeniedReadsNoSlot() {
            denyStoreAccess();

            assertThatThrownBy(() -> service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(1), null, null))
                    .isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(schedulingSlotRepository);
        }
    }

    // ── Filters and projection ──────────────────────────────────────────

    @Nested
    @DisplayName("filters and projection")
    class ProjectionTests {

        @Test
        @DisplayName("should narrow the slots with activityId and never call the unfiltered finder")
        void activityFilterNarrowsTheSlotRead() {
            when(schedulingSlotRepository.findCalendarSlotsByActivityId(
                            STORE_ID, MONDAY, MONDAY.plusDays(1), ACTIVITY_ID))
                    .thenReturn(List.of(row(FIRST_SLOT_ID, ACTIVITY_ID, "Yoga", MONDAY.plusHours(9), 4, 0, true)));

            var calendar = service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(1), null, ACTIVITY_ID);

            assertThat(calendar).hasSize(1);
            verify(schedulingSlotRepository)
                    .findCalendarSlotsByActivityId(STORE_ID, MONDAY, MONDAY.plusDays(1), ACTIVITY_ID);
            verify(schedulingSlotRepository, never()).findCalendarSlots(any(), any(), any());
        }

        @Test
        @DisplayName("should carry an activity's disabled flag through and keep it true for an enabled one")
        void activityEnabledRidesThroughForDisabledAndEnabledActivities() {
            when(schedulingSlotRepository.findCalendarSlots(STORE_ID, MONDAY, MONDAY.plusDays(1)))
                    .thenReturn(List.of(
                            row(FIRST_SLOT_ID, ACTIVITY_ID, "Yoga", MONDAY.plusHours(9), 2, 1, false),
                            row(SECOND_SLOT_ID, OTHER_ACTIVITY_ID, "Pilates", MONDAY.plusHours(10), 2, 0, true)));

            var calendar = service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(1), null, null);

            // D37: the finder is the unfiltered read and the flag is projected, not filtered on. A
            // hard-coded constant fails one of these two assertions.
            verify(schedulingSlotRepository).findCalendarSlots(STORE_ID, MONDAY, MONDAY.plusDays(1));
            assertThat(calendar).hasSize(2);
            assertThat(calendar.get(0).activityEnabled()).isFalse();
            assertThat(calendar.get(1).activityEnabled()).isTrue();
        }

        @Test
        @DisplayName("should narrow the appointments with userId and never call the unfiltered finder")
        void userFilterNarrowsTheAppointmentRead() {
            when(schedulingSlotRepository.findCalendarSlots(STORE_ID, MONDAY, MONDAY.plusDays(1)))
                    .thenReturn(List.of(row(FIRST_SLOT_ID, ACTIVITY_ID, "Yoga", MONDAY.plusHours(9), 2, 0, true)));
            when(schedulingAppointmentRepository.findBySlotIdInAndUserIdOrderByIdAsc(any(), eq("employee-1")))
                    .thenReturn(List.of(appointment(
                            UUID.randomUUID(), FIRST_SLOT_ID, "employee-1", null, SCHEDULED_STATUS_ID, true)));
            when(statusRepository.findAllById(any())).thenReturn(List.of(status(SCHEDULED_STATUS_ID, "Scheduled")));

            var calendar = service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(1), "employee-1", null);

            assertThat(calendar.getFirst().appointments()).hasSize(1);
            verify(schedulingAppointmentRepository).findBySlotIdInAndUserIdOrderByIdAsc(any(), eq("employee-1"));
            verify(schedulingAppointmentRepository, never()).findBySlotIdInOrderByIdAsc(any());
        }

        @Test
        @DisplayName("should resolve each of the three batched reads exactly once however many rows come back")
        void threeBatchedReadsNeverNPlusOne() {
            var secondStart = MONDAY.plusHours(10);
            when(schedulingSlotRepository.findCalendarSlots(STORE_ID, MONDAY, MONDAY.plusDays(1)))
                    .thenReturn(List.of(
                            row(FIRST_SLOT_ID, ACTIVITY_ID, "Yoga", MONDAY.plusHours(9), 4, 2, true),
                            row(SECOND_SLOT_ID, ACTIVITY_ID, "Pilates", secondStart, 4, 0, true)));
            when(schedulingAppointmentRepository.findBySlotIdInOrderByIdAsc(any()))
                    .thenReturn(List.of(
                            appointment(
                                    UUID.randomUUID(),
                                    FIRST_SLOT_ID,
                                    "employee-1",
                                    CUSTOMER_ID,
                                    SCHEDULED_STATUS_ID,
                                    true),
                            appointment(
                                    UUID.randomUUID(),
                                    FIRST_SLOT_ID,
                                    "employee-2",
                                    CUSTOMER_ID,
                                    COMPLETED_STATUS_ID,
                                    true),
                            appointment(
                                    UUID.randomUUID(),
                                    SECOND_SLOT_ID,
                                    "employee-1",
                                    OTHER_CUSTOMER_ID,
                                    SCHEDULED_STATUS_ID,
                                    true)));
            when(statusRepository.findAllById(any()))
                    .thenReturn(List.of(
                            status(SCHEDULED_STATUS_ID, "Scheduled"), status(COMPLETED_STATUS_ID, "Completed")));
            when(customerRepository.findAllById(any()))
                    .thenReturn(List.of(customer(CUSTOMER_ID, "Ada"), customer(OTHER_CUSTOMER_ID, "Bob")));

            var calendar = service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(1), null, null);

            assertThat(calendar).hasSize(2);
            // D35: one batched read per concern, never one per row.
            verify(schedulingSlotRepository, times(1)).findCalendarSlots(STORE_ID, MONDAY, MONDAY.plusDays(1));
            verify(schedulingAppointmentRepository, times(1)).findBySlotIdInOrderByIdAsc(any());
            verify(statusRepository, times(1)).findAllById(any());
            verify(customerRepository, times(1)).findAllById(any());
        }

        @Test
        @DisplayName("should render a slot with no appointments as an entry with an empty list and full availability")
        void emptySlotStillAppears() {
            when(schedulingSlotRepository.findCalendarSlots(STORE_ID, MONDAY, MONDAY.plusDays(1)))
                    .thenReturn(List.of(
                            row(FIRST_SLOT_ID, ACTIVITY_ID, "Yoga", MONDAY.plusHours(9), 4, 1, true),
                            row(SECOND_SLOT_ID, ACTIVITY_ID, "Yoga", MONDAY.plusHours(10), 3, 0, true)));
            when(schedulingAppointmentRepository.findBySlotIdInOrderByIdAsc(any()))
                    .thenReturn(List.of(appointment(
                            UUID.randomUUID(), FIRST_SLOT_ID, "employee-1", null, SCHEDULED_STATUS_ID, true)));
            when(statusRepository.findAllById(any())).thenReturn(List.of(status(SCHEDULED_STATUS_ID, "Scheduled")));

            var calendar = service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(1), null, null);

            assertThat(calendar).hasSize(2);
            var empty = calendar.get(1);
            assertThat(empty.slotId()).isEqualTo(SECOND_SLOT_ID);
            assertThat(empty.appointments()).isEmpty();
            assertThat(empty.booked()).isZero();
            assertThat(empty.available()).isEqualTo(empty.capacity());
            // D21: available is derived, never carried by the row itself.
            assertThat(empty.available()).isEqualTo(3);
        }

        @Test
        @DisplayName("should carry a soft-deleted Completed appointment with enabled=false and still count its booked")
        void softDeletedCompletedStaysAndCounts() {
            when(schedulingSlotRepository.findCalendarSlots(STORE_ID, MONDAY, MONDAY.plusDays(1)))
                    .thenReturn(List.of(row(FIRST_SLOT_ID, ACTIVITY_ID, "Yoga", MONDAY.plusHours(9), 4, 1, true)));
            when(schedulingAppointmentRepository.findBySlotIdInOrderByIdAsc(any()))
                    .thenReturn(List.of(appointment(
                            UUID.randomUUID(), FIRST_SLOT_ID, "employee-1", null, COMPLETED_STATUS_ID, false)));
            when(statusRepository.findAllById(any())).thenReturn(List.of(status(COMPLETED_STATUS_ID, "Completed")));

            var calendar = service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(1), null, null);

            var entry = calendar.getFirst();
            assertThat(entry.capacity()).isEqualTo(4);
            assertThat(entry.booked()).isEqualTo(1);
            // capacity/booked/available are pairwise distinct (4/1/3), so a derivation that returned
            // the wrong operand (available = booked or available = capacity) fails here.
            assertThat(entry.available()).isEqualTo(3);
            assertThat(entry.appointments()).singleElement().satisfies(appointment -> {
                assertThat(appointment.enabled()).isFalse();
                assertThat(appointment.statusName()).isEqualTo("Completed");
            });
        }

        @Test
        @DisplayName("should resolve the status and customer names and keep an unknown customer's name null")
        void namesAreResolved() {
            when(schedulingSlotRepository.findCalendarSlots(STORE_ID, MONDAY, MONDAY.plusDays(1)))
                    .thenReturn(List.of(row(FIRST_SLOT_ID, ACTIVITY_ID, "Yoga", MONDAY.plusHours(9), 4, 2, true)));
            when(schedulingAppointmentRepository.findBySlotIdInOrderByIdAsc(any()))
                    .thenReturn(List.of(
                            appointment(
                                    UUID.randomUUID(),
                                    FIRST_SLOT_ID,
                                    "employee-1",
                                    CUSTOMER_ID,
                                    SCHEDULED_STATUS_ID,
                                    true),
                            appointment(
                                    UUID.randomUUID(), FIRST_SLOT_ID, "employee-2", null, SCHEDULED_STATUS_ID, true)));
            when(statusRepository.findAllById(any())).thenReturn(List.of(status(SCHEDULED_STATUS_ID, "Scheduled")));
            when(customerRepository.findAllById(any())).thenReturn(List.of(customer(CUSTOMER_ID, "Ada")));

            var calendar = service.getCalendar(STORE_ID, MONDAY, MONDAY.plusDays(1), null, null);

            var appointments = calendar.getFirst().appointments();
            assertThat(appointments).hasSize(2);
            assertThat(appointments.getFirst().statusName()).isEqualTo("Scheduled");
            assertThat(appointments.getFirst().customerName()).isEqualTo("Ada");
            assertThat(appointments.get(1).customerId()).isNull();
            assertThat(appointments.get(1).customerName()).isNull();
            // A null customerId is filtered out of the name batch: never part of the id set.
            verify(customerRepository, times(1)).findAllById(any());
        }
    }
}
