package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.hr.dto.CloseStoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.StoreAssignmentRequest;
import com.lifecontrol.api.hr.repository.EmployeeStoreAssignmentRepository;
import com.lifecontrol.api.hr.service.EmployeeStoreAssignmentService;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Persistence-level verification of the store-assignment write and close paths against real
 * PostgreSQL, with the V23 schema applied by Flyway and {@code ddl-auto=validate}.
 *
 * <p>W1b owns no controller, so — unlike {@code ContractCrudIntegrationTest} — this suite drives
 * {@link EmployeeStoreAssignmentService} directly. It binds a request and an admin JWT the way
 * {@code GoodsReceiptIntegrationTest} does, because the service reads the request-scoped
 * {@code CurrentUserContext}: the admin authority makes {@code verifyCompanyAccess} a pass-through,
 * so what this suite proves is the persistence and the transaction, not the authorization rule
 * (that is pinned by {@code EmployeeStoreAssignmentServiceTest}).</p>
 *
 * <p>The load-bearing assertion is the handover: opening a second assignment for the same store
 * must leave <b>no coverage hole</b>, so it closes the predecessor by writing the successor's
 * {@code validFrom} verbatim as the predecessor's exclusive stored bound (T4/D5), and the
 * successor's first covered day is exactly the predecessor's last covered day + 1. The close path
 * and the same-company 404 (T8) are proven too. The partial exclusion constraint's refusal matrix
 * (the named constraint at the SQL level, the HTTP layer) deliberately belongs to W2.</p>
 *
 * <p>Cleanup is leaf-first and scoped: the {@code @BeforeEach} and {@code @AfterEach} delete every
 * row of {@code employee_store_assignments} (a table this feature solely owns) and the employees of
 * the two companies this suite creates. It never runs a blanket {@code DELETE FROM employees},
 * which would collide with the rows other shared-container suites leave behind. The store tree and
 * the {@code companies} rows are find-or-create fixtures, never deleted.</p>
 */
