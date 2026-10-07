package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of {@code V21__employee_contracts.sql} against real PostgreSQL with
 * Flyway enabled and {@code ddl-auto=validate}, so a green run proves the contract history schema
 * landed exactly as the migration declares it.
 *
 * <p>The service layer cannot prove these guarantees: they are enforced by the database itself. This
 * suite pins the schema's <b>first PostgreSQL extension</b> ({@code btree_gist}, without which the
 * exclusion constraint is not even parseable), its <b>first exclusion constraint</b>, and the
 * <b>partial</b> predicate ({@code WHERE (enabled)}) that lets a soft-deleted contract stop blocking
 * its replacement. The half-open range is the discriminating pair: with
 * {@code daterange(start_date, end_date, '[)')} the end date is exclusive, so a contract starting on
 * the previous contract's end date is accepted while one starting the day before is refused.</p>
 *
 * <p>Cleanup is deliberately broad, leaf-first, and unconditional over the tables this suite owns:
 * the {@code @BeforeEach} deletes every row of {@code employee_contracts}, then of
 * {@code position_salary_bands}, {@code position_roles}, {@code positions}, {@code departments},
 * {@code seniority_levels} and {@code employees}, in that order, and never touches
 * {@code companies}, {@code statuses} or {@code status_types} (the shared fixtures other suites
 * depend on). The PostgreSQL container is shared per JVM, so a wholly owned starting state is what
 * keeps the row-count assertions deterministic; the sibling schema suites delete these same HR
 * tables wholesale, and every suite that writes {@code employees} deletes the whole table too.</p>
 */
