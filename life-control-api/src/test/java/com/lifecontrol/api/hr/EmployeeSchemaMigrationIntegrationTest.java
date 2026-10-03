package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence-level verification of {@code V20__employee_registry.sql} against real PostgreSQL with
 * Flyway enabled and {@code ddl-auto=validate}, so a green run proves the employee registry schema
 * landed exactly as the migration declares it.
 *
 * <p>The service layer cannot prove these guarantees: they are enforced by the database itself. The
 * {@code employees} table and the {@code companies.email_domain} column must exist after the
 * migration, the {@code EMPLOYEE_STATUS} family must carry exactly its four statuses, and every
 * named CHECK and UNIQUE constraint must reject its invalid write and leave the table untouched.
 * The seed itself uses the repository's established natural-key guard idiom
 * ({@code INSERT ... SELECT ... WHERE NOT EXISTS}, the V3/V12/V18 pattern); the guard's idempotency
 * is inherited from that idiom and is <b>not</b> exercised by this suite, which pins only presence
 * and the four names on a fresh database.</p>
 *
 * <p>Cleanup is deliberately broad on {@code employees} and narrow everywhere else: the PostgreSQL
 * container is shared per JVM, so the {@code @BeforeEach} runs an unconditional
 * {@code DELETE FROM employees} that removes every row of the table, not a scoped subset. Two suites
 * write this table — this class and {@code EmployeeCrudIntegrationTest} — and both start by deleting
 * all of its rows, so they share the container without stepping on each other and every count
 * assertion reads a table fully owned by the running test. The delete never touches {@code statuses},
 * {@code status_types} or {@code companies}, which other suites depend on, and the count assertions
 * are additionally scoped by {@code company_id}.</p>
 */
