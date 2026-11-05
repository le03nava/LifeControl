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
 * Verification of {@code V77__product_variant_store_stock_drop_list_price.sql}, the retirement issue #779 asked
 * for: the store row no longer carries a price in any representation.
 *
 * <p><b>The state this suite is written against.</b> {@code V58} copied the store price into
 * {@code variant_listing} instead of moving it and said so on purpose ({@code V58:38-42}); T4b then moved every
 * read, so the column has had no writer and no reader since. What survived was a column that still looked live
 * to anyone reading the schema — the entity mapped it, both builders offered it, and eleven test fixtures still
 * populated it — which is the difference between dead code and a trap.</p>
 *
 * <p><b>Why a scratch database.</b> The shared test database boots straight to head, so it can only show the
 * destination: a database where the column is already gone. Here the database is held at {@code V76} (the head
 * this unit starts from, where the column is still there and still accepting prices), the tree a store row needs
 * is seeded the way that schema holds it, and the chain then runs to head — so the drop is observed as a
 * <i>change</i> in behaviour and not as a property of the tree it was born in. Without this, "the column is
 * absent" would pass on any database, including one where no migration ever ran.</p>
 *
 * <p><b>Why the refusals are two cases and not one.</b> The guard exists because dropping the column destroys
 * the only record of a price in two distinct situations: the listing was edited after {@code V58}'s copy (the two
 * disagree), and the price was never copied at all (there is no listing). A single case would leave the other
 * branch of the {@code NOT EXISTS} unproven, and the second branch is the one this schema produces more often —
 * a store row exists as soon as a receipt books stock in a store that never priced the article (ADR-0006
 * decision 6).</p>
 *
 * <p><b>Why the passing cases are two.</b> A priced row that the listing reproduces proves the drop is reachable
 * at all; a row with no price proves it is reachable for the majority of rows, where there is nothing to lose and
 * the guard must not invent an offender out of {@code NULL <> NULL}.</p>
 *
 * <p><b>What else is asserted, and why it is not ceremony.</b> {@code list_price} is the name of live columns in
 * {@code variant_listing} and {@code sales_order_items}, so the self-check reads all three tables: the price a
 * client reads is the listing's, and a sale keeps the price it was sold at. The last case pins that
 * {@code variant_listing} is still {@code FORCE}d after the migration — the guard opens a {@code NO FORCE} window
 * on it, and a window left open would weaken every policy test that runs after this one.</p>
 */
