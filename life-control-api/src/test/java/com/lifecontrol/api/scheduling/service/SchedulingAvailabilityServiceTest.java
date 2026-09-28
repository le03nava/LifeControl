package com.lifecontrol.api.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityWindowRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityWindowResponse;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingAvailabilityException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import com.lifecontrol.api.scheduling.model.SchedulingAvailability;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAvailabilityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit coverage of {@link SchedulingAvailabilityService}: the ordered read, the whole-set replace and
 * its delete-before-insert ordering, the store isolation shared with the activity service, and every
 * domain-validation rule. Persistence and the real transaction live in the PostgreSQL integration
 * test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchedulingAvailabilityService Tests")
class SchedulingAvailabilityServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID COMPANY_COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b5");
    private static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b6");
    private static final UUID WINDOW_ID_1 = UUID.fromString("00000000-0000-0000-0000-0000000000b7");
    private static final UUID WINDOW_ID_2 = UUID.fromString("00000000-0000-0000-0000-0000000000b8");

    private static final String START_1 = "09:00";
    private static final String END_1 = "13:00";
    private static final String START_2 = "14:00";
    private static final String END_2 = "18:00";
    private static final String FROM = "2026-09-28";
    private static final String TO = "2026-12-31";

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

    private SchedulingAvailabilityService service;
    private CompanyStore store;
    private SchedulingActivity activity;

    @BeforeEach
    void setUp() {
        service = new SchedulingAvailabilityService(
                schedulingActivityRepository,
                schedulingAvailabilityRepository,
                schedulingSlotRepository,
                companyStoreRepository,
                currentUserContext);

        var company = Company.builder().id(COMPANY_ID).companyKey("SC-KEY").build();
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
                .durationMinutes(60)
                .capacityPerSlot(4)
                .enabled(true)
                .build();
    }

    // ── Fixtures and stubs ──────────────────────────────────────────────

    private static SchedulingAvailabilityWindowRequest window(int day, String start, String end) {
        return new SchedulingAvailabilityWindowRequest(
                day, LocalTime.parse(start), LocalTime.parse(end), LocalDate.parse(FROM), LocalDate.parse(TO));
    }

    private static SchedulingAvailability storedWindow(UUID id, int day, String start, String end) {
        return SchedulingAvailability.builder()
                .id(id)
                .activityId(ACTIVITY_ID)
                .dayOfWeek((short) day)
                .startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end))
                .validFrom(LocalDate.parse(FROM))
                .validTo(LocalDate.parse(TO))
                .enabled(true)
                .build();
    }

    private void stubActivityAndStore() {
        when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
        when(companyStoreRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
    }

    private void stubSaveReturnsArgument() {
        when(schedulingAvailabilityRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubReadBack(SchedulingAvailability... windows) {
        when(schedulingAvailabilityRepository.findByActivityIdOrderByDayOfWeekAscStartTimeAsc(ACTIVITY_ID))
                .thenReturn(List.of(windows));
    }

    private void denyStoreAccess() {
        doThrow(new AccessDeniedException("denied"))
                .when(currentUserContext)
                .verifyCompanyStoreAccess(any(), any(), any(), any(), any());
    }

    // ── read ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAvailability")
    class GetAvailabilityTests {

        @Test
        @DisplayName("should authorize the activity's store and return the finder's order")
        void returnsOrderedWindowsAndAuthorizesStore() {
            stubActivityAndStore();
            when(schedulingAvailabilityRepository.findByActivityIdOrderByDayOfWeekAscStartTimeAsc(ACTIVITY_ID))
                    .thenReturn(List.of(
                            storedWindow(WINDOW_ID_1, 1, START_1, END_1),
                            storedWindow(WINDOW_ID_2, 3, START_2, END_2)));

            var response = service.getAvailability(ACTIVITY_ID);

            assertThat(response.activityId()).isEqualTo(ACTIVITY_ID);
            assertThat(response.windows()).hasSize(2);
            assertThat(response.windows().getFirst().id()).isEqualTo(WINDOW_ID_1);
            assertThat(response.windows().getFirst().dayOfWeek()).isEqualTo(1);
            assertThat(response.windows().getFirst().startTime()).isEqualTo(LocalTime.parse(START_1));
            assertThat(response.windows().getFirst().validFrom()).isEqualTo(LocalDate.parse(FROM));
            assertThat(response.windows().getLast().dayOfWeek()).isEqualTo(3);
            verify(currentUserContext)
                    .verifyCompanyStoreAccess(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);
            verify(schedulingAvailabilityRepository).findByActivityIdOrderByDayOfWeekAscStartTimeAsc(ACTIVITY_ID);
        }

        @Test
        @DisplayName("should return 404 for an unknown activity")
        void unknownActivityIsNotFound() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAvailability(ACTIVITY_ID))
                    .isInstanceOf(SchedulingActivityNotFoundException.class);

            verifyNoInteractions(companyStoreRepository);
            verifyNoInteractions(schedulingAvailabilityRepository);
        }

        @Test
        @DisplayName("should return 404 when the activity's store does not exist")
        void unknownStoreIsNotFound() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            when(companyStoreRepository.findById(STORE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAvailability(ACTIVITY_ID))
                    .isInstanceOf(CompanyStoreNotFoundException.class);

            verifyNoInteractions(schedulingAvailabilityRepository);
        }

        @Test
        @DisplayName("should propagate the access-denied failure and read no availability")
        void accessDeniedPropagates() {
            stubActivityAndStore();
            denyStoreAccess();

            assertThatThrownBy(() -> service.getAvailability(ACTIVITY_ID)).isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(schedulingAvailabilityRepository);
        }
    }

    // ── replace ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("replaceAvailability")
    class ReplaceAvailabilityTests {

        @Test
        @DisplayName("should delete the old set and flush before inserting the new one")
        void deletesAndFlushesBeforeInserting() {
            stubActivityAndStore();
            stubSaveReturnsArgument();
            stubReadBack(storedWindow(WINDOW_ID_1, 1, START_1, END_1), storedWindow(WINDOW_ID_2, 3, START_2, END_2));

            var response = service.replaceAvailability(
                    ACTIVITY_ID,
                    new SchedulingAvailabilityRequest(List.of(window(1, START_1, END_1), window(3, START_2, END_2))));

            assertThat(response.activityId()).isEqualTo(ACTIVITY_ID);
            assertThat(response.windows()).hasSize(2);

            InOrder inOrder = inOrder(schedulingAvailabilityRepository, schedulingSlotRepository);
            inOrder.verify(schedulingAvailabilityRepository).deleteByActivityId(ACTIVITY_ID);
            inOrder.verify(schedulingAvailabilityRepository).flush();
            inOrder.verify(schedulingSlotRepository).deleteUnbookedByActivityId(ACTIVITY_ID);
            inOrder.verify(schedulingAvailabilityRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("should authorize the activity's store before touching the availability table")
        void authorizesStoreBeforeWriting() {
            stubActivityAndStore();
            stubSaveReturnsArgument();

            service.replaceAvailability(
                    ACTIVITY_ID, new SchedulingAvailabilityRequest(List.of(window(1, START_1, END_1))));

            var invocationOrder = inOrder(currentUserContext, schedulingAvailabilityRepository);
            invocationOrder
                    .verify(currentUserContext)
                    .verifyCompanyStoreAccess(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);
            invocationOrder.verify(schedulingAvailabilityRepository).deleteByActivityId(ACTIVITY_ID);
        }

        @Test
        @DisplayName("should accept an empty window list and clear the set")
        void emptyWindowListClearsTheSet() {
            stubActivityAndStore();
            stubReadBack();

            var response = service.replaceAvailability(ACTIVITY_ID, new SchedulingAvailabilityRequest(List.of()));

            assertThat(response.windows()).isEmpty();
            verify(schedulingAvailabilityRepository).deleteByActivityId(ACTIVITY_ID);
            verify(schedulingAvailabilityRepository).flush();
            verify(schedulingAvailabilityRepository).saveAll(List.of());
        }

        @Test
        @DisplayName("should insert every window with enabled = true")
        void insertedWindowsAreEnabled() {
            stubActivityAndStore();
            stubSaveReturnsArgument();

            service.replaceAvailability(
                    ACTIVITY_ID,
                    new SchedulingAvailabilityRequest(List.of(window(1, START_1, END_1), window(7, START_2, END_2))));

            @SuppressWarnings("unchecked")
            var captor = ArgumentCaptor.forClass(List.class);
            verify(schedulingAvailabilityRepository).saveAll(captor.capture());
            List<SchedulingAvailability> saved = captor.getValue();
            assertThat(saved)
                    .allSatisfy(entity -> assertThat(entity.getEnabled()).isTrue());
        }

        @Test
        @DisplayName("should accept the same window on two different weekdays")
        void sameWindowOnDifferentDaysIsAccepted() {
            stubActivityAndStore();
            stubSaveReturnsArgument();
            stubReadBack(storedWindow(WINDOW_ID_1, 1, START_1, END_1), storedWindow(WINDOW_ID_2, 2, START_1, END_1));

            var response = service.replaceAvailability(
                    ACTIVITY_ID,
                    new SchedulingAvailabilityRequest(List.of(window(1, START_1, END_1), window(2, START_1, END_1))));

            assertThat(response.windows()).hasSize(2);
            assertThat(response.windows().getFirst().dayOfWeek()).isEqualTo(1);
            assertThat(response.windows().getLast().dayOfWeek()).isEqualTo(2);
        }

        @Test
        @DisplayName("should accept weekday 1 and weekday 7")
        void firstAndLastWeekdayAreAccepted() {
            stubActivityAndStore();
            stubSaveReturnsArgument();
            stubReadBack(storedWindow(WINDOW_ID_1, 1, START_1, END_1), storedWindow(WINDOW_ID_2, 7, START_2, END_2));

            var response = service.replaceAvailability(
                    ACTIVITY_ID,
                    new SchedulingAvailabilityRequest(List.of(window(1, START_1, END_1), window(7, START_2, END_2))));

            assertThat(response.windows())
                    .extracting(SchedulingAvailabilityWindowResponse::dayOfWeek)
                    .containsExactly(1, 7);
        }

        @Test
        @DisplayName("should accept two touching windows on the same weekday (end == next start)")
        void touchingWindowsOnSameDayAreAccepted() {
            stubActivityAndStore();
            stubSaveReturnsArgument();
            stubReadBack(storedWindow(WINDOW_ID_1, 1, START_1, END_1), storedWindow(WINDOW_ID_2, 1, END_1, END_2));

            var response = service.replaceAvailability(
                    ACTIVITY_ID,
                    new SchedulingAvailabilityRequest(List.of(window(1, START_1, END_1), window(1, END_1, END_2))));

            assertThat(response.windows()).hasSize(2);
        }

        @Test
        @DisplayName("should return the ordered read-back, not the order the request was sent in")
        void returnsOrderedReadBackNotRequestOrder() {
            stubActivityAndStore();
            // saveAll preserves the (unsorted) request order; the read-back finder is the ordering
            // source of truth, so the response must match it and not the request.
            stubSaveReturnsArgument();
            stubReadBack(
                    storedWindow(WINDOW_ID_1, 1, START_1, END_1),
                    storedWindow(WINDOW_ID_2, 1, START_2, END_2),
                    storedWindow(WINDOW_ID_1, 5, START_2, END_2));

            var unsorted = List.of(window(5, START_2, END_2), window(1, START_2, END_2), window(1, START_1, END_1));
            var response = service.replaceAvailability(ACTIVITY_ID, new SchedulingAvailabilityRequest(unsorted));

            assertThat(response.windows())
                    .extracting(SchedulingAvailabilityWindowResponse::dayOfWeek)
                    .containsExactly(1, 1, 5);
            assertThat(response.windows())
                    .extracting(SchedulingAvailabilityWindowResponse::startTime)
                    .containsExactly(LocalTime.parse(START_1), LocalTime.parse(START_2), LocalTime.parse(START_2));
            verify(schedulingAvailabilityRepository).findByActivityIdOrderByDayOfWeekAscStartTimeAsc(ACTIVITY_ID);
        }

        @Test
        @DisplayName("should reject a zero-length window with 400 and write nothing")
        void zeroLengthWindowIsRejected() {
            stubActivityAndStore();

            assertThatThrownBy(() -> service.replaceAvailability(
                            ACTIVITY_ID, new SchedulingAvailabilityRequest(List.of(window(1, START_1, START_1)))))
                    .isInstanceOf(InvalidSchedulingAvailabilityException.class)
                    .hasMessageContaining("endTime must be after startTime");

            verifyNoInteractions(schedulingAvailabilityRepository);
        }

        @Test
        @DisplayName("should reject an inverted window with 400 and write nothing")
        void invertedWindowIsRejected() {
            stubActivityAndStore();

            assertThatThrownBy(() -> service.replaceAvailability(
                            ACTIVITY_ID, new SchedulingAvailabilityRequest(List.of(window(1, END_1, START_1)))))
                    .isInstanceOf(InvalidSchedulingAvailabilityException.class)
                    .hasMessageContaining("endTime must be after startTime");

            verifyNoInteractions(schedulingAvailabilityRepository);
        }

        @Test
        @DisplayName("should reject an inverted validity range with 400 and write nothing")
        void invertedValidityIsRejected() {
            stubActivityAndStore();
            var inverted = new SchedulingAvailabilityWindowRequest(
                    1, LocalTime.parse(START_1), LocalTime.parse(END_1), LocalDate.parse(TO), LocalDate.parse(FROM));

            assertThatThrownBy(() -> service.replaceAvailability(
                            ACTIVITY_ID, new SchedulingAvailabilityRequest(List.of(inverted))))
                    .isInstanceOf(InvalidSchedulingAvailabilityException.class)
                    .hasMessageContaining("validTo must not be before validFrom");

            verifyNoInteractions(schedulingAvailabilityRepository);
        }

        @Test
        @DisplayName("should reject an overlap inside one weekday with 400 and write nothing")
        void sameDayOverlapIsRejected() {
            stubActivityAndStore();
            // 09:00-13:00 and 12:00-18:00 intersect on the same weekday.
            var overlapping = List.of(window(1, START_1, END_1), window(1, "12:00", END_2));

            assertThatThrownBy(() ->
                            service.replaceAvailability(ACTIVITY_ID, new SchedulingAvailabilityRequest(overlapping)))
                    .isInstanceOf(InvalidSchedulingAvailabilityException.class)
                    .hasMessageContaining("overlap");

            verifyNoInteractions(schedulingAvailabilityRepository);
        }

        @Test
        @DisplayName("should return 404 for an unknown activity and write nothing")
        void unknownActivityIsNotFound() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.replaceAvailability(
                            ACTIVITY_ID, new SchedulingAvailabilityRequest(List.of(window(1, START_1, END_1)))))
                    .isInstanceOf(SchedulingActivityNotFoundException.class);

            verify(schedulingAvailabilityRepository, never()).deleteByActivityId(any());
            verify(schedulingAvailabilityRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("should propagate the access-denied failure and write nothing")
        void accessDeniedPropagates() {
            stubActivityAndStore();
            denyStoreAccess();

            assertThatThrownBy(() -> service.replaceAvailability(
                            ACTIVITY_ID, new SchedulingAvailabilityRequest(List.of(window(1, START_1, END_1)))))
                    .isInstanceOf(AccessDeniedException.class);

            verify(schedulingAvailabilityRepository, never()).deleteByActivityId(any());
            verify(schedulingAvailabilityRepository, never()).saveAll(anyList());
        }
    }
}
