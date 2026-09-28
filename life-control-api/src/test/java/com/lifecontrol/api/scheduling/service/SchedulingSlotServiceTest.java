package com.lifecontrol.api.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.lifecontrol.api.scheduling.dto.SchedulingSlotResponse;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingSlotRangeException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import com.lifecontrol.api.scheduling.model.SchedulingAvailability;
import com.lifecontrol.api.scheduling.model.SchedulingSlot;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAvailabilityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
 * Unit coverage of {@link SchedulingSlotService}: the window expansion and its clipping, the ISO
 * weekday agreement, the range guard, the store isolation shared with the other scheduling services,
 * and the derived {@code available}. The real upsert and the reconciliation live in the PostgreSQL
 * integration test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchedulingSlotService Tests")
class SchedulingSlotServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID COMPANY_COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c5");
    private static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c6");

    private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 27);
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 29);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 10, 5);

    private static final int DURATION_MINUTES = 60;
    private static final int CAPACITY_PER_SLOT = 4;

    @Mock
    private SchedulingActivityRepository schedulingActivityRepository;

    @Mock
    private SchedulingAvailabilityRepository schedulingAvailabilityRepository;

    @Mock
    private SchedulingSlotRepository schedulingSlotRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    private SchedulingSlotService service;
    private CompanyStore store;
    private SchedulingActivity activity;

    @BeforeEach
    void setUp() {
        service = new SchedulingSlotService(
                schedulingActivityRepository,
                schedulingAvailabilityRepository,
                schedulingSlotRepository,
                companyStoreRepository,
                currentUserContext);

        var company = Company.builder().id(COMPANY_ID).companyKey("SC-SLOT").build();
        var companyCountry =
                CompanyCountry.builder().id(COMPANY_COUNTRY_ID).company(company).build();
        var companyRegion = CompanyRegion.builder()
                .id(REGION_ID)
                .companyCountry(companyCountry)
                .build();
        var companyZone =
                CompanyZone.builder().id(ZONE_ID).companyRegion(companyRegion).build();
        store = CompanyStore.builder()
                .id(STORE_ID)
                .companyZone(companyZone)
                .storeName("Store")
                .enabled(true)
                .build();

        activity = SchedulingActivity.builder()
                .id(ACTIVITY_ID)
                .companyStoreId(STORE_ID)
                .activityName("Yoga")
                .durationMinutes(DURATION_MINUTES)
                .capacityPerSlot(CAPACITY_PER_SLOT)
                .enabled(true)
                .build();
    }

    // ── Fixtures and stubs ──────────────────────────────────────────────

    private static SchedulingAvailability window(
            int day, String start, String end, LocalDate validFrom, LocalDate validTo, boolean enabled) {
        return SchedulingAvailability.builder()
                .activityId(ACTIVITY_ID)
                .dayOfWeek((short) day)
                .startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end))
                .validFrom(validFrom)
                .validTo(validTo)
                .enabled(enabled)
                .build();
    }

    private static SchedulingSlot slot(LocalDateTime startAt, int capacity, int booked) {
        return SchedulingSlot.builder()
                .id(UUID.randomUUID())
                .activityId(ACTIVITY_ID)
                .startAt(startAt)
                .endAt(startAt.plusMinutes(DURATION_MINUTES))
                .capacity(capacity)
                .booked(booked)
                .status("Available")
                .enabled(true)
                .build();
    }

    private void stubActivityAndStore() {
        when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
        when(companyStoreRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
    }

    private void stubWindows(SchedulingAvailability... windows) {
        when(schedulingAvailabilityRepository.findByActivityIdOrderByDayOfWeekAscStartTimeAsc(ACTIVITY_ID))
                .thenReturn(List.of(windows));
    }

    private void denyStoreAccess() {
        doThrow(new AccessDeniedException("denied"))
                .when(currentUserContext)
                .verifyCompanyStoreAccess(any(), any(), any(), any(), any());
    }

    private List<SchedulingSlotResponse> readBack(LocalDateTime from, LocalDateTime to, SchedulingSlot... slots) {
        when(schedulingSlotRepository.findByActivityIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                        ACTIVITY_ID, from, to))
                .thenReturn(List.of(slots));
        return service.getSlots(ACTIVITY_ID, from, to);
    }

    // ── expansion ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("materialization")
    class MaterializationTests {

        @Test
        @DisplayName("should step by durationMinutes and never overrun the window end")
        void expandsByDurationAndStopsAtTheWindowEnd() {
            stubActivityAndStore();
            stubWindows(window(1, "09:00", "11:00", MONDAY, MONDAY, true));

            service.getSlots(ACTIVITY_ID, MONDAY.atStartOfDay(), TUESDAY.atStartOfDay());

            verify(schedulingSlotRepository).insertIfAbsent(ACTIVITY_ID, MONDAY.atTime(9, 0), MONDAY.atTime(10, 0), 4);
            verify(schedulingSlotRepository).insertIfAbsent(ACTIVITY_ID, MONDAY.atTime(10, 0), MONDAY.atTime(11, 0), 4);
            // 11:00 + 60m would run past the 11:00 window end, so it is not a candidate even though
            // 11:00 itself is inside [from, to).
            verify(schedulingSlotRepository, never())
                    .insertIfAbsent(eq(ACTIVITY_ID), eq(MONDAY.atTime(11, 0)), any(), anyInt());
            verify(schedulingSlotRepository, times(2)).insertIfAbsent(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should materialize a dayOfWeek=1 window on Mondays only, never on a neighbouring weekday")
        void isoMondayWindowLandsOnlyOnMondays() {
            stubActivityAndStore();
            // validTo is the next Monday, so any generated date in the range is eligible and the
            // only thing that can select a date is the ISO weekday comparison.
            stubWindows(window(1, "09:00", "10:00", SUNDAY, NEXT_MONDAY, true));

            service.getSlots(ACTIVITY_ID, SUNDAY.atStartOfDay(), TUESDAY.atStartOfDay());

            verify(schedulingSlotRepository).insertIfAbsent(ACTIVITY_ID, MONDAY.atTime(9, 0), MONDAY.atTime(10, 0), 4);
            verify(schedulingSlotRepository, never())
                    .insertIfAbsent(eq(ACTIVITY_ID), eq(SUNDAY.atTime(9, 0)), any(), anyInt());
            verify(schedulingSlotRepository, never())
                    .insertIfAbsent(eq(ACTIVITY_ID), eq(TUESDAY.atTime(9, 0)), any(), anyInt());
            verify(schedulingSlotRepository, times(1)).insertIfAbsent(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should produce no slot for a date the validFrom excludes")
        void validFromExcludesEarlierDates() {
            stubActivityAndStore();
            // The window becomes valid on the Tuesday, so the first Monday lies outside [validFrom,
            // validTo] and must yield nothing; the next Monday lies inside and must yield a slot.
            stubWindows(window(1, "09:00", "10:00", TUESDAY, NEXT_MONDAY, true));

            service.getSlots(
                    ACTIVITY_ID, MONDAY.atStartOfDay(), TUESDAY.atTime(0, 0).plusDays(7));

            verify(schedulingSlotRepository, never())
                    .insertIfAbsent(eq(ACTIVITY_ID), eq(MONDAY.atTime(9, 0)), any(), anyInt());
            verify(schedulingSlotRepository)
                    .insertIfAbsent(ACTIVITY_ID, NEXT_MONDAY.atTime(9, 0), NEXT_MONDAY.atTime(10, 0), 4);
        }

        @Test
        @DisplayName("should produce no slot for a date the validTo excludes")
        void validToExcludesLaterDates() {
            stubActivityAndStore();
            // The window is valid only on the first Monday, so that Monday must yield a slot and the
            // next Monday inside the requested range must not: both sides are present in one fixture.
            stubWindows(window(1, "09:00", "10:00", MONDAY, MONDAY, true));

            service.getSlots(
                    ACTIVITY_ID, MONDAY.atStartOfDay(), NEXT_MONDAY.atTime(0, 0).plusDays(1));

            verify(schedulingSlotRepository).insertIfAbsent(ACTIVITY_ID, MONDAY.atTime(9, 0), MONDAY.atTime(10, 0), 4);
            verify(schedulingSlotRepository, never())
                    .insertIfAbsent(eq(ACTIVITY_ID), eq(NEXT_MONDAY.atTime(9, 0)), any(), anyInt());
            verify(schedulingSlotRepository, times(1)).insertIfAbsent(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should produce nothing for a disabled window")
        void disabledWindowProducesNoSlot() {
            stubActivityAndStore();
            stubWindows(window(1, "09:00", "10:00", MONDAY, MONDAY, false));

            service.getSlots(ACTIVITY_ID, MONDAY.atStartOfDay(), TUESDAY.atStartOfDay());

            verify(schedulingSlotRepository, never()).insertIfAbsent(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should clip the candidates to [from, to)")
        void rangeClipDropsOutOfRangeCandidates() {
            stubActivityAndStore();
            stubWindows(window(1, "08:00", "12:00", MONDAY, MONDAY, true));

            service.getSlots(ACTIVITY_ID, MONDAY.atTime(9, 0), MONDAY.atTime(11, 0));

            // 08:00 is before `from`; 11:00 is not before `to`. Only 09:00 and 10:00 lie inside.
            verify(schedulingSlotRepository).insertIfAbsent(ACTIVITY_ID, MONDAY.atTime(9, 0), MONDAY.atTime(10, 0), 4);
            verify(schedulingSlotRepository).insertIfAbsent(ACTIVITY_ID, MONDAY.atTime(10, 0), MONDAY.atTime(11, 0), 4);
            verify(schedulingSlotRepository, never())
                    .insertIfAbsent(eq(ACTIVITY_ID), eq(MONDAY.atTime(8, 0)), any(), anyInt());
            verify(schedulingSlotRepository, never())
                    .insertIfAbsent(eq(ACTIVITY_ID), eq(MONDAY.atTime(11, 0)), any(), anyInt());
            verify(schedulingSlotRepository, times(2)).insertIfAbsent(any(), any(), any(), anyInt());
        }
    }

    // ── range guard ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("range guard")
    class RangeGuardTests {

        @Test
        @DisplayName("should reject an inverted range with 400 before touching the slot table")
        void invertedRangeIsRejected() {
            stubActivityAndStore();

            assertThatThrownBy(() -> service.getSlots(ACTIVITY_ID, MONDAY.atTime(11, 0), MONDAY.atTime(9, 0)))
                    .isInstanceOf(InvalidSchedulingSlotRangeException.class)
                    .hasMessageContaining("to must be after from");

            verifyNoInteractions(schedulingSlotRepository);
        }

        @Test
        @DisplayName("should reject a span wider than 90 days with 400 before touching the slot table")
        void tooWideRangeIsRejected() {
            stubActivityAndStore();

            assertThatThrownBy(() -> service.getSlots(
                            ACTIVITY_ID,
                            MONDAY.atStartOfDay(),
                            MONDAY.atStartOfDay().plusDays(91)))
                    .isInstanceOf(InvalidSchedulingSlotRangeException.class)
                    .hasMessageContaining("90");

            verifyNoInteractions(schedulingSlotRepository);
        }

        @Test
        @DisplayName("should accept a range of exactly 90 days")
        void exactlyNinetyDaysIsAccepted() {
            stubActivityAndStore();
            stubWindows();

            assertThatCode(() -> service.getSlots(
                            ACTIVITY_ID,
                            MONDAY.atStartOfDay(),
                            MONDAY.atStartOfDay().plusDays(90)))
                    .doesNotThrowAnyException();
        }
    }

    // ── authorization and mapping ───────────────────────────────────────

    @Nested
    @DisplayName("authorization and mapping")
    class AuthorizationAndMappingTests {

        @Test
        @DisplayName("should raise AccessDenied and touch no slot when the store scope is denied")
        void accessDeniedTouchesNoSlot() {
            stubActivityAndStore();
            denyStoreAccess();

            assertThatThrownBy(() -> service.getSlots(ACTIVITY_ID, MONDAY.atStartOfDay(), TUESDAY.atStartOfDay()))
                    .isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(schedulingSlotRepository);
        }

        @Test
        @DisplayName("should return 404 for an unknown activity")
        void unknownActivityIsNotFound() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSlots(ACTIVITY_ID, MONDAY.atStartOfDay(), TUESDAY.atStartOfDay()))
                    .isInstanceOf(SchedulingActivityNotFoundException.class);

            verifyNoInteractions(schedulingSlotRepository);
        }

        @Test
        @DisplayName("should return 404 when the activity's store does not exist")
        void unknownStoreIsNotFound() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            when(companyStoreRepository.findById(STORE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSlots(ACTIVITY_ID, MONDAY.atStartOfDay(), TUESDAY.atStartOfDay()))
                    .isInstanceOf(CompanyStoreNotFoundException.class);

            verifyNoInteractions(schedulingSlotRepository);
        }

        @Test
        @DisplayName("should derive available as capacity - booked and pass the stored fields through")
        void availableIsDerivedFromCapacityMinusBooked() {
            stubActivityAndStore();
            stubWindows(window(1, "09:00", "10:00", MONDAY, MONDAY, true));

            var found = readBack(MONDAY.atStartOfDay(), TUESDAY.atStartOfDay(), slot(MONDAY.atTime(9, 0), 4, 1));

            assertThat(found).hasSize(1);
            var response = found.getFirst();
            assertThat(response.capacity()).isEqualTo(4);
            assertThat(response.booked()).isEqualTo(1);
            assertThat(response.available()).isEqualTo(3);
            assertThat(response.startAt()).isEqualTo(MONDAY.atTime(9, 0));
            assertThat(response.endAt()).isEqualTo(MONDAY.atTime(10, 0));
            assertThat(response.status()).isEqualTo("Available");
            assertThat(response.enabled()).isTrue();
            assertThat(response.activityId()).isEqualTo(ACTIVITY_ID);
        }
    }
}
