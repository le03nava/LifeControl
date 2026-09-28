package com.lifecontrol.api.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.exception.VersionPreconditionException;
import com.lifecontrol.api.scheduling.dto.SchedulingActivityRequest;
import com.lifecontrol.api.scheduling.exception.DuplicateSchedulingActivityException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit coverage of {@link SchedulingActivityService}: the read/write matrix of the activity
 * catalogue, its store isolation ({@code verifyCompanyStoreAccess} on every path), the duplicate
 * rule and the optional version precondition. Persistence and the real transaction live in the
 * integration tests on PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchedulingActivityService Tests")
class SchedulingActivityServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID COMPANY_COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final UUID OTHER_STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a6");
    private static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a7");
    private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a8");
    private static final String EMPLOYEE_SUB = "auth0|employee-1";

    @Mock
    private SchedulingActivityRepository schedulingActivityRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    private SchedulingActivityService service;
    private CompanyStore store;
    private SchedulingActivity activity;

    private final Pageable pageable = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service =
                new SchedulingActivityService(schedulingActivityRepository, companyStoreRepository, currentUserContext);

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
                .userId(EMPLOYEE_SUB)
                .activityName("Yoga")
                .description("One-hour yoga class")
                .durationMinutes(60)
                .capacityPerSlot(4)
                .enabled(true)
                .build();
    }

    // ── Fixtures and stubs ──────────────────────────────────────────────

    private SchedulingActivityRequest request(
            UUID companyStoreId,
            String activityName,
            Integer durationMinutes,
            Integer capacity,
            Boolean enabled,
            Long version) {
        return new SchedulingActivityRequest(
                companyStoreId, EMPLOYEE_SUB, activityName, "Description", durationMinutes, capacity, enabled, version);
    }

    private void stubStore() {
        when(companyStoreRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
    }

    private void stubSaveReturnsArgument() {
        when(schedulingActivityRepository.saveAndFlush(any(SchedulingActivity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void denyStoreAccess() {
        doThrow(new AccessDeniedException("denied"))
                .when(currentUserContext)
                .verifyCompanyStoreAccess(any(), any(), any(), any(), any());
    }

    // ── list ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getActivities")
    class GetActivitiesTests {

        @Test
        @DisplayName("should include disabled activities when includeDisabled is true")
        void includesDisabledWhenRequested() {
            stubStore();
            activity.setEnabled(false);
            Page<SchedulingActivity> page = new PageImpl<>(List.of(activity));
            when(schedulingActivityRepository.findByCompanyStoreIdOrderByActivityNameAsc(STORE_ID, pageable))
                    .thenReturn(page);

            var result = service.getActivities(STORE_ID, true, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().getFirst().id()).isEqualTo(ACTIVITY_ID);
            assertThat(result.getContent().getFirst().enabled()).isFalse();
            verify(schedulingActivityRepository).findByCompanyStoreIdOrderByActivityNameAsc(STORE_ID, pageable);
            verify(schedulingActivityRepository, never())
                    .findByCompanyStoreIdAndEnabledTrueOrderByActivityNameAsc(any(), any());
        }

        @Test
        @DisplayName("should use the enabled-filtered finder by default")
        void excludesDisabledByDefault() {
            stubStore();
            when(schedulingActivityRepository.findByCompanyStoreIdAndEnabledTrueOrderByActivityNameAsc(
                            STORE_ID, pageable))
                    .thenReturn(new PageImpl<>(List.of(activity)));

            var result = service.getActivities(STORE_ID, false, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().getFirst().activityName()).isEqualTo("Yoga");
            verify(schedulingActivityRepository)
                    .findByCompanyStoreIdAndEnabledTrueOrderByActivityNameAsc(STORE_ID, pageable);
            verify(schedulingActivityRepository, never()).findByCompanyStoreIdOrderByActivityNameAsc(any(), any());
        }

        @Test
        @DisplayName("should return 404 when the store does not exist")
        void unknownStoreIsNotFound() {
            when(companyStoreRepository.findById(STORE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getActivities(STORE_ID, false, pageable))
                    .isInstanceOf(CompanyStoreNotFoundException.class);

            verifyNoInteractions(schedulingActivityRepository);
        }

        @Test
        @DisplayName("should propagate the access-denied failure of verifyCompanyStoreAccess")
        void accessDeniedPropagates() {
            stubStore();
            denyStoreAccess();

            assertThatThrownBy(() -> service.getActivities(STORE_ID, false, pageable))
                    .isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(schedulingActivityRepository);
        }
    }

    // ── get by id ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getActivity")
    class GetActivityTests {

        @Test
        @DisplayName("should map the activity and authorize its store")
        void returnsMappedActivity() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();

            var response = service.getActivity(ACTIVITY_ID);

            assertThat(response.id()).isEqualTo(ACTIVITY_ID);
            assertThat(response.companyStoreId()).isEqualTo(STORE_ID);
            assertThat(response.userId()).isEqualTo(EMPLOYEE_SUB);
            assertThat(response.activityName()).isEqualTo("Yoga");
            assertThat(response.durationMinutes()).isEqualTo(60);
            assertThat(response.capacityPerSlot()).isEqualTo(4);
            assertThat(response.enabled()).isTrue();
            verify(currentUserContext)
                    .verifyCompanyStoreAccess(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);
        }

        @Test
        @DisplayName("should return 404 for an unknown activity")
        void unknownActivityIsNotFound() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getActivity(ACTIVITY_ID))
                    .isInstanceOf(SchedulingActivityNotFoundException.class);

            verifyNoInteractions(companyStoreRepository);
        }

        @Test
        @DisplayName("should propagate the access-denied failure of verifyCompanyStoreAccess")
        void accessDeniedPropagates() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();
            denyStoreAccess();

            assertThatThrownBy(() -> service.getActivity(ACTIVITY_ID)).isInstanceOf(AccessDeniedException.class);
        }
    }

    // ── create ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("should persist the activity with enabled defaulting to true")
        void createsWithEnabledDefault() {
            stubStore();
            stubSaveReturnsArgument();

            var response = service.create(request(STORE_ID, "Yoga", 60, 4, null, null));

            assertThat(response.companyStoreId()).isEqualTo(STORE_ID);
            assertThat(response.activityName()).isEqualTo("Yoga");
            assertThat(response.description()).isEqualTo("Description");
            assertThat(response.durationMinutes()).isEqualTo(60);
            assertThat(response.capacityPerSlot()).isEqualTo(4);
            assertThat(response.enabled()).isTrue();
            verify(schedulingActivityRepository).saveAndFlush(any(SchedulingActivity.class));
        }

        @Test
        @DisplayName("should honour an explicit enabled=false")
        void createsDisabledWhenAsked() {
            stubStore();
            stubSaveReturnsArgument();

            var response = service.create(request(STORE_ID, "Yoga", 60, 4, false, null));

            assertThat(response.enabled()).isFalse();
        }

        @Test
        @DisplayName("should return 409 for a duplicate name in the same store and not write")
        void duplicateNameIsConflict() {
            stubStore();
            when(schedulingActivityRepository.existsByCompanyStoreIdAndActivityName(STORE_ID, "Yoga"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.create(request(STORE_ID, "Yoga", 60, 4, true, null)))
                    .isInstanceOf(DuplicateSchedulingActivityException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
        }

        @Test
        @DisplayName("should return 404 when the store does not exist and not write")
        void unknownStoreIsNotFound() {
            when(companyStoreRepository.findById(STORE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(request(STORE_ID, "Yoga", 60, 4, true, null)))
                    .isInstanceOf(CompanyStoreNotFoundException.class);

            verifyNoInteractions(schedulingActivityRepository);
        }

        @Test
        @DisplayName("should reject a null store on create with a bad request and not write")
        void nullStoreIsBadRequest() {
            assertThatThrownBy(() -> service.create(request(null, "Yoga", 60, 4, true, null)))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(companyStoreRepository);
            verifyNoInteractions(schedulingActivityRepository);
        }

        @Test
        @DisplayName("should propagate the access-denied failure of verifyCompanyStoreAccess and not write")
        void accessDeniedPropagates() {
            stubStore();
            denyStoreAccess();

            assertThatThrownBy(() -> service.create(request(STORE_ID, "Yoga", 60, 4, true, null)))
                    .isInstanceOf(AccessDeniedException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
        }
    }

    // ── update ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("should mutate the activity, ignore the store and return the incrementable version")
        void updatesInPlace() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();
            stubSaveReturnsArgument();

            var response =
                    service.update(ACTIVITY_ID, request(OTHER_STORE_ID, "Pilates", 45, 2, true, activity.getVersion()));

            assertThat(response.activityName()).isEqualTo("Pilates");
            assertThat(response.durationMinutes()).isEqualTo(45);
            assertThat(response.capacityPerSlot()).isEqualTo(2);
            // The store is immutable: the request's OTHER_STORE_ID is ignored.
            assertThat(response.companyStoreId()).isEqualTo(STORE_ID);
            assertThat(activity.getCompanyStoreId()).isEqualTo(STORE_ID);
            verify(schedulingActivityRepository).saveAndFlush(activity);
        }

        @Test
        @DisplayName("should leave enabled untouched when the request omits it")
        void nullEnabledKeepsCurrentState() {
            activity.setEnabled(false);
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();
            stubSaveReturnsArgument();

            var response = service.update(ACTIVITY_ID, request(STORE_ID, "Yoga", 60, 4, null, null));

            assertThat(response.enabled()).isFalse();
        }

        @Test
        @DisplayName("should return 404 for an unknown activity")
        void unknownActivityIsNotFound() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(ACTIVITY_ID, request(STORE_ID, "Yoga", 60, 4, true, null)))
                    .isInstanceOf(SchedulingActivityNotFoundException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
        }

        @Test
        @DisplayName("should return 412 when the version precondition does not hold and not write")
        void versionMismatchIsPreconditionFailed() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();

            assertThatThrownBy(() -> service.update(ACTIVITY_ID, request(STORE_ID, "Yoga", 60, 4, true, 5L)))
                    .isInstanceOf(VersionPreconditionException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
            verify(schedulingActivityRepository, never())
                    .existsByCompanyStoreIdAndActivityNameAndIdNot(any(), any(), any());
        }

        @Test
        @DisplayName("should allow the version the row currently carries")
        void matchingVersionPasses() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();
            stubSaveReturnsArgument();

            var response = service.update(ACTIVITY_ID, request(STORE_ID, "Yoga", 60, 4, true, activity.getVersion()));

            assertThat(response.id()).isEqualTo(ACTIVITY_ID);
        }

        @Test
        @DisplayName("should return 409 for a duplicate name, excluding the row itself")
        void duplicateIgnoringItselfIsConflict() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();
            when(schedulingActivityRepository.existsByCompanyStoreIdAndActivityNameAndIdNot(
                            STORE_ID, "Pilates", ACTIVITY_ID))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.update(ACTIVITY_ID, request(STORE_ID, "Pilates", 60, 4, true, null)))
                    .isInstanceOf(DuplicateSchedulingActivityException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
        }

        @Test
        @DisplayName("should propagate the access-denied failure of verifyCompanyStoreAccess and not write")
        void accessDeniedPropagates() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();
            denyStoreAccess();

            assertThatThrownBy(() -> service.update(ACTIVITY_ID, request(STORE_ID, "Yoga", 60, 4, true, null)))
                    .isInstanceOf(AccessDeniedException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
        }
    }

    // ── delete / enable ─────────────────────────────────────────────────

    @Nested
    @DisplayName("delete and enable")
    class DeleteAndEnableTests {

        @Test
        @DisplayName("should soft-delete the activity and flush")
        void deleteSoftDeletes() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();

            service.delete(ACTIVITY_ID);

            assertThat(activity.getEnabled()).isFalse();
            verify(schedulingActivityRepository).saveAndFlush(activity);
        }

        @Test
        @DisplayName("should return 404 for an unknown activity")
        void deleteUnknownActivityIsNotFound() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(ACTIVITY_ID))
                    .isInstanceOf(SchedulingActivityNotFoundException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
        }

        @Test
        @DisplayName("should propagate the access-denied failure of verifyCompanyStoreAccess on delete")
        void deleteAccessDeniedPropagates() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();
            denyStoreAccess();

            assertThatThrownBy(() -> service.delete(ACTIVITY_ID)).isInstanceOf(AccessDeniedException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
        }

        @Test
        @DisplayName("should re-enable the activity and flush")
        void enableReenables() {
            activity.setEnabled(false);
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();
            stubSaveReturnsArgument();

            var response = service.enable(ACTIVITY_ID);

            assertThat(response.enabled()).isTrue();
            assertThat(activity.getEnabled()).isTrue();
            verify(schedulingActivityRepository).saveAndFlush(activity);
        }

        @Test
        @DisplayName("should return 404 when enabling an unknown activity")
        void enableUnknownActivityIsNotFound() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.enable(ACTIVITY_ID))
                    .isInstanceOf(SchedulingActivityNotFoundException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
        }

        @Test
        @DisplayName("should propagate the access-denied failure of verifyCompanyStoreAccess on enable")
        void enableAccessDeniedPropagates() {
            when(schedulingActivityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
            stubStore();
            denyStoreAccess();

            assertThatThrownBy(() -> service.enable(ACTIVITY_ID)).isInstanceOf(AccessDeniedException.class);

            verify(schedulingActivityRepository, never()).saveAndFlush(any(SchedulingActivity.class));
        }
    }
}
