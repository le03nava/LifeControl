package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.hr.model.EmployeeStoreAssignment;
import com.lifecontrol.api.store.model.CompanyStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Spec of the pure derivation (decision T6). No Spring, no Mockito: {@link StoreScopeDerivation} is
 * a static function and every rule here is a pure function of its inputs, exactly the shape
 * {@code EmployeeEmailGeneratorTest} gives the neighbouring pure helper.
 *
 * <p>The derivation is the risky piece of the feature — it produces the five id sets the token's
 * claim parser reads (F3/F4) — so this suite pins each rule the record states: the company-level
 * fact always present (D3/G6), a row contributing only while enabled, covered and inside an enabled
 * store (T12), the complete ancestor chain with no partial output, and the exact half-open predicate
 * boundary. The disabled ancestor flags are deliberately exercised too, because
 * {@code StoreScopeDerivation} must <b>not</b> consult them (T12).</p>
 */
@DisplayName("StoreScopeDerivation Tests")
class StoreScopeDerivationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Nested
    @DisplayName("a single assignment")
    class SingleAssignmentTests {

        @Test
        @DisplayName("derives the whole ancestor chain of the one assigned store")
        void oneStore_DerivesTheWholeChain() {
            var company = company();
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);
            var store = store(zone, true);

            var scope = StoreScopeDerivation.derive(
                    company.getId(), List.of(assignment(store, TODAY.minusDays(30), null, true)), TODAY);

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
            assertThat(scope.companyRegionIds()).containsExactly(region.getId());
            assertThat(scope.companyZoneIds()).containsExactly(zone.getId());
            assertThat(scope.companyStoreIds()).containsExactly(store.getId());
        }

        @Test
        @DisplayName("a company-wide person keeps the company id and derives nothing deeper (D3/G6)")
        void noAssignment_KeepsTheCompanyIdOnly() {
            var company = company();

            var scope = StoreScopeDerivation.derive(company.getId(), List.of(), TODAY);

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyStoreIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("several assignments")
    class SeveralAssignmentsTests {

        @Test
        @DisplayName("two stores in different countries produce two values per level in insertion order")
        void twoStoresInDifferentCountries_ProduceLists() {
            var company = company();
            var companyCountryOne = companyCountry(company);
            var regionOne = region(companyCountryOne);
            var zoneOne = zone(regionOne);
            var storeOne = store(zoneOne, true);
            var companyCountryTwo = companyCountry(company);
            var regionTwo = region(companyCountryTwo);
            var zoneTwo = zone(regionTwo);
            var storeTwo = store(zoneTwo, true);

            var scope = StoreScopeDerivation.derive(
                    company.getId(),
                    List.of(
                            assignment(storeOne, TODAY.minusDays(30), null, true),
                            assignment(storeTwo, TODAY.minusDays(30), null, true)),
                    TODAY);

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyCountryIds()).containsExactly(companyCountryOne.getId(), companyCountryTwo.getId());
            assertThat(scope.companyRegionIds()).containsExactly(regionOne.getId(), regionTwo.getId());
            assertThat(scope.companyZoneIds()).containsExactly(zoneOne.getId(), zoneTwo.getId());
            assertThat(scope.companyStoreIds()).containsExactly(storeOne.getId(), storeTwo.getId());
        }

        @Test
        @DisplayName("two rows resolving the same store collapse to a single id per level")
        void duplicateStore_IsCollapsed() {
            var company = company();
            var companyCountry = companyCountry(company);
            var region = region(companyCountry);
            var zone = zone(region);
            var store = store(zone, true);

            var scope = StoreScopeDerivation.derive(
                    company.getId(),
                    List.of(
                            assignment(store, TODAY.minusDays(30), TODAY.minusDays(1), true),
                            assignment(store, TODAY, null, true)),
                    TODAY);

            assertThat(scope.companyStoreIds()).containsExactly(store.getId());
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
        }
    }

    @Nested
    @DisplayName("the coverage predicate and the enabled flags")
    class PredicateTests {

        @Test
        @DisplayName("a closed assignment is excluded")
        void closedAssignment_IsExcluded() {
            var scope = derive(assignment(store(), TODAY.minusDays(60), TODAY.minusDays(1), true));

            assertThat(scope.companyStoreIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("a future assignment is excluded")
        void futureAssignment_IsExcluded() {
            var scope = derive(assignment(store(), TODAY.plusDays(1), null, true));

            assertThat(scope.companyStoreIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("validTo == today is NOT covered, validFrom == today IS covered (half-open bound)")
        void predicateBoundary_IsStrictlyExclusiveAtTheTop() {
            assertThat(derive(assignment(store(), TODAY.minusDays(10), TODAY, true))
                            .companyStoreIds())
                    .as("a row whose stored bound is today does not cover today")
                    .isEmpty();
            assertThat(derive(assignment(store(), TODAY, null, true)).companyStoreIds())
                    .as("a row starting today covers today")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a soft-deleted (enabled = false) row is excluded")
        void softDeletedAssignment_IsExcluded() {
            var scope = derive(assignment(store(), TODAY.minusDays(10), null, false));

            assertThat(scope.companyStoreIds()).isEmpty();
        }

        @Test
        @DisplayName("a disabled store derives nothing but the company, even with a live enabled row (T12)")
        void disabledStore_IsExcludedAndTheDeeperSetsStayEmpty() {
            var company = company();
            var store = storeIn(company, false);

            var scope = StoreScopeDerivation.derive(
                    company.getId(), List.of(assignment(store, TODAY.minusDays(10), null, true)), TODAY);

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyStoreIds()).isEmpty();
            assertThat(scope.companyZoneIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("the ancestor enabled flags are deliberately not consulted (T12)")
        void disabledAncestors_AreIgnoredWhenTheStoreIsEnabled() {
            var company = Company.builder()
                    .id(UUID.randomUUID())
                    .companyName("Co")
                    .enabled(false)
                    .build();
            var companyCountry = CompanyCountry.builder()
                    .id(UUID.randomUUID())
                    .company(company)
                    .build();
            var region = CompanyRegion.builder()
                    .id(UUID.randomUUID())
                    .companyCountry(companyCountry)
                    .regionCode("R")
                    .regionName("Region")
                    .enabled(false)
                    .build();
            var zone = CompanyZone.builder()
                    .id(UUID.randomUUID())
                    .companyRegion(region)
                    .zoneCode("Z")
                    .zoneName("Zone")
                    .enabled(false)
                    .build();
            var store = store(zone, true);

            var scope = StoreScopeDerivation.derive(
                    company.getId(), List.of(assignment(store, TODAY.minusDays(10), null, true)), TODAY);

            assertThat(scope.companyStoreIds()).containsExactly(store.getId());
            assertThat(scope.companyCountryIds()).containsExactly(companyCountry.getId());
        }
    }

    @Nested
    @DisplayName("the no-partial-chain invariant")
    class NoPartialChainTests {

        @Test
        @DisplayName("a store without a zone contributes nothing, not a bare store id")
        void missingZone_IsSkippedWhole() {
            var company = company();
            var orphan = CompanyStore.builder()
                    .id(UUID.randomUUID())
                    .companyZone(null)
                    .storeName("Orphan")
                    .enabled(true)
                    .build();

            var scope = StoreScopeDerivation.derive(
                    company.getId(), List.of(assignment(orphan, TODAY.minusDays(10), null, true)), TODAY);

            assertThat(scope.companyIds()).containsExactly(company.getId());
            assertThat(scope.companyStoreIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
        }

        @Test
        @DisplayName("a store whose region hop is missing contributes nothing either")
        void missingRegion_IsSkippedWhole() {
            var company = company();
            var companyCountry = companyCountry(company);
            var orphanZone = CompanyZone.builder()
                    .id(UUID.randomUUID())
                    .companyRegion(null)
                    .zoneCode("Z")
                    .zoneName("Zone")
                    .build();
            var store = store(orphanZone, true);

            var scope = StoreScopeDerivation.derive(
                    company.getId(), List.of(assignment(store, TODAY.minusDays(10), null, true)), TODAY);

            assertThat(scope.companyStoreIds()).isEmpty();
            assertThat(scope.companyRegionIds()).isEmpty();
            assertThat(scope.companyCountryIds()).isEmpty();
            assertThat(companyCountry.getId()).isNotNull();
        }

        @Test
        @DisplayName("every non-empty deeper level implies a non-empty country set")
        void deeperLevelsImplyACountry() {
            var company = company();
            var store = storeIn(company, true);

            var scope = StoreScopeDerivation.derive(
                    company.getId(), List.of(assignment(store, TODAY.minusDays(10), null, true)), TODAY);

            assertThat(scope.companyStoreIds()).isNotEmpty();
            assertThat(scope.companyCountryIds()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("the value object")
    class ValueObjectTests {

        @Test
        @DisplayName("defensively copies and exposes unmodifiable sets")
        void scope_CopiesAndIsUnmodifiable() {
            var mutable = new LinkedHashSet<UUID>();
            var id = UUID.randomUUID();
            mutable.add(id);
            var scope = new EmployeeStoreScope(
                    mutable,
                    new LinkedHashSet<>(),
                    new LinkedHashSet<>(),
                    new LinkedHashSet<>(),
                    new LinkedHashSet<>());

            mutable.add(UUID.randomUUID());

            assertThat(scope.companyIds()).containsExactly(id);
            assertThatThrownBy(() -> scope.companyIds().add(UUID.randomUUID()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("rejects a null set and a null element")
        void scope_RejectsNulls() {
            assertThatThrownBy(() -> new EmployeeStoreScope(
                            null,
                            new LinkedHashSet<>(),
                            new LinkedHashSet<>(),
                            new LinkedHashSet<>(),
                            new LinkedHashSet<>()))
                    .isInstanceOf(NullPointerException.class);

            var withNull = new LinkedHashSet<UUID>();
            withNull.add(null);
            assertThatThrownBy(() -> new EmployeeStoreScope(
                            withNull,
                            new LinkedHashSet<>(),
                            new LinkedHashSet<>(),
                            new LinkedHashSet<>(),
                            new LinkedHashSet<>()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentTests {

        @Test
        @DisplayName("rejects a null company, null assignments and null today")
        void derive_RejectsNullArguments() {
            var company = company();
            assertThatThrownBy(() -> StoreScopeDerivation.derive(null, List.of(), TODAY))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> StoreScopeDerivation.derive(company.getId(), null, TODAY))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> StoreScopeDerivation.derive(company.getId(), List.of(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("tolerates a null element in the collection instead of failing")
        void derive_ToleratesNullElement() {
            var company = company();
            var assignments = new ArrayList<EmployeeStoreAssignment>();
            assignments.add(null);

            var scope = StoreScopeDerivation.derive(company.getId(), assignments, TODAY);

            assertThat(scope.companyIds()).containsExactly(company.getId());
        }
    }

    private EmployeeStoreScope derive(EmployeeStoreAssignment assignment) {
        var company = assignment
                .getCompanyStore()
                .getCompanyZone()
                .getCompanyRegion()
                .getCompanyCountry()
                .getCompany();
        return StoreScopeDerivation.derive(company.getId(), List.of(assignment), TODAY);
    }

    private Company company() {
        return Company.builder()
                .id(UUID.randomUUID())
                .companyName("Derivation Company")
                .enabled(true)
                .build();
    }

    private CompanyCountry companyCountry(Company company) {
        var country = Country.builder()
                .id(UUID.randomUUID())
                .countryCode("MX")
                .countryName("México")
                .enabled(true)
                .build();
        return CompanyCountry.builder()
                .id(UUID.randomUUID())
                .company(company)
                .country(country)
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

    private CompanyStore store() {
        return storeIn(company(), true);
    }

    /** A whole chain under a fresh company, with the store's enabled flag set as asked. */
    private CompanyStore storeIn(Company company, boolean storeEnabled) {
        return store(zone(region(companyCountry(company))), storeEnabled);
    }

    private CompanyStore store(CompanyZone zone, boolean enabled) {
        return CompanyStore.builder()
                .id(UUID.randomUUID())
                .companyZone(zone)
                .storeName("Store")
                .enabled(enabled)
                .build();
    }

    private EmployeeStoreAssignment assignment(
            CompanyStore store, LocalDate validFrom, LocalDate validTo, boolean enabled) {
        return EmployeeStoreAssignment.builder()
                .id(UUID.randomUUID())
                .companyStore(store)
                .validFrom(validFrom)
                .validTo(validTo)
                .enabled(enabled)
                .build();
    }
}
