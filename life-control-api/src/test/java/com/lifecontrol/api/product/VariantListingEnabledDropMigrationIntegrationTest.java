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
 * Verification of {@code V79__variant_listing_drop_enabled.sql}, the decision issue #777 asked for: the
 * availability toggle is out of scope by decision, so the column that promised it goes.
 *
 * <p><b>The state this suite is written against.</b> {@code V58:99} created
 * {@code variant_listing.enabled BOOLEAN NOT NULL DEFAULT true} and nothing in the tree writes it: the entity
 * carried it, the price upsert deliberately left it alone, both store-scoped queries decided "listed" by the
 * presence of the row, and the comment in {@code ProductVariantRepository} said so out loud. The API answers a
 * field called {@code listed} for a state nobody can reach — dead schema that still reads as live.</p>
 *
 * <p><b>Why a scratch database.</b> The shared test database boots straight to head, so it can only show the
 * destination: a database where the column is already gone. Here the database is held at {@code V78} (the head
 * this unit starts from, where the column is still there and a {@code false} is still storable), the tree a
 * listing needs is seeded the way that schema holds it, and the chain then runs to head — so the drop is
 * observed as a <i>change</i>, not as a property of a tree the column was never part of.</p>
 *
 * <p><b>Why the refusal exists.</b> Nothing in the tree can set {@code false}, so a row that holds it was written
 * by a hand: an operator, a psql session, an old client. That value is then the only record of an intent somebody
 * expressed, and a migration that discards the sole record of a fact is not a cleanup ({@code V74} and
 * {@code V77} refuse for the same reason). The guard is the difference between retiring a column nobody could use
 * and deleting a decision somebody made.</p>
 *
 * <p><b>What else is asserted, and why it is not ceremony.</b> {@code enabled} is the name of live columns in
 * {@code product_variants} and {@code company_stores}, so the self-check reads all three tables: a drop written
 * against the wrong table would take a flag that has a writer and a meaning. The passing case also pins that the
 * listing survives with its price, which is what keeps {@code listed} meaning exactly what ADR-0006 decision 6
 * says it means. The last case pins that {@code variant_listing} is still {@code FORCE}d after the migration —
 * the guard opens a {@code NO FORCE} window on it, and a window left open would weaken every policy test that
 * runs after this one.</p>
 */
