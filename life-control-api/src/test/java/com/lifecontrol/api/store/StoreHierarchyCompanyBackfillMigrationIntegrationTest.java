package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Provenance-level verification of the derivation each CONTRACT migration of this unit performs — the
 * claim their headers make in their first lines: the owner is <b>derived from the parent</b> and
 * <b>nothing is discarded</b>, which is what separates this unit from {@code V40} and {@code V42}.
 *
 * <p>It runs on a <b>separate scratch database</b> created on this suite's singleton container, for the
 * same reason {@code EmployeeStoreScopeBackfillMigrationIntegrationTest} does: the derivation has to be
 * observed from the other side of the migration. The shared test database boots straight to head, where
 * the columns are already {@code NOT NULL} and the {@code UPDATE}s match nothing — a green run there
 * would prove the constraint exists and say nothing about whether the derivation works. Here the
 * database is held at <b>V43</b> (this unit's pre-head: the supplier chain is in, the store hierarchy has
 * no owner yet), a company tree is seeded with no {@code company_id} anywhere below the country, and the
 * migration chain then runs to head.</p>
 *
 * <p>Two facts are asserted per level, and the second is the one a truncating migration would fail: the
 * seeded row <b>survives</b> with the company its parent answers, and the row count is unchanged. The
 * scratch database is dropped in {@link AfterEach} unconditionally, so a failed assertion cannot leak a
 * database into the container every other suite in the JVM shares.</p>
 */
@DisplayName("Store Hierarchy Company Backfill Integration Tests")
class StoreHierarchyCompanyBackfillMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: the supplier chain is in, the hierarchy has no owner yet. */
    private static final String PRE_UNIT_HEAD = "43";

    private String databaseName;
    private String jdbcUrl;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_store_hierarchy_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(databaseName);
        jdbcUrl = jdbcUrlFor(databaseName);
        jdbcTemplate =
                new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterEach
    void dropScratchDatabase() {
        if (databaseName == null) {
            return;
        }
        // FORCE terminates any leftover session (PostgreSQL 13+), so a connection the pool did not return
        // cannot turn this cleanup into a failure that hides the test's own result.
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
    @DisplayName("V45 derives every region's company from its country and discards no region")
    void regionContractDerivesFromCountryAndDiscardsNothing() {
        migrateTo(PRE_UNIT_HEAD);

        var seeded = seedCompanyTree();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM company_regions", Integer.class))
                .as("the fixture wrote the region at V43, where the table still has no owner column")
                .isEqualTo(1);

        migrateToHead();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM company_regions WHERE id = ?", UUID.class, seeded.regionId()))
                .as("the owner is the fact of the row's own country, not an invented one")
                .isEqualTo(seeded.companyId());

        assertThat(jdbcTemplate.queryForList("SELECT id FROM company_regions", UUID.class))
                .as("the contract derives rather than discards: the seeded region is still there")
                .isEqualTo(List.of(seeded.regionId()));
    }

    // --- migration control ---

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private void migrateToHead() {
        Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();
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

    private record Seeded(UUID companyId, UUID regionId) {}

    /**
     * Seeds exactly what the pre-V45 schema could hold: a company, its company country and one region
     * that names only its country. No `company_id` is written anywhere below the country, because the
     * column does not exist yet at this version.
     */
    private Seeded seedCompanyTree() {
        var countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);

        var companyId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO companies (id, company_key, company_name, rfc, enabled)
                VALUES (?, 'STORE-BACKFILL', 'Store Backfill Company', 'STBF010101AB1', true)
                """, companyId);

        var companyCountryId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                companyCountryId,
                companyId,
                countryId);

        var regionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO company_regions (id, company_country_id, region_code, region_name, enabled)
                VALUES (?, ?, 'BF1', 'Backfill Region', true)
                """, regionId, companyCountryId);

        return new Seeded(companyId, regionId);
    }
}