@SpringBootTest
@DisplayName("Employee Registry Schema Integration Tests")
class EmployeeSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "EMP-SCHEMA-KEY";
    private static final String COMPANY_RFC = "EMPSC010101AB";

    private static final List<String> EMPLOYEE_STATUS_NAMES = List.of("Active", "Inactive", "OnLeave", "Terminated");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetEmployeeTable() {
        // Unconditional: removes every row of employees, not a scoped subset. Both suites that
        // write this table do the same, so the shared container is cleaned without cross-test
        // interference. status_types, statuses and companies are shared and are never touched here.
        jdbcTemplate.update("DELETE FROM employees");
    }

    @Test
    @DisplayName("applies every migration through V21 and leaves no pending migration")
    void flywayHeadIsV21() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("21");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("creates the employees table")
    void employeesTableExists() {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'employees'",
                Integer.class);
        assertThat(count).as("table employees exists").isEqualTo(1);
    }

    @Test
    @DisplayName("adds companies.email_domain")
    void companiesEmailDomainColumnExists() {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name = 'companies' AND column_name = 'email_domain'",
                Integer.class);
        assertThat(count).as("column companies.email_domain exists").isEqualTo(1);
    }

    @Test
    @DisplayName("seeds exactly one enabled EMPLOYEE_STATUS type with exactly its four statuses")
    void employeeStatusSeedLanded() {
        var typeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM status_types WHERE LOWER(status_type_name) = LOWER('EMPLOYEE_STATUS')"
                        + " AND enabled = true",
                Integer.class);
        assertThat(typeCount).as("one enabled EMPLOYEE_STATUS status_type").isEqualTo(1);

        var statusNames = jdbcTemplate.queryForList("""
                SELECT s.status_name FROM statuses s
                JOIN status_types st ON st.id = s.status_type_id
                WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
                """, String.class);
        assertThat(statusNames)
                .as("exactly the four EMPLOYEE_STATUS status names")
                .containsExactlyInAnyOrderElementsOf(EMPLOYEE_STATUS_NAMES);
    }

    @Test
    @DisplayName("uq_employees_company_number rejects a duplicate employee_number for one company")
    void duplicateEmployeeNumberIsRejectedByUniqueKey() {
        var companyId = employeeCompanyId();
        var statusId = employeeStatusId();
        insertEmployee(companyId, statusId, "EMP-001", "ada.one@example.com");

        assertViolatesConstraint("uq_employees_company_number", """
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, 'EMP-001', 'Ada', 'Lovelace', 'ada.two@example.com',
                        DATE '1990-01-01', DATE '2020-01-01', ?)
                """, UUID.randomUUID(), companyId, statusId);

        assertThat(countEmployees(companyId)).isEqualTo(1);
    }

    @Test
    @DisplayName("uq_employees_company_email rejects a duplicate email for one company")
    void duplicateEmployeeEmailIsRejectedByUniqueKey() {
        var companyId = employeeCompanyId();
        var statusId = employeeStatusId();
        insertEmployee(companyId, statusId, "EMP-101", "ada.dup@example.com");

        assertViolatesConstraint("uq_employees_company_email", """
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, 'EMP-102', 'Ada', 'Lovelace', 'ada.dup@example.com',
                        DATE '1990-01-01', DATE '2020-01-01', ?)
                """, UUID.randomUUID(), companyId, statusId);

        assertThat(countEmployees(companyId)).isEqualTo(1);
    }

    @Test
    @DisplayName("ck_employees_termination_after_hire rejects a termination date before the hire date")
    void terminationBeforeHireIsRejectedByCheckConstraint() {
        var companyId = employeeCompanyId();
        var statusId = employeeStatusId();

        assertViolatesConstraint("ck_employees_termination_after_hire", """
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, termination_date, employment_status_id)
                VALUES (?, ?, 'EMP-TERM', 'Ada', 'Lovelace', 'ada.term@example.com',
                        DATE '1990-01-01', DATE '2020-01-01', DATE '2019-12-31', ?)
                """, UUID.randomUUID(), companyId, statusId);

        assertThat(countEmployees(companyId)).isZero();
    }

    @Test
    @DisplayName("ck_employees_birth_before_hire rejects a birth date on or after the hire date")
    void birthOnOrAfterHireIsRejectedByCheckConstraint() {
        var companyId = employeeCompanyId();
        var statusId = employeeStatusId();

        // birth_date strictly after hire_date.
        assertViolatesConstraint("ck_employees_birth_before_hire", """
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, 'EMP-BIRTH', 'Ada', 'Lovelace', 'ada.birth@example.com',
                        DATE '2021-01-01', DATE '2020-01-01', ?)
                """, UUID.randomUUID(), companyId, statusId);

        // The boundary: birth_date equal to hire_date is also refused, so a weakened `<=` check
        // would be caught here and not only by the strict-after case above.
        assertViolatesConstraint("ck_employees_birth_before_hire", """
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, 'EMP-BIRTH-EQ', 'Ada', 'Lovelace', 'ada.birth.eq@example.com',
                        DATE '2020-01-01', DATE '2020-01-01', ?)
                """, UUID.randomUUID(), companyId, statusId);

        assertThat(countEmployees(companyId)).isZero();
    }

    @Test
    @DisplayName("the keycloak_user_id UNIQUE rejects a second employee linked to the same account")
    void duplicateKeycloakUserIdIsRejectedByUniqueKey() {
        var companyId = employeeCompanyId();
        var statusId = employeeStatusId();
        insertEmployeeWithKeycloakId(companyId, statusId, "EMP-KC-1", "ada.kc1@example.com", "kc-dup-1");

        assertViolatesConstraint("employees_keycloak_user_id_key", """
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id, keycloak_user_id)
                VALUES (?, ?, 'EMP-KC-2', 'Ada', 'Lovelace', 'ada.kc2@example.com',
                        DATE '1990-01-01', DATE '2020-01-01', ?, 'kc-dup-1')
                """, UUID.randomUUID(), companyId, statusId);

        assertThat(countEmployees(companyId)).isEqualTo(1);
    }

    @Test
    @DisplayName("round-trips a persisted employee through the repository")
    void employeeRoundTripsThroughTheRepository() {
        var companyId = employeeCompanyId();
        var statusId = employeeStatusId();

        var saved = employeeRepository.saveAndFlush(Employee.builder()
                .company(companyRepository.findById(companyId).orElseThrow())
                .status(statusRepository.findById(statusId).orElseThrow())
                .employeeNumber("EMP-RT-1")
                .firstName("Ada")
                .paternalLastName("Lovelace")
                .maternalLastName("Byron")
                .email("ada.lovelace@example.com")
                .phoneNumber("+52 55 0000 0000")
                .birthDate(LocalDate.of(1815, 12, 10))
                .hireDate(LocalDate.of(2020, 1, 15))
                .enabled(true)
                .build());

        // Read the lazy associations back inside a transaction: the repository commits its own
        // transaction, so touching company/status on the detached entity would throw.
        var reloaded =
                inTransaction(() -> employeeRepository.findById(saved.getId()).orElseThrow());

        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getEmployeeNumber()).isEqualTo("EMP-RT-1");
        assertThat(reloaded.getFirstName()).isEqualTo("Ada");
        assertThat(reloaded.getPaternalLastName()).isEqualTo("Lovelace");
        assertThat(reloaded.getMaternalLastName()).isEqualTo("Byron");
        assertThat(reloaded.getEmail()).isEqualTo("ada.lovelace@example.com");
        assertThat(reloaded.getPhoneNumber()).isEqualTo("+52 55 0000 0000");
        assertThat(reloaded.getBirthDate()).isEqualTo(LocalDate.of(1815, 12, 10));
        assertThat(reloaded.getHireDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(reloaded.getTerminationDate()).isNull();
        assertThat(reloaded.getEnabled()).isTrue();
        assertThat(reloaded.getVersion()).isNotNull();
        assertThat(reloaded.getCompany().getId()).isEqualTo(companyId);
        assertThat(reloaded.getStatus().getId()).isEqualTo(statusId);
        assertThat(reloaded.getKeycloakUserId()).isNull();
    }

    /**
     * Find-or-create the company row the company-scoped keys hang off, mirroring the idempotent
     * fixture style of {@code HrSchemaMigrationIntegrationTest}. Reuses the persisted entity path
     * instead of hand-writing an insert into {@code companies}.
     */
    private UUID employeeCompanyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Employee Schema Test Company")
                        .rfc(COMPANY_RFC)
                        .enabled(true)
                        .build()))
                .getId();
    }

    /** Resolves the seeded {@code Active} row of the {@code EMPLOYEE_STATUS} family. */
    private UUID employeeStatusId() {
        return jdbcTemplate.queryForObject("""
                SELECT s.id FROM statuses s
                JOIN status_types st ON st.id = s.status_type_id
                WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
                  AND LOWER(s.status_name) = LOWER('Active')
                """, UUID.class);
    }

    private UUID insertEmployee(UUID companyId, UUID statusId, String number, String email) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, ?, 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                """, id, companyId, number, email, statusId);
        return id;
    }

    private UUID insertEmployeeWithKeycloakId(
            UUID companyId, UUID statusId, String number, String email, String keycloakUserId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id, keycloak_user_id)
                VALUES (?, ?, ?, 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?, ?)
                """, id, companyId, number, email, statusId, keycloakUserId);
        return id;
    }

    /** Scoped count: {@code employees} rows of the test company, never the whole table. */
    private int countEmployees(UUID companyId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employees WHERE company_id = ?", Integer.class, companyId);
    }

    /**
     * Reads a lazy association inside a transaction: the repository commits its own transaction, so
     * reading {@code company} or {@code status} afterwards on the detached entity would throw.
     */
    private <T> T inTransaction(Supplier<T> reader) {
        return new TransactionTemplate(transactionManager).execute(status -> reader.get());
    }

    /**
     * Asserts the raw write is rejected by a real PostgreSQL constraint and that the reported error
     * names the exact constraint. The most specific cause carries the {@code PSQLException} message.
     */
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
