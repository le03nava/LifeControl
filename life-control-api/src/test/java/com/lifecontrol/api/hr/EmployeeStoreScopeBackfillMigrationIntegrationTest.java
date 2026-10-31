package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Provenance-level verification of the store backfill in
 * {@code V32__employee_scope_assignments_store_level.sql} — spec <b>S6</b>: "the copy runs inside the
 * migration and a row-count guard turns a partial copy into a failed migration instead of a silent
 * loss of tenancy; nothing else about a copied row changes: its dates, its {@code enabled} flag and its
 * close state survive verbatim."
 *
 * <p>It runs on a <b>separate scratch database</b> created on this suite's singleton container, for one
 * reason: the migration has to be observed in the middle. The shared test database boots straight to
 * head, so the only way to hold a database at <b>V31</b> — after {@code employee_store_assignments}
 * exists (V23) and before the copy runs (V32) — seed real rows into it, and then let the copy run is to
 * own the whole history of a database. That is what this test does: it creates a database, migrates it
 * with {@code target(31)}, seeds a company tree, an employee and two store assignments through JDBC,
 * migrates to head, and reads both tables back.</p>
 *
 * <p>Preserving the <b>id</b> is the load-bearing assertion: it is what makes the copy auditable
 * against its source and what keeps a support reference to a row valid, and it is the fact a
 * {@code INSERT … SELECT} that let the column default would silently lose.
 * {@code created_at}/{@code updated_at} are asserted for the same reason from the other direction — a
 * copy must not claim the migration's own timestamp — and {@code valid_from}/{@code valid_to}/
 * {@code enabled} are asserted because they decide whether the row still contributes a claim, including
 * the closed row (a non-null {@code valid_to}) whose close state has to survive verbatim.</p>
 *
 * <p>The scratch database is dropped in {@link AfterEach} unconditionally, so a failed assertion cannot
 * leak a database into the container. The singleton container itself is started by
 * {@link AbstractPostgresIntegrationTest} and is never stopped here: every other suite in the JVM shares
 * it.</p>
 */
