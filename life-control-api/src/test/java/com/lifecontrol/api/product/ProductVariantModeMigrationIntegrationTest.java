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
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verification of {@code V54__product_variant_mode.sql}, the schema half of ADR-0006 decision 1.
 *
 * <p><b>Why a scratch database.</b> The one fact this migration asserts is a <b>derivation</b>: `MULTI`
 * for a product that already carries more than one variant, `SIMPLE` for the rest. The shared test
 * database boots straight to head, where every product row a suite creates is already classified, so a
 * green run there would prove the column exists and say nothing about the derivation. Here the database
 * is held at {@code V53} — the pre-unit head, where the column does not exist — a catalogue is seeded,
 * and the migration chain then runs to head.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}</b> and not as the container's superuser, the way
 * the older scratch-database suites do it. {@code V52} calls {@code ENABLE} <b>and {@code FORCE}</b> on
 * every table it arms, and {@code FORCE} is what makes a policy apply to the table's owner — the role
 * Flyway runs as in delivery. The container's own user is a superuser, which bypasses the policies
 * whatever they say, so a derivation asserted under it would report the backfill working while
 * delivery's {@code NOSUPERUSER} owner matched zero rows in silence. That is the whole reason
 * {@code V54} opens a {@code NO FORCE} window, and the premise is pinned by its own case below.</p>
 */
@DisplayName("Product variant mode migration (V54)")
class ProductVariantModeMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: the catalogue is there and no product states a mode. */
    private static final String PRE_UNIT_HEAD = "53";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_variant_mode_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            // OWNER and not the superuser: the policies of ADR-0005 apply to whichever role owns these
            // tables, and this suite exists to observe that they apply to the delivery one.
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
    @DisplayName("the mode is derived from the catalogue: one variant is SIMPLE, more than one is MULTI")
    void derivesTheModeFromTheCatalogue() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var single = insertProduct(company, "V54-SIMPLE", "A loaf of bread");
        insertVariant(company, single, "7509999999999", "Unica");
        var multi = insertProduct(company, "V54-MULTI", "A shoe");
        insertVariant(company, multi, "7501234567890", "Talla 40");
        insertVariant(company, multi, "7501234567891", "Talla 41");
        var compound = insertProduct(company, "V54-MULTI-3", "A three-size shirt");
        insertVariant(company, compound, "7501234567801", "Chica");
        insertVariant(company, compound, "7501234567802", "Mediana");
        insertVariant(company, compound, "7501234567803", "Grande");

        migrateToHead();

        assertThat(variantMode(company, single))
                .as("one variant is SIMPLE, which is what the tree already was")
                .isEqualTo("SIMPLE");
        assertThat(variantMode(company, multi))
                .as("two variants is MULTI, derived from the catalogue and not from the default")
                .isEqualTo("MULTI");
        assertThat(variantMode(company, compound))
                .as("three variants is the same fact, so the derivation counts rather than compares to two")
                .isEqualTo("MULTI");

        // The default is `SIMPLE`, and it is a migration default that the creation transaction will stop
        // relying on — asserted because a later slice flipping it to MULTI would remove the only thing
        // that keeps a one-variant article from needing a decision.
        var afterMigration = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id) VALUES ('" + afterMigration
                        + "', 'V54-DEFAULT', 'A new loaf', '" + company + "')");
        assertThat(variantMode(company, afterMigration)).isEqualTo("SIMPLE");

        // And there are exactly two legal values: a third one is refused by the schema, not by a caller.
        assertThatThrownBy(() ->
                        execute(company, "UPDATE products SET variant_mode = 'FAMILY' WHERE id = '" + single + "'"))
                .hasStackTraceContaining("ck_products_variant_mode");
    }

    @Test
    @DisplayName("the premise: without the tenant scope, the owner reads no row of a FORCEd table")
    void theOwnerReadsNothingWithoutTheScope() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var product = insertProduct(company, "V54-SCOPE", "A scoped article");
        insertVariant(company, product, "7501234567700", "Unica");

        assertThat(countScoped(company, "SELECT count(*) FROM products"))
                .as("with the scope, the fixture's own rows are there")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM products"))
                .as("without it, this owner sees nothing — which is why V54 opens its NO FORCE window "
                        + "instead of trusting an UPDATE to match a row")
                .isZero();
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
                "INSERT INTO companies (id, company_key, company_name, rfc) VALUES ('" + id + "', 'V54-"
                        + id.toString().substring(0, 8) + "', 'V54 Company', 'V5401010AB1')");
        return id;
    }

    /**
     * A product as the pre-unit schema can hold it: no {@code variant_mode} column exists at {@code V53},
     * which is what makes the migration's derivation observable instead of pre-decided by the fixture.
     */
    private UUID insertProduct(UUID company, String sku, String name) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id) VALUES ('" + id + "', '" + sku + "', '" + name
                        + "', '" + company + "')");
        return id;
    }

    /** A barcode is mandatory on every variant at {@code V53}, so the fixture always states one. */
    private void insertVariant(UUID company, UUID product, String barCode, String variantName) throws SQLException {
        execute(
                company,
                "INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name, measure_unit_id)"
                        + " VALUES ('" + UUID.randomUUID() + "', '" + company + "', '" + product + "', '" + barCode
                        + "', '"
                        + variantName + "', '" + seededUnitId() + "')");
    }

    /**
     * The {@code measure_units} row {@code V3} seeds as "Pieza". The unit is required from {@code V55}
     * on, so a fixture that migrates to head has to declare a real one — and it is read, never invented:
     * a random uuid would be refused by the foreign key.
     */
    private UUID seededUnitId() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT id FROM measure_units WHERE sat_code = 'H87'")) {
            resultSet.next();
            return resultSet.getObject(1, UUID.class);
        }
    }

    /**
     * One statement on one connection with the tenant scope set first.
     *
     * <p>The scope is not a convenience: {@code companies} is isolated by {@code id} and every catalog
     * table by {@code company_id}, so without it the write is refused by the policy's {@code WITH CHECK}
     * — the behaviour ADR-0005 wants, and the reason a scratch-database fixture cannot borrow a pool that
     * installs the scope for it.</p>
     */
    private void execute(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                statement.execute(sql);
            }
        }
    }

    // --- reads ---

    /** A read of a tenant table with the scope set: without it the policies answer with no row at all. */
    private String variantMode(UUID company, UUID product) throws SQLException {
        return readScoped(company, "SELECT variant_mode FROM products WHERE id = '" + product + "'");
    }

    private String readScoped(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
            }
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private int countScoped(UUID scope, String sql) throws SQLException {
        return Integer.parseInt(readScoped(scope, sql));
    }

    /** The same read with no scope: what a migration that forgot the window would be looking at. */
    private int count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
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