@SpringBootTest
@DisplayName("Employee Contract Schema Integration Tests")
class EmployeeContractSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "EMP-CONTRACT-KEY";
    private static final String COMPANY_RFC = "EMPC010101AB";

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    // One test method per test instance (JUnit's default lifecycle), so this counter restarts at 0
    // for each test and the natural keys it builds never collide with the @BeforeEach-cleared tables.
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void resetContractAndHrTables() {
        // Leaf-first and unconditional: employee_contracts references employees, positions and
        // seniority_levels; positions references departments; position_salary_bands and
        // position_roles reference positions. Every row of these tables is deleted, not a scoped
        // subset, so every method starts from a wholly owned state. companies, statuses and
        // status_types are shared fixtures and are never touched here.
        jdbcTemplate.update("DELETE FROM employee_contracts");
        jdbcTemplate.update("DELETE FROM position_salary_bands");
        jdbcTemplate.update("DELETE FROM position_roles");
        jdbcTemplate.update("DELETE FROM positions");
        jdbcTemplate.update("DELETE FROM departments");
        jdbcTemplate.update("DELETE FROM seniority_levels");
        jdbcTemplate.update("DELETE FROM employees");
    }

    @Test
    @DisplayName("applies every migration through V24 and leaves no pending migration")
    void flywayHeadIsV24() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("24");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("installs the btree_gist extension the exclusion constraint needs")
    void btreeGistExtensionExists() {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class);
        assertThat(count).as("btree_gist extension installed").isEqualTo(1);
    }

    @Test
    @DisplayName("creates employee_contracts with its eleven columns, their types and the three named constraints")
    void employeeContractsTableHasItsColumnsAndNamedConstraints() {
        var columns = jdbcTemplate.query(
                """
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'employee_contracts'
                """, (rs, rowNum) -> new ColumnType(rs.getString("column_name"), rs.getString("data_type")));

        assertThat(columns)
                .as("employee_contracts columns and their data types")
                .containsExactlyInAnyOrder(
                        new ColumnType("id", "uuid"),
                        new ColumnType("employee_id", "uuid"),
                        new ColumnType("position_id", "uuid"),
                        new ColumnType("seniority_level_id", "uuid"),
                        new ColumnType("contract_type", "character varying"),
                        new ColumnType("monthly_salary", "numeric"),
                        new ColumnType("start_date", "date"),
                        new ColumnType("end_date", "date"),
                        new ColumnType("enabled", "boolean"),
                        new ColumnType("created_at", "timestamp without time zone"),
                        new ColumnType("updated_at", "timestamp without time zone"));

        var constraints = jdbcTemplate.query(
                """
                SELECT c.conname, c.contype::text AS contype FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public' AND t.relname = 'employee_contracts'
                  AND c.conname IN ('ck_employee_contracts_salary',
                                    'ck_employee_contracts_dates',
                                    'ex_employee_contracts_no_overlap')
                """, (rs, rowNum) -> new ConstraintType(rs.getString("conname"), rs.getString("contype")));

        assertThat(constraints)
                .as("named CHECK and EXCLUDE constraints of employee_contracts")
                .containsExactlyInAnyOrder(
                        new ConstraintType("ck_employee_contracts_salary", "c"),
                        new ConstraintType("ck_employee_contracts_dates", "c"),
                        new ConstraintType("ex_employee_contracts_no_overlap", "x"));
    }

    @Test
    @DisplayName("ex_employee_contracts_no_overlap refuses an overlapping enabled contract for one employee")
    void overlappingEnabledContractIsRefusedByExclusionConstraint() {
        var fixtures = contractFixtures();
        insertContract(
                fixtures.employeeId(),
                fixtures.positionId(),
                fixtures.seniorityLevelId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
                true);

        assertViolatesConstraint(
                "ex_employee_contracts_no_overlap",
                """
                INSERT INTO employee_contracts
                    (id, employee_id, position_id, seniority_level_id, contract_type,
                     monthly_salary, start_date, end_date, enabled)
                VALUES (?, ?, ?, ?, 'PERMANENT', 1000.00, DATE '2026-03-01', DATE '2026-09-30', true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.positionId(),
                fixtures.seniorityLevelId());

        assertThat(countContracts(fixtures.employeeId())).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "the contract range is half-open: a start on the previous end date is accepted, one day earlier is refused")
    void contractRangeIsHalfOpenOnTheRight() {
        // Case (a), its own employee so its rows do not participate in case (b):
        // [2026-01-01, 2026-06-30) does not contain 2026-06-30, so a contract starting that day
        // is accepted. Under '[]' this write would be refused.
        var accepted = contractFixtures();
        insertContract(
                accepted.employeeId(),
                accepted.positionId(),
                accepted.seniorityLevelId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
                true);
        insertContract(
                accepted.employeeId(),
                accepted.positionId(),
                accepted.seniorityLevelId(),
                LocalDate.of(2026, 6, 30),
                null,
                true);
        assertThat(countContracts(accepted.employeeId()))
                .as("a contract starting on the previous end date is accepted")
                .isEqualTo(2);

        // Case (b), a second and independent employee: 2026-06-29 is inside the previous range,
        // so a contract starting that day is refused.
        var refused = contractFixtures();
        insertContract(
                refused.employeeId(),
                refused.positionId(),
                refused.seniorityLevelId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
                true);

        assertViolatesConstraint(
                "ex_employee_contracts_no_overlap",
                """
                INSERT INTO employee_contracts
                    (id, employee_id, position_id, seniority_level_id, contract_type,
                     monthly_salary, start_date, enabled)
                VALUES (?, ?, ?, ?, 'PERMANENT', 1000.00, DATE '2026-06-29', true)
                """,
                UUID.randomUUID(),
                refused.employeeId(),
                refused.positionId(),
                refused.seniorityLevelId());

        assertThat(countContracts(refused.employeeId()))
                .as("a contract starting one day before the previous end date is refused")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a soft-deleted contract stops blocking its replacement because the constraint is partial")
    void softDeletedContractDoesNotBlockItsReplacement() {
        var fixtures = contractFixtures();
        insertContract(
                fixtures.employeeId(),
                fixtures.positionId(),
                fixtures.seniorityLevelId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
                false);
        insertContract(
                fixtures.employeeId(),
                fixtures.positionId(),
                fixtures.seniorityLevelId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
                true);

        assertThat(countContracts(fixtures.employeeId()))
                .as("the enabled replacement is accepted over the disabled contract's window")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the overlap rule is per employee: two employees may hold overlapping contracts")
    void overlapIsPerEmployeeNotGlobal() {
        var first = contractFixtures();
        var second = contractFixtures();
        insertContract(
                first.employeeId(),
                first.positionId(),
                first.seniorityLevelId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                true);
        insertContract(
                second.employeeId(),
                second.positionId(),
                second.seniorityLevelId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                true);

        assertThat(countContracts(first.employeeId())).isEqualTo(1);
        assertThat(countContracts(second.employeeId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the two CHECKs refuse their inputs while end_date = start_date is accepted")
    void checkConstraintsRefuseTheirInputs() {
        var fixtures = contractFixtures();

        assertViolatesConstraint(
                "ck_employee_contracts_salary",
                """
                INSERT INTO employee_contracts
                    (id, employee_id, position_id, seniority_level_id, contract_type,
                     monthly_salary, start_date, enabled)
                VALUES (?, ?, ?, ?, 'PERMANENT', -1.00, DATE '2026-01-01', true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.positionId(),
                fixtures.seniorityLevelId());

        assertViolatesConstraint(
                "ck_employee_contracts_dates",
                """
                INSERT INTO employee_contracts
                    (id, employee_id, position_id, seniority_level_id, contract_type,
                     monthly_salary, start_date, end_date, enabled)
                VALUES (?, ?, ?, ?, 'PERMANENT', 1000.00, DATE '2026-06-30', DATE '2026-01-01', true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.positionId(),
                fixtures.seniorityLevelId());

        // The boundary: the CHECK is `end_date >= start_date`, so equality is accepted. A weakened
        // `>` rule would fail here and not only on the strict-after case above.
        insertContract(
                fixtures.employeeId(),
                fixtures.positionId(),
                fixtures.seniorityLevelId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 1),
                true);

        assertThat(countContracts(fixtures.employeeId()))
                .as("end_date equal to start_date is accepted")
                .isEqualTo(1);
    }

    /**
     * Find-or-create the company row the company-scoped keys hang off, mirroring the idempotent
     * fixture style of {@code HrSchemaMigrationIntegrationTest}. Reuses the persisted entity path
     * instead of hand-writing an insert into {@code companies}.
     */
    private UUID contractCompanyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Employee Contract Schema Test Company")
                        .rfc(COMPANY_RFC)
                        .enabled(true)
                        .build()))
                .getId();
    }

    /** Resolves the seeded {@code Active} row of the {@code EMPLOYEE_STATUS} family. */
    private UUID activeEmployeeStatusId() {
        return jdbcTemplate.queryForObject("""
                SELECT s.id FROM statuses s
                JOIN status_types st ON st.id = s.status_type_id
                WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
                  AND LOWER(s.status_name) = LOWER('Active')
                """, UUID.class);
    }

    /**
     * Builds one independent employee with its own department, position and seniority level, so a
     * method that needs two employees never makes one of them depend on the other's rows.
     */
    private ContractFixtures contractFixtures() {
        var companyId = contractCompanyId();
        var statusId = activeEmployeeStatusId();
        var n = sequence.incrementAndGet();
        var departmentId = insertDepartment(companyId, "D" + n, "Department " + n);
        var positionId = insertPosition(departmentId, "P" + n, "Position " + n);
        var seniorityLevelId = insertSeniorityLevel("L" + n, "Level " + n, n);
        var employeeId = insertEmployee(companyId, statusId, "EMP-C-" + n, "contract.employee" + n + "@example.com");
        return new ContractFixtures(employeeId, positionId, seniorityLevelId);
    }

    private UUID insertDepartment(UUID companyId, String code, String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO departments (id, company_id, department_code, department_name)
                VALUES (?, ?, ?, ?)
                """, id, companyId, code, name);
        return id;
    }

    private UUID insertPosition(UUID departmentId, String code, String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO positions (id, department_id, position_code, position_name)
                VALUES (?, ?, ?, ?)
                """, id, departmentId, code, name);
        return id;
    }

    private UUID insertSeniorityLevel(String code, String name, int rank) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO seniority_levels (id, level_code, level_name, rank)
                VALUES (?, ?, ?, ?)
                """, id, code, name, rank);
        return id;
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

    /**
     * Inserts a contract through raw SQL. {@code endDate} is {@code null} for an open-ended range,
     * which the INSERT expresses by omitting the column rather than by sending a typed null.
     */
    private UUID insertContract(
            UUID employeeId,
            UUID positionId,
            UUID seniorityLevelId,
            LocalDate startDate,
            LocalDate endDate,
            boolean enabled) {
        var id = UUID.randomUUID();
        if (endDate == null) {
            jdbcTemplate.update("""
                    INSERT INTO employee_contracts
                        (id, employee_id, position_id, seniority_level_id, contract_type,
                         monthly_salary, start_date, enabled)
                    VALUES (?, ?, ?, ?, 'PERMANENT', 1000.00, ?, ?)
                    """, id, employeeId, positionId, seniorityLevelId, startDate, enabled);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO employee_contracts
                        (id, employee_id, position_id, seniority_level_id, contract_type,
                         monthly_salary, start_date, end_date, enabled)
                    VALUES (?, ?, ?, ?, 'PERMANENT', 1000.00, ?, ?, ?)
                    """, id, employeeId, positionId, seniorityLevelId, startDate, endDate, enabled);
        }
        return id;
    }

    /** Scoped count: {@code employee_contracts} rows of one employee, never the whole table. */
    private int countContracts(UUID employeeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employee_contracts WHERE employee_id = ?", Integer.class, employeeId);
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

    private record ColumnType(String name, String type) {}

    private record ConstraintType(String name, String type) {}

    private record ContractFixtures(UUID employeeId, UUID positionId, UUID seniorityLevelId) {}
}