@SpringBootTest
@DisplayName("Store Assignment CRUD Integration Tests")
class StoreAssignmentCrudIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "EMP-STORE-ASSIGN-CRUD-KEY";
    private static final String COMPANY_RFC = "ESAC010101AB";
    private static final String COMPANY_NAME = "Store Assignment CRUD Test Company";
    private static final String OTHER_COMPANY_KEY = "EMP-STORE-ASSIGN-CRUD-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "ESAC020202CD";
    private static final String REGION_CODE = "ESAC";
    private static final String REGION_NAME = "Store Assignment CRUD Region";
    private static final String ZONE_CODE = "ESAC";
    private static final String ZONE_NAME = "Store Assignment CRUD Zone";
    private static final String STORE_ONE_NAME = "Assignment CRUD Store One";
    private static final String STORE_TWO_NAME = "Assignment CRUD Store Two";
    private static final String FOREIGN_STORE_NAME = "Assignment CRUD Foreign Store";
    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    @Autowired
    private EmployeeStoreAssignmentService service;

    @Autowired
    private EmployeeStoreAssignmentRepository assignmentRepository;

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
    private UUID employeeId;
    private UUID storeOneId;
    private UUID storeTwoId;
    private UUID foreignStoreId;
    private String countryName;

    @BeforeEach
    void setUp() {
        bindRequestAndSecurityContext();
        cleanOwnedRows();
        seedCompanyTrees();
        employeeId = insertEmployee(companyId, activeEmployeeStatusId(), "EMP-SA-CRUD-1", "sa.crud@example.com");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        cleanOwnedRows();
    }

    private void bindRequestAndSecurityContext() {
        // The service reads the request-scoped CurrentUserContext, so calling it outside a web
        // request needs both a bound request (so the scoped proxy resolves) and a JWT
        // authentication. The admin authority makes verifyCompanyAccess a pass-through.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("assignment-sub")
                .claim("preferred_username", "assignment")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(ROLE_LC_ADMIN)));
    }

    /**
     * Deletes the rows this feature owns, leaf-first: every assignment (this table is the feature's
     * own) and only the employees of the two companies this suite creates, so the shared container's
     * rows — and the provisioning rows other suites leave behind — are never touched.
     */
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
        var companyZone = companyZone(company, REGION_CODE, REGION_NAME, ZONE_CODE, ZONE_NAME);
        storeOneId = findOrCreateStore(companyZone, STORE_ONE_NAME);
        storeTwoId = findOrCreateStore(companyZone, STORE_TWO_NAME);

        var otherCompany = findOrCreateCompany(OTHER_COMPANY_KEY, OTHER_COMPANY_RFC, "Store Assignment CRUD Other");
        var otherZone = companyZone(otherCompany, REGION_CODE, REGION_NAME, ZONE_CODE, ZONE_NAME);
        foreignStoreId = findOrCreateStore(otherZone, FOREIGN_STORE_NAME);
    }

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

    private CompanyZone companyZone(
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
        return companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .filter(candidate -> zoneCode.equals(candidate.getZoneCode()))
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode(zoneCode)
                        .zoneName(zoneName)
                        .enabled(true)
                        .build()));
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

    private int countAssignments(UUID employee) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employee_store_assignments WHERE employee_id = ?", Integer.class, employee);
    }

    @Nested
    @DisplayName("create — the handover leaves no coverage hole")
    class HandoverTests {

        @Test
        @DisplayName("under '[)' the predecessor's first uncovered day is the successor's first covered day")
        void successorStartsOnThePredecessorsFirstUncoveredDay() {
            var predecessor = service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(storeOneId, LocalDate.of(2026, 1, 1)));
            var successorRequest = new StoreAssignmentRequest(storeOneId, LocalDate.of(2026, 6, 1));
            var successor = service.createAssignment(companyId, employeeId, successorRequest);

            var predecessorStored =
                    assignmentRepository.findById(predecessor.id()).orElseThrow();
            var successorStored = assignmentRepository.findById(successor.id()).orElseThrow();

            assertThat(predecessorStored.getValidTo())
                    .as("the predecessor's stored exclusive bound is the successor's requested start date (D5)")
                    .isEqualTo(successorRequest.validFrom());
            assertThat(successorStored.getValidFrom())
                    .as("the successor's own stored validFrom is the start date its request asked for")
                    .isEqualTo(successorRequest.validFrom());
            assertThat(countAssignments(employeeId)).isEqualTo(2);
        }

        @Test
        @DisplayName("opening for a different store leaves the first store's assignment untouched (D1)")
        void differentStoreLeavesTheFirstAssignmentOpen() {
            var first = service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(storeOneId, LocalDate.of(2026, 1, 1)));
            service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(storeTwoId, LocalDate.of(2026, 1, 1)));

            assertThat(assignmentRepository.findById(first.id()).orElseThrow().getValidTo())
                    .as("a different store closes nothing")
                    .isNull();
            assertThat(countAssignments(employeeId)).isEqualTo(2);
        }

        @Test
        @DisplayName("a store of another company is a 404 and writes nothing (T8)")
        void foreignStoreIsA404() {
            assertThatThrownBy(() -> service.createAssignment(
                            companyId,
                            employeeId,
                            new StoreAssignmentRequest(foreignStoreId, LocalDate.of(2026, 1, 1))))
                    .isInstanceOf(CompanyStoreNotFoundException.class);

            assertThat(countAssignments(employeeId)).isZero();
        }
    }

    @Nested
    @DisplayName("close (T5/D5)")
    class CloseTests {

        @Test
        @DisplayName("stores the inclusive end date as its exclusive bound, one day later")
        void closeStoresTheExclusiveBound() {
            var created = service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(storeOneId, LocalDate.of(2026, 1, 1)));

            var closed = service.closeAssignment(
                    companyId, employeeId, created.id(), new CloseStoreAssignmentRequest(LocalDate.of(2026, 6, 30)));

            assertThat(closed.validTo()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(assignmentRepository.findById(created.id()).orElseThrow().getValidTo())
                    .isEqualTo(LocalDate.of(2026, 7, 1));
        }

        @Test
        @DisplayName("closing without a body defaults the end date to today")
        void closeWithoutBodyDefaultsToToday() {
            var created = service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(storeOneId, LocalDate.of(2026, 1, 1)));

            var closed = service.closeAssignment(companyId, employeeId, created.id(), null);

            assertThat(closed.validTo()).isEqualTo(LocalDate.now());
            assertThat(assignmentRepository.findById(created.id()).orElseThrow().getValidTo())
                    .isEqualTo(LocalDate.now().plusDays(1));
        }

        @Test
        @DisplayName("closing an already-closed assignment is a 409 and leaves the row as it was")
        void closingTwiceIsAConflict() {
            var created = service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(storeOneId, LocalDate.of(2026, 1, 1)));
            service.closeAssignment(
                    companyId, employeeId, created.id(), new CloseStoreAssignmentRequest(LocalDate.of(2026, 6, 30)));

            assertThatThrownBy(() -> service.closeAssignment(companyId, employeeId, created.id(), null))
                    .isInstanceOf(ConflictException.class);

            assertThat(assignmentRepository.findById(created.id()).orElseThrow().getValidTo())
                    .isEqualTo(LocalDate.of(2026, 7, 1));
        }
    }

    @Nested
    @DisplayName("list — the derived display chain (T14)")
    class ListTests {

        @Test
        @DisplayName("returns the store name and the four ancestor ids and names")
        void listShowsTheDerivedChain() {
            service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(storeOneId, LocalDate.of(2026, 1, 1)));

            var rows = service.getAssignments(companyId, employeeId, true);

            assertThat(rows).hasSize(1);
            var row = rows.get(0);
            assertThat(row.companyStoreId()).isEqualTo(storeOneId);
            assertThat(row.companyStoreName()).isEqualTo(STORE_ONE_NAME);
            assertThat(row.validFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(row.validTo()).isNull();
            assertThat(row.enabled()).isTrue();
            assertThat(row.derived().companyId()).isEqualTo(companyId);
            assertThat(row.derived().companyName()).isEqualTo(COMPANY_NAME);
            assertThat(row.derived().companyCountryName()).isEqualTo(countryName);
            assertThat(row.derived().companyRegionName()).isEqualTo(REGION_NAME);
            assertThat(row.derived().companyZoneName()).isEqualTo(ZONE_NAME);
        }

        @Test
        @DisplayName("excludes disabled rows when includeDisabled is false")
        void listExcludesDisabledWhenAsked() {
            var created = service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(storeOneId, LocalDate.of(2026, 1, 1)));
            jdbcTemplate.update("UPDATE employee_store_assignments SET enabled = false WHERE id = ?", created.id());

            assertThat(service.getAssignments(companyId, employeeId, false)).isEmpty();
            assertThat(service.getAssignments(companyId, employeeId, true)).hasSize(1);
        }
    }
}
