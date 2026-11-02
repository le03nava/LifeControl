package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verification of {@code V58__variant_listing.sql}, the schema half of ADR-0006 decision 5.
 *
 * <p><b>Why a scratch database.</b> The fact this file asserts is a <b>copy</b>: for every
 * {@code product_variant_store_stock} row that carries a {@code list_price}, {@code variant_listing} gets
 * the price, the tenant and the company's currency, and a row with no price gets nothing. That state can
 * only be observed by seeding it <b>before</b> the migration exists — the shared test database boots
 * straight to head, where {@code variant_listing} is already there and already empty. Here the database is
 * held at {@code V57} (the pre-unit head), a catalogue is seeded the way that schema holds it, and the
 * chain then runs to head.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}</b> and not as the container's superuser, the way the
 * older scratch-database suites do it. {@code V52__tenant_isolation_machinery.sql:152-153} calls
 * {@code ENABLE} <b>and {@code FORCE}</b> on every table it arms, {@code V53__tenant_isolation_policies.sql:108}
 * arms {@code companies} and {@code :122-123} arms {@code products} and {@code product_variants}, and
 * {@code FORCE} is what makes a policy apply to the table's <b>owner</b> — the role Flyway runs as in
 * delivery. A superuser bypasses the policies whatever they say, so a backfill asserted under it would
 * report having read the catalogue while delivery's {@code NOSUPERUSER} owner read no row at all. That is
 * precisely what {@code V58}'s {@code NO FORCE} window exists for, and the premise is pinned by its own
 * case below.</p>
 */