@DisplayName("Employee Store Scope Backfill (V32) Integration Tests")
class EmployeeStoreScopeBackfillMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The migration that copies, and the head this suite stops at: V33 drops the source table. */
    private static final String V32 = "32";

    private static final String SOURCE_TABLE = "employee_store_assignments";
    private static final String TARGET_TABLE = "employee_scope_assignments";

    private static final LocalDateTime OPEN_CREATED_AT = LocalDateTime.of(2025, 3, 4, 10, 11, 12, 111_000_000);
    private static final LocalDateTime OPEN_UPDATED_AT = LocalDateTime.of(2025, 4, 5, 11, 12, 13, 222_000_000);
    private static final LocalDateTime CLOSED_CREATED_AT = LocalDateTime.of(2024, 7, 8, 9, 10, 11, 333_000_000);
    private static final LocalDateTime CLOSED_UPDATED_AT = LocalDateTime.of(2025, 1, 2, 3, 4, 5, 444_000_000);

    private String databaseName;
    private String jdbcUrl;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_v32_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(databaseName);
        jdbcUrl = jdbcUrlFor(databaseName);
        var scratchDataSource = new DriverManagerDataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(scratchDataSource);
        // This suite owns a database of its own and creates it with the container's SUPERUSER, so no
        // ADR-0005 policy applies here and the scope has nothing to filter. The boundary is bound anyway
        // because `TenantFixtures` states one rule without exceptions — a raw write needs a transaction to
        // carry it — and a suite that skips the binding would fail on the helper's refusal rather than on
        // the migration it is here to measure.
        TenantFixtures.bindTransactionManager(new DataSourceTransactionManager(scratchDataSource));
    }

    @AfterEach
    void dropScratchDatabase() {
        if (databaseName == null) {
            return;
        }
        // FORCE terminates any leftover session (PostgreSQL 13+), so a connection the pool did not
        // return cannot turn this cleanup into a failure that hides the test's own result.
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        } catch (Exception e) {
            throw new IllegalStateException("could not drop scratch database " + databaseName, e);
        } finally {
            databaseName = null;
        }
    }

    @Test
    @DisplayName("copies every store row into the scope table with its id, dates, flag and timestamps intact")
    void copiesEveryStoreRowVerbatim() {
        migrateTo("31");
        var seeded = seedStoreAssignments();

        var sourceRows = readSourceRows();
        assertThat(sourceRows)
                .as("the fixture wrote one open-ended and one closed store assignment at V31")
                .hasSize(2);

        migrateTo(V32);

        var targetRows = readTargetRows();
        assertThat(targetRows)
                .as("every store row of the source table is a store row of the scope table")
                .hasSize(2);

        assertThat(targetRows)
                .as("the copy preserves id, employee, store, dates, enabled flag and both timestamps")
                .containsExactlyInAnyOrderElementsOf(sourceRows);

        assertThat(targetRows)
                .as("every copied row carries the store it was assigned, never a null node")
                .allSatisfy(row -> assertThat(row.storeId()).isEqualTo(seeded.storeId()));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'public' AND table_name = ?",
                        Integer.class,
                        SOURCE_TABLE))
                .as("V32 does not drop employee_store_assignments: the drop is V33's own reviewed step")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + SOURCE_TABLE, Integer.class))
                .as("the source table keeps its rows too, so the copy is auditable against it")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the copied close state decides contribution: the closed row is still closed, open stays open")
    void copiesTheCloseStateVerbatim() {
        migrateTo("31");
        var seeded = seedStoreAssignments();
        migrateTo(V32);

        var byId = readTargetRows().stream().collect(Collectors.toMap(Row::id, row -> row));

        assertThat(byId.get(seeded.openId()).validTo())
                .as("open-ended stays open-ended")
                .isNull();
        assertThat(byId.get(seeded.openId()).enabled()).isTrue();
        assertThat(byId.get(seeded.openId()).validFrom()).isEqualTo(LocalDate.of(2026, 1, 1));

        assertThat(byId.get(seeded.closedId()).validTo())
                .as("a closed row keeps the exact exclusive bound it was closed with")
                .isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(byId.get(seeded.closedId()).validFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(byId.get(seeded.closedId()).enabled()).isTrue();
        assertThat(byId.get(seeded.closedId()).createdAt()).isEqualTo(CLOSED_CREATED_AT);
        assertThat(byId.get(seeded.closedId()).updatedAt()).isEqualTo(CLOSED_UPDATED_AT);
    }

    @Test
    @DisplayName("copies a disabled duplicate and a second employee's row: the guard counts rows, not pairs")
    void copiesDuplicateAndSecondEmployeeRows() {
        migrateTo("31");
        var seeded = seedStoreAssignments();

        // The source table's exclusion constraint is partial on `enabled`, so two rows for one
        // (employee, store) pair covering the same window are legal while only one of them is enabled.
        // The target's fourth constraint is partial on the same column, so the copy carries both, and
        // the guard compares row counts rather than joined pairs — which is exactly the case the
        // guard's own comment claims is safe, and is why it is pinned instead of argued.
        insertSourceRow(
                UUID.randomUUID(),
                seeded.employeeId(),
                seeded.storeId(),
                LocalDate.of(2026, 1, 1),
                null,
                OPEN_CREATED_AT,
                OPEN_UPDATED_AT,
                false);

        // A second employee in the same store over the same window: the invariant is per
        // (employee, store), so several people in one store are legal and one person's copy cannot
        // swallow another's row.
        var secondEmployeeId = insertEmployee(seeded.companyId(), "EMP-V32-BF-2", "v32.backfill.2@example.com");
        insertSourceRow(
                UUID.randomUUID(),
                secondEmployeeId,
                seeded.storeId(),
                LocalDate.of(2026, 1, 1),
                null,
                OPEN_CREATED_AT,
                OPEN_UPDATED_AT,
                true);

        assertThat(readSourceRows())
                .as("the fixture wrote the two original rows plus the disabled duplicate and the second employee's")
                .hasSize(4);

        migrateTo(V32);

        assertThat(readTargetRows())
                .as("every source row is copied verbatim, including what a join would collapse")
                .containsExactlyInAnyOrderElementsOf(readSourceRows());
    }

    // --- migration control ---

    private void migrateTo(String target) {
        flyway(target).migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private void createDatabase(String name) throws Exception {
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
    }

    /** The container's own (already migrated) database, used only as the connection to administer with. */
    private String adminJdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /** The container's JDBC URL with its database name replaced by the scratch one. */
    private String jdbcUrlFor(String name) {
        var base = POSTGRES.getJdbcUrl();
        var queryStart = base.indexOf('?');
        var withoutQuery = queryStart < 0 ? base : base.substring(0, queryStart);
        var suffix = queryStart < 0 ? "" : base.substring(queryStart);
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + name + suffix;
    }

    // --- fixtures ---

    private record Seeded(UUID openId, UUID closedId, UUID storeId, UUID employeeId, UUID companyId) {}

    private Seeded seedStoreAssignments() {
        var countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);

        var companyId = TenantFixtures.insertCompany(
                jdbcTemplate, UUID.randomUUID(), "V32-BACKFILL", "V32 Backfill Company", "V32BF010101AB");

        var companyCountryId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                companyCountryId,
                companyId,
                countryId);

        var regionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO company_regions (id, company_country_id, region_code, region_name, enabled)
                VALUES (?, ?, 'V32R', 'V32 Backfill Region', true)
                """, regionId, companyCountryId);

        var zoneId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, zone_code, zone_name, enabled)
                VALUES (?, ?, 'V32Z', 'V32 Backfill Zone', true)
                """, zoneId, regionId);

        var storeId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO company_stores (id, company_zone_id, store_name, enabled) VALUES (?, ?, ?, true)",
                storeId,
                zoneId,
                "V32 Backfill Store");

        var employeeId = insertEmployee(companyId, "EMP-V32-BF-1", "v32.backfill@example.com");

        var openId = UUID.randomUUID();
        insertSourceRow(openId, employeeId, storeId, LocalDate.of(2026, 1, 1), null, OPEN_CREATED_AT, OPEN_UPDATED_AT);

        // The closed row ends the day before the open one starts: the source table carries its own
        // partial exclusion constraint per (employee, store), so a fixture that overlapped them could
        // never have existed and would prove nothing about the copy.
        var closedId = UUID.randomUUID();
        insertSourceRow(
                closedId,
                employeeId,
                storeId,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31),
                CLOSED_CREATED_AT,
                CLOSED_UPDATED_AT);

        return new Seeded(openId, closedId, storeId, employeeId, companyId);
    }

    private UUID activeEmployeeStatusId() {
        return jdbcTemplate.queryForObject("""
                SELECT s.id FROM statuses s
                JOIN status_types st ON st.id = s.status_type_id
                WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
                  AND LOWER(s.status_name) = LOWER('Active')
                """, UUID.class);
    }

    private UUID insertEmployee(UUID companyId, String employeeNumber, String email) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, ?, 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                """, id, companyId, employeeNumber, email, activeEmployeeStatusId());
        return id;
    }

    private void insertSourceRow(
            UUID id,
            UUID employeeId,
            UUID storeId,
            LocalDate validFrom,
            LocalDate validTo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        insertSourceRow(id, employeeId, storeId, validFrom, validTo, createdAt, updatedAt, true);
    }

    private void insertSourceRow(
            UUID id,
            UUID employeeId,
            UUID storeId,
            LocalDate validFrom,
            LocalDate validTo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean enabled) {
        jdbcTemplate.update(
                """
                INSERT INTO employee_store_assignments
                    (id, employee_id, company_store_id, valid_from, valid_to, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                employeeId,
                storeId,
                validFrom,
                validTo,
                enabled,
                Timestamp.valueOf(createdAt),
                Timestamp.valueOf(updatedAt));
    }

    // --- reads ---

    private record Row(
            UUID id,
            UUID employeeId,
            UUID storeId,
            LocalDate validFrom,
            LocalDate validTo,
            Boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    private List<Row> readSourceRows() {
        return jdbcTemplate.query(
                """
                SELECT id, employee_id, company_store_id, valid_from, valid_to, enabled, created_at, updated_at
                FROM employee_store_assignments
                """,
                (rs, rowNum) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getObject("company_store_id", UUID.class),
                        rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class),
                        rs.getObject("enabled", Boolean.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)));
    }

    private List<Row> readTargetRows() {
        return jdbcTemplate.query(
                """
                SELECT id, employee_id, company_store_id, valid_from, valid_to, enabled, created_at, updated_at
                FROM employee_scope_assignments
                WHERE company_store_id IS NOT NULL
                """,
                (rs, rowNum) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getObject("company_store_id", UUID.class),
                        rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class),
                        rs.getObject("enabled", Boolean.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)));
    }
}
