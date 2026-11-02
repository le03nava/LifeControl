package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verification of {@code V55__variant_measure_unit_required.sql}, the schema half of ADR-0006 decision 3.
 *
 * <p><b>Why a scratch database.</b> The one thing this migration may not do is guess: ADR-0006's
 * migration step 2 makes the null-unit rows a <b>report that aborts</b>, and a shared test database that
 * boots straight to head never holds such a row — every suite there declares a unit, because the column
 * refuses anything else once the migration has run. So the database is held at {@code V54} (the head this
 * unit starts from, where the column is still nullable), a catalogue is seeded the way the pre-unit tree
 * could hold it, and the chain then runs to head.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}.</b> Same reason as the {@code V54} suite: {@code V52}
 * calls {@code ENABLE} <b>and {@code FORCE}</b> on every table it arms and {@code V53} arms
 * {@code products} and {@code product_variants}, so the report this migration runs matches <b>zero</b> rows
 * unless the migration opens a {@code NO FORCE} window first. The container's superuser bypasses the
 * policies whatever they say, so a report asserted under it would look green while delivery's
 * {@code NOSUPERUSER} owner listed nobody and the operator had no row to fix. Both cases below therefore
 * run as the delivery role.</p>
 */
@DisplayName("Product variant measure unit migration (V55)")
class ProductVariantMeasureUnitMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: {@code measure_unit_id} is still nullable there. */
    private static final String PRE_UNIT_HEAD = "54";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The {@code PRODUCT} unit {@code V3} seeds as {@code Pieza}, which is a real catalog row. */
    private static final String SEEDED_UNIT_SAT_CODE = "H87";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_variant_unit_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName + " OWNER " + OWNER);
        }
        jdbcUrl = jdbcUrlFor(databaseName);
    }

    @AfterEach
    void dropScratchDatabase() {
        if (databaseName == null) {
            return;
        }
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

    // --- cases ---

    @Test
    @DisplayName("a variant with no unit makes the migration abort, name it, and leave the column nullable")
    void abortsAndNamesEveryVariantWithoutAUnit() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var bread = insertProduct(company, "V55-NO-UNIT", "A loaf of bread");
        var breadVariant = insertVariant(company, bread, "7509999900001", "Unica");
        var cheese = insertProduct(company, "V55-NO-UNIT-2", "A wheel of cheese");
        var cheeseVariant = insertVariant(company, cheese, "7509999900002", "Entera");

        assertThatThrownBy(this::migrateToHead)
                .as("the report aborts the migration instead of defaulting a unit nobody declared")
                .isInstanceOf(FlywayException.class)
                // The exact shape the operator reads: sku / variant_name (variant id), for every row.
                .hasStackTraceContaining("V55-NO-UNIT / Unica (variant " + breadVariant + ")")
                .hasStackTraceContaining("V55-NO-UNIT-2 / Entera (variant " + cheeseVariant + ")")
                .hasStackTraceContaining("Declare the unit")
                .hasStackTraceContaining("re-run");

        // Atomic with the refusal: the column is still nullable, so nothing was half-applied.
        assertThat(nullable("product_variants", "measure_unit_id"))
                .as("the refusal leaves the column exactly as it was")
                .isEqualTo("YES");
        // And nothing was rewritten: the unit of a reported variant is still the absence it always was.
        assertThat(measureUnitOf(company, breadVariant)).isNull();
        assertThat(measureUnitOf(company, cheeseVariant)).isNull();
    }

    @Test
    @DisplayName("with every variant declaring a unit the migration applies, the column stops accepting null")
    void appliesOnceEveryVariantDeclaresAUnit() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        // MULTI, because this product carries two variants: since ADR-0006 decision 4 and V56's trigger a
        // SIMPLE product refuses the second one, and this case is about the unit column rather than the
        // mode.
        var product = insertProduct(company, "V55-DECLARED", "A shoe", "MULTI");
        var unit = seededUnitId();
        var first = insertVariantWithUnit(company, product, "7509999910001", "Talla 40", unit);
        var second = insertVariantWithUnit(company, product, "7509999910002", "Talla 41", unit);

        migrateToHead();

        assertThat(nullable("product_variants", "measure_unit_id"))
                .as("ADR-0006 decision 3: a quantity without a unit is a meaningless number")
                .isEqualTo("NO");
        assertThat(measureUnitOf(company, first)).isEqualTo(unit);
        assertThat(measureUnitOf(company, second)).isEqualTo(unit);

        // The rule the column now states on its own, without the service: no unit, no row.
        assertThatThrownBy(() -> execute(
                        company,
                        "INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name) VALUES ('"
                                + UUID.randomUUID() + "', '" + company + "', '" + product + "', '7509999910003', "
                                + "'Talla 42')"))
                .hasStackTraceContaining("measure_unit_id");
    }

    // --- migrations ---

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                // 0 and not 1: delivery baselines at 0 (#722), and a scratch database is empty anyway.
                .baselineVersion("0")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private void migrateToHead() {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    // --- fixtures: every write states the tenant scope, because the policies apply to the owner ---

    private UUID insertCompany() throws SQLException {
        var id = UUID.randomUUID();
        execute(
                id,
                "INSERT INTO companies (id, company_key, company_name, rfc) VALUES ('" + id + "', 'V55-"
                        + id.toString().substring(0, 8) + "', 'V55 Company', 'V5501010AB1')");
        return id;
    }

    private UUID insertProduct(UUID company, String sku, String name) throws SQLException {
        return insertProduct(company, sku, name, "SIMPLE");
    }

    /** A product whose mode is stated: {@code V54} added the column, and {@code V56} makes it decisive. */
    private UUID insertProduct(UUID company, String sku, String name, String variantMode) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id, variant_mode) VALUES ('" + id + "', '" + sku + "', '"
                        + name + "', '" + company + "', '" + variantMode + "')");
        return id;
    }

    /**
     * A variant as the pre-unit schema legitimately holds it: no unit. This is the row the migration must
     * refuse to invent a unit for, so the fixture never states one.
     */
    private UUID insertVariant(UUID company, UUID product, String barCode, String variantName) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name) VALUES ('" + id
                        + "', '" + company + "', '" + product + "', '" + barCode + "', '" + variantName + "')");
        return id;
    }

    private UUID insertVariantWithUnit(
            UUID company, UUID product, String barCode, String variantName, UUID measureUnitId) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name, measure_unit_id)"
                        + " VALUES ('" + id + "', '" + company + "', '" + product + "', '" + barCode + "', '"
                        + variantName + "', '" + measureUnitId + "')");
        return id;
    }

    private void execute(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                statement.execute(sql);
            }
        }
    }

    // --- reads ---

    /**
     * The {@code measure_units} row {@code V3} seeds, read instead of invented: a random uuid would be
     * refused by the foreign key and would prove nothing about a catalogue delivery already ships.
     */
    private UUID seededUnitId() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "SELECT id FROM measure_units WHERE sat_code = '" + SEEDED_UNIT_SAT_CODE + "'")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("V3 did not seed measure_units.sat_code " + SEEDED_UNIT_SAT_CODE);
            }
            return resultSet.getObject(1, UUID.class);
        }
    }

    /** {@code IS_NULLABLE} of one column: {@code YES} before the migration applies, {@code NO} after. */
    private String nullable(String table, String column) throws SQLException {
        return read("SELECT is_nullable FROM information_schema.columns WHERE table_name = '" + table
                + "' AND column_name = '" + column + "'");
    }

    /** The unit of a variant, read with the tenant scope the policies require — {@code null} when none. */
    private UUID measureUnitOf(UUID company, UUID variantId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + company + "', false)");
            }
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(
                            "SELECT measure_unit_id FROM product_variants WHERE id = '" + variantId + "'")) {
                resultSet.next();
                return resultSet.getObject(1, UUID.class);
            }
        }
    }

    private String read(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    // --- the scratch database's own administration ---

    private String adminJdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    private String jdbcUrlFor(String name) {
        var base = POSTGRES.getJdbcUrl();
        var queryStart = base.indexOf('?');
        var withoutQuery = queryStart < 0 ? base : base.substring(0, queryStart);
        var suffix = queryStart < 0 ? "" : base.substring(queryStart);
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + name + suffix;
    }
}
