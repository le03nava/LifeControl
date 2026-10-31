package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.hr.dto.CloseScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentRequest;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP-level verification of the per-level scope-assignment read, create and close routes against real
 * PostgreSQL, with the schema applied by Flyway and {@code ddl-auto=validate}.
 *
 * <p>This is the suite that drives the <b>store level end to end over HTTP</b> (S2), and it exists
 * because the store route it replaces is gone: since {@code V33} and the retirement of the
 * {@code /store-assignments} surface, {@code /scope-assignments} is the only route the application
 * calls, so nothing else exercises a store assignment from the wire down to the
 * {@code employee_scope_assignments} row and back out through the response the screen renders (S5).
 * The request names one node column ({@code companyStoreId}) and no level — the level is the row's
 * shape — and the read reports {@code level}, {@code companyStoreId}, {@code nodeName} and the derived
 * ancestor chain.</p>
 *
 * <p>The requests carry a real {@code jwt()} whose claims {@code CurrentUserContext} reads from the
 * security context — the admin authority short-circuits {@code verifyCompanyAccess}, and the
 * non-admin cases carry (or deliberately omit) the {@code company_id} claim the scope check requires.
 * What this suite proves is the wire contract plus the persistence semantics of the create path: the
 * 201 with its level, node name and derived display chain, the {@code includeDisabled} filter, the
 * same-company refusal of a store as a 404 that writes nothing, the 404s for a foreign employee and a
 * foreign company, the 403 for a caller with no scope chain, the positive scope case (an
 * {@code lc-employee} caller carrying the {@code company_id} claim is admitted and served), the 400
 * for a missing required field, and the overlap the service pre-check cannot see ending as the generic
 * 409. The list also proves the history's newest-first {@code validFrom} order, and asserts the
 * id-descending tie-breaker for rows sharing a {@code validFrom} (its regression sensitivity is
 * probabilistic, since the id is a random UUID). The close path is covered too: the default-to-today
 * close, the explicit end date, the inverted-date 400, the already-closed 409 and the
 * foreign-assignment 404, all of them measured against the service as it is rather than asserted from
 * the brief.</p>
 *
 * <p>Cleanup is scoped: the {@code employee_scope_assignments} rows of the two companies this suite
 * creates, and then those employees. The store tree and the {@code companies} rows are find-or-create
 * fixtures and are never deleted, so the shared container's rows survive.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Employee Scope Assignment HTTP Integration Tests")
class EmployeeScopeAssignmentHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "EMP-SCOPE-ASSIGN-HTTP-KEY";
    private static final String COMPANY_RFC = "ESAH010101AB";
    private static final String COMPANY_NAME = "Scope Assignment HTTP Test Company";
    private static final String OTHER_COMPANY_KEY = "EMP-SCOPE-ASSIGN-HTTP-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "ESAH020202CD";
    private static final String OTHER_COMPANY_NAME = "Scope Assignment HTTP Other Company";
    private static final String REGION_CODE = "ESAH";
    private static final String REGION_NAME = "Scope Assignment HTTP Region";
    private static final String ZONE_CODE = "ESAH";
    private static final String ZONE_NAME = "Scope Assignment HTTP Zone";
    private static final String STORE_NAME = "Scope Assignment HTTP Store";
    private static final String SECOND_STORE_NAME = "Scope Assignment HTTP Second Store";
    private static final String FOREIGN_STORE_NAME = "Scope Assignment HTTP Foreign Store";

    private static final String BASE_URL = "/api/companies/{companyId}/employees/{employeeId}/scope-assignments";
    private static final String CLOSE_URL = BASE_URL + "/{id}/close";
    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");
    private static final SimpleGrantedAuthority ROLE_LC_EMPLOYEE = new SimpleGrantedAuthority("ROLE_lc-employee");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private UUID companyId;
    private UUID otherCompanyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;
    private UUID secondStoreId;
    private UUID foreignStoreId;
    private UUID employeeId;
    private UUID foreignEmployeeId;
    private String countryName;

    @BeforeEach
    void setUp() {
        cleanOwnedRows();
        seedCompanyTrees();
        var activeStatusId = activeEmployeeStatusId();
        employeeId = insertEmployee(companyId, activeStatusId, "EMP-SCOPE-HTTP-1", "scope.http@example.com");
        foreignEmployeeId =
                insertEmployee(otherCompanyId, activeStatusId, "EMP-SCOPE-HTTP-2", "scope.http.other@example.com");
    }

    @AfterEach
    void tearDown() {
        cleanOwnedRows();
    }

    /**
     * Deletes the rows this feature owns, leaf-first, and only the employees this suite creates.
     *
     * <p>The suite writes {@code employee_scope_assignments} at the store level and reads it back, so
     * the cleanup targets that table — scoped to the two companies' employees, never a blanket
     * delete.</p>
     */
    private void cleanOwnedRows() {
        jdbcTemplate.update("""
                DELETE FROM employee_scope_assignments a
                USING employees e
                WHERE a.employee_id = e.id AND e.company_id IN
                    (SELECT id FROM companies WHERE company_key IN (?, ?))
                """, COMPANY_KEY, OTHER_COMPANY_KEY);
        jdbcTemplate.update("""
                DELETE FROM employees WHERE company_id IN
                    (SELECT id FROM companies WHERE company_key IN (?, ?))
                """, COMPANY_KEY, OTHER_COMPANY_KEY);
    }

    private void seedCompanyTrees() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();
        countryName = country.getCountryName();

        var company = findOrCreateCompany(COMPANY_KEY, COMPANY_RFC, COMPANY_NAME);
        companyId = company.getId();
        var seeded = companyZone(company, REGION_CODE, REGION_NAME, ZONE_CODE, ZONE_NAME);
        companyCountryId = seeded.companyCountryId();
        regionId = seeded.regionId();
        zoneId = seeded.zone().getId();
        storeId = findOrCreateStore(seeded.zone(), STORE_NAME);
        secondStoreId = findOrCreateStore(seeded.zone(), SECOND_STORE_NAME);

        var otherCompany = findOrCreateCompany(OTHER_COMPANY_KEY, OTHER_COMPANY_RFC, OTHER_COMPANY_NAME);
        otherCompanyId = otherCompany.getId();
        var otherSeeded = companyZone(otherCompany, REGION_CODE, REGION_NAME, ZONE_CODE, ZONE_NAME);
        foreignStoreId = findOrCreateStore(otherSeeded.zone(), FOREIGN_STORE_NAME);
    }

    /**
     * The find-or-create tree, with the ancestor ids captured while the entities are still in hand.
     *
     * <p>The ids cannot be read by walking {@code zone.getCompanyRegion().getCompanyCountry()} after
     * the repository call returns: those associations are lazy and the entity is detached once the
     * repository's own transaction closes, so the walk throws {@code LazyInitializationException}.
     * The locals below are the same objects the builder received, so their ids are always readable.</p>
     */
    private record SeededZone(CompanyZone zone, UUID regionId, UUID companyCountryId) {}

    private Company findOrCreateCompany(String key, String rfc, String name) {
        return TenantFixtures.findOrCreate(companyRepository, key, name, rfc);
    }

    private SeededZone companyZone(
            Company company, String regionCode, String regionName, String zoneCode, String zoneName) {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();
        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));
        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .filter(candidate -> regionCode.equals(candidate.getRegionCode()))
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode(regionCode)
                        .regionName(regionName)
                        .enabled(true)
                        .build()));
        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .filter(candidate -> zoneCode.equals(candidate.getZoneCode()))
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode(zoneCode)
                        .zoneName(zoneName)
                        .enabled(true)
                        .build()));
        return new SeededZone(zone, region.getId(), companyCountry.getId());
    }

    private UUID findOrCreateStore(CompanyZone zone, String storeName) {
        return companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .filter(candidate -> storeName.equals(candidate.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName(storeName)
                        .enabled(true)
                        .build()))
                .getId();
    }

    private UUID activeEmployeeStatusId() {
        return jdbcTemplate.queryForObject("""
                SELECT s.id FROM statuses s
                JOIN status_types st ON st.id = s.status_type_id
                WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
                  AND LOWER(s.status_name) = LOWER('Active')
                """, UUID.class);
    }

    private UUID insertEmployee(UUID company, UUID statusId, String number, String email) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, ?, 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                """, id, company, number, email, statusId);
        return id;
    }

    private RequestPostProcessor admin() {
        return jwt().authorities(ROLE_LC_ADMIN);
    }

    /** A store-level body: the store is the only node column set, and the level is never sent. */
    private ScopeAssignmentRequest storeRequest(UUID store, LocalDate validFrom) {
        return new ScopeAssignmentRequest(null, null, null, store, validFrom);
    }

    private ResultActions postAssignment(UUID employee, Object body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(BASE_URL, companyId, employee)
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions postRaw(UUID employee, String body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(BASE_URL, companyId, employee)
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions getAssignments(String query, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(get(BASE_URL + query, companyId, employeeId).with(auth));
    }

    /** A close with no body at all: {@code @RequestBody(required = false)} must default the date. */
    private ResultActions closeAssignment(UUID employee, UUID assignment, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(patch(CLOSE_URL, companyId, employee, assignment).with(auth));
    }

    private ResultActions closeAssignment(UUID employee, UUID assignment, Object body, RequestPostProcessor auth)
            throws Exception {
        return mockMvc.perform(patch(CLOSE_URL, companyId, employee, assignment)
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** The id of the row a create call just returned, read from its 201 body. */
    private UUID createdAssignmentId(ResultActions result) throws Exception {
        var body = result.andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    /** The employee's STORE rows: the scope table holds every level, this suite writes stores only. */
    private int countStoreRows(UUID employee) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employee_scope_assignments WHERE employee_id = ? AND company_store_id IS NOT NULL",
                Integer.class,
                employee);
    }

    private void disableAssignmentAt(LocalDate validFrom) {
        jdbcTemplate.update("""
                UPDATE employee_scope_assignments SET enabled = false
                WHERE employee_id = ? AND valid_from = ? AND company_store_id IS NOT NULL
                """, employeeId, validFrom);
    }

    private LocalDate storedValidToAt(LocalDate validFrom) {
        return jdbcTemplate.queryForObject(
                """
                SELECT valid_to FROM employee_scope_assignments
                WHERE employee_id = ? AND valid_from = ? AND company_store_id IS NOT NULL
                """, (rs, rowNum) -> rs.getObject("valid_to", LocalDate.class), employeeId, validFrom);
    }

    @Nested
    @DisplayName("create over HTTP")
    class CreateTests {

        @Test
        @DisplayName("returns 201 with the store level, its node name and its derived chain")
        void createReturns201WithTheStoreLevel() throws Exception {
            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.level").value("STORE"))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.nodeName").value(STORE_NAME))
                    .andExpect(jsonPath("$.validFrom").value("2026-01-01"))
                    .andExpect(jsonPath("$.derived.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.derived.companyName").value(COMPANY_NAME))
                    .andExpect(jsonPath("$.derived.companyCountryId").value(companyCountryId.toString()))
                    .andExpect(jsonPath("$.derived.companyCountryName").value(countryName))
                    .andExpect(jsonPath("$.derived.companyRegionId").value(regionId.toString()))
                    .andExpect(jsonPath("$.derived.companyRegionName").value(REGION_NAME))
                    .andExpect(jsonPath("$.derived.companyZoneId").value(zoneId.toString()))
                    .andExpect(jsonPath("$.derived.companyZoneName").value(ZONE_NAME));

            assertThat(countStoreRows(employeeId)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("list over HTTP")
    class ListTests {

        @Test
        @DisplayName("returns 200 and lists the created assignment with its level, node name and chain")
        void listReturnsTheCreatedAssignment() throws Exception {
            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isCreated());

            getAssignments("", admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].level").value("STORE"))
                    .andExpect(jsonPath("$[0].companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$[0].nodeName").value(STORE_NAME))
                    .andExpect(jsonPath("$[0].validFrom").value("2026-01-01"))
                    .andExpect(jsonPath("$[0].derived.companyZoneId").value(zoneId.toString()));
        }

        @Test
        @DisplayName("returns the history newest first by validFrom, regardless of creation order")
        void listIsNewestFirstRegardlessOfCreationOrder() throws Exception {
            // The earlier-dated row is created first and the later-dated one second, on two
            // different stores so the create path closes no predecessor. Insertion order is
            // therefore [2026-01-01, 2026-06-01] while the expected order is its reverse,
            // [2026-06-01, 2026-01-01]: a query that lost its ORDER BY and fell back to that
            // insertion (heap) order fails this test.
            postAssignment(employeeId, storeRequest(secondStoreId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isCreated());
            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 6, 1)), admin())
                    .andExpect(status().isCreated());

            getAssignments("", admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].validFrom").value("2026-06-01"))
                    .andExpect(jsonPath("$[0].companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$[1].validFrom").value("2026-01-01"))
                    .andExpect(jsonPath("$[1].companyStoreId").value(secondStoreId.toString()));
        }

        @Test
        @DisplayName("rows sharing a validFrom come back by id descending")
        void sameValidFromRowsComeBackByIdDescending() throws Exception {
            // Two rows on the same validFrom, on different stores. The tie-breaker is the id, which
            // is a random UUID, so the expected order is computed from the ids captured out of the
            // POST 201 bodies, never from the order the GET returns (nor from insertion order).
            // PostgreSQL compares uuid with memcmp over the 16 bytes; the canonical lowercase string
            // is fixed-width hex, so lexicographic order of that string equals the byte order (and is
            // NOT Java's signed UUID.compareTo).
            var firstId = createdAssignmentId(
                    postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                            .andExpect(status().isCreated()));
            var secondId = createdAssignmentId(
                    postAssignment(employeeId, storeRequest(secondStoreId, LocalDate.of(2026, 1, 1)), admin())
                            .andExpect(status().isCreated()));

            var expectedNewestFirst = Stream.of(firstId, secondId)
                    .sorted(Comparator.comparing(UUID::toString).reversed())
                    .toList();

            var body = getAssignments("", admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var returned = objectMapper.readTree(body);

            assertThat(returned.get(0).get("id").asText())
                    .as("the id-descending row leads")
                    .isEqualTo(expectedNewestFirst.get(0).toString());
            assertThat(returned.get(1).get("id").asText())
                    .as("the id-ascending row trails")
                    .isEqualTo(expectedNewestFirst.get(1).toString());
        }

        @Test
        @DisplayName("includeDisabled defaults to false and hides a disabled row, true shows it")
        void includeDisabledFiltersTheList() throws Exception {
            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isCreated());
            disableAssignmentAt(LocalDate.of(2026, 1, 1));

            getAssignments("", admin()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

            getAssignments("?includeDisabled=true", admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].enabled").value(false));
        }
    }

    @Nested
    @DisplayName("same-company rule and scope")
    class ScopeTests {

        @Test
        @DisplayName("a store of another company is a 404 and writes nothing")
        void foreignStoreIsA404() throws Exception {
            postAssignment(employeeId, storeRequest(foreignStoreId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isNotFound());

            assertThat(countStoreRows(employeeId)).isZero();
        }

        @Test
        @DisplayName("an employee of another company is a 404 and writes nothing")
        void foreignEmployeeIsA404() throws Exception {
            postAssignment(foreignEmployeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isNotFound());

            assertThat(countStoreRows(foreignEmployeeId)).isZero();
        }

        @Test
        @DisplayName("a company that does not exist is a 404")
        void unknownCompanyIsA404() throws Exception {
            var unknownCompanyId = UUID.randomUUID();

            mockMvc.perform(post(BASE_URL, unknownCompanyId, employeeId)
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(storeRequest(storeId, LocalDate.of(2026, 1, 1)))))
                    .andExpect(status().isNotFound());

            assertThat(countStoreRows(employeeId)).isZero();
        }

        @Test
        @DisplayName("the right role with no claim for the company is a 403")
        void companyOutsideTheTokenClaimIsA403() throws Exception {
            var otherCompanyClaim = jwt().authorities(ROLE_LC_EMPLOYEE)
                    .jwt(builder ->
                            builder.claim("company_id", UUID.randomUUID().toString()));

            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), otherCompanyClaim)
                    .andExpect(status().isForbidden());

            assertThat(countStoreRows(employeeId)).isZero();
        }

        @Test
        @DisplayName("the right role with no scope claims at all is a 403")
        void missingScopeClaimsIsA403() throws Exception {
            var noClaims = jwt().authorities(ROLE_LC_EMPLOYEE);

            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), noClaims)
                    .andExpect(status().isForbidden());

            assertThat(countStoreRows(employeeId)).isZero();
        }

        @Test
        @DisplayName("an lc-employee caller carrying the company claim is admitted and served")
        void employeeWithTheCompanyClaimIsAdmitted() throws Exception {
            // Genuinely non-admin: only ROLE_lc-employee, never ROLE_lc-admin. The company level
            // requires the company_id claim (ScopeLevel.COMPANY.isRequired()), so a caller with that
            // claim passes verifyCompanyAccess and is served instead of denied.
            var scopedEmployee = jwt().authorities(ROLE_LC_EMPLOYEE)
                    .jwt(builder -> builder.claim("company_id", companyId.toString()));

            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), scopedEmployee)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.level").value("STORE"))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()));

            getAssignments("", scopedEmployee)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].companyStoreId").value(storeId.toString()));
        }
    }

    @Nested
    @DisplayName("rejections")
    class RejectionTests {

        @Test
        @DisplayName("a body missing companyStoreId or validFrom is a 400")
        void missingRequiredFieldsAreA400() throws Exception {
            postRaw(employeeId, "{}", admin()).andExpect(status().isBadRequest());

            postRaw(employeeId, "{\"validFrom\":\"2026-01-01\"}", admin()).andExpect(status().isBadRequest());

            postRaw(employeeId, "{\"companyStoreId\":\"" + storeId + "\"}", admin())
                    .andExpect(status().isBadRequest());

            assertThat(countStoreRows(employeeId)).isZero();
        }

        @Test
        @DisplayName("the overlap the pre-check cannot see ends as the generic 409")
        void unseenOverlapIsAGeneric409() throws Exception {
            // A covers the new start date (so the pre-check closes it) and C starts after A ends.
            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isCreated());
            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2027, 1, 1)), admin())
                    .andExpect(status().isCreated());
            assertThat(countStoreRows(employeeId)).isEqualTo(2);

            // Open-ended from 2026-06-01: the pre-check sees only A, closes it at 2026-06-01, and the
            // insert then overlaps C. The partial exclusion constraint refuses it; the handler maps
            // DataIntegrityViolationException to the generic 409, not a 500. Only the status is
            // asserted here: the HTTP layer cannot see the constraint name.
            postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 6, 1)), admin())
                    .andExpect(status().isConflict());

            assertThat(countStoreRows(employeeId))
                    .as("the failed write leaves the history untouched")
                    .isEqualTo(2);
            assertThat(storedValidToAt(LocalDate.of(2026, 1, 1)))
                    .as("the predecessor close rolled back with the refused insert")
                    .isEqualTo(LocalDate.of(2027, 1, 1));
        }
    }

    @Nested
    @DisplayName("close over HTTP")
    class CloseTests {

        @Test
        @DisplayName("closing with no body returns 200 and reports today as the last covered day")
        void closeWithoutBodyDefaultsToToday() throws Exception {
            var id = createdAssignmentId(
                    postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                            .andExpect(status().isCreated()));
            var today = LocalDate.now();

            closeAssignment(employeeId, id, admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.level").value("STORE"))
                    .andExpect(jsonPath("$.validTo").value(today.toString()));

            assertThat(storedValidToAt(LocalDate.of(2026, 1, 1)))
                    .as("the column stores the exclusive day after the inclusive API date")
                    .isEqualTo(today.plusDays(1));
        }

        @Test
        @DisplayName("closing with an explicit endDate returns 200 and reports exactly that date")
        void closeWithExplicitEndDate() throws Exception {
            var id = createdAssignmentId(
                    postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                            .andExpect(status().isCreated()));

            closeAssignment(employeeId, id, new CloseScopeAssignmentRequest(LocalDate.of(2026, 3, 15)), admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.validFrom").value("2026-01-01"))
                    .andExpect(jsonPath("$.validTo").value("2026-03-15"));

            assertThat(storedValidToAt(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2026, 3, 16));
        }

        @Test
        @DisplayName("an endDate equal to validFrom is accepted, a single covered day (measured)")
        void closeWithEndDateEqualToValidFromIsAccepted() throws Exception {
            var id = createdAssignmentId(
                    postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 2, 1)), admin())
                            .andExpect(status().isCreated()));

            closeAssignment(employeeId, id, new CloseScopeAssignmentRequest(LocalDate.of(2026, 2, 1)), admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.validFrom").value("2026-02-01"))
                    .andExpect(jsonPath("$.validTo").value("2026-02-01"));

            assertThat(storedValidToAt(LocalDate.of(2026, 2, 1)))
                    .as("the '[)' range stores validFrom + 1, which is a legal one-day range")
                    .isEqualTo(LocalDate.of(2026, 2, 2));
        }

        @Test
        @DisplayName("an endDate before validFrom is a 400 and leaves the row open")
        void closeWithEndDateBeforeValidFromIsA400() throws Exception {
            var id = createdAssignmentId(
                    postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 5, 1)), admin())
                            .andExpect(status().isCreated()));

            closeAssignment(employeeId, id, new CloseScopeAssignmentRequest(LocalDate.of(2026, 4, 30)), admin())
                    .andExpect(status().isBadRequest());

            assertThat(storedValidToAt(LocalDate.of(2026, 5, 1)))
                    .as("the refused close leaves valid_to null")
                    .isNull();
        }

        @Test
        @DisplayName("closing an already-closed assignment is a 409")
        void closeAlreadyClosedIsA409() throws Exception {
            var id = createdAssignmentId(
                    postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                            .andExpect(status().isCreated()));
            closeAssignment(employeeId, id, new CloseScopeAssignmentRequest(LocalDate.of(2026, 1, 31)), admin())
                    .andExpect(status().isOk());

            closeAssignment(employeeId, id, new CloseScopeAssignmentRequest(LocalDate.of(2026, 2, 28)), admin())
                    .andExpect(status().isConflict());

            assertThat(storedValidToAt(LocalDate.of(2026, 1, 1)))
                    .as("the second close changes nothing")
                    .isEqualTo(LocalDate.of(2026, 2, 1));
        }

        @Test
        @DisplayName("an unknown assignment id is a 404")
        void closeUnknownAssignmentIsA404() throws Exception {
            closeAssignment(
                            employeeId,
                            UUID.randomUUID(),
                            new CloseScopeAssignmentRequest(LocalDate.of(2026, 1, 31)),
                            admin())
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an assignment of another company is a 404, never a 200")
        void closeForeignAssignmentIsA404() throws Exception {
            var foreignId = createdAssignmentId(mockMvc.perform(post(BASE_URL, otherCompanyId, foreignEmployeeId)
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    storeRequest(foreignStoreId, LocalDate.of(2026, 1, 1)))))
                    .andExpect(status().isCreated()));

            closeAssignment(employeeId, foreignId, new CloseScopeAssignmentRequest(LocalDate.of(2026, 1, 31)), admin())
                    .andExpect(status().isNotFound());

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM employee_scope_assignments WHERE id = ? AND valid_to IS NULL",
                            Integer.class,
                            foreignId))
                    .as("the foreign assignment stays open")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("close flips no flag: the closed row stays in the default list (measured)")
        void closeDoesNotHideTheRowFromTheDefaultList() throws Exception {
            var id = createdAssignmentId(
                    postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                            .andExpect(status().isCreated()));
            closeAssignment(employeeId, id, new CloseScopeAssignmentRequest(LocalDate.of(2026, 1, 31)), admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            getAssignments("", admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(id.toString()))
                    .andExpect(jsonPath("$[0].enabled").value(true));

            getAssignments("?includeDisabled=true", admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(id.toString()));
        }

        @Test
        @DisplayName("a closed row still carries its level and derived display chain (measured)")
        void closedRowStillCarriesItsDerivedChain() throws Exception {
            var id = createdAssignmentId(
                    postAssignment(employeeId, storeRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                            .andExpect(status().isCreated()));
            closeAssignment(employeeId, id, new CloseScopeAssignmentRequest(LocalDate.of(2026, 1, 31)), admin())
                    .andExpect(status().isOk());

            getAssignments("?includeDisabled=true", admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].level").value("STORE"))
                    .andExpect(jsonPath("$[0].companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$[0].validTo").value("2026-01-31"))
                    .andExpect(jsonPath("$[0].derived.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$[0].derived.companyZoneId").value(zoneId.toString()));
        }
    }
}
