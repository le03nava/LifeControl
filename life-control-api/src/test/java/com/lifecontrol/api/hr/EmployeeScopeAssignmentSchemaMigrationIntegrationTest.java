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
import com.lifecontrol.api.hr.repository.EmployeeScopeAssignmentRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of {@code V26__employee_scope_assignments.sql} against real
 * PostgreSQL with Flyway enabled and {@code ddl-auto=validate}, so a green run proves the
 * scope-assignment schema landed exactly as the migration declares it and that the entity maps to it.
 *
 * <p>The service layer cannot prove these guarantees: they are enforced by the database itself. This
 * suite pins the table's columns and their types, the three <b>partial exclusion constraints</b> (one
 * per node column, because a single {@code EXCLUDE} cannot span three alternative columns),
 * {@code ck_employee_scope_assignments_one_node}, {@code ck_employee_scope_assignments_dates} and the
 * four named indexes. The load-bearing facts are behavioural: an overlapping enabled assignment for
 * the same node is refused while an adjacent one is accepted ({@code '[)'}), a soft-deleted row stops
 * blocking its replacement (the partial {@code WHERE}), two different nodes of one employee may
 * overlap (the invariant is per node, not per employee), and a row must set exactly one node column.
 * The constraints need the {@code btree_gist} extension that V21 installs.</p>
 *
 * <p>Cleanup is unconditional over the two tables this suite owns, in {@code @BeforeEach} <b>and</b>
 * {@code @AfterEach}: {@code employee_scope_assignments} references {@code employees}, so a row left
 * behind would break every other suite's own {@code DELETE FROM employees} against the shared
 * container. The node tree ({@code companies}, {@code company_countries}, {@code company_regions},
 * {@code company_zones}) is found-or-create and never deleted, because other tables of the shared
 * database reference it.</p>
 */