@DisplayName("Variant listing migration (V58)")
class ProductVariantListingMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: the price still lives in the per-store stock row. */
    private static final String PRE_UNIT_HEAD = "57";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_variant_listing_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("the price is copied for the store rows that have one, with the company's own currency")
    void copiesThePriceOfEveryStoreRowThatHasOne() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        // A currency that is NOT the one V57 seeds (MXN). The listing's `currency` has to come from
        // `companies.currency`, so a fixture that uses the seeded code could not tell a real copy from a
        // literal in the migration.
        var currency = "USD";
        insertCurrency(currency);
        var company = insertCompany(currency);
        var storeWithPrice = insertStore(company, "V58 Store Priced");
        var storeWithoutPrice = insertStore(company, "V58 Store Unpriced");
        var product = insertProduct(company, "V58-SKU", "V58 Article");
        var variant = insertVariant(company, product, "Unica");

        insertStorePrice(company, variant, storeWithPrice, "100.50");
        // The row that must produce NO listing: the ADR copies a price, and a row with no price has none.
        insertStorePrice(company, variant, storeWithoutPrice, null);

        migrateToHead();

        assertThat(countScoped(company, "SELECT count(*) FROM variant_listing"))
                .as("exactly one listing: the priced store row, and not the unpriced one")
                .isEqualTo(1);

        var listing = listingOf(company, variant);
        assertThat(listing.storeId())
                .as("the listing names the store that had the price")
                .isEqualTo(storeWithPrice);
        assertThat(listing.listPrice())
                .as("the price is copied, not recomputed")
                .isEqualByComparingTo("100.50");
        assertThat(listing.currency())
                .as("the currency is the variant's company's (V57), never invented by the migration")
                .isEqualTo(currency);

        assertThat(readScoped(
                        company, "SELECT policy_kind FROM tenant_table WHERE table_name::text = 'variant_listing'"))
                .as("a tenant table is armed in the migration that creates it, or the startup check refuses to boot")
                .isEqualTo("tenant");
        assertThat(readScoped(
                        company, "SELECT key_column FROM tenant_table WHERE table_name::text = 'variant_listing'"))
                .isEqualTo("company_id");
        assertThat(
                        readScoped(
                                company,
                                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = 'variant_listing'"))
                .as("ENABLE and FORCE, the pair ADR-0005 uses for every tenant table")
                .isEqualTo("t");
        assertThat(readScoped(company, "SELECT count(*) FROM pg_policies WHERE tablename = 'variant_listing'"))
                .as("one policy, created by apply_tenant_isolation and not by hand")
                .isEqualTo("1");
    }

    @Test
    @DisplayName("C6: another tenant's store, and a second listing for the same (variant, store), are refused")
    void refusesCrossTenantAndDuplicateListings() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany("MXN");
        var store = insertStore(company, "V58 Own Store");
        var product = insertProduct(company, "V58-C6", "V58 C6 Article");
        var variant = insertVariant(company, product, "Unica");

        var otherCompany = insertCompany("MXN");
        var foreignStore = insertStore(otherCompany, "V58 Foreign Store");

        migrateToHead();

        // The variant's company and the store's company disagree. The composite key is the whole point of
        // decision 7's "the listing depends on the variant AND on the store being of the same tenant".
        assertThatThrownBy(() -> insertListing(company, variant, foreignStore, "10.00"))
                .as("a listing of this tenant's variant at another tenant's store")
                .hasStackTraceContaining("fk_listing_store");

        insertListing(company, variant, store, "10.00");
        assertThatThrownBy(() -> insertListing(company, variant, store, "11.00"))
                .as("one listing per (variant, store), refused by the schema and not by a service")
                .hasStackTraceContaining("uq_listing_variant_store");
    }

    @Test
    @DisplayName("C7: the listing carries no stock and no cost column")
    void carriesNoStockAndNoCostColumn() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = insertCompany("MXN");
        insertStore(company, "V58 C7 Store");

        migrateToHead();

        var columns = columnNames("variant_listing");

        assertThat(columns)
                .as("quantity is ADR-0001's ledger and cost is the inbound movement's, so neither is a "
                        + "column here — and `tax_rate_id` is absent because `tax_rate` does not exist yet")
                .containsExactly(
                        "company_id",
                        "company_store_id",
                        "currency",
                        "enabled",
                        "id",
                        "list_price",
                        "product_variant_id");
    }

    @Test
    @DisplayName("the premise: without the tenant scope the owner reads no row, and writes no listing")
    void theOwnerReadsNothingWithoutTheScope() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany("MXN");
        var store = insertStore(company, "V58 Scope Store");
        var product = insertProduct(company, "V58-SCOPE", "V58 Scoped Article");
        var variant = insertVariant(company, product, "Unica");
        insertStorePrice(company, variant, store, "9.00");

        assertThat(countScoped(company, "SELECT count(*) FROM companies"))
                .as("with the scope, the fixture's own company is there")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM companies"))
                .as("without it, this owner sees nothing — which is why V58 opens its NO FORCE window "
                        + "instead of trusting the copy to read a company")
                .isZero();

        migrateToHead();

        assertThat(countScoped(company, "SELECT count(*) FROM variant_listing"))
                .as("the copy did reach the one priced row")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM variant_listing"))
                .as("and the armed table reads as empty to a session with no scope, exactly like the rest")
                .isZero();
        assertThatThrownBy(() -> insertListingWithoutScope(company, variant, store))
                .as("arming is not decoration: an insert that states no tenant is refused by the policy")
                .hasStackTraceContaining("row-level security policy");
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

    /** A second currency row, so the copy can be told apart from a literal in the migration. */
    private void insertCurrency(String code) throws SQLException {
        execute(
                null,
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('" + code + "', 2, '$', true)"
                        + " ON CONFLICT (code) DO NOTHING");
    }

    /** A company as {@code V57} requires it: it states its own operating currency. */
    private UUID insertCompany(String currency) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                id,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + id + "', 'V58-"
                        + id.toString().substring(0, 8) + "', 'V58 Company', 'V58"
                        + id.toString().replace("-", "").substring(0, 10).toUpperCase() + "', '" + currency + "')");
        return id;
    }

    /** A store of that company, with the country → region → zone chain the hierarchy requires. */
    private UUID insertStore(UUID company, String storeName) throws SQLException {
        // One row per (company, country): V1's UNIQUE(company_id, country_id) refuses a second one, and a
        // company with two stores is still one company in one country.
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
                "INSERT INTO company_regions (id, company_id, company_country_id, region_code, region_name)"
                        + " VALUES ('" + region + "', '" + company + "', '" + companyCountry + "', '"
                        + region.toString().substring(0, 6) + "', 'V58 Region')");

        var zone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name) VALUES ('"
                        + zone + "', '" + company + "', '" + region + "', '"
                        + zone.toString().substring(0, 6)
                        + "', 'V58 Zone')");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + zone + "', '" + storeName + "', true)");
        return store;
    }

    private UUID insertProduct(UUID company, String sku, String name) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id) VALUES ('" + id + "', '" + sku + "', '" + name
                        + "', '" + company + "')");
        return id;
    }

    /** One variant of a {@code SIMPLE} product, with the unit {@code V55} made mandatory. */
    private UUID insertVariant(UUID company, UUID product, String variantName) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name, measure_unit_id)"
                        + " VALUES ('" + id + "', '" + company + "', '" + product + "', 'V58-BAR-"
                        + id.toString().substring(0, 12) + "', '" + variantName + "', '" + seededUnitId() + "')");
        return id;
    }

    /**
     * The per-store row as the pre-unit schema holds it: price and stock together. {@code list_price} is
     * the only column the migration copies, and a {@code null} here is the row that must produce no listing.
     */
    private void insertStorePrice(UUID company, UUID variant, UUID store, String listPrice) throws SQLException {
        execute(
                company,
                "INSERT INTO product_variant_store_stock (product_variant_id, company_store_id, stock, list_price,"
                        + " cost_price) VALUES ('" + variant + "', '" + store + "', 4, "
                        + (listPrice == null ? "NULL" : "'" + listPrice + "'") + ", 70.00)");
    }

    /** The same insert on a connection that states no tenant at all: what the policy's WITH CHECK refuses. */
    private void insertListingWithoutScope(UUID company, UUID variant, UUID store) throws SQLException {
        execute(
                null,
                "INSERT INTO variant_listing (company_id, product_variant_id, company_store_id, list_price, currency)"
                        + " VALUES ('" + company + "', '" + variant + "', '" + store + "', 5.00, 'MXN')");
    }

    /** A listing written the way a client would: the row states its own tenant, the policy checks it. */
    private void insertListing(UUID company, UUID variant, UUID store, String listPrice) throws SQLException {
        execute(
                company,
                "INSERT INTO variant_listing (company_id, product_variant_id, company_store_id, list_price, currency)"
                        + " VALUES ('" + company + "', '" + variant + "', '" + store + "', '" + listPrice
                        + "', 'MXN')");
    }

    /** The {@code measure_units} row {@code V3} seeds as "Pieza", read and never invented. */
    private UUID seededUnitId() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT id FROM measure_units WHERE sat_code = 'H87'")) {
            resultSet.next();
            return resultSet.getObject(1, UUID.class);
        }
    }

    /**
     * One statement on one connection with the tenant scope set first, when there is one.
     *
     * <p>The scope is not a convenience: {@code companies} is isolated by {@code id} and every catalog
     * table by {@code company_id}, so without it a write is refused by the policy's {@code WITH CHECK} —
     * the behaviour ADR-0005 wants, and the reason a scratch-database fixture cannot borrow a pool that
     * installs the scope for it.</p>
     */
    private void execute(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            if (scope != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    // --- reads ---

    /** One row of a tenant table with the scope set: without it the policies answer with no row at all. */
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

    private Listing listingOf(UUID company, UUID variant) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + company + "', false)");
            }
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet =
                            statement.executeQuery("SELECT company_store_id, list_price, currency FROM variant_listing"
                                    + " WHERE product_variant_id = '" + variant + "'")) {
                assertThat(resultSet.next())
                        .as("the priced store row produced a listing")
                        .isTrue();
                return new Listing(
                        resultSet.getObject(1, UUID.class), resultSet.getBigDecimal(2), resultSet.getString(3));
            }
        }
    }

    private List<String> columnNames(String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                                + " AND table_name = '" + table + "' ORDER BY column_name")) {
            var columns = new ArrayList<String>();
            while (resultSet.next()) {
                columns.add(resultSet.getString(1));
            }
            return columns;
        }
    }

    private record Listing(UUID storeId, BigDecimal listPrice, String currency) {}

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
