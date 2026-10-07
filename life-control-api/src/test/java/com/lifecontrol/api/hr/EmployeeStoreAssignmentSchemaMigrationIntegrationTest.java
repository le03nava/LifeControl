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
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
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
 * Persistence-level verification of {@code V23__employee_store_assignments.sql} against real
 * PostgreSQL with Flyway enabled and {@code ddl-auto=validate}, so a green run proves the assignment
 * schema landed exactly as the migration declares it.
 *
 * <p>The service layer cannot prove these guarantees: they are enforced by the database itself. This
 * suite pins the table's column types (the two range columns are {@code DATE}, not timestamps), both
 * named indexes, the named {@code CHECK}, and — the load-bearing one — the <b>partial exclusion
 * constraint</b> {@code ex_employee_store_assignments_no_overlap} over
 * {@code daterange(valid_from, valid_to, '[)')} per {@code (employee, company_store)} with
 * {@code WHERE (enabled)}. The half-open range and the partial predicate are the discriminating
 * facts: an adjacent range is accepted ({@code '[)'}) while an overlap is refused, a soft-deleted
 * row stops blocking its replacement ({@code WHERE (enabled)}), and a second store of the same
 * employee is accepted (decision D1: several stores at once). The constraint needs the
 * {@code btree_gist} extension, whose presence is re-asserted here because this constraint depends
 * on it.</p>
 *
 * <p>Cleanup is deliberately broad and unconditional over the tables this suite owns: the
 * {@code @BeforeEach} deletes every row of {@code employee_store_assignments}, then of
 * {@code employees}, and never touches the shared store tree or the shared fixtures
 * ({@code companies}, {@code statuses}, {@code status_types}) other suites depend on. The store
 * chain is found-or-created rather than deleted, because it is referenced by many other tables of
 * the shared PostgreSQL container, which is started once per JVM.</p>
 */