@SpringBootTest
@DisplayName("Employee Scope Assignment Schema Integration Tests")
class EmployeeScopeAssignmentSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "EMP-SCOPE-ASSIGN-KEY";
    private static final String COMPANY_RFC = "EMPSP010101AB";
    private static final String FIRST_REGION_CODE = "ESPA";
    private static final String FIRST_ZONE_CODE = "ESPA";
    private static final String SECOND_ZONE_CODE = "ESPB";

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
    private EmployeeScopeAssignmentRepository employeeScopeAssignmentRepository;

    // One test method per test instance (JUnit's default lifecycle), so this counter restarts at 0 for
    // each test and the natural keys it builds never collide with the @BeforeEach-cleared tables.
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void resetScopeAssignmentAndEmployeeTables() {
        deleteOwnedRows();
    }

    @AfterEach
    void leaveNothingBehind() {
        // A row of this table references employees, so a leftover would fail the unscoped
        // `DELETE FROM employees` of the suites that own that table — which is why this suite owns its
        // rows and only its rows, and removes them before and after every method.
        deleteOwnedRows();
    }

    /**
     * Deletes this suite's assignments and employees only, addressed by the {@code EMP-SCOPE-} employee
     * number prefix it writes. It deliberately does <b>not</b> delete the whole {@code employees} table
     * the way the store-assignment schema suite does: that suite owns the table, while this one shares
     * the JVM with suites whose contract rows would make an unscoped delete violate a foreign key.
     */
    private void deleteOwnedRows() {
        jdbcTemplate.update("""
                DELETE FROM employee_scope_assignments a
                USING employees e
                WHERE a.employee_id = e.id AND e.employee_number LIKE 'EMP-SCOPE-%'
                """);
        jdbcTemplate.update("DELETE FROM employees WHERE employee_number LIKE 'EMP-SCOPE-%'");
    }

    @Test
    @DisplayName("applies every migration through V27 and leaves no pending migration")
    void flywayHeadIsV27() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("27");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("creates employee_scope_assignments with its nine columns, their types and nullability")
    void tableHasItsColumnsAndTypes() {
        var columns = jdbcTemplate.query(
                """
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'employee_scope_assignments'
                """,
                (rs, rowNum) -> new ColumnType(
                        rs.getString("column_name"), rs.getString("data_type"), rs.getString("is_nullable")));

        assertThat(columns)
                .as("employee_scope_assignments columns, their data types and nullability")
                .containsExactlyInAnyOrder(
                        new ColumnType("id", "uuid", "NO"),
                        new ColumnType("employee_id", "uuid", "NO"),
                        new ColumnType("company_country_id", "uuid", "YES"),
                        new ColumnType("company_region_id", "uuid", "YES"),
                        new ColumnType("company_zone_id", "uuid", "YES"),
                        new ColumnType("valid_from", "date", "NO"),
                        new ColumnType("valid_to", "date", "YES"),
                        new ColumnType("enabled", "boolean", "NO"),
                        new ColumnType("created_at", "timestamp without time zone", "NO"),
                        new ColumnType("updated_at", "timestamp without time zone", "NO"));
    }

    @Test
    @DisplayName("carries three named EXCLUDE constraints, one per node column, plus the two named CHECKs")
    void namedConstraintsExist() {
        var constraints = jdbcTemplate.query(
                """
                SELECT c.conname, c.contype::text AS contype FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public' AND t.relname = 'employee_scope_assignments'
                  AND c.conname IN ('ck_employee_scope_assignments_dates',
                                    'ck_employee_scope_assignments_one_node',
                                    'ex_employee_scope_assignments_country_no_overlap',
                                    'ex_employee_scope_assignments_region_no_overlap',
                                    'ex_employee_scope_assignments_zone_no_overlap')
                """, (rs, rowNum) -> new ConstraintType(rs.getString("conname"), rs.getString("contype")));

        assertThat(constraints)
                .as("named CHECK and EXCLUDE constraints of employee_scope_assignments")
                .containsExactlyInAnyOrder(
                        new ConstraintType("ck_employee_scope_assignments_dates", "c"),
                        new ConstraintType("ck_employee_scope_assignments_one_node", "c"),
                        new ConstraintType("ex_employee_scope_assignments_country_no_overlap", "x"),
                        new ConstraintType("ex_employee_scope_assignments_region_no_overlap", "x"),
                        new ConstraintType("ex_employee_scope_assignments_zone_no_overlap", "x"));
    }

    @Test
    @DisplayName("creates the four named indexes of employee_scope_assignments")
    void indexesExist() {
        var indexes = jdbcTemplate.query("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'employee_scope_assignments'
                  AND indexname IN ('idx_employee_scope_assignments_employee_id',
                                    'idx_employee_scope_assignments_company_country_id',
                                    'idx_employee_scope_assignments_company_region_id',
                                    'idx_employee_scope_assignments_company_zone_id')
                """, (rs, rowNum) -> rs.getString("indexname"));

        assertThat(indexes)
                .as("named indexes of employee_scope_assignments")
                .containsExactlyInAnyOrder(
                        "idx_employee_scope_assignments_employee_id",
                        "idx_employee_scope_assignments_company_country_id",
                        "idx_employee_scope_assignments_company_region_id",
                        "idx_employee_scope_assignments_company_zone_id");
    }

    @Test
    @DisplayName("ex_employee_scope_assignments_zone_no_overlap refuses an overlapping enabled zone assignment")
    void overlappingZoneAssignmentIsRefused() {
        var fixtures = fixtures();
        insertZoneAssignment(
                fixtures.employeeId(), fixtures.zoneId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);

        assertViolatesConstraint(
                "ex_employee_scope_assignments_zone_no_overlap",
                """
                INSERT INTO employee_scope_assignments
                    (id, employee_id, company_zone_id, valid_from, valid_to, enabled)
                VALUES (?, ?, ?, DATE '2026-03-01', DATE '2026-09-30', true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.zoneId());

        assertThat(countAssignments(fixtures.employeeId())).isEqualTo(1);
    }

    @Test
    @DisplayName("each level has its own exclusion constraint: an overlapping country assignment is refused too")
    void overlappingCountryAssignmentIsRefusedByItsOwnConstraint() {
        var fixtures = fixtures();
        insertCountryAssignment(
                fixtures.employeeId(),
                fixtures.companyCountryId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
                true);

        assertViolatesConstraint(
                "ex_employee_scope_assignments_country_no_overlap",
                """
                INSERT INTO employee_scope_assignments
                    (id, employee_id, company_country_id, valid_from, valid_to, enabled)
                VALUES (?, ?, ?, DATE '2026-06-01', NULL, true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.companyCountryId());

        assertThat(countAssignments(fixtures.employeeId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the region level has its own constraint: an overlapping region assignment is refused")
    void overlappingRegionAssignmentIsRefusedByItsOwnConstraint() {
        var fixtures = fixtures();
        insertRegionAssignment(
                fixtures.employeeId(), fixtures.regionId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);

        assertViolatesConstraint(
                "ex_employee_scope_assignments_region_no_overlap",
                """
                INSERT INTO employee_scope_assignments
                    (id, employee_id, company_region_id, valid_from, valid_to, enabled)
                VALUES (?, ?, ?, DATE '2026-06-01', NULL, true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.regionId());

        assertThat(countAssignments(fixtures.employeeId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the range is half-open: a start on the previous end date is accepted, one day earlier refused")
    void rangeIsHalfOpenOnTheRight() {
        // Case (a), its own employee and zone so its rows do not participate in case (b):
        // [2026-01-01, 2026-06-30) does not contain 2026-06-30, so an assignment starting that day is
        // accepted. Under '[]' this write would be refused.
        var accepted = fixtures();
        insertZoneAssignment(
                accepted.employeeId(), accepted.zoneId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);
        insertZoneAssignment(accepted.employeeId(), accepted.zoneId(), LocalDate.of(2026, 6, 30), null, true);
        assertThat(countAssignments(accepted.employeeId()))
                .as("an assignment starting on the previous end date is accepted")
                .isEqualTo(2);

        var refused = fixtures();
        insertZoneAssignment(
                refused.employeeId(), refused.zoneId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);

        assertViolatesConstraint(
                "ex_employee_scope_assignments_zone_no_overlap",
                """
                INSERT INTO employee_scope_assignments
                    (id, employee_id, company_zone_id, valid_from, enabled)
                VALUES (?, ?, ?, DATE '2026-06-29', true)
                """,
                UUID.randomUUID(),
                refused.employeeId(),
                refused.zoneId());

        assertThat(countAssignments(refused.employeeId()))
                .as("an assignment starting one day before the previous end date is refused")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a soft-deleted assignment stops blocking its replacement because the constraint is partial")
    void softDeletedAssignmentDoesNotBlockItsReplacement() {
        var fixtures = fixtures();
        insertZoneAssignment(
                fixtures.employeeId(), fixtures.zoneId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), false);
        insertZoneAssignment(
                fixtures.employeeId(), fixtures.zoneId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);

        assertThat(countAssignments(fixtures.employeeId()))
                .as("the enabled replacement is accepted over the disabled assignment's window")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the overlap rule is per (employee, node): two nodes of one employee may overlap")
    void overlapIsPerNodeNotPerEmployee() {
        var fixtures = fixtures();
        insertZoneAssignment(
                fixtures.employeeId(), fixtures.zoneId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true);
        insertZoneAssignment(
                fixtures.employeeId(),
                fixtures.otherZoneId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                true);

        assertThat(countAssignments(fixtures.employeeId()))
                .as("the same employee may hold two concurrently assigned zones")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a row at a different level never blocks a row at another level of the same employee")
    void levelsDoNotBlockEachOther() {
        var fixtures = fixtures();
        insertCountryAssignment(
                fixtures.employeeId(),
                fixtures.companyCountryId(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                true);
        insertRegionAssignment(
                fixtures.employeeId(), fixtures.regionId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true);
        insertZoneAssignment(
                fixtures.employeeId(), fixtures.zoneId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true);

        assertThat(countAssignments(fixtures.employeeId()))
                .as("one country, one region and one zone of the same employee are three legal rows")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("ck_employee_scope_assignments_one_node refuses zero nodes and two nodes")
    void oneNodeConstraintIsEnforced() {
        var fixtures = fixtures();

        assertViolatesConstraint(
                "ck_employee_scope_assignments_one_node", """
                INSERT INTO employee_scope_assignments
                    (id, employee_id, valid_from, enabled)
                VALUES (?, ?, DATE '2026-01-01', true)
                """, UUID.randomUUID(), fixtures.employeeId());

        assertViolatesConstraint(
                "ck_employee_scope_assignments_one_node",
                """
                INSERT INTO employee_scope_assignments
                    (id, employee_id, company_country_id, company_zone_id, valid_from, enabled)
                VALUES (?, ?, ?, ?, DATE '2026-01-01', true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.companyCountryId(),
                fixtures.zoneId());

        assertThat(countAssignments(fixtures.employeeId())).isZero();

        // The boundary: exactly one node is accepted, which is what makes the level the row's shape.
        insertZoneAssignment(fixtures.employeeId(), fixtures.zoneId(), LocalDate.of(2026, 1, 1), null, true);
        assertThat(countAssignments(fixtures.employeeId())).isEqualTo(1);
    }

    @Test
    @DisplayName("ck_employee_scope_assignments_dates refuses an inverted range while equality is accepted")
    void datesCheckRefusesInvertedRange() {
        var fixtures = fixtures();

        assertViolatesConstraint(
                "ck_employee_scope_assignments_dates",
                """
                INSERT INTO employee_scope_assignments
                    (id, employee_id, company_zone_id, valid_from, valid_to, enabled)
                VALUES (?, ?, ?, DATE '2026-06-30', DATE '2026-01-01', true)
                """,
                UUID.randomUUID(),
                fixtures.employeeId(),
                fixtures.zoneId());

        insertZoneAssignment(
                fixtures.employeeId(), fixtures.zoneId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), true);

        assertThat(countAssignments(fixtures.employeeId()))
                .as("valid_to equal to valid_from is accepted")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the covering-date finder returns the live rows with their whole chain already resolved")
    void coveringDateFinderLoadsTheFetchGraph() {
        var fixtures = fixtures();
        insertZoneAssignment(
                fixtures.employeeId(), fixtures.zoneId(), LocalDate.now().minusDays(10), null, true);
        // A row closed yesterday does not cover today, and a disabled row never does: both are the
        // finder's predicate, not the derivation's.
        insertRegionAssignment(
                fixtures.employeeId(),
                fixtures.regionId(),
                LocalDate.now().minusDays(60),
                LocalDate.now().minusDays(1),
                true);
        insertCountryAssignment(
                fixtures.employeeId(),
                fixtures.companyCountryId(),
                LocalDate.now().minusDays(10),
                null,
                false);

        var covering = employeeScopeAssignmentRepository.findEnabledAssignmentsCoveringDate(
                fixtures.employeeId(), LocalDate.now());

        assertThat(covering).hasSize(1);
        var row = covering.get(0);
        // Read outside a transaction on purpose: these assertions only hold because the query fetches
        // the chain, which is the point of the fetch graph.
        assertThat(row.getCompanyZone().getId()).isEqualTo(fixtures.zoneId());
        assertThat(row.getCompanyZone().getCompanyRegion().getId()).isEqualTo(fixtures.regionId());
        assertThat(row.getCompanyZone().getCompanyRegion().getCompanyCountry().getId())
                .isEqualTo(fixtures.companyCountryId());
        assertThat(row.getCompanyZone()
                        .getCompanyRegion()
                        .getCompanyCountry()
                        .getCompany()
                        .getCompanyName())
                .isEqualTo("Employee Scope Assignment Schema Test Company");
        assertThat(row.getCompanyZone()
                        .getCompanyRegion()
                        .getCompanyCountry()
                        .getCountry()
                        .getCountryName())
                .isNotNull();
    }

    @Test
    @DisplayName("the predecessor finder is node-scoped: another node's live row is never returned")
    void predecessorFinderIsNodeScoped() {
        var fixtures = fixtures();
        insertZoneAssignment(fixtures.employeeId(), fixtures.zoneId(), LocalDate.of(2026, 1, 1), null, true);
        insertZoneAssignment(fixtures.employeeId(), fixtures.otherZoneId(), LocalDate.of(2026, 1, 1), null, true);

        var rows = employeeScopeAssignmentRepository.findEnabledAssignmentForNodeCoveringDate(
                fixtures.employeeId(), fixtures.zoneId(), LocalDate.of(2026, 3, 1));

        // The close-the-previous rule is only safe if this finder answers for ONE node: a second live
        // zone row of the same employee must not be closed by opening an assignment for this one.
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCompanyZone().getId()).isEqualTo(fixtures.zoneId());
    }

    @Test
    @DisplayName(
            "the history finder returns every row of the employee, newest first, and hides disabled ones on request")
    void historyFinderHonoursIncludeDisabled() {
        var fixtures = fixtures();
        insertZoneAssignment(
                fixtures.employeeId(), fixtures.zoneId(), LocalDate.now().minusDays(30), null, true);
        insertRegionAssignment(
                fixtures.employeeId(),
                fixtures.regionId(),
                LocalDate.now().minusDays(90),
                LocalDate.now().minusDays(31),
                false);

        assertThat(employeeScopeAssignmentRepository.findEmployeeAssignments(fixtures.employeeId(), false))
                .as("the soft-deleted row is hidden by default")
                .hasSize(1);
        var history = employeeScopeAssignmentRepository.findEmployeeAssignments(fixtures.employeeId(), true);
        assertThat(history).as("the whole history is the record").hasSize(2);
        assertThat(history.get(0).getValidFrom()).isAfter(history.get(1).getValidFrom());
        assertThat(history.get(0).getEnabled()).isTrue();
        assertThat(history.get(1).getEnabled()).isFalse();
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
     * Find-or-create the {@code company &rarr; country &rarr; region &rarr; zone} tree with two regions
     * and two zones, plus a fresh employee, so a method can prove that overlapping assignments of one
     * employee are accepted only when they target different nodes.
     */
    private Fixtures fixtures() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Employee Scope Assignment Schema Test Company")
                        .rfc(COMPANY_RFC)
                        .enabled(true)
                        .build()));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));

        var region = findOrCreateRegion(companyCountry);
        var zone = findOrCreateZone(region, FIRST_ZONE_CODE, "Employee Scope Zone A");
        var otherZone = findOrCreateZone(region, SECOND_ZONE_CODE, "Employee Scope Zone B");

        var statusId = activeEmployeeStatusId();
        var n = sequence.incrementAndGet();
        var employeeId =
                insertEmployee(company.getId(), statusId, "EMP-SCOPE-" + n, "scope.employee" + n + "@example.com");
        return new Fixtures(employeeId, companyCountry.getId(), region.getId(), zone.getId(), otherZone.getId());
    }

    private CompanyRegion findOrCreateRegion(CompanyCountry companyCountry) {
        return companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode(FIRST_REGION_CODE)
                        .regionName("Employee Scope Region A")
                        .enabled(true)
                        .build()));
    }

    private CompanyZone findOrCreateZone(CompanyRegion region, String code, String name) {
        return companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .filter(candidate -> code.equals(candidate.getZoneCode()))
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode(code)
                        .zoneName(name)
                        .enabled(true)
                        .build()));
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
     * Inserts a zone assignment through raw SQL. {@code validTo} is {@code null} for an open-ended
     * range, which the INSERT expresses by omitting the column rather than by sending a typed null.
     */
    private UUID insertZoneAssignment(
            UUID employeeId, UUID zoneId, LocalDate validFrom, LocalDate validTo, boolean enabled) {
        var id = UUID.randomUUID();
        if (validTo == null) {
            jdbcTemplate.update("""
                    INSERT INTO employee_scope_assignments
                        (id, employee_id, company_zone_id, valid_from, enabled)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, employeeId, zoneId, validFrom, enabled);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO employee_scope_assignments
                        (id, employee_id, company_zone_id, valid_from, valid_to, enabled)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, employeeId, zoneId, validFrom, validTo, enabled);
        }
        return id;
    }

    private UUID insertRegionAssignment(
            UUID employeeId, UUID regionId, LocalDate validFrom, LocalDate validTo, boolean enabled) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO employee_scope_assignments
                    (id, employee_id, company_region_id, valid_from, valid_to, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, employeeId, regionId, validFrom, validTo, enabled);
        return id;
    }

    private UUID insertCountryAssignment(
            UUID employeeId, UUID companyCountryId, LocalDate validFrom, LocalDate validTo, boolean enabled) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO employee_scope_assignments
                    (id, employee_id, company_country_id, valid_from, valid_to, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, employeeId, companyCountryId, validFrom, validTo, enabled);
        return id;
    }

    /** Scoped count: {@code employee_scope_assignments} rows of one employee, never the whole table. */
    private int countAssignments(UUID employeeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employee_scope_assignments WHERE employee_id = ?", Integer.class, employeeId);
    }

    /**
     * Asserts the raw write is rejected by a real PostgreSQL constraint and that the reported error names
     * the exact constraint. The most specific cause carries the {@code PSQLException} message.
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

    private record Fixtures(UUID employeeId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID otherZoneId) {}
}