@DisplayName("Product variant store stock list_price drop migration (V77)")
class StoreStockListPriceDropMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: the column is present and still accepts a price. */
    private static final String PRE_UNIT_HEAD = "76";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_drop_list_price_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("a priced row its listing reproduces is migrated, and the column is gone with the cost still there")
    void dropsTheColumnWhenTheListingReproducesThePrice() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var fixture = seedPricedRow("AC-77-A", "99.90", "99.90");

        migrateToHead();

        assertThat(columnExists("product_variant_store_stock", "list_price"))
                .as("the whole point of the unit: the store row carries no price")
                .isFalse();
        assertThat(columnExists("product_variant_store_stock", "cost_price"))
                .as("the cost an inbound movement has to state is not this file's to remove")
                .isTrue();
        assertThat(columnExists("product_variant_store_stock", "company_store_id")).isTrue();
        assertThat(readScoped(
                        fixture.companyId(),
                        "SELECT cost_price FROM product_variant_store_stock WHERE product_variant_id = '"
                                + fixture.variantId() + "' AND company_store_id = '" + fixture.storeId() + "'"))
                .as("the surviving row is the same row, with its cost intact")
                .isEqualTo("12.00");
        assertThat(readScoped(
                        fixture.companyId(),
                        "SELECT list_price FROM variant_listing WHERE product_variant_id = '" + fixture.variantId()
                                + "' AND company_store_id = '" + fixture.storeId() + "'"))
                .as("the price a client reads is the listing's, before and after")
                .isEqualTo("99.9000");
        assertThat(columnExists("sales_order_items", "list_price"))
                .as("a sale keeps the price it was sold at")
                .isTrue();
    }

    @Test
    @DisplayName("a store row with no price is migrated: there is nothing to lose")
    void migratesWhenTheStoreRowHasNoPrice() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        seedPricedRow("AC-77-B", null, null);

        migrateToHead();

        assertThat(columnExists("product_variant_store_stock", "list_price")).isFalse();
    }

    // --- the refusals: the column is the only record of a price ---

    @Test
    @DisplayName("a listing that holds a different price is refused, and the message names the variant and the store")
    void refusesWhenTheListingHoldsADifferentPrice() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var fixture = seedPricedRow("AC-77-C", "99.90", "89.90");

        assertThatThrownBy(this::migrateToHead)
                .hasStackTraceContaining("refusing to drop product_variant_store_stock.list_price")
                .hasStackTraceContaining("variant " + fixture.variantId())
                .hasStackTraceContaining("store " + fixture.storeId())
                .hasStackTraceContaining("column 99.90")
                .hasStackTraceContaining("listing 89.9000");
        assertThat(columnExists("product_variant_store_stock", "list_price"))
                .as("a refusal that dropped the column anyway is the defect the guard exists to prevent")
                .isTrue();
    }

    @Test
    @DisplayName("a priced row with no listing at all is refused, and the message says the listing is none")
    void refusesWhenThePricedRowHasNoListing() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var fixture = seedPricedRow("AC-77-D", "99.90", null);

        assertThatThrownBy(this::migrateToHead)
                .hasStackTraceContaining("refusing to drop product_variant_store_stock.list_price")
                .hasStackTraceContaining("variant " + fixture.variantId())
                .hasStackTraceContaining("listing none");
        assertThat(columnExists("product_variant_store_stock", "list_price")).isTrue();
    }

    // --- the window the guard needs, closed ---

    @Test
    @DisplayName("variant_listing is FORCEd after the migration, so no NO FORCE window is left open")
    void leavesVariantListingForced() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        seedPricedRow("AC-77-E", "10.00", "10.00");

        migrateToHead();

        assertThat(readUnscoped(
                        "SELECT c.relforcerowsecurity FROM pg_class c WHERE c.relname = 'variant_listing' AND c.relkind = 'r'"))
                .as("V77 opens NO FORCE on variant_listing to read it; a window left open weakens the table for "
                        + "everything that runs after it (V55)")
                .isEqualTo("t");
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
     * One company, one store, one product, one variant, one store row — and, when {@code listingPrice} is not
     * {@code null}, one listing for the same pair.
     *
     * <p>The two prices are separate parameters on purpose: that is the whole question the guard asks. A case
     * that set one from the other could not tell "the listing reproduces the price" from "there is nothing to
     * compare".</p>
     */
    private PricedRow seedPricedRow(String sku, String storeRowPrice, String listingPrice) throws SQLException {
        var company = insertCompany();
        var tree = insertTree(company);
        var product = insertProduct(company, sku);
        var variant = insertVariant(company, product, sku);

        var columnLiteral = storeRowPrice == null ? "NULL" : "'" + storeRowPrice + "'";
        execute(
                company,
                "INSERT INTO product_variant_store_stock (product_variant_id, company_store_id, cost_price, list_price)"
                        + " VALUES ('" + variant + "', '" + tree.storeId() + "', 12.00, " + columnLiteral + ")");

        if (listingPrice != null) {
            execute(
                    company,
                    "INSERT INTO variant_listing (company_id, product_variant_id, company_store_id, list_price, currency)"
                            + " VALUES ('" + company + "', '" + variant + "', '" + tree.storeId() + "', "
                            + listingPrice + ", 'MXN')");
        }
        return new PricedRow(company, variant, tree.storeId());
    }

    /** A company as {@code V57} requires it: it states its own operating currency. */
    private UUID insertCompany() throws SQLException {
        var id = UUID.randomUUID();
        execute(
                id,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + id + "', 'V77-"
                        + id.toString().substring(0, 8) + "', 'V77 Company', 'V77"
                        + id.toString().replace("-", "").substring(0, 10).toUpperCase() + "', 'MXN')");
        return id;
    }

    /**
     * The store a listing and a store row both name, in the shape
     * {@code ProductVariantModeImmutabilityMigrationIntegrationTest#insertTree} casts it: country → region → zone
     * → store, with the areas and locations that make the tree complete for the schema's own foreign keys.
     */
    private Tree insertTree(UUID company) throws SQLException {
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
                        + region.toString().substring(0, 6).toUpperCase() + "', 'V77 Region', true)");

        var zone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name, enabled)"
                        + " VALUES ('" + zone + "', '" + company + "', '" + region + "', '"
                        + zone.toString().substring(0, 6).toUpperCase() + "', 'V77 Zone', true)");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + zone + "', 'V77 Store', true)");

        return new Tree(store);
    }

    private UUID insertProduct(UUID company, String sku) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id, variant_mode) VALUES ('" + id + "', '" + sku
                        + "', 'V77 Product " + sku + "', '" + company + "', 'SIMPLE')");
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
        return "t".equals(readUnscoped(
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
    private record PricedRow(UUID companyId, UUID variantId, UUID storeId) {}

    /** The store a listing and a store row both name. */
    private record Tree(UUID storeId) {}
}
