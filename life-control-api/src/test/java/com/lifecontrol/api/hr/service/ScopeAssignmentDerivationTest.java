package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.hr.model.EmployeeScopeAssignment;
import com.lifecontrol.api.hr.model.EmployeeStoreAssignment;
import com.lifecontrol.api.store.model.CompanyStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Spec of the per-level scope derivation (decision T4). No Spring and no Mockito:
 * {@link ScopeAssignmentDerivation} is a pure function, exactly the shape
 * {@code StoreScopeDerivationTest} gives the neighbouring one.
 *
 * <p>Every rule the record states is pinned here: a {@code COUNTRY} row contributes its country, a
 * {@code REGION} row contributes region <b>and</b> its country, a {@code ZONE} row contributes zone,
 * region and country, the company level is always the employee's own and never the row's, a row
 * contributes only while enabled and covering {@code today} under the strictly-exclusive
 * {@code '[)'} reading, a chain that does not resolve contributes <b>nothing</b> rather than a
 * partial chain, and {@link ScopeAssignmentDerivation#union} merges the store-derived ids with the
 * level-derived ones without duplicating the company.</p>
 */
@DisplayName("ScopeAssignmentDerivation Tests")
class ScopeAssignmentDerivationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Nested
    @DisplayName("a single row, one level at a time")
    class SingleRowTests {

        @Test
        @DisplayName("a COUNTRY row contributes only its country")
        void countryRow_ContributesOnlyItsCountry() {
            var company = company();
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);

            var scope = derive(company.getId(), countryAssignment(companyCountry));

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();
        }

        @Test
        @DisplayName("a REGION row contributes its region and its country, never its zones")
        void regionRow_ContributesRegionAndItsCountry() {
            var company = company();
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            zone(region);

            var scope = derive(company.getId(), regionAssignment(region));

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(region.getId());
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();
        }

        @Test
        @DisplayName("a ZONE row contributes its zone, its region and its country")
        void zoneRow_ContributesTheWholeAncestorChain() {
            var company = company();
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);

            var scope = derive(company.getId(), zoneAssignment(zone));

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(region.getId());
            assertThat(scope.companyZoneIds()).containsExactly(zone.getId());
            assertThat(scope.companyStoreIds()).isEmpty();
        }

        @Test
        @DisplayName("no row keeps the company id alone, which is the company-wide scope")
        void noRows_KeepsTheCompanyIdOnly() {
            var company = company();

            var scope = ScopeAssignmentDerivation.derive(company.getId(), List.of(), TODAY);

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("several rows")
    class SeveralRowsTests {

        @Test
        @DisplayName("rows at three levels of one chain dedupe to one id per level, in insertion order")
        void rowsAtThreeLevels_DedupePerLevel() {
            var company = company();
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);

            var scope = derive(
                    company.getId(), countryAssignment(companyCountry), regionAssignment(region), zoneAssignment(zone));

            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(region.getId());
            assertThat(scope.companyZoneIds()).containsExactly(zone.getId());
        }

        @Test
        @DisplayName("two independent chains produce two values per level in row order")
        void twoChains_ProduceTwoValuesPerLevel() {
            var company = company();
            var firstCountry = companyCountry(company);
            var firstRegion = region(firstCountry);
            var secondCountry = companyCountry(company);
            var secondZone = zone(region(secondCountry));

            var scope = derive(company.getId(), regionAssignment(firstRegion), zoneAssignment(secondZone));

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).containsExactly(firstCountry.getId(), secondCountry.getId());
            assertThat(scope.companyRegionIds())
                    .containsExactly(
                            firstRegion.getId(), secondZone.getCompanyRegion().getId());
            assertThat(scope.companyZoneIds()).containsExactly(secondZone.getId());
        }
    }

    @Nested
    @DisplayName("the enabled and coverage predicates")
    class PredicateTests {

        @Test
        @DisplayName("a soft-deleted (enabled = false) row contributes nothing")
        void disabledRow_IsExcluded() {
            var company = company();
            var companyCountry = companyCountry(company);

            var scope =
                    derive(company.getId(), assignment(companyCountry, null, null, TODAY.minusDays(10), null, false));

            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("a row whose stored bound is today does not cover today (half-open)")
        void boundEqualToToday_IsExcluded() {
            var company = company();
            var companyCountry = companyCountry(company);

            var scope =
                    derive(company.getId(), assignment(companyCountry, null, null, TODAY.minusDays(10), TODAY, true));

            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("a row starting today covers today; a row starting tomorrow does not")
        void futureStart_IsExcluded() {
            var company = company();
            var companyCountry = companyCountry(company);

            assertThat(derive(company.getId(), assignment(companyCountry, null, null, TODAY, null, true))
                            .companyCountryIds())
                    .as("a row starting today covers today")
                    .containsExactly(companyCountry.getId());
            assertThat(derive(company.getId(), assignment(companyCountry, null, null, TODAY.plusDays(1), null, true))
                            .companyCountryIds())
                    .as("a row starting tomorrow covers nothing yet")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the assigned node's own enabled flag (symmetry with StoreScopeDerivation T12)")
    class NodeEnabledFlagTests {

        // There is deliberately no COUNTRY case here: `company_countries` carries no `enabled` column
        // (V1__baseline_schema.sql) and `CompanyCountry` no flag, so a country row has no node flag to
        // consult and its contributiveness is decided by the row's own `enabled` alone.

        @Test
        @DisplayName("a disabled region row contributes nothing, not even its country")
        void disabledRegionRow_ContributesNothing() {
            var company = company();
            var companyCountry = companyCountry(company);
            var disabledRegion = region(companyCountry, false);

            var scope = derive(company.getId(), regionAssignment(disabledRegion));

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("a disabled zone row contributes nothing, not even its region and country")
        void disabledZoneRow_ContributesNothing() {
            var company = company();
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var disabledZone = zone(region, false);

            var scope = derive(company.getId(), zoneAssignment(disabledZone));

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName(
                "a zone row whose region is disabled still contributes its whole chain: the ancestor flag is not consulted")
        void zoneRowWithDisabledRegion_KeepsItsWholeChain() {
            var company = company();
            var companyCountry = companyCountry(company);
            var disabledRegion = region(companyCountry, false);
            var zone = zone(disabledRegion);

            var scope = derive(company.getId(), zoneAssignment(zone));

            // The symmetry this pins: a disabled ancestor must not drop a grant the tree still
            // resolves, exactly as a disabled zone does not drop a store assignment today.
            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(disabledRegion.getId());
            assertThat(scope.companyZoneIds()).containsExactly(zone.getId());
        }
    }

    @Nested
    @DisplayName("the no-partial-chain invariant")
    class NoPartialChainTests {

        @Test
        @DisplayName("a zone whose region hop is missing contributes nothing at all")
        void zoneWithoutRegion_IsSkippedWhole() {
            var company = company();
            var orphanZone = CompanyZone.builder()
                    .id(UUID.randomUUID())
                    .companyRegion(null)
                    .zoneCode("Z")
                    .zoneName("Zone")
                    .enabled(true)
                    .build();

            var scope = derive(company.getId(), zoneAssignment(orphanZone));

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("a zone whose region has no country contributes nothing, not a zone without its country")
        void zoneWithoutCountry_IsSkippedWhole() {
            var company = company();
            var orphanRegion = CompanyRegion.builder()
                    .id(UUID.randomUUID())
                    .companyCountry(null)
                    .regionCode("R")
                    .regionName("Region")
                    .enabled(true)
                    .build();
            var zone = zone(orphanRegion);

            var scope = derive(company.getId(), zoneAssignment(zone));

            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("a region whose country is missing contributes nothing")
        void regionWithoutCountry_IsSkippedWhole() {
            var company = company();
            var orphanRegion = CompanyRegion.builder()
                    .id(UUID.randomUUID())
                    .companyCountry(null)
                    .regionCode("R")
                    .regionName("Region")
                    .enabled(true)
                    .build();

            var scope = derive(company.getId(), regionAssignment(orphanRegion));

            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("a row whose chain belongs to another company contributes no company id (S3)")
        void foreignChainContributesNoCompanyId() {
            var employeeCompany = company();
            var foreignCountry = companyCountry(company());

            var scope = derive(employeeCompany.getId(), countryAssignment(foreignCountry));

            // S3 from the other direction: the company-level fact is the employee's own and a row can
            // never move it. The write path refuses a foreign node with a 404, and this pins what the
            // derivation would answer even if such a row reached it.
            assertThat(scope.companyIds()).containsExactly(employeeCompany.getId());
        }

        @Test
        @DisplayName("a row with no node column set contributes nothing (the CHECK forbids it on a stored row)")
        void rowWithoutNode_IsSkipped() {
            var company = company();

            var scope = derive(company.getId(), assignment(null, null, null, TODAY.minusDays(10), null, true));

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("union with the store-derived scope (S5)")
    class UnionTests {

        @Test
        @DisplayName("the union carries the store chain and the level chain, with one company id")
        void union_CarriesBothSourcesOnce() {
            var company = company();
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);
            var store = store(zone);
            var storeScope = StoreScopeDerivation.derive(
                    company.getId(),
                    List.of(EmployeeStoreAssignment.builder()
                            .id(UUID.randomUUID())
                            .companyStore(store)
                            .validFrom(TODAY.minusDays(30))
                            .enabled(true)
                            .build()),
                    TODAY);
            var levelScope = derive(company.getId(), regionAssignment(region));

            var union = ScopeAssignmentDerivation.union(storeScope, levelScope);

            assertThat(union.companyIds()).containsExactly(company.getId());
            assertThat(union.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(union.companyRegionIds()).containsExactly(region.getId());
            assertThat(union.companyZoneIds()).containsExactly(zone.getId());
            assertThat(union.companyStoreIds()).containsExactly(store.getId());
        }

        @Test
        @DisplayName("the union keeps the store-derived ids first and appends the new ones")
        void union_PreservesBothOrders() {
            var company = company();
            var storeCountry = companyCountry(company);
            var storeScope = StoreScopeDerivation.derive(
                    company.getId(),
                    List.of(EmployeeStoreAssignment.builder()
                            .id(UUID.randomUUID())
                            .companyStore(store(zone(region(storeCountry))))
                            .validFrom(TODAY.minusDays(30))
                            .enabled(true)
                            .build()),
                    TODAY);
            var levelCountry = companyCountry(company);
            var levelScope = derive(company.getId(), countryAssignment(levelCountry));

            var union = ScopeAssignmentDerivation.union(storeScope, levelScope);

            assertThat(union.companyCountryIds()).containsExactly(storeCountry.getId(), levelCountry.getId());
        }

        @Test
        @DisplayName("unioning two empty deep levels yields the company alone, not an error")
        void union_OfTwoCompanyWideScopes_HoldsTheCompanyAlone() {
            var company = company();
            var storeScope = StoreScopeDerivation.derive(company.getId(), List.of(), TODAY);
            var levelScope = ScopeAssignmentDerivation.derive(company.getId(), List.of(), TODAY);

            var union = ScopeAssignmentDerivation.union(storeScope, levelScope);

            assertThat(union.companyIds()).containsExactly(company.getId());
            assertThat(union.companyCountryIds()).isEmpty();
            assertThat(union.companyZoneIds()).isEmpty();
        }

        @Test
        @DisplayName("the union rejects a null side instead of silently returning the other")
        void union_RejectsNull() {
            var company = company();
            var scope = StoreScopeDerivation.derive(company.getId(), List.of(), TODAY);

            assertThatThrownBy(() -> ScopeAssignmentDerivation.union(scope, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ScopeAssignmentDerivation.union(null, scope))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the union's output is unmodifiable, like every value that answered an authorization question")
        void union_IsUnmodifiable() {
            var company = company();
            var union = ScopeAssignmentDerivation.union(
                    StoreScopeDerivation.derive(company.getId(), List.of(), TODAY),
                    ScopeAssignmentDerivation.derive(company.getId(), List.of(), TODAY));

            assertThatThrownBy(() -> union.companyIds().add(UUID.randomUUID()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentTests {

        @Test
        @DisplayName("rejects a null company, null assignments and null today")
        void derive_RejectsNullArguments() {
            assertThatThrownBy(() -> ScopeAssignmentDerivation.derive(null, List.of(), TODAY))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ScopeAssignmentDerivation.derive(UUID.randomUUID(), null, TODAY))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ScopeAssignmentDerivation.derive(UUID.randomUUID(), List.of(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("tolerates a null element in the collection instead of failing")
        void derive_ToleratesNullElement() {
            var company = company();
            var rows = new ArrayList<EmployeeScopeAssignment>();
            rows.add(null);

            var scope = ScopeAssignmentDerivation.derive(company.getId(), rows, TODAY);

            assertThat(scope.companyIds()).containsExactly(company.getId());
        }
    }

    // --- fixtures ---

    private EmployeeStoreScope derive(UUID employeeCompanyId, EmployeeScopeAssignment... rows) {
        return ScopeAssignmentDerivation.derive(employeeCompanyId, List.of(rows), TODAY);
    }

    private Company company() {
        return Company.builder()
                .id(UUID.randomUUID())
                .companyName("Scope Derivation Company")
                .enabled(true)
                .build();
    }

    private CompanyCountry companyCountry(Company company) {
        return CompanyCountry.builder()
                .id(UUID.randomUUID())
                .company(company)
                .country(Country.builder()
                        .id(UUID.randomUUID())
                        .countryCode("MX")
                        .countryName("México")
                        .enabled(true)
                        .build())
                .build();
    }

    private CompanyRegion region(CompanyCountry companyCountry) {
        return CompanyRegion.builder()
                .id(UUID.randomUUID())
                .companyCountry(companyCountry)
                .regionCode("R")
                .regionName("Region")
                .enabled(true)
                .build();
    }

    private CompanyZone zone(CompanyRegion region) {
        return CompanyZone.builder()
                .id(UUID.randomUUID())
                .companyRegion(region)
                .zoneCode("Z")
                .zoneName("Zone")
                .enabled(true)
                .build();
    }

    /** The same region chain with the region's own {@code enabled} flag set as asked. */
    private CompanyRegion region(CompanyCountry companyCountry, boolean enabled) {
        return CompanyRegion.builder()
                .id(UUID.randomUUID())
                .companyCountry(companyCountry)
                .regionCode("R")
                .regionName("Region")
                .enabled(enabled)
                .build();
    }

    /** The same zone chain with the zone's own {@code enabled} flag set as asked. */
    private CompanyZone zone(CompanyRegion region, boolean enabled) {
        return CompanyZone.builder()
                .id(UUID.randomUUID())
                .companyRegion(region)
                .zoneCode("Z")
                .zoneName("Zone")
                .enabled(enabled)
                .build();
    }

    private CompanyStore store(CompanyZone zone) {
        return CompanyStore.builder()
                .id(UUID.randomUUID())
                .companyZone(zone)
                .storeName("Store")
                .enabled(true)
                .build();
    }

    private EmployeeScopeAssignment countryAssignment(CompanyCountry companyCountry) {
        return assignment(companyCountry, null, null, TODAY.minusDays(10), null, true);
    }

    private EmployeeScopeAssignment regionAssignment(CompanyRegion region) {
        return assignment(null, region, null, TODAY.minusDays(10), null, true);
    }

    private EmployeeScopeAssignment zoneAssignment(CompanyZone zone) {
        return assignment(null, null, zone, TODAY.minusDays(10), null, true);
    }

    private EmployeeScopeAssignment assignment(
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
}
