package com.lifecontrol.api.provisioning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.EmployeeScopeAssignment;
import com.lifecontrol.api.hr.repository.EmployeeScopeAssignmentRepository;
import com.lifecontrol.api.hr.service.EmployeeStoreScope;
import com.lifecontrol.api.hr.service.ScopeAssignmentDerivation;
import com.lifecontrol.api.provisioning.exception.AccountNotLinkedException;
import com.lifecontrol.api.provisioning.exception.CompanyScopeInvariantException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Focused unit tests for {@link AccessProvisioningMembershipService}: the convergence of the five
 * {@code company_*} attributes to "the key present ⇔ the level is held" (T39), the multivalued write
 * the derivation exists for (D1/T6), the fail-closed company invariant (T1/T39), the {@code Terminated}
 * deletion path (T6) and the "touch nothing else" boundary (T34/T39).
 *
 * <p>The mocked finder returns exactly the rows the fixture declares, <b>unfiltered</b>, in the order
 * the repository's own {@code ORDER BY validFrom DESC} would return them: the enabled and coverage
 * filters belong to the derivation, so a row the fixture declares as ignored really exercises the
 * filter here instead of being filtered away by the stub. The fixture dates bracket
 * {@link LocalDate#now()}, so a wrong date in the service (a tomorrow, say) would let an ignored row
 * contribute and fail the test rather than pass it silently.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessProvisioningMembershipService Tests")
class AccessProvisioningMembershipServiceTest {

    private static final String KC_USER_ID = "kc-user-1";

    /**
     * Deliberately fixed and ordered so that an implementation which <b>sorts</b> the store ids
     * instead of preserving the derivation's own order fails deterministically: the derivation order
     * pinned below is descending ({@code SECOND} then {@code FIRST}), the opposite of a sort.
     */
    private static final UUID FIRST_STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID SECOND_STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private IdentityProvider identityProvider;

    @Mock
    private EmployeeScopeAssignmentRepository scopeAssignmentRepository;

    @InjectMocks
    private AccessProvisioningMembershipService service;

    private UUID employeeId;
    private UUID companyId;
    private Company company;

    @BeforeEach
    void setUp() {
        employeeId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        company = company(companyId);
    }

    // --- fixtures ---

    private LocalDate today() {
        return LocalDate.now();
    }

    private Company company(UUID id) {
        return Company.builder()
                .id(id)
                .companyKey("acme")
                .companyName("Acme")
                .rfc("ACM010101ABC")
                .emailDomain("acme.com")
                .build();
    }

    private CompanyCountry companyCountry(Company ofCompany) {
        var country = Country.builder()
                .id(UUID.randomUUID())
                .countryCode("MX")
                .countryName("México")
                .enabled(true)
                .build();
        return CompanyCountry.builder()
                .id(UUID.randomUUID())
                .company(ofCompany)
                .country(country)
                .build();
    }

    private CompanyRegion region(CompanyCountry ofCompanyCountry) {
        return CompanyRegion.builder()
                .id(UUID.randomUUID())
                .companyCountry(ofCompanyCountry)
                .regionCode("R")
                .regionName("Region")
                .enabled(true)
                .build();
    }

    private CompanyZone zone(CompanyRegion ofRegion) {
        return CompanyZone.builder()
                .id(UUID.randomUUID())
                .companyRegion(ofRegion)
                .zoneCode("Z")
                .zoneName("Zone")
                .enabled(true)
                .build();
    }

    private CompanyStore store(CompanyZone ofZone) {
        return store(ofZone, UUID.randomUUID());
    }

    private CompanyStore store(CompanyZone ofZone, UUID id) {
        return CompanyStore.builder()
                .id(id)
                .companyZone(ofZone)
                .storeName("Store")
                .enabled(true)
                .build();
    }

    private EmployeeScopeAssignment storeScopeAssignment(
            CompanyStore store, LocalDate validFrom, LocalDate validTo, boolean enabled) {
        return EmployeeScopeAssignment.builder()
                .id(UUID.randomUUID())
                .companyStore(store)
                .validFrom(validFrom)
                .validTo(validTo)
                .enabled(enabled)
                .build();
    }

    private EmployeeScopeAssignment scopeAssignment(
            CompanyCountry companyCountry,
            CompanyRegion companyRegion,
            CompanyZone companyZone,
            LocalDate validFrom,
            LocalDate validTo,
            boolean enabled) {
        return EmployeeScopeAssignment.builder()
                .id(UUID.randomUUID())
                .companyCountry(companyCountry)
                .companyRegion(companyRegion)
                .companyZone(companyZone)
                .validFrom(validFrom)
                .validTo(validTo)
                .enabled(enabled)
                .build();
    }

    private Employee linkedEmployee() {
        return Employee.builder()
                .id(employeeId)
                .company(company)
                .email("jane.doe@acme.com")
                .keycloakUserId(KC_USER_ID)
                .status(activeStatus())
                .build();
    }

    private Employee terminatedEmployee() {
        return Employee.builder()
                .id(employeeId)
                .company(company)
                .email("jane.doe@acme.com")
                .keycloakUserId(KC_USER_ID)
                .status(Status.builder().statusName("Terminated").enabled(true).build())
                .build();
    }

    /** A soft-deleted employee: {@code enabled = false}, every other fact still standing (G41). */
    private Employee disabledEmployee() {
        return Employee.builder()
                .id(employeeId)
                .company(company)
                .email("jane.doe@acme.com")
                .keycloakUserId(KC_USER_ID)
                .status(activeStatus())
                .enabled(false)
                .build();
    }

    private Status activeStatus() {
        return Status.builder().statusName("Active").enabled(true).build();
    }

    // --- stubbing ---

    /**
     * Stubs the per-level finder with the declared rows, with no filter applied: the enabled and
     * coverage filters belong to {@code ScopeAssignmentDerivation}, so an ignored row really exercises
     * the filter here instead of being filtered away by the stub. An unstubbed call returns an empty
     * list from Mockito's default answer, which is the "no level assigned" case the store-only
     * fixtures rely on.
     */
    private void stubScopeAssignments(EmployeeScopeAssignment... rows) {
        when(scopeAssignmentRepository.findEnabledAssignmentsCoveringDate(eq(employeeId), any(LocalDate.class)))
                .thenReturn(List.of(rows));
    }

    // --- assertions ---

    /** The company-wide shape: {@code company_id} written, the four deeper keys deleted, nothing else. */
    private void assertOnlyCompanyWritten() {
        verify(identityProvider)
                .updateUserAttribute(KC_USER_ID, ScopeLevel.COMPANY.claim(), List.of(companyId.toString()));
        verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.COUNTRY.claim());
        verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.REGION.claim());
        verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.ZONE.claim());
        verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.STORE.claim());
        verify(identityProvider, times(1)).updateUserAttribute(eq(KC_USER_ID), anyString(), any());
        verify(identityProvider, times(4)).deleteUserAttribute(eq(KC_USER_ID), anyString());
        verifyNoMoreInteractions(identityProvider);
    }

    @Nested
    @DisplayName("convergeMembership")
    class ConvergeMembershipTests {

        @Test
        @DisplayName("an active employee with one store has all five keys written once, each with its own id")
        void activeEmployeeWithOneStoreWritesAllFiveKeys() {
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);
            var store = store(zone);
            stubScopeAssignments(storeScopeAssignment(store, today().minusDays(30), null, true));

            var scope = service.convergeMembership(linkedEmployee());

            assertThat(scope.companyIds()).containsExactly(companyId);
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(region.getId());
            assertThat(scope.companyZoneIds()).containsExactly(zone.getId());
            assertThat(scope.companyStoreIds()).containsExactly(store.getId());

            verify(identityProvider)
                    .updateUserAttribute(KC_USER_ID, ScopeLevel.COMPANY.claim(), List.of(companyId.toString()));
            verify(identityProvider)
                    .updateUserAttribute(
                            KC_USER_ID,
                            ScopeLevel.COUNTRY.claim(),
                            List.of(companyCountry.getId().toString()));
            verify(identityProvider)
                    .updateUserAttribute(
                            KC_USER_ID,
                            ScopeLevel.REGION.claim(),
                            List.of(region.getId().toString()));
            verify(identityProvider)
                    .updateUserAttribute(
                            KC_USER_ID,
                            ScopeLevel.ZONE.claim(),
                            List.of(zone.getId().toString()));
            verify(identityProvider)
                    .updateUserAttribute(
                            KC_USER_ID,
                            ScopeLevel.STORE.claim(),
                            List.of(store.getId().toString()));
            verify(identityProvider, times(5)).updateUserAttribute(eq(KC_USER_ID), anyString(), any());
            verify(identityProvider, never()).deleteUserAttribute(any(), any());
        }

        @Test
        @DisplayName("two stores of the same country carry two store values and one country value, in derivation order")
        void twoStoresOfTheSameCountryCarryTwoStoreValuesAndOneCountryValue() {
            var companyCountry = companyCountry(company);
            var olderRegion = region(companyCountry);
            var olderStore = store(zone(olderRegion), FIRST_STORE_ID);
            var newerRegion = region(companyCountry);
            var newerStore = store(zone(newerRegion), SECOND_STORE_ID);
            // Newest first, exactly as the repository's ORDER BY validFrom DESC returns them: the
            // projection must keep this order and not sort the ids.
            stubScopeAssignments(
                    storeScopeAssignment(newerStore, today().minusDays(1), null, true),
                    storeScopeAssignment(olderStore, today().minusDays(30), null, true));

            var scope = service.convergeMembership(linkedEmployee());

            assertThat(scope.companyStoreIds()).containsExactly(SECOND_STORE_ID, FIRST_STORE_ID);
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(newerRegion.getId(), olderRegion.getId());
            assertThat(scope.companyZoneIds())
                    .containsExactly(
                            newerStore.getCompanyZone().getId(),
                            olderStore.getCompanyZone().getId());

            verify(identityProvider)
                    .updateUserAttribute(
                            KC_USER_ID,
                            ScopeLevel.STORE.claim(),
                            List.of(SECOND_STORE_ID.toString(), FIRST_STORE_ID.toString()));
            verify(identityProvider)
                    .updateUserAttribute(
                            KC_USER_ID,
                            ScopeLevel.COUNTRY.claim(),
                            List.of(companyCountry.getId().toString()));
            verify(identityProvider, never()).deleteUserAttribute(any(), any());
        }

        @Test
        @DisplayName("a company-wide employee has company_id written and the other four keys deleted")
        void companyWideEmployeeWritesCompanyAndDeletesTheFourDeeperKeys() {
            stubScopeAssignments();

            var scope = service.convergeMembership(linkedEmployee());

            assertThat(scope.companyIds()).containsExactly(companyId);
            assertThat(scope.companyCountryIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();
            assertOnlyCompanyWritten();
        }

        @Test
        @DisplayName("an assignment whose validTo is today contributes nothing: the bound is exclusive")
        void assignmentWhoseValidToIsTodayIsIgnored() {
            var store = store(zone(region(companyCountry(company))));
            stubScopeAssignments(storeScopeAssignment(store, today().minusDays(30), today(), true));

            service.convergeMembership(linkedEmployee());

            assertOnlyCompanyWritten();
        }

        @Test
        @DisplayName("the finder is queried with the same today the derivation is driven with")
        void finderIsQueriedWithTheSameTodayTheDerivationUses() {
            var store = store(zone(region(companyCountry(company))));
            stubScopeAssignments(storeScopeAssignment(store, today().minusDays(30), null, true));

            service.convergeMembership(linkedEmployee());

            var dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(scopeAssignmentRepository).findEnabledAssignmentsCoveringDate(eq(employeeId), dateCaptor.capture());
            assertThat(dateCaptor.getValue()).isEqualTo(today());
        }

        @Test
        @DisplayName("a disabled assignment contributes nothing")
        void disabledAssignmentIsIgnored() {
            var store = store(zone(region(companyCountry(company))));
            stubScopeAssignments(storeScopeAssignment(store, today().minusDays(30), null, false));

            service.convergeMembership(linkedEmployee());

            assertOnlyCompanyWritten();
        }

        @Test
        @DisplayName("an assignment with a future validFrom contributes nothing")
        void assignmentWithAFutureValidFromIsIgnored() {
            var store = store(zone(region(companyCountry(company))));
            stubScopeAssignments(storeScopeAssignment(store, today().plusDays(1), null, true));

            service.convergeMembership(linkedEmployee());

            assertOnlyCompanyWritten();
        }

        @Test
        @DisplayName("a Terminated employee has all five keys deleted, nothing written and holds an empty scope")
        void terminatedEmployeeDeletesAllFiveKeysAndWritesNothing() {
            var scope = service.convergeMembership(terminatedEmployee());

            assertThat(scope.companyIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();

            verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.COMPANY.claim());
            verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.COUNTRY.claim());
            verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.REGION.claim());
            verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.ZONE.claim());
            verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.STORE.claim());
            verify(identityProvider, never()).updateUserAttribute(any(), any(), any());
            verify(identityProvider, times(5)).deleteUserAttribute(eq(KC_USER_ID), anyString());
            verifyNoMoreInteractions(identityProvider);
            verifyNoInteractions(scopeAssignmentRepository);
        }

        @Test
        @DisplayName("a soft-deleted employee has all five keys deleted too, so the revocation removes the claims")
        void disabledEmployeeDeletesAllFiveKeysAndWritesNothing() {
            var scope = service.convergeMembership(disabledEmployee());

            assertThat(scope.companyIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();

            verify(identityProvider, times(5)).deleteUserAttribute(eq(KC_USER_ID), anyString());
            verify(identityProvider, never()).updateUserAttribute(any(), any(), any());
            verifyNoInteractions(scopeAssignmentRepository);
        }

        @Test
        @DisplayName("an employee without a keycloakUserId refuses and touches nothing at all")
        void accountNotLinkedRefusesBeforeAnyCall() {
            var unlinked = Employee.builder()
                    .id(employeeId)
                    .company(company)
                    .email("jane.doe@acme.com")
                    .status(activeStatus())
                    .build();
            var blank = Employee.builder()
                    .id(employeeId)
                    .company(company)
                    .email("jane.doe@acme.com")
                    .keycloakUserId("   ")
                    .status(activeStatus())
                    .build();

            assertThatThrownBy(() -> service.convergeMembership(unlinked))
                    .isInstanceOf(AccountNotLinkedException.class)
                    .hasMessageContaining(employeeId.toString());
            assertThatThrownBy(() -> service.convergeMembership(blank))
                    .isInstanceOf(AccountNotLinkedException.class)
                    .hasMessageContaining(employeeId.toString());

            verifyNoInteractions(identityProvider, scopeAssignmentRepository);
        }

        @Test
        @DisplayName("more than one company_id fails closed and writes nothing")
        void moreThanOneCompanyIdFailsClosed() {
            var otherCompanyId = UUID.randomUUID();
            var twoCompanies =
                    new EmployeeStoreScope(Set.of(companyId, otherCompanyId), Set.of(), Set.of(), Set.of(), Set.of());

            try (var derivation = mockStatic(ScopeAssignmentDerivation.class)) {
                derivation
                        .when(() -> ScopeAssignmentDerivation.derive(any(), any(), any()))
                        .thenReturn(twoCompanies);

                assertThatThrownBy(() -> service.convergeMembership(linkedEmployee()))
                        .isInstanceOf(CompanyScopeInvariantException.class)
                        .hasMessageContaining(employeeId.toString())
                        .hasMessageContaining("produced 2 company ids");

                verifyNoInteractions(identityProvider);
            }
        }

        @Test
        @DisplayName("no attribute other than the five claim keys is ever touched")
        void neverTouchesAnyOtherAttribute() {
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);
            var store = store(zone);
            stubScopeAssignments(storeScopeAssignment(store, today().minusDays(30), null, true));

            service.convergeMembership(linkedEmployee());

            verify(identityProvider, never()).updateUserAttribute(eq(KC_USER_ID), eq("locale"), any());
            verify(identityProvider, never()).deleteUserAttribute(eq(KC_USER_ID), eq("locale"));
            verify(identityProvider, never()).updateUserAttribute(eq(KC_USER_ID), eq("email"), any());
            verify(identityProvider, times(5)).updateUserAttribute(eq(KC_USER_ID), anyString(), any());
            verify(identityProvider, never()).deleteUserAttribute(eq(KC_USER_ID), anyString());
            verifyNoMoreInteractions(identityProvider);
        }

        @Test
        @DisplayName("an IdentityProviderConnectionException from a write propagates unchanged")
        void identityProviderFailurePropagates() {
            var store = store(zone(region(companyCountry(company))));
            stubScopeAssignments(storeScopeAssignment(store, today().minusDays(30), null, true));
            var boom = new IdentityProviderConnectionException("keycloak is unreachable");
            doThrow(boom)
                    .when(identityProvider)
                    .updateUserAttribute(KC_USER_ID, ScopeLevel.COMPANY.claim(), List.of(companyId.toString()));

            assertThatThrownBy(() -> service.convergeMembership(linkedEmployee()))
                    .isSameAs(boom);

            verify(identityProvider, times(1)).updateUserAttribute(eq(KC_USER_ID), anyString(), any());
            verify(identityProvider, never()).deleteUserAttribute(any(), any());
        }
    }

    @Nested
    @DisplayName("one source: the scope table (S5)")
    class ScopeSourceUnionTests {

        @Test
        @DisplayName("a region scope row writes country and region and deletes zone and store")
        void regionScopeRowWritesItsOwnLevelAndItsAncestor() {
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            stubScopeAssignments(scopeAssignment(null, region, null, today().minusDays(5), null, true));

            var scope = service.convergeMembership(linkedEmployee());

            assertThat(scope.companyIds()).containsExactly(companyId);
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(region.getId());
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();

            verify(identityProvider)
                    .updateUserAttribute(
                            KC_USER_ID,
                            ScopeLevel.COUNTRY.claim(),
                            List.of(companyCountry.getId().toString()));
            verify(identityProvider)
                    .updateUserAttribute(
                            KC_USER_ID,
                            ScopeLevel.REGION.claim(),
                            List.of(region.getId().toString()));
            verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.ZONE.claim());
            verify(identityProvider).deleteUserAttribute(KC_USER_ID, ScopeLevel.STORE.claim());
            verify(identityProvider, times(3)).updateUserAttribute(eq(KC_USER_ID), anyString(), any());
            verify(identityProvider, times(2)).deleteUserAttribute(eq(KC_USER_ID), anyString());
            verifyNoMoreInteractions(identityProvider);
        }

        @Test
        @DisplayName("a zone scope row derives the whole chain it implies, through the read-only accessor too")
        void zoneScopeRowDerivesItsWholeChain() {
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);
            stubScopeAssignments(scopeAssignment(null, null, zone, today().minusDays(5), null, true));

            var scope = service.deriveMembership(linkedEmployee());

            assertThat(scope.companyIds()).containsExactly(companyId);
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(region.getId());
            assertThat(scope.companyZoneIds()).containsExactly(zone.getId());
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("a store row and a country row of one source both contribute, in row order")
        void aStoreRowAndACountryRowCarryTheirOwnValuesInOrder() {
            var storeCountry = companyCountry(company);
            var store = store(zone(region(storeCountry)));
            var scopeCountry = companyCountry(company);
            // Both rows come from ONE finder now: since V32 the store is a level of the scope table, so
            // there is no second source to fold — the derivation reads a single list and emits a claim
            // per row, keeping the finder's newest-first order instead of sorting or rebuilding it.
            stubScopeAssignments(
                    storeScopeAssignment(store, today().minusDays(1), null, true),
                    scopeAssignment(scopeCountry, null, null, today().minusDays(5), null, true));

            var scope = service.convergeMembership(linkedEmployee());

            assertThat(scope.companyIds()).containsExactly(companyId);
            assertThat(scope.companyCountryIds()).containsExactly(storeCountry.getId(), scopeCountry.getId());
            assertThat(scope.companyStoreIds()).containsExactly(store.getId());
            verify(identityProvider)
                    .updateUserAttribute(
                            KC_USER_ID,
                            ScopeLevel.COUNTRY.claim(),
                            List.of(
                                    storeCountry.getId().toString(),
                                    scopeCountry.getId().toString()));
        }

        @Test
        @DisplayName("a disabled, an ended and a future scope row all contribute nothing")
        void disabledAndNonCoveringScopeRowsContributeNothing() {
            var companyCountry = companyCountry(company);
            stubScopeAssignments(
                    scopeAssignment(companyCountry, null, null, today().minusDays(5), null, false),
                    scopeAssignment(companyCountry, null, null, today().minusDays(5), today(), true),
                    scopeAssignment(companyCountry, null, null, today().plusDays(1), null, true));

            var scope = service.convergeMembership(linkedEmployee());

            assertThat(scope.companyCountryIds()).isEmpty();
            assertOnlyCompanyWritten();
        }

        @Test
        @DisplayName("the per-level finder is queried with the same today the derivation is driven with")
        void scopeFinderUsesTheSameToday() {
            stubScopeAssignments();

            service.convergeMembership(linkedEmployee());

            var dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(scopeAssignmentRepository).findEnabledAssignmentsCoveringDate(eq(employeeId), dateCaptor.capture());
            assertThat(dateCaptor.getValue()).isEqualTo(today());
        }

        @Test
        @DisplayName("a Terminated employee with live scope rows has all five keys deleted and holds no scope")
        void terminatedEmployeeIgnoresItsScopeRows() {
            var scope = service.convergeMembership(terminatedEmployee());

            assertThat(scope.companyIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();
            verify(identityProvider, times(5)).deleteUserAttribute(eq(KC_USER_ID), anyString());
            verify(identityProvider, never()).updateUserAttribute(any(), any(), any());
            verifyNoInteractions(scopeAssignmentRepository);
        }
    }

    @Nested
    @DisplayName("deriveMembership (read-only accessor)")
    class DeriveMembershipTests {

        @Test
        @DisplayName("returns the same derivation the write path uses and writes nothing")
        void returnsTheDerivationWithoutWriting() {
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);
            var store = store(zone);
            stubScopeAssignments(storeScopeAssignment(store, today().minusDays(30), null, true));

            var scope = service.deriveMembership(linkedEmployee());

            assertThat(scope.companyIds()).containsExactly(companyId);
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(region.getId());
            assertThat(scope.companyZoneIds()).containsExactly(zone.getId());
            assertThat(scope.companyStoreIds()).containsExactly(store.getId());
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("returns five empty sets for a Terminated employee and writes nothing")
        void terminatedEmployeeHoldsAnEmptyScope() {
            var scope = service.deriveMembership(terminatedEmployee());

            assertThat(scope.companyIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();
            verifyNoInteractions(identityProvider);
            verifyNoInteractions(scopeAssignmentRepository);
        }

        @Test
        @DisplayName("returns five empty sets for a soft-deleted employee and writes nothing (G41)")
        void disabledEmployeeHoldsAnEmptyScope() {
            var scope = service.deriveMembership(disabledEmployee());

            assertThat(scope.companyIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();
            verifyNoInteractions(identityProvider);
            verifyNoInteractions(scopeAssignmentRepository);
        }

        @Test
        @DisplayName("refuses when the account is not linked and touches nothing")
        void refusesWhenTheAccountIsNotLinked() {
            var unlinked = Employee.builder()
                    .id(employeeId)
                    .company(company)
                    .email("jane.doe@acme.com")
                    .status(activeStatus())
                    .build();

            assertThatThrownBy(() -> service.deriveMembership(unlinked))
                    .isInstanceOf(AccountNotLinkedException.class)
                    .hasMessageContaining(employeeId.toString());

            verifyNoInteractions(identityProvider, scopeAssignmentRepository);
        }

        @Test
        @DisplayName("fails closed on more than one company_id and writes nothing")
        void failsClosedOnMoreThanOneCompanyId() {
            var twoCompanies = new EmployeeStoreScope(
                    Set.of(companyId, UUID.randomUUID()), Set.of(), Set.of(), Set.of(), Set.of());

            try (var derivation = mockStatic(ScopeAssignmentDerivation.class)) {
                derivation
                        .when(() -> ScopeAssignmentDerivation.derive(any(), any(), any()))
                        .thenReturn(twoCompanies);

                assertThatThrownBy(() -> service.deriveMembership(linkedEmployee()))
                        .isInstanceOf(CompanyScopeInvariantException.class)
                        .hasMessageContaining(employeeId.toString());

                verifyNoInteractions(identityProvider);
            }
        }
    }
}
