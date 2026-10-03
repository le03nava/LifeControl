package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.EmployeeRequest;
import com.lifecontrol.api.hr.exception.DuplicateEmployeeException;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
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
        jdbcTemplate.update("DELETE FROM employees");
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

    private void insertEmployee(String number, String email) {
        jdbcTemplate.update("""
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, ?, 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                """, UUID.randomUUID(), companyId, number, email, activeStatusId);
    }

    private UUID employeeCompanyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
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
                .orElseGet(() -> companyRepository.save(Company.builder()
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
