package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.hr.dto.StoreAssignmentRequest;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-level verification of the store-assignment read and create routes against real PostgreSQL,
 * with the V23 schema applied by Flyway and {@code ddl-auto=validate}.
 *
 * <p>The requests carry a real {@code jwt()} whose claims {@code CurrentUserContext} reads from the
 * security context — the admin authority short-circuits {@code verifyCompanyAccess}, and the
 * non-admin cases carry (or deliberately omit) the {@code company_id} claim the scope check
 * requires. What this suite proves is the wire contract plus the persistence semantics of the create
 * path: the 201 with its derived display chain (T14), the {@code includeDisabled} filter, T8's
 * same-company refusal as a 404, the 404s for a foreign employee and a foreign company, the 403 for
 * a caller with no scope chain, the positive scope case (an {@code lc-employee} caller carrying the
 * {@code company_id} claim is admitted and served), the 400 for a missing required field, and the
 * overlap the service pre-check cannot see ending as the generic 409 (W2a's slice of the refusal
 * matrix; the by-name proof stays W1a's JDBC-level assertion). The list also proves the history's
 * newest-first {@code validFrom} order, and asserts the id-descending tie-breaker for rows sharing a
 * {@code validFrom} (its regression sensitivity is probabilistic, since the id is a random UUID).</p>
 *
 * <p>Cleanup is scoped: every row of {@code employee_store_assignments} (this feature's own table)
 * and the employees of the two companies this suite creates. The store tree and the {@code companies}
 * rows are find-or-create fixtures and are never deleted, so the shared container's rows survive.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Employee Store Assignment HTTP Integration Tests")
class EmployeeStoreAssignmentHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "EMP-STORE-ASSIGN-HTTP-KEY";
    private static final String COMPANY_RFC = "ESAHT010101AB";
    private static final String COMPANY_NAME = "Store Assignment HTTP Test Company";
    private static final String OTHER_COMPANY_KEY = "EMP-STORE-ASSIGN-HTTP-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "ESAHT020202CD";
    private static final String OTHER_COMPANY_NAME = "Store Assignment HTTP Other Company";
    private static final String REGION_CODE = "ESAHT";
    private static final String REGION_NAME = "Store Assignment HTTP Region";
    private static final String ZONE_CODE = "ESAHT";
    private static final String ZONE_NAME = "Store Assignment HTTP Zone";
    private static final String STORE_NAME = "Assignment HTTP Store";
    private static final String SECOND_STORE_NAME = "Assignment HTTP Second Store";
    private static final String FOREIGN_STORE_NAME = "Assignment HTTP Foreign Store";

    private static final String BASE_URL = "/api/companies/{companyId}/employees/{employeeId}/store-assignments";
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
        employeeId = insertEmployee(companyId, activeStatusId, "EMP-SA-HTTP-1", "sa.http@example.com");
        foreignEmployeeId =
                insertEmployee(otherCompanyId, activeStatusId, "EMP-SA-HTTP-2", "sa.http.other@example.com");
    }

    @AfterEach
    void tearDown() {
        cleanOwnedRows();
    }

    /** Deletes the rows this feature owns, leaf-first, and only the employees this suite creates. */
    private void cleanOwnedRows() {
        jdbcTemplate.update("DELETE FROM employee_store_assignments");
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
     * The locals below are the same objects the builder received, so their ids are always readable.
     */
    private record SeededZone(CompanyZone zone, UUID regionId, UUID companyCountryId) {}

    private Company findOrCreateCompany(String key, String rfc, String name) {
        return companyRepository
                .findByCompanyKey(key)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(key)
                        .companyName(name)
                        .rfc(rfc)
                        .enabled(true)
                        .build()));
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

    /** The id of the row a create call just returned, read from its 201 body. */
    private UUID createdAssignmentId(ResultActions result) throws Exception {
        var body = result.andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private int countAssignments(UUID employee) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employee_store_assignments WHERE employee_id = ?", Integer.class, employee);
    }

    private void disableAssignmentAt(LocalDate validFrom) {
        jdbcTemplate.update(
                "UPDATE employee_store_assignments SET enabled = false WHERE employee_id = ? AND valid_from = ?",
                employeeId,
                validFrom);
    }

    private LocalDate storedValidToAt(LocalDate validFrom) {
        return jdbcTemplate.queryForObject(
                "SELECT valid_to FROM employee_store_assignments WHERE employee_id = ? AND valid_from = ?",
                (rs, rowNum) -> rs.getObject("valid_to", LocalDate.class),
                employeeId,
                validFrom);
    }

    @Nested
    @DisplayName("create over HTTP")
    class CreateTests {

        @Test
        @DisplayName("returns 201 with the assignment and its derived chain, ids and names (T14)")
        void createReturns201WithTheDerivedChain() throws Exception {
            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.companyStoreName").value(STORE_NAME))
                    .andExpect(jsonPath("$.validFrom").value("2026-01-01"))
                    .andExpect(jsonPath("$.derived.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.derived.companyName").value(COMPANY_NAME))
                    .andExpect(jsonPath("$.derived.companyCountryId").value(companyCountryId.toString()))
                    .andExpect(jsonPath("$.derived.companyCountryName").value(countryName))
                    .andExpect(jsonPath("$.derived.companyRegionId").value(regionId.toString()))
                    .andExpect(jsonPath("$.derived.companyRegionName").value(REGION_NAME))
                    .andExpect(jsonPath("$.derived.companyZoneId").value(zoneId.toString()))
                    .andExpect(jsonPath("$.derived.companyZoneName").value(ZONE_NAME));

            assertThat(countAssignments(employeeId)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("list over HTTP")
    class ListTests {

        @Test
        @DisplayName("returns 200 and lists the created assignment")
        void listReturnsTheCreatedAssignment() throws Exception {
            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isCreated());

            getAssignments("", admin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$[0].companyStoreName").value(STORE_NAME))
                    .andExpect(jsonPath("$[0].validFrom").value("2026-01-01"));
        }

        @Test
        @DisplayName("returns the history newest first by validFrom, regardless of creation order")
        void listIsNewestFirstRegardlessOfCreationOrder() throws Exception {
            // The earlier-dated row is created first and the later-dated one second, on two
            // different stores so the create path closes no predecessor. Insertion order is
            // therefore [2026-01-01, 2026-06-01] while the expected order is its reverse,
            // [2026-06-01, 2026-01-01]: a query that lost its ORDER BY and fell back to that
            // insertion (heap) order fails this test.
            postAssignment(employeeId, new StoreAssignmentRequest(secondStoreId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isCreated());
            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 6, 1)), admin())
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
                    postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                            .andExpect(status().isCreated()));
            var secondId = createdAssignmentId(postAssignment(
                            employeeId, new StoreAssignmentRequest(secondStoreId, LocalDate.of(2026, 1, 1)), admin())
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
            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
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
        @DisplayName("a store of another company is a 404 and writes nothing (T8)")
        void foreignStoreIsA404() throws Exception {
            postAssignment(employeeId, new StoreAssignmentRequest(foreignStoreId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isNotFound());

            assertThat(countAssignments(employeeId)).isZero();
        }

        @Test
        @DisplayName("an employee of another company is a 404 and writes nothing")
        void foreignEmployeeIsA404() throws Exception {
            postAssignment(foreignEmployeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isNotFound());

            assertThat(countAssignments(foreignEmployeeId)).isZero();
        }

        @Test
        @DisplayName("a company that does not exist is a 404")
        void unknownCompanyIsA404() throws Exception {
            var unknownCompanyId = UUID.randomUUID();

            mockMvc.perform(post(BASE_URL, unknownCompanyId, employeeId)
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)))))
                    .andExpect(status().isNotFound());

            assertThat(countAssignments(employeeId)).isZero();
        }

        @Test
        @DisplayName("the right role with no claim for the company is a 403")
        void companyOutsideTheTokenClaimIsA403() throws Exception {
            var otherCompanyClaim = jwt().authorities(ROLE_LC_EMPLOYEE)
                    .jwt(builder ->
                            builder.claim("company_id", UUID.randomUUID().toString()));

            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)), otherCompanyClaim)
                    .andExpect(status().isForbidden());

            assertThat(countAssignments(employeeId)).isZero();
        }

        @Test
        @DisplayName("the right role with no scope claims at all is a 403")
        void missingScopeClaimsIsA403() throws Exception {
            var noClaims = jwt().authorities(ROLE_LC_EMPLOYEE);

            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)), noClaims)
                    .andExpect(status().isForbidden());

            assertThat(countAssignments(employeeId)).isZero();
        }

        @Test
        @DisplayName("an lc-employee caller carrying the company claim is admitted and served")
        void employeeWithTheCompanyClaimIsAdmitted() throws Exception {
            // Genuinely non-admin: only ROLE_lc-employee, never ROLE_lc-admin. The company level
            // requires the company_id claim (ScopeLevel.COMPANY.isRequired()), so a caller with that
            // claim passes verifyCompanyAccess and is served instead of denied.
            var scopedEmployee = jwt().authorities(ROLE_LC_EMPLOYEE)
                    .jwt(builder -> builder.claim("company_id", companyId.toString()));

            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)), scopedEmployee)
                    .andExpect(status().isCreated())
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

            assertThat(countAssignments(employeeId)).isZero();
        }

        @Test
        @DisplayName("the overlap the pre-check cannot see ends as the generic 409")
        void unseenOverlapIsAGeneric409() throws Exception {
            // A covers the new start date (so the pre-check closes it) and C starts after A ends.
            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 1, 1)), admin())
                    .andExpect(status().isCreated());
            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2027, 1, 1)), admin())
                    .andExpect(status().isCreated());
            assertThat(countAssignments(employeeId)).isEqualTo(2);

            // Open-ended from 2026-06-01: the pre-check sees only A, closes it at 2026-06-01, and the
            // insert then overlaps C. The partial exclusion constraint refuses it; the handler maps
            // DataIntegrityViolationException to the generic 409, not a 500. Only the status is
            // asserted here: the by-name proof is W1a's JDBC-level assertion, and the HTTP layer
            // cannot see the constraint name.
            postAssignment(employeeId, new StoreAssignmentRequest(storeId, LocalDate.of(2026, 6, 1)), admin())
                    .andExpect(status().isConflict());

            assertThat(countAssignments(employeeId))
                    .as("the failed write leaves the history untouched")
                    .isEqualTo(2);
            assertThat(storedValidToAt(LocalDate.of(2026, 1, 1)))
                    .as("the predecessor close rolled back with the refused insert")
                    .isEqualTo(LocalDate.of(2027, 1, 1));
        }
    }
}