@SpringBootTest
@DisplayName("Employee Store Assignment Schema Integration Tests")
class EmployeeStoreAssignmentSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "EMP-STORE-ASSIGN-KEY";
    private static final String COMPANY_RFC = "EMPSA010101AB";
    private static final String REGION_CODE = "ESA";
    private static final String ZONE_CODE = "ESA";
    private static final String STORE_ONE_NAME = "Assignment Store One";
    private static final String STORE_TWO_NAME = "Assignment Store Two";

    @Autowired
    private Flyway flyway;

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

    // One test method per test instance (JUnit's default lifecycle), so this counter restarts at 0
    // for each test and the natural keys it builds never collide with the @BeforeEach-cleared tables.
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void resetAssignmentAndEmployeeTables() {
        // Leaf-first and unconditional: employee_store_assignments references employees; deleting
        // assignments before employees keeps the foreign key satisfiable. Every row of these two
        // tables is deleted, not a scoped subset, so every method starts from a wholly owned state.
        // The store tree, companies, statuses and status_types are shared fixtures and are never
        // touched here.
        jdbcTemplate.update("DELETE FROM employee_store_assignments");
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
    @DisplayName("creates employee_store_assignments with its eight columns, their types and the two named constraints")
    void employeeStoreAssignmentsTableHasItsColumnsAndNamedConstraints() {
        var columns = jdbcTemplate.query(
                """
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'employee_store_assignments'
                """,
                (rs, rowNum) -> new ColumnType(
                        rs.getString("column_name"), rs.getString("data_type"), rs.getString("is_nullable")));

        assertThat(columns)
                .as("employee_store_assignments columns, their data types and nullability")
                .containsExactlyInAnyOrder(
                        new ColumnType("id", "uuid", "NO"),
                        new ColumnType("employee_id", "uuid", "NO"),
                        new ColumnType("company_store_id", "uuid", "NO"),
                        new ColumnType("valid_from", "date", "NO"),
                        new ColumnType("valid_to", "date", "YES"),
                        new ColumnType("enabled", "boolean", "NO"),
                        new ColumnType("created_at", "timestamp without time zone", "NO"),
                        new ColumnType("updated_at", "timestamp without time zone", "NO"));

        var constraints = jdbcTemplate.query(
                """
                SELECT c.conname, c.contype::text AS contype FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public' AND t.relname = 'employee_store_assignments'
                  AND c.conname IN ('ck_employee_store_assignments_dates',
                                    'ex_employee_store_assignments_no_overlap')
                """, (rs, rowNum) -> new ConstraintType(rs.getString("conname"), rs.getString("contype")));

        assertThat(constraints)
                .as("named CHECK and EXCLUDE constraints of employee_store_assignments")
                .containsExactlyInAnyOrder(
                        new ConstraintType("ck_employee_store_assignments_dates", "c"),
                        new ConstraintType("ex_employee_store_assignments_no_overlap", "x"));
    }

    @Test
    @DisplayName("creates both named indexes of employee_store_assignments")
    void employeeStoreAssignmentsIndexesExist() {
        var indexes = jdbcTemplate.query("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'employee_store_assignments'
                  AND indexname IN ('idx_employee_store_assignments_employee_id',
                                    'idx_employee_store_assignments_company_store_id')
                """, (rs, rowNum) -> rs.getString("indexname"));

        assertThat(indexes)
                .as("named indexes of employee_store_assignments")
                .containsExactlyInAnyOrder(
                        "idx_employee_store_assignments_employee_id",
                        "idx_employee_store_assignments_company_store_id");
    }

    @Test
    @DisplayName(
            "ex_employee_store_assignments_no_overlap refuses an overlapping enabled assignment for one (employee, store)")
    void overlappingEnabledAssignmentIsRefusedByExclusionConstraint() {
        var fixtures = assignmentFixtures();
        insertAssignment(
                fixtures.employeeId(), fixtures.storeId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);

        assertViolatesConstraint(
                "ex_employee_store_assignments_no_overlap",
                """
                INSERT INTO employee_store_assignments
                    (id, employee_id, company_store_id, valid_from, valid_to, enabled)
                VALUES (?, ?, ?, DATE '2026-03-01', DATE '2026-09-30', true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.storeId());

        assertThat(countAssignments(fixtures.employeeId())).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "the assignment range is half-open: a start on the previous end date is accepted, one day earlier is refused")
    void assignmentRangeIsHalfOpenOnTheRight() {
        // Case (a), its own employee and store so its rows do not participate in case (b):
        // [2026-01-01, 2026-06-30) does not contain 2026-06-30, so an assignment starting that day
        // is accepted. Under '[]' this write would be refused.
        var accepted = assignmentFixtures();
        insertAssignment(
                accepted.employeeId(), accepted.storeId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);
        insertAssignment(accepted.employeeId(), accepted.storeId(), LocalDate.of(2026, 6, 30), null, true);
        assertThat(countAssignments(accepted.employeeId()))
                .as("an assignment starting on the previous end date is accepted")
                .isEqualTo(2);

        // Case (b), a second and independent employee and store: 2026-06-29 is inside the previous
        // range, so an assignment starting that day is refused.
        var refused = assignmentFixtures();
        insertAssignment(
                refused.employeeId(), refused.storeId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);

        assertViolatesConstraint(
                "ex_employee_store_assignments_no_overlap",
                """
                INSERT INTO employee_store_assignments
                    (id, employee_id, company_store_id, valid_from, enabled)
                VALUES (?, ?, ?, DATE '2026-06-29', true)
                """,
                UUID.randomUUID(),
                refused.employeeId(),
                refused.storeId());

        assertThat(countAssignments(refused.employeeId()))
                .as("an assignment starting one day before the previous end date is refused")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a soft-deleted assignment stops blocking its replacement because the constraint is partial")
    void softDeletedAssignmentDoesNotBlockItsReplacement() {
        var fixtures = assignmentFixtures();
        insertAssignment(
                fixtures.employeeId(), fixtures.storeId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), false);
        insertAssignment(
                fixtures.employeeId(), fixtures.storeId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);

        assertThat(countAssignments(fixtures.employeeId()))
                .as("the enabled replacement is accepted over the disabled assignment's window")
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "the overlap rule is per (employee, store): one employee assigned to two stores with overlapping dates is accepted (D1)")
    void overlapIsPerEmployeeAndStoreNotPerEmployee() {
        var fixtures = assignmentFixtures();
        insertAssignment(
                fixtures.employeeId(), fixtures.storeId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true);
        insertAssignment(
                fixtures.employeeId(),
                fixtures.otherStoreId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                true);

        assertThat(countAssignments(fixtures.employeeId()))
                .as("the same employee may hold two concurrently assigned stores")
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "the overlap rule is per (employee, store): two different employees may share one store over the same dates (D1)")
    void overlapIsPerEmployeeAndStoreNotPerStore() {
        // Two independent fixtures, which means two distinct employees. Both resolve the same store
        // through the find-or-create helper, so the rows below collide on the store and differ only
        // on the employee: under a store-only key the second write would be refused.
        var first = assignmentFixtures();
        var second = assignmentFixtures();
        assertThat(second.storeId())
                .as("both employees are assigned to the same store")
                .isEqualTo(first.storeId());

        insertAssignment(
                first.employeeId(), first.storeId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true);
        insertAssignment(
                second.employeeId(), second.storeId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true);

        assertThat(countAssignments(first.employeeId()))
                .as("the first employee's assignment is accepted in the shared store")
                .isEqualTo(1);
        assertThat(countAssignments(second.employeeId()))
                .as("a second employee may occupy the same store over the same dates")
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "ck_employee_store_assignments_dates refuses an inverted range while valid_to = valid_from is accepted")
    void checkConstraintRefusesInvertedRange() {
        var fixtures = assignmentFixtures();

        assertViolatesConstraint(
                "ck_employee_store_assignments_dates",
                """
                INSERT INTO employee_store_assignments
                    (id, employee_id, company_store_id, valid_from, valid_to, enabled)
                VALUES (?, ?, ?, DATE '2026-06-30', DATE '2026-01-01', true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.storeId());

        // The boundary: the CHECK is `valid_to >= valid_from`, so equality is accepted. A weakened
        // `>` rule would fail here and not only on the strict-before case above.
        insertAssignment(
                fixtures.employeeId(), fixtures.storeId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), true);

        assertThat(countAssignments(fixtures.employeeId()))
                .as("valid_to equal to valid_from is accepted")
                .isEqualTo(1);
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
     * Find-or-create the {@code company &rarr; country &rarr; region &rarr; zone} chain and two
     * distinct stores inside it, so a method can prove that two overlapping assignments of one
     * employee are accepted only when they target different stores.
     */
    private AssignmentFixtures assignmentFixtures() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Employee Store Assignment Schema Test Company")
                        .rfc(COMPANY_RFC)
                        .enabled(true)
                        .build()));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));

        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode(REGION_CODE)
                        .regionName("Employee Store Assignment Region")
                        .enabled(true)
                        .build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode(ZONE_CODE)
                        .zoneName("Employee Store Assignment Zone")
                        .enabled(true)
                        .build()));

        var storeId = findOrCreateStore(zone, STORE_ONE_NAME);
        var otherStoreId = findOrCreateStore(zone, STORE_TWO_NAME);

        var statusId = activeEmployeeStatusId();
        var n = sequence.incrementAndGet();
        var employeeId =
                insertEmployee(company.getId(), statusId, "EMP-SA-" + n, "assignment.employee" + n + "@example.com");
        return new AssignmentFixtures(employeeId, storeId, otherStoreId);
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
     * Inserts an assignment through raw SQL. {@code validTo} is {@code null} for an open-ended range,
     * which the INSERT expresses by omitting the column rather than by sending a typed null.
     */
    private UUID insertAssignment(
            UUID employeeId, UUID storeId, LocalDate validFrom, LocalDate validTo, boolean enabled) {
        var id = UUID.randomUUID();
        if (validTo == null) {
            jdbcTemplate.update("""
                    INSERT INTO employee_store_assignments
                        (id, employee_id, company_store_id, valid_from, enabled)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, employeeId, storeId, validFrom, enabled);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO employee_store_assignments
                        (id, employee_id, company_store_id, valid_from, valid_to, enabled)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, employeeId, storeId, validFrom, validTo, enabled);
        }
        return id;
    }

    /** Scoped count: {@code employee_store_assignments} rows of one employee, never the whole table. */
    private int countAssignments(UUID employeeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employee_store_assignments WHERE employee_id = ?", Integer.class, employeeId);
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

    private record ColumnType(String name, String type, String nullable) {}

    private record ConstraintType(String name, String type) {}

    private record AssignmentFixtures(UUID employeeId, UUID storeId, UUID otherStoreId) {}
}