@DisplayName("Variant listing enabled drop migration (V79)")
class VariantListingEnabledDropMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: the column is present and still stores a {@code false}. */
    private static final String PRE_UNIT_HEAD = "78";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_drop_listing_enabled_" + UUID.randomUUID().toString().replace("-", "");
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

    // --- the drop: what a database that is safe to migrate looks like afterwards ---

    @Test
    @DisplayName("a listing that never left the schema's default is migrated, and the row survives with its price")
    void dropsTheColumnWhenNoListingWasDisabled() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedListing("AC-79-A", null);

        migrateToHead();

        assertThat(columnExists("variant_listing", "enabled"))
                .as("the whole point of the unit: the flag nobody can set stops being promised")
                .isFalse();
        for (var survivor : new String[] {
            "id", "company_id", "product_variant_id", "company_store_id", "list_price", "currency"
        }) {
            assertThat(columnExists("variant_listing", survivor))
                    .as("the drop must reach exactly one column, not the listing's identity or its price")
                    .isTrue();
        }
        assertThat(readScoped(
                        company.companyId(),
                        "SELECT list_price FROM variant_listing WHERE product_variant_id = '"
                                + company.variantId() + "' AND company_store_id = '" + company.storeId() + "'"))
                .as("the row is what makes a variant listed (ADR-0006 decision 6), and it is untouched")
                .isEqualTo("99.9000");
    }

    @Test
    @DisplayName("the flags that do have a writer survive: product_variants and company_stores keep theirs")
    void keepsTheEnabledColumnsThatHaveMeaning() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        seedListing("AC-79-B", null);

        migrateToHead();

        assertThat(columnExists("product_variants", "enabled"))
                .as("the variant-wide flag has its own writer (DELETE / PATCH .../variants/{variantId}/enable) "
                        + "and its own meaning; it is not this file's to remove")
                .isTrue();
        assertThat(columnExists("company_stores", "enabled"))
                .as("a store's enabled flag is read by the store hierarchy, not by this unit")
                .isTrue();
    }

    // --- the refusal: a false is the only record of an intent ---

    @Test
    @DisplayName("a listing disabled by hand is refused, and the message names the variant, the store and the next step")
    void refusesWhenAListingHoldsFalse() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedListing("AC-79-C", Boolean.FALSE);

        assertThatThrownBy(this::migrateToHead)
                .hasStackTraceContaining("refusing to drop variant_listing.enabled")
                .hasStackTraceContaining("variant " + company.variantId())
                .hasStackTraceContaining("store " + company.storeId());
        assertThat(columnExists("variant_listing", "enabled"))
                .as("a refusal that dropped the column anyway is the defect the guard exists to prevent")
                .isTrue();
        assertThat(readScoped(
                        company.companyId(),
                        "SELECT enabled FROM variant_listing WHERE product_variant_id = '" + company.variantId()
                                + "'"))
                .as("the value the guard refused over is still readable by whoever has to decide what to do with it")
                .isEqualTo("f");
    }

    // --- the window the guard needs, closed ---

    @Test
    @DisplayName("variant_listing is FORCEd after the migration, so no NO FORCE window is left open")
    void leavesVariantListingForced() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        seedListing("AC-79-D", null);

        migrateToHead();

        assertThat(readUnscoped(
                        "SELECT c.relforcerowsecurity FROM pg_class c WHERE c.relname = 'variant_listing' AND c.relkind = 'r'"))
                .as("V79 opens NO FORCE on variant_listing to read it; a window left open weakens the table for "
                        + "everything that runs after it (V55)")
                .isEqualTo("t");
    }

    // --- the window, and the continuation the refusal promises ---

    @Test
    @DisplayName("a false in another tenant's row is refused, which only happens if the window really opened")
    void refusesAFalseThatBelongsToAnotherTenant() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var clean = seedListing("AC-79-E", Boolean.TRUE);
        var offender = seedListing("AC-79-F", Boolean.FALSE);

        assertThatThrownBy(this::migrateToHead)
                .hasStackTraceContaining("refusing to drop variant_listing.enabled")
                .hasStackTraceContaining("variant " + offender.variantId())
                .hasStackTraceContaining("store " + offender.storeId());
        assertThat(columnExists("variant_listing", "enabled"))
                .as("with the window broken the guard sees no row at all and drops; FORCE filters the owner")
                .isTrue();
        assertThat(readScoped(
                        clean.companyId(),
                        "SELECT enabled FROM variant_listing WHERE product_variant_id = '" + clean.variantId() + "'"))
                .as("a row that never left the schema's default is not an offender")
                .isEqualTo("t");
    }

    @Test
    @DisplayName("clearing the value the message names lets the migration through: its stated next step works")
    void migratesAfterTheOffendingValueIsCleared() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedListing("AC-79-G", Boolean.FALSE);

        assertThatThrownBy(this::migrateToHead).hasStackTraceContaining("refusing to drop variant_listing.enabled");

        execute(
                company.companyId(),
                "UPDATE variant_listing SET enabled = true WHERE product_variant_id = '" + company.variantId() + "'");
        migrateToHead();

        assertThat(columnExists("variant_listing", "enabled")).isFalse();
        assertThat(readScoped(
                        company.companyId(),
                        "SELECT list_price FROM variant_listing WHERE product_variant_id = '"
                                + company.variantId() + "'"))
                .as("the row the operator kept keeps its price")
                .isEqualTo("99.9000");
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

    /**
     * One company, one store, one product, one variant and one listing priced at 99.90.
     *
     * <p>{@code enabled} is {@code null} for the common case — the insert omits the column and the schema's own
     * {@code DEFAULT true} answers, which is what every row in a real database holds. A case states a value only
     * when the value <i>is</i> the question, which is the {@code false} the guard exists for.</p>
     */
    private Listing seedListing(String sku, Boolean enabled) throws SQLException {
        var company = insertCompany(sku);
        var store = insertTree(company, sku);
        var product = insertProduct(company, sku);
        var variant = insertVariant(company, product, sku);

        var columns = "company_id, product_variant_id, company_store_id, list_price, currency";
        var values = "'" + company + "', '" + variant + "', '" + store + "', 99.90, 'MXN'";
        if (enabled != null) {
            columns += ", enabled";
            values += ", " + enabled;
        }
        execute(company, "INSERT INTO variant_listing (" + columns + ") VALUES (" + values + ")");
        return new Listing(company, variant, store);
    }

    /** A company as {@code V57} requires it: it states its own operating currency. */
    private UUID insertCompany(String sku) throws SQLException {
        var id = UUID.randomUUID();
        var suffix = id.toString().replace("-", "").substring(0, 10).toUpperCase();
        execute(
                id,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + id + "', '"
                        + sku + "', 'V79 Company', 'V79" + suffix + "', 'MXN')");
        return id;
    }

    /**
     * The store a listing names, in the shape
     * {@code ProductVariantModeImmutabilityMigrationIntegrationTest#insertTree} casts it: country → region → zone
     * → store, with the areas and locations that make the tree complete for the schema's own foreign keys.
     */
    private UUID insertTree(UUID company, String sku) throws SQLException {
        execute(
                company,
                "INSERT INTO company_countries (id, company_id, country_id)"
                        + " SELECT gen_random_uuid(), '" + company + "', c.id FROM countries c"
                        + " WHERE c.country_code = 'MX'"
                        + " ON CONFLICT (company_id, country_id) DO NOTHING");
        var companyCountry =
                readScoped(company, "SELECT id FROM company_countries WHERE company_id = '" + company + "'");

        var region = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_regions (id, company_id, company_country_id, region_code, region_name, enabled)"
                        + " VALUES ('" + region + "', '" + company + "', '" + companyCountry + "', '"
                        + region.toString().substring(0, 6).toUpperCase() + "', 'V79 Region', true)");

        var zone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name, enabled)"
                        + " VALUES ('" + zone + "', '" + company + "', '" + region + "', '"
                        + zone.toString().substring(0, 6).toUpperCase() + "', 'V79 Zone', true)");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + zone + "', 'V79 Store', true)");
        return store;
    }

    private UUID insertProduct(UUID company, String sku) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id, variant_mode) VALUES ('" + id + "', '" + sku
                        + "', 'V79 Product " + sku + "', '" + company + "', 'SIMPLE')");
        return id;
    }

    private UUID insertVariant(UUID company, UUID product, String barCode) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name, measure_unit_id)"
                        + " VALUES ('" + id + "', '" + company + "', '" + product + "', '" + barCode
                        + "', 'Unit', '" + seededUnitId() + "')");
        return id;
    }

    /** The {@code measure_units} row {@code V3} seeds as "Pieza": read, never invented. */
    private UUID seededUnitId() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT id FROM measure_units WHERE sat_code = 'H87'")) {
            resultSet.next();
            return resultSet.getObject(1, UUID.class);
        }
    }

    // --- writes and reads, every one of them under a stated tenant scope ---

    /**
     * One statement on its own transaction, with {@code set_tenant_scope} stated first.
     *
     * <p>{@code set_tenant_scope} is {@code SET LOCAL} ({@code V52:60-63}), so the transaction has to stay open
     * while the statement runs: autocommit would end the transaction — and the scope with it — before the
     * statement it is there for.</p>
     */
    private int execute(UUID scope, String sql) throws SQLException {
        try (Connection connection = scopedConnection(scope);
                Statement statement = connection.createStatement()) {
            var affected = statement.executeUpdate(sql);
            connection.commit();
            return affected;
        }
    }

    /** One row's first column, read under a stated scope: without it the policies answer with no row at all. */
    private String readScoped(UUID scope, String sql) throws SQLException {
        try (Connection connection = scopedConnection(scope);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private Connection scopedConnection(UUID scope) throws SQLException {
        var connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_tenant_scope('" + scope + "')");
        }
        return connection;
    }

    // --- the catalog, read unscoped: information_schema and pg_class carry no policy ---

    private boolean columnExists(String table, String column) throws SQLException {
        return "t"
                .equals(readUnscoped(
                        "SELECT 't' FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '"
                                + table + "' AND column_name = '" + column + "'"));
    }

    private String readUnscoped(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
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

    /** What a seeded case is about: the three ids every assertion names. */
    private record Listing(UUID companyId, UUID variantId, UUID storeId) {}
}
