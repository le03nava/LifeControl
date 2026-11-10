package com.lifecontrol.api.profile;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code GET}/{@code PUT /api/profile} served to a caller whose token claims the company that owns the
 * stored store preference: the real request path, the real token, real PostgreSQL and the armed policies.
 *
 * <p><b>Why this suite exists (record L4 of {@code odd/tasks/profile-tenant-scope.md}).</b>
 * {@code TenantScopeBoundaryIntegrationTest} pins the boundary hook with the scope <b>already stated</b>
 * ({@code TenantScope.with(...)}), so the machinery was verified and the request path was not.
 * {@code /api/profile} is a tenant-agnostic path in {@code TenantScopeFilter}, so the scope it carries is
 * {@code (company = null, subject = sub)}; {@code employees} and {@code company_stores} are armed with
 * {@code ENABLE} + {@code FORCE} row level security on {@code company_id = current_setting('app.company_id')},
 * so every read of them on that path returned zero rows: {@code location} degraded to {@code null} and
 * {@code assignedStores} was {@code null} for every caller — which silently disabled
 * {@code ProfileService.verifyStoreIsAssigned}.</p>
 *
 * <p><b>Not {@code @Transactional}, on purpose.</b> The fix reads each claimed company inside its own
 * transaction ({@code ProfileTenantReads}) with the caller's transaction suspended, so a suite whose rows
 * lived in one uncommitted test transaction would prove nothing: the scoped transaction is a different
 * one and cannot see them. Every fixture here states its tenant the way production does
 * ({@link TenantFixtures#inTenant(UUID, java.util.function.Supplier)}), which commits, and {@code @AfterEach}
 * removes the rows this suite owns so the shared container stays as it was found. The company hierarchy is
 * seeded find-or-create and deliberately left behind, the way {@code StoreInventorySettingsIntegrationTest}
 * leaves its own.</p>
 *
 * <p><b>PostgreSQL and not H2</b>, because the whole defect is a policy: a policy does not exist on H2,
 * and a mock would have to fake the one fact under test — that a read of {@code company_stores} without a
 * stated company returns no row.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Profile tenant scope — HTTP acceptance")
class ProfileTenantScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The {@code sub} of every token this suite issues, and the subject of the seeded preference row. */
    private static final String SUBJECT = "kc-subject-profile-scope";

    /**
     * A second person who exists only for the zone-level case: same company, different assignments.
     *
     * <p>It has to be a different subject because assignments are the employee's, and the case is about a
     * caller whose only assignment is not a store.</p>
     */
    private static final String ZONE_ONLY_SUBJECT = "kc-subject-profile-zone-only";

    private static final String OWNER_COMPANY_KEY = "PROFILE-TENANT-SCOPE-OWNER";
    private static final String FOREIGN_COMPANY_KEY = "PROFILE-TENANT-SCOPE-FOREIGN";
    private static final String STORE_A_NAME = "Profile Scope Store A";
    private static final String STORE_B_NAME = "Profile Scope Store B";

    /** The employee row and its store assignment: one node column, which the constraint makes exclusive. */
    private static final String INSERT_EMPLOYEE = """
            INSERT INTO employees (id, company_id, employee_number, first_name, paternal_last_name, email,
                                   birth_date, hire_date, employment_status_id, keycloak_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_STORE_ASSIGNMENT = """
            INSERT INTO employee_scope_assignments (id, employee_id, company_store_id, valid_from, valid_to, enabled)
            VALUES (?, ?, ?, ?, NULL, true)
            """;

    private static final String INSERT_PREFERENCE =
            "INSERT INTO user_preferences (id, keycloak_user_id, company_store_id) VALUES (?, ?, ?)";

    /** The status row an employee requires; the catalog is not tenant-armed, so no scope is needed. */
    private static final String SELECT_EMPLOYMENT_STATUS = """
            SELECT s.id FROM statuses s
            JOIN status_types t ON t.id = s.status_type_id
            WHERE t.status_type_name = 'EMPLOYEE_STATUS' AND s.status_name = 'Active'
            """;

    /** The same assignment table with its <b>zone</b> node set, which is what S4's empty set is made of. */
    private static final String INSERT_ZONE_ASSIGNMENT = """
            INSERT INTO employee_scope_assignments (id, employee_id, company_zone_id, valid_from, valid_to, enabled)
            VALUES (?, ?, ?, ?, NULL, true)
            """;

    /**
     * A company inserted with an id the case chose, for the case whose premise is the <b>sorted order</b>
     * of the claimed ids. {@code companies}' policy is {@code id = current_setting('app.company_id')}, so
     * the insert only lands inside the tenant boundary that states that same id.
     */
    private static final String INSERT_COMPANY = """
            INSERT INTO companies (id, company_key, company_name, rfc, currency)
            VALUES (?, ?, ?, ?, 'MXN')
            ON CONFLICT (id) DO NOTHING
            """;

    /** V52's own function: {@code set_config('app.user_id', …, true)}, i.e. {@code SET LOCAL}. */
    private static final String SET_USER_SCOPE = "select set_user_scope(cast(? as text))";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private CompanyCountryRepository companyCountryRepository;

    @Autowired
    private CompanyRegionRepository companyRegionRepository;

    @Autowired
    private CompanyZoneRepository companyZoneRepository;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    /** The company that owns the employee row, the stores and the stored store preference. */
    private UUID ownerCompanyId;

    /** A company of the same database the tokens below may or may not claim. */
    private UUID foreignCompanyId;

    private UUID zoneId;
    private UUID storeAId;
    private UUID storeBId;
    private UUID employeeId;

    /**
     * Seed the two companies, the store hierarchy, and the subject's own rows.
     *
     * <p>The companies and the hierarchy are find-or-create, because the shared container outlives every
     * method and {@code companies}' {@code company_key} is unique; the employee, its assignment and the
     * preference row are this suite's own and are removed in {@link #removeOwnRows()}.</p>
     */
    @BeforeEach
    void seedTheTenantAndTheSubjectsOwnRows() {
        ownerCompanyId = TenantFixtures.findOrCreate(
                        companyRepository, OWNER_COMPANY_KEY, "Profile Tenant Scope Owner", "PTSC010101AB")
                .getId();
        foreignCompanyId = TenantFixtures.findOrCreate(
                        companyRepository, FOREIGN_COMPANY_KEY, "Profile Tenant Scope Foreign", "PTSF010101AB")
                .getId();

        seedStoreHierarchy();
        seedEmployeeAssignmentAndPreference();
    }

    @AfterEach
    void removeOwnRows() {
        if (employeeId == null) {
            return;
        }
        // One boundary per cleanup, and the subject scope stated inside it: `user_preferences` is isolated
        // by `keycloak_user_id = app.user_id`, and the employee rows by the company, so both facts are
        // stated here and neither table is touched without its own predicate.
        TenantFixtures.inTenant(ownerCompanyId, () -> {
            jdbcTemplate.query(SET_USER_SCOPE, rs -> {}, SUBJECT);
            jdbcTemplate.update("DELETE FROM user_preferences WHERE keycloak_user_id = ?", SUBJECT);
            jdbcTemplate.update("DELETE FROM employee_scope_assignments WHERE employee_id = ?", employeeId);
            jdbcTemplate.update("DELETE FROM employees WHERE id = ?", employeeId);
        });
        employeeId = null;
    }

    // ── S1: the stored chain comes back ─────────────────────────────────────

    @Test
    @DisplayName("S1: a caller claiming the owning company gets the stored chain back")
    void readsTheStoredChainForTheClaimedCompany() throws Exception {
        mockMvc.perform(get("/api/profile").with(token(ownerCompanyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.location.companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.location.companyId").value(ownerCompanyId.toString()))
                .andExpect(jsonPath("$.location.companyZoneId").value(zoneId.toString()))
                .andExpect(jsonPath("$.location.companyStoreName").value(STORE_A_NAME));
    }

    // ── S2: the assignment set is reported, so the screen is in constrained mode ──

    @Test
    @DisplayName("S2: a caller with an employee row reports the assigned store instead of null")
    void reportsTheAssignedStores() throws Exception {
        mockMvc.perform(get("/api/profile").with(token(ownerCompanyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedStores.length()").value(1))
                .andExpect(jsonPath("$.assignedStores[0].companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.assignedStores[0].companyStoreName").value(STORE_A_NAME))
                .andExpect(jsonPath("$.assignedStores[0].companyId").value(ownerCompanyId.toString()));
    }

    // ── S3: fail-closed is preserved ────────────────────────────────────────

    @Test
    @DisplayName("S3: a token claiming no company reads the profile with no location and no assignments")
    void readsNothingInventedWithoutACompanyClaim() throws Exception {
        // The guard on the whole design: the reads are derived from the claims, so a caller with no claim
        // has nothing to derive them from. The answer is a 200 with two nulls — never a 403, never a 500,
        // and never a store invented from the stored column.
        mockMvc.perform(get("/api/profile").with(tokenClaimingNothing()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value(nullValue()))
                .andExpect(jsonPath("$.assignedStores").value(nullValue()));
    }

    // ── S4a: no widening — location resolves only through a claimed company ─

    @Test
    @DisplayName("S4a: a token claiming only another company resolves no location")
    void resolvesNoLocationForAClaimedCompanyThatOwnsNothing() throws Exception {
        // The stores belong to a company this token does not claim, so the read must answer what the
        // policy answers — nothing — instead of widening the scope to the stored column's owner.
        mockMvc.perform(get("/api/profile").with(token(foreignCompanyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value(nullValue()))
                .andExpect(jsonPath("$.assignedStores").value(nullValue()));
    }

    // ── S4b: the guard runs again ───────────────────────────────────────────

    @Test
    @DisplayName("S4b: PUT refuses a store of the same company the employee is not assigned to")
    void refusesAStoreOutsideTheAssignments() throws Exception {
        // Store B belongs to the same company as the employee's company, so the 404 can only come from
        // `verifyStoreIsAssigned` — the guard that silently disabled itself while `assignedStores` was
        // null for every caller.
        mockMvc.perform(put("/api/profile")
                        .with(token(ownerCompanyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyStoreId\":\"" + storeBId + "\"}"))
                .andExpect(status().isNotFound());
    }

    // ── S4: a present-but-empty assignment set over the real request path ───

    @Test
    @DisplayName("S4: a caller assigned at zone level only is constrained to no stores, not unconstrained")
    void refusesEveryStoreWhenTheOnlyAssignmentsAreNotStores() throws Exception {
        // The unit suite pins "present but empty means constrained to exactly none" through a transparent
        // stub; this is the same rule over the request path, the armed policies and a real employee row.
        // The zone assignment is a current assignment the service reports as no store, so the answer must be
        // an empty array — the discriminator that keeps `verifyStoreIsAssigned` speaking — and never `null`,
        // which would mean "no employee row, any store accepted".
        var employmentStatusId = jdbcTemplate.queryForObject(SELECT_EMPLOYMENT_STATUS, UUID.class);
        var employee = UUID.randomUUID();
        try {
            // The seed is inside the try so a failure of any statement below still removes whatever landed:
            // this suite's own rows are the only ones it may leave behind, and none of them may outlive it.
            TenantFixtures.inTenant(ownerCompanyId, () -> {
                jdbcTemplate.update(
                        INSERT_EMPLOYEE,
                        employee,
                        ownerCompanyId,
                        "PTS-2",
                        "Profile",
                        "ZoneOnly",
                        "profile.zoneonly@example.com",
                        LocalDate.of(1990, 1, 1),
                        LocalDate.of(2020, 1, 1),
                        employmentStatusId,
                        ZONE_ONLY_SUBJECT);
                jdbcTemplate.update(INSERT_ZONE_ASSIGNMENT, UUID.randomUUID(), employee, zoneId, LocalDate.now());
            });

            var token = tokenForSubject(ZONE_ONLY_SUBJECT, ownerCompanyId);

            mockMvc.perform(get("/api/profile").with(token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assignedStores").isArray())
                    .andExpect(jsonPath("$.assignedStores.length()").value(0))
                    .andExpect(jsonPath("$.location").value(nullValue()));

            // Every store is outside a set the caller does have, so the refusal is the same 404 S4b pins;
            // the difference is which side of the guard produced it.
            mockMvc.perform(put("/api/profile")
                            .with(token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"companyStoreId\":\"" + storeAId + "\"}"))
                    .andExpect(status().isNotFound());
        } finally {
            // The GET above creates this subject's empty preference row when it is absent, so it is this
            // case's row and is removed with the employee it belongs to.
            TenantFixtures.inTenant(ownerCompanyId, () -> {
                jdbcTemplate.query(SET_USER_SCOPE, rs -> {}, ZONE_ONLY_SUBJECT);
                jdbcTemplate.update("DELETE FROM user_preferences WHERE keycloak_user_id = ?", ZONE_ONLY_SUBJECT);
                jdbcTemplate.update("DELETE FROM employee_scope_assignments WHERE employee_id = ?", employee);
                jdbcTemplate.update("DELETE FROM employees WHERE id = ?", employee);
            });
        }
    }

    // ── S5: a multi-company token still resolves ────────────────────────────

    @Test
    @DisplayName("S5: a token claiming both companies still resolves the chain")
    void resolvesTheChainForATwoClaimToken() throws Exception {
        mockMvc.perform(get("/api/profile").with(tokenOf(ownerCompanyId, foreignCompanyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.location.companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.assignedStores.length()").value(1))
                .andExpect(jsonPath("$.assignedStores[0].companyStoreId").value(storeAId.toString()));
    }

    @Test
    @DisplayName("S5: a miss on the first claimed company does not stop the loop")
    void keepsLookingAfterAClaimedCompanyThatOwnsNothing() throws Exception {
        // The case above claims two companies but cannot say which one is tried first, so the
        // continue-after-a-miss branch of `ProfileTenantReads` only runs when the ids happen to sort that
        // way. These two ids are literals chosen for it: 0x…0010 sorts before 0x…0020 under `UUID`'s
        // natural order (same high bits, positive low bits), and the store belongs to the second one. The
        // first attempt therefore misses both reads — no employee row, no store the policy admits — and the
        // chain is found only because the loop keeps going.
        var first = UUID.fromString("00000000-0000-4000-8000-000000000010");
        var second = UUID.fromString("00000000-0000-4000-8000-000000000020");
        seedCompanyWithAStore(first, "PROFILE-SCOPE-ORDER-A", "Profile Scope Order A", "PSOA010101AB");
        var secondStore =
                seedCompanyWithAStore(second, "PROFILE-SCOPE-ORDER-B", "Profile Scope Order B", "PSOB010101AB");

        // Point the subject's stored preference at the store of the company that sorts last. The subject
        // has no employee row under either company, so the preference is returned as stored.
        TenantFixtures.inTenant(second, () -> {
            jdbcTemplate.query(SET_USER_SCOPE, rs -> {}, SUBJECT);
            jdbcTemplate.update("DELETE FROM user_preferences WHERE keycloak_user_id = ?", SUBJECT);
            jdbcTemplate.update(INSERT_PREFERENCE, UUID.randomUUID(), SUBJECT, secondStore);
        });

        mockMvc.perform(get("/api/profile").with(tokenOf(first, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStoreId").value(secondStore.toString()))
                .andExpect(jsonPath("$.location.companyStoreId").value(secondStore.toString()))
                .andExpect(jsonPath("$.location.companyId").value(second.toString()));
    }

    // ──────────────────────────────────────
    // fixtures
    // ──────────────────────────────────────

    /**
     * Country MX &rarr; region &rarr; zone &rarr; two stores of that zone, find-or-create.
     *
     * <p>One {@link TenantFixtures#inTenant} around the children and not one per statement: every child is
     * a row on a policy-armed table and references a detached parent, so both the {@code INSERT} and the
     * flush's unsaved-value probe need that parent's tenant installed.</p>
     */
    private void seedStoreHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        TenantFixtures.inTenant(ownerCompanyId, () -> {
            var company = companyRepository.findById(ownerCompanyId).orElseThrow();
            var companyCountry = companyCountryRepository
                    .findByCompanyIdAndCountryId(company.getId(), country.getId())
                    .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                            .company(company)
                            .country(country)
                            .build()));

            var region =
                    companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                            .findFirst()
                            .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                                    .companyCountry(companyCountry)
                                    .regionCode("PTS")
                                    .regionName("Profile Tenant Scope Region")
                                    .enabled(true)
                                    .build()));

            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("PTSZ")
                            .zoneName("Profile Tenant Scope Zone")
                            .enabled(true)
                            .build()));

            zoneId = zone.getId();
            storeAId = findOrCreateStore(zone, STORE_A_NAME).getId();
            storeBId = findOrCreateStore(zone, STORE_B_NAME).getId();
        });
    }

    private CompanyStore findOrCreateStore(CompanyZone zone, String storeName) {
        return companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .filter(store -> storeName.equals(store.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName(storeName)
                        .enabled(true)
                        .build()));
    }

    /**
     * One company with its own MX &rarr; region &rarr; zone chain and a single store, find-or-create.
     *
     * <p>The id is the caller's, and a literal, because the case that uses this needs the <b>sorted</b>
     * order of the claimed ids to put the miss first ({@code ProfileTenantReads} iterates the claims in
     * sorted order, so the attempt order is the ids' order and not the claim list's). The company insert
     * runs inside the boundary that states its own id, which is what its policy compares against.</p>
     *
     * @return the id of that company's only store
     */
    private UUID seedCompanyWithAStore(UUID companyId, String companyKey, String companyName, String rfc) {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        return TenantFixtures.inTenant(companyId, () -> {
            jdbcTemplate.update(INSERT_COMPANY, companyId, companyKey, companyName, rfc);

            var company = companyRepository.findById(companyId).orElseThrow();
            var companyCountry = companyCountryRepository
                    .findByCompanyIdAndCountryId(company.getId(), country.getId())
                    .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                            .company(company)
                            .country(country)
                            .build()));

            var region =
                    companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                            .findFirst()
                            .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                                    .companyCountry(companyCountry)
                                    .regionCode("PSO")
                                    .regionName(companyName + " Region")
                                    .enabled(true)
                                    .build()));

            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("PSOZ")
                            .zoneName(companyName + " Zone")
                            .enabled(true)
                            .build()));

            return findOrCreateStore(zone, companyName + " Store").getId();
        });
    }

    /**
     * The subject's employee row, its store assignment and its stored store preference, all under the
     * owning company.
     *
     * <p>Raw {@code JdbcTemplate} statements and not repositories, so the three rows carry exactly the
     * columns each of them needs and no entity state beyond that: the employee is found by
     * {@code keycloak_user_id}, the assignment is a single store node covering today, and the preference
     * is the pointer the profile resolves (D10). The preference row is written after
     * {@code set_user_scope(subject)} inside the <b>same</b> transaction, because that table's policy is
     * {@code keycloak_user_id = app.user_id}.</p>
     */
    private void seedEmployeeAssignmentAndPreference() {
        var employmentStatusId = jdbcTemplate.queryForObject(SELECT_EMPLOYMENT_STATUS, UUID.class);

        employeeId = UUID.randomUUID();
        TenantFixtures.inTenant(ownerCompanyId, () -> {
            jdbcTemplate.update(
                    INSERT_EMPLOYEE,
                    employeeId,
                    ownerCompanyId,
                    "PTS-1",
                    "Profile",
                    "Scope",
                    "profile.scope@example.com",
                    LocalDate.of(1990, 1, 1),
                    LocalDate.of(2020, 1, 1),
                    employmentStatusId,
                    SUBJECT);

            jdbcTemplate.update(INSERT_STORE_ASSIGNMENT, UUID.randomUUID(), employeeId, storeAId, LocalDate.now());

            jdbcTemplate.query(SET_USER_SCOPE, rs -> {}, SUBJECT);
            jdbcTemplate.update(INSERT_PREFERENCE, UUID.randomUUID(), SUBJECT, storeAId);
        });
    }

    // ──────────────────────────────────────
    // tokens
    // ──────────────────────────────────────

    /** One claim: the shape the Keycloak mapper emits for a caller that belongs to a single company. */
    private static RequestPostProcessor token(UUID claimedCompany) {
        return tokenForSubject(SUBJECT, claimedCompany);
    }

    /** The same single-claim token for another person, which the zone-level case needs. */
    private static RequestPostProcessor tokenForSubject(String subject, UUID claimedCompany) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-company"))
                .jwt(builder -> builder.subject(subject).claim("company_id", claimedCompany.toString()));
    }

    /** The multi-company caller: the claim is multivalued, and here it carries both seeded companies. */
    private static RequestPostProcessor tokenOf(UUID... claimedCompanies) {
        var claims = Arrays.stream(claimedCompanies).map(UUID::toString).toList();
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-company"))
                .jwt(builder -> builder.subject(SUBJECT).claim("company_id", claims));
    }

    /** A caller with no tenancy claim at all: authenticated, and the fail-closed half of S3. */
    private static RequestPostProcessor tokenClaimingNothing() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-company"))
                .jwt(builder -> builder.subject(SUBJECT));
    }
}
