package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.EmployeeRequest;
import com.lifecontrol.api.hr.exception.DuplicateEmployeeException;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end verification of the employee CRUD service against real PostgreSQL, with the V20 schema
 * applied by Flyway and {@code ddl-auto=validate}.
 *
 * <p>The company-scope contract is exercised through a mocked {@link CurrentUserContext}: the
 * service's own scope logic is pinned by {@code EmployeeServiceTest}, while this suite proves the
 * persistence semantics — the generated address reaching the database, the two per-company UNIQUE
 * keys, both CHECK constraints, the soft delete and the filtered list. The raw inserts for the
 * constraint tests exist because the service pre-checks those invariants; the point here is that the
 * database refuses the invalid write even when the service is bypassed.</p>
 */
@SpringBootTest
@DisplayName("Employee CRUD Integration Tests")
class EmployeeCrudIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "EMP-CRUD-KEY";
    private static final String COMPANY_RFC = "EMPCR010101AB";
    private static final String COMPANY_DOMAIN = "crud.example.com";
    private static final String OTHER_COMPANY_KEY = "EMP-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "EMPO010101AB";
    private static final String OTHER_COMPANY_DOMAIN = "other.example.com";

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    private UUID companyId;
    private UUID activeStatusId;
    private UUID inactiveStatusId;

    @BeforeEach
    void setUp() {
        // W5c's producer writes an access_provisioning_tasks row when a contract starts or an
        // employment ends, and that table references employees: the rows that reference them are
        // deleted first (record T90).
        deleteAllAccessProvisioningRows(jdbcTemplate);
        deleteAllEmployees(jdbcTemplate);
        companyId = employeeCompanyId();
        activeStatusId = employeeStatusId("Active");
        inactiveStatusId = employeeStatusId("Inactive");
    }

    @Nested
    @DisplayName("round trip")
    class RoundTripTests {

        @Test
        @DisplayName("create, read, update and soft-delete an employee through the service")
        void createReadUpdateSoftDelete() {
            var created = employeeService.createEmployee(
                    companyId, request("EMP-001", "Ada", "Lovelace", "Byron", null, null));

            assertThat(created.id()).isNotNull();
            assertThat(created.employeeNumber()).isEqualTo("EMP-001");
            assertThat(created.firstName()).isEqualTo("Ada");
            assertThat(created.paternalLastName()).isEqualTo("Lovelace");
            assertThat(created.maternalLastName()).isEqualTo("Byron");
            assertThat(created.email()).isEqualTo("ada.lovelace@" + COMPANY_DOMAIN);
            assertThat(created.statusName()).isEqualTo("Active");
            assertThat(created.enabled()).isTrue();
            assertThat(created.keycloakUserId()).isNull();

            var read = employeeService.getEmployeeById(companyId, created.id());
            assertThat(read.employeeNumber()).isEqualTo("EMP-001");

            var updated = employeeService.updateEmployee(
                    companyId, created.id(), request("EMP-001", "Ada", "Lovelace", "Byron", null, null));
            assertThat(updated.id()).isEqualTo(created.id());
            assertThat(updated.email()).isEqualTo("ada.lovelace@" + COMPANY_DOMAIN);

            employeeService.deleteEmployee(companyId, created.id());
            var afterDelete = employeeService.getEmployeeById(companyId, created.id());
            assertThat(afterDelete.enabled()).isFalse();
            assertThat(employeeRepository.findById(created.id())).isPresent();
        }

        @Test
        @DisplayName("the generated email reaches the database")
        void generatedEmailReachesTheDatabase() {
            var created = employeeService.createEmployee(
                    companyId, request("EMP-GEN", "Juan Carlos", "Pérez", null, null, null));

            var stored =
                    jdbcTemplate.queryForObject("SELECT email FROM employees WHERE id = ?", String.class, created.id());

            assertThat(stored).isEqualTo("juan.perez@" + COMPANY_DOMAIN);
        }

        @Test
        @DisplayName("keycloakUserId stays null after a create and an update")
        void keycloakUserIdStaysNull() {
            var created =
                    employeeService.createEmployee(companyId, request("EMP-KC", "Ada", "Lovelace", null, null, null));
            assertThat(created.keycloakUserId()).isNull();

            var updated = employeeService.updateEmployee(
                    companyId, created.id(), request("EMP-KC", "Ada", "Lovelace", null, null, null));

            assertThat(updated.keycloakUserId()).isNull();
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT keycloak_user_id FROM employees WHERE id = ?", String.class, created.id()))
                    .isNull();
        }

        @Test
        @DisplayName("a soft-deleted row keeps its employee number, so re-creating it is a 409")
        void softDeletedEmployeeNumberCannotBeReused() {
            var created =
                    employeeService.createEmployee(companyId, request("EMP-500", "Ada", "Lovelace", null, null, null));
            employeeService.deleteEmployee(companyId, created.id());

            assertThatThrownBy(() -> employeeService.createEmployee(
                            companyId, request("EMP-500", "Grace", "Hopper", null, null, null)))
                    .isInstanceOf(DuplicateEmployeeException.class);
        }

        @Test
        @DisplayName("a soft-deleted row keeps its address, so re-creating it yields the suffixed address")
        void softDeletedEmployeeAddressIsSuffixed() {
            var created =
                    employeeService.createEmployee(companyId, request("EMP-600", "Ada", "Lovelace", null, null, null));
            assertThat(created.email()).isEqualTo("ada.lovelace@" + COMPANY_DOMAIN);
            employeeService.deleteEmployee(companyId, created.id());

            var recreated =
                    employeeService.createEmployee(companyId, request("EMP-601", "Ada", "Lovelace", null, null, null));

            assertThat(recreated.email()).isEqualTo("ada.lovelace2@" + COMPANY_DOMAIN);
        }
    }

    @Nested
    @DisplayName("created_by audit column")
    class CreatedByTests {

        @Test
        @DisplayName("create records the authenticated actor's username as created_by")
        void createRecordsTheActorUsername() {
            // The mocked CurrentUserContext stands in for the JWT: getUsername() is the
            // preferred_username claim the real context resolves.
            when(currentUserContext.getUsername()).thenReturn("hr-operator");

            var created =
                    employeeService.createEmployee(companyId, request("EMP-CB-1", "Ada", "Lovelace", null, null, null));

            assertThat(created.createdBy()).isEqualTo("hr-operator");
            assertThat(storedCreatedBy(created.id())).isEqualTo("hr-operator");
        }

        @Test
        @DisplayName("update changes the editable fields and leaves created_by and created_at untouched")
        void updateLeavesCreatedByUntouched() {
            when(currentUserContext.getUsername()).thenReturn("hr-operator");
            var created =
                    employeeService.createEmployee(companyId, request("EMP-CB-2", "Ada", "Lovelace", null, null, null));
            var createdAtBefore = storedCreatedAt(created.id());

            // A different actor edits the row: created_by must still name the creator.
            when(currentUserContext.getUsername()).thenReturn("another-operator");
            var updated = employeeService.updateEmployee(
                    companyId, created.id(), request("EMP-CB-2", "Grace", "Hopper", null, null, null));

            assertThat(updated.firstName()).isEqualTo("Grace");
            assertThat(updated.createdBy()).isEqualTo("hr-operator");
            assertThat(storedCreatedBy(created.id())).isEqualTo("hr-operator");
            assertThat(storedCreatedAt(created.id())).isEqualTo(createdAtBefore);
        }

        @Test
        @DisplayName("the enable path leaves created_by untouched")
        void enablePathLeavesCreatedByUntouched() {
            when(currentUserContext.getUsername()).thenReturn("hr-operator");
            var created =
                    employeeService.createEmployee(companyId, request("EMP-CB-3", "Ada", "Lovelace", null, null, null));

            when(currentUserContext.getUsername()).thenReturn("another-operator");
            employeeService.setEmployeeEnabled(companyId, created.id(), false);
            employeeService.setEmployeeEnabled(companyId, created.id(), true);

            assertThat(storedCreatedBy(created.id())).isEqualTo("hr-operator");
        }

        private String storedCreatedBy(UUID id) {
            return jdbcTemplate.queryForObject("SELECT created_by FROM employees WHERE id = ?", String.class, id);
        }

        private java.time.LocalDateTime storedCreatedAt(UUID id) {
            return jdbcTemplate.queryForObject(
                    "SELECT created_at FROM employees WHERE id = ?", java.time.LocalDateTime.class, id);
        }
    }

    @Nested
    @DisplayName("database constraints")
    class ConstraintTests {

        @Test
        @DisplayName("uq_employees_company_number rejects a duplicate employee number for one company")
        void duplicateEmployeeNumberIsRejected() {
            insertEmployee("EMP-DUP-N", "ada.one@" + COMPANY_DOMAIN);

            assertViolatesConstraint(
                    "uq_employees_company_number",
                    """
                    INSERT INTO employees
                        (id, company_id, employee_number, first_name, paternal_last_name, email,
                         birth_date, hire_date, employment_status_id)
                    VALUES (?, ?, 'EMP-DUP-N', 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                    """,
                    UUID.randomUUID(),
                    companyId,
                    "ada.two@" + COMPANY_DOMAIN,
                    activeStatusId);
        }

        @Test
        @DisplayName("uq_employees_company_email rejects a duplicate email for one company")
        void duplicateEmployeeEmailIsRejected() {
            insertEmployee("EMP-DUP-E1", "ada.dup@" + COMPANY_DOMAIN);

            assertViolatesConstraint(
                    "uq_employees_company_email",
                    """
                    INSERT INTO employees
                        (id, company_id, employee_number, first_name, paternal_last_name, email,
                         birth_date, hire_date, employment_status_id)
                    VALUES (?, ?, 'EMP-DUP-E2', 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                    """,
                    UUID.randomUUID(),
                    companyId,
                    "ada.dup@" + COMPANY_DOMAIN,
                    activeStatusId);
        }

        @Test
        @DisplayName("ck_employees_termination_after_hire rejects a termination date before the hire date")
        void terminationBeforeHireIsRejected() {
            assertViolatesConstraint(
                    "ck_employees_termination_after_hire",
                    """
                    INSERT INTO employees
                        (id, company_id, employee_number, first_name, paternal_last_name, email,
                         birth_date, hire_date, termination_date, employment_status_id)
                    VALUES (?, ?, 'EMP-TERM', 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01',
                            DATE '2019-12-31', ?)
                    """,
                    UUID.randomUUID(),
                    companyId,
                    "ada.term@" + COMPANY_DOMAIN,
                    activeStatusId);
        }

        @Test
        @DisplayName("ck_employees_birth_before_hire rejects a birth date equal to the hire date")
        void birthEqualToHireIsRejected() {
            assertViolatesConstraint(
                    "ck_employees_birth_before_hire",
                    """
                    INSERT INTO employees
                        (id, company_id, employee_number, first_name, paternal_last_name, email,
                         birth_date, hire_date, employment_status_id)
                    VALUES (?, ?, 'EMP-BIRTH-EQ', 'Ada', 'Lovelace', ?, DATE '2020-01-01', DATE '2020-01-01', ?)
                    """,
                    UUID.randomUUID(),
                    companyId,
                    "ada.birth.eq@" + COMPANY_DOMAIN,
                    activeStatusId);
        }
    }

    @Nested
    @DisplayName("list filters")
    class ListFilterTests {

        @Test
        @DisplayName("orders lexicographically by employee number and excludes disabled by default")
        void listOrdersAndExcludesDisabled() {
            var nine =
                    employeeService.createEmployee(companyId, request("EMP-9", "Ada", "Lovelace", "Byron", null, null));
            var ten = employeeService.createEmployee(companyId, request("EMP-10", "Grace", "Hopper", null, null, null));
            var disabled = employeeService.createEmployee(
                    companyId, request("EMP-100", "Alan", "Turing", null, null, inactiveStatusId));
            employeeService.deleteEmployee(companyId, disabled.id());

            var result = employeeService.getAllEmployees(companyId, null, null, false);

            // Lexicographic VARCHAR ordering: "EMP-10" sorts before "EMP-9", while numeric ordering
            // would put "EMP-9" first.
            assertThat(result).extracting(r -> r.employeeNumber()).containsExactly("EMP-10", "EMP-9");
            assertThat(result).extracting(r -> r.id()).containsExactly(ten.id(), nine.id());
        }

        @Test
        @DisplayName("the list and search never return another company's employees")
        void listIsScopedToTheRequestedCompany() {
            employeeService.createEmployee(companyId, request("EMP-100", "Ada", "Lovelace", null, null, null));
            var otherCompanyId = otherCompanyId();
            employeeService.createEmployee(otherCompanyId, request("EMP-200", "Grace", "Hopper", null, null, null));

            var plain = employeeService.getAllEmployees(companyId, null, null, false);
            assertThat(plain).extracting(r -> r.employeeNumber()).containsExactly("EMP-100");

            // "grace" matches only the other company's employee: without the company predicate this
            // search would leak that row.
            var searched = employeeService.getAllEmployees(companyId, "grace", null, false);
            assertThat(searched).isEmpty();
        }

        @Test
        @DisplayName("includeDisabled true returns the soft-deleted employee")
        void includeDisabledReturnsSoftDeleted() {
            var employee =
                    employeeService.createEmployee(companyId, request("EMP-300", "Alan", "Turing", null, null, null));
            employeeService.deleteEmployee(companyId, employee.id());

            var result = employeeService.getAllEmployees(companyId, null, null, true);

            assertThat(result).extracting(r -> r.employeeNumber()).contains("EMP-300");
        }

        @Test
        @DisplayName("search matches employee number, first name, paternal and maternal last name and email")
        void searchMatchesEveryField() {
            employeeService.createEmployee(companyId, request("EMP-100", "Ada", "Lovelace", "Byron", null, null));
            employeeService.createEmployee(
                    companyId, request("EMP-200", "Grace", "Hopper", "Murray", null, inactiveStatusId));

            assertThat(search("EMP-2")).extracting(r -> r.employeeNumber()).containsExactly("EMP-200");
            assertThat(search("grace")).extracting(r -> r.employeeNumber()).containsExactly("EMP-200");
            assertThat(search("hopper")).extracting(r -> r.employeeNumber()).containsExactly("EMP-200");
            assertThat(search("murray")).extracting(r -> r.employeeNumber()).containsExactly("EMP-200");
            assertThat(search("grace.hopper@crud"))
                    .extracting(r -> r.employeeNumber())
                    .containsExactly("EMP-200");
        }

        @Test
        @DisplayName("statusId filters by the EMPLOYEE_STATUS row")
        void statusIdFilters() {
            employeeService.createEmployee(companyId, request("EMP-100", "Ada", "Lovelace", null, null, null));
            employeeService.createEmployee(
                    companyId, request("EMP-200", "Grace", "Hopper", null, null, inactiveStatusId));

            var result = employeeService.getAllEmployees(companyId, null, inactiveStatusId, false);

            assertThat(result).extracting(r -> r.employeeNumber()).containsExactly("EMP-200");
        }

        private java.util.List<com.lifecontrol.api.hr.dto.EmployeeResponse> search(String term) {
            return employeeService.getAllEmployees(companyId, term, null, true);
        }
    }

    @Nested
    @DisplayName("test isolation")
    class IsolationTests {

        /**
         * The rule, not the luck. Issue #562 was a full run where this class failed all 14 tests
         * because another suite left a child row behind on the container it shares with every other
         * integration class; the failure needs the right class order to surface, so a test that only
         * runs when the order is hostile cannot guard it. This one builds the hostile state on
         * purpose and runs the cleanup the {@code @BeforeEach} runs.
         */
        @Test
        @DisplayName("the setUp cleanup removes an employee that still has contract and scope rows")
        void cleanupRemovesAnEmployeeWithChildRows() {
            var employeeId = insertEmployee("EMP-ISO-1", "iso.one@" + COMPANY_DOMAIN);
            insertChildRows(employeeId);

            assertThat(rowCount("employee_contracts")).isPositive();
            assertThat(rowCount("employee_scope_assignments")).isPositive();

            assertThatCode(() -> deleteAllEmployees(jdbcTemplate)).doesNotThrowAnyException();

            assertThat(rowCount("employees")).isZero();
            assertThat(rowCount("employee_contracts")).isZero();
            assertThat(rowCount("employee_scope_assignments")).isZero();
        }

        /**
         * The child tables are the whole reason the parent delete can fail; a new table that
         * references {@code employees} would reintroduce #562 without touching this suite. The
         * assertion is derived from the catalogue, so it fails for the table nobody remembered.
         */
        @Test
        @DisplayName("every table that references employees is cleaned by the shared helper")
        void everyReferencingTableIsCleaned() {
            var referencing = jdbcTemplate.queryForList("""
                    SELECT tc.table_name
                      FROM information_schema.table_constraints tc
                      JOIN information_schema.constraint_column_usage ccu
                        ON ccu.constraint_name = tc.constraint_name
                       AND ccu.constraint_schema = tc.constraint_schema
                     WHERE tc.constraint_type = 'FOREIGN KEY'
                       AND ccu.table_name = 'employees'
                       AND tc.table_name <> 'employees'
                     ORDER BY tc.table_name
                    """, String.class);

            assertThat(referencing)
                    .as("tables with a foreign key to employees — each one must be emptied before the "
                            + "parent delete, and a new one is the next #562")
                    .containsExactly("access_provisioning_tasks", "employee_contracts", "employee_scope_assignments");
        }

        private int rowCount(String table) {
            return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        }
    }

    private EmployeeRequest request(
            String number,
            String firstName,
            String paternalLastName,
            String maternalLastName,
            String email,
            UUID statusId) {
        return new EmployeeRequest(
                number,
                firstName,
                paternalLastName,
                maternalLastName,
                email,
                null,
                LocalDate.of(1990, 1, 1),
                LocalDate.of(2020, 1, 1),
                null,
                null,
                statusId);
    }

    private UUID insertEmployee(String number, String email) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, ?, 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                """, id, companyId, number, email, activeStatusId);
        return id;
    }

    /**
     * Builds the minimal fixture graph and the two child rows that reference one employee: a
     * contract (position + seniority) and a scope assignment (company country). Codes carry a random
     * suffix so the natural keys never collide with the rows another suite left behind.
     */
    private void insertChildRows(UUID employeeId) {
        // Six characters keep every generated code inside its VARCHAR(10) ceiling.
        var suffix = employeeId.toString().substring(0, 6);

        var departmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO departments (id, company_id, department_code, department_name) VALUES (?, ?, ?, ?)",
                departmentId,
                companyId,
                "ISO" + suffix,
                "Isolation " + suffix);
        var positionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO positions (id, department_id, position_code, position_name) VALUES (?, ?, ?, ?)",
                positionId,
                departmentId,
                "IS" + suffix,
                "Isolation " + suffix);
        // rank and level_name are UNIQUE across the table, so neither can be a constant: the rank is
        // the next free one and the name carries the suffix.
        var nextRank =
                jdbcTemplate.queryForObject("SELECT coalesce(max(rank), 0) + 1 FROM seniority_levels", Integer.class);
        var seniorityId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO seniority_levels (id, level_code, level_name, rank) VALUES (?, ?, ?, ?)",
                seniorityId,
                "L" + suffix,
                "Isolation " + suffix,
                nextRank);
        jdbcTemplate.update("""
                INSERT INTO employee_contracts
                    (employee_id, position_id, seniority_level_id, contract_type, monthly_salary, start_date)
                VALUES (?, ?, ?, 'PERMANENT', 1000.00, DATE '2020-01-01')
                """, employeeId, positionId, seniorityId);

        var countryId = jdbcTemplate.queryForObject("""
                SELECT c.id FROM countries c
                 WHERE NOT EXISTS (SELECT 1 FROM company_countries cc
                                    WHERE cc.company_id = ? AND cc.country_id = c.id)
                 ORDER BY c.country_code LIMIT 1
                """, UUID.class, companyId);
        var companyCountryId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                companyCountryId,
                companyId,
                countryId);

        jdbcTemplate.update("""
                INSERT INTO employee_scope_assignments (employee_id, company_country_id, valid_from)
                VALUES (?, ?, DATE '2020-01-01')
                """, employeeId, companyCountryId);
    }

    private UUID employeeCompanyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> TenantFixtures.persist(
                        companyRepository,
                        Company.builder()
                                .companyKey(COMPANY_KEY)
                                .companyName("Employee CRUD Test Company")
                                .rfc(COMPANY_RFC)
                                .emailDomain(COMPANY_DOMAIN)
                                .enabled(true)
                                .build()))
                .getId();
    }

    private UUID otherCompanyId() {
        return companyRepository
                .findByCompanyKey(OTHER_COMPANY_KEY)
                .orElseGet(() -> TenantFixtures.persist(
                        companyRepository,
                        Company.builder()
                                .companyKey(OTHER_COMPANY_KEY)
                                .companyName("Employee CRUD Other Company")
                                .rfc(OTHER_COMPANY_RFC)
                                .emailDomain(OTHER_COMPANY_DOMAIN)
                                .enabled(true)
                                .build()))
                .getId();
    }

    private UUID employeeStatusId(String name) {
        return jdbcTemplate.queryForObject("""
                SELECT s.id FROM statuses s
                JOIN status_types st ON st.id = s.status_type_id
                WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
                  AND LOWER(s.status_name) = LOWER(?)
                """, UUID.class, name);
    }

    private void assertViolatesConstraint(String constraintName, String sql, Object... args) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, args))
                .as("write rejected by %s", constraintName)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(((DataIntegrityViolationException) thrown)
                                .getMostSpecificCause()
                                .getMessage())
                        .as("PostgreSQL reports the violated constraint")
                        .contains(constraintName));
    }
}
