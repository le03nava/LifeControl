package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
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
import org.springframework.core.io.ClassPathResource;

/**
 * Behavioural verification of {@code V62__stock_ledger_path_a_backfill.sql}: ADR-0001's
 * {@code ## Migration from the current tree} Path A ({@code docs/adr/0001-inventory-ledger-single-source.md:217-221})
 * and its step 3 ({@code :239}), replayed over a tree that already holds balances.
 *
 * <p><b>Why a scratch database held at {@code V61}.</b> The subject of the migration is what happens to
 * rows that <b>already exist</b>: one {@code ADJUSTMENT_IN} per {@code product_variant_locations} row with
 * stock, the cost copied from {@code product_variant_store_stock.cost_price}, and the two divergences
 * reported instead of decided. The shared test database boots straight to head, where the ledger is empty
 * and there is nothing to replay, so this suite seeds the pre-{@code V62} state the way
 * {@code ProductVariantListingMigrationIntegrationTest} seeds its own and then runs the chain to head.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}.</b> {@code V52__tenant_isolation_machinery.sql:152-153}
 * calls {@code ENABLE} <b>and {@code FORCE}</b> on every table it arms — {@code V60__stock_ledger_schema.sql:307-311}
 * arms the five ledger tables, {@code V53:108} arms {@code companies} by its own {@code id} and {@code V53:123}
 * arms {@code product_variants}. {@code FORCE} is what makes a policy apply to the table's <b>owner</b>, and
 * the owner is the role Flyway runs as in delivery; the container's own user is a superuser and bypasses the
 * policies whatever they say. Backfilling under the superuser would therefore prove nothing: the premise that
 * the owner reads no row without a scope, and that {@code V62}'s {@code NO FORCE} window is what makes its
 * statements see the tree at all, is pinned by the cases below running under the role delivery uses.</p>
 *
 * <p><b>How the "reported" half of the contract is observed.</b> {@code V62} prints the two divergences and
 * the zero-cost count with {@code RAISE NOTICE}, because a migration's only output is a message on the
 * server's notice channel — and Flyway owns its own connection, so its notices are not reachable through the
 * Flyway API. PostgreSQL's driver maps a notice onto {@link SQLWarning} of the statement that produced it, so
 * the two cases that assert a report execute the migration file itself, as one simple-query statement (which
 * PostgreSQL runs in a single implicit transaction, exactly as Flyway wraps a migration) and read the
 * warnings back. Everything else — the happy path, the abort case and the invariants — runs through
 * {@link Flyway}, the credential and the path delivery uses.</p>
 *
 * <p><b>What is deliberately not asserted here.</b> Nothing about the engine: the projection trigger, the
 * allocation writer and the reversal rules are {@code StockLedgerEngineIntegrationTest}'s (T3), and no case
 * below repeats them. This suite asks only what {@code V62} made of the rows that were already in the tree.</p>
 */
@DisplayName("Stock ledger Path A backfill migration (V62)")
class StockLedgerBackfillMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: the ledger's tables and engine exist, and hold no row. */
    private static final String PRE_UNIT_HEAD = "61";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The file under test, executed directly by the two cases that assert what it prints. */
    private static final String MIGRATION_FILE = "db/migration/V62__stock_ledger_path_a_backfill.sql";

    /**
     * The seven tables the migration writes or reads through a policy. {@code stock_allocation} is in the
     * list although no allocation is written: the valuation replay reads it.
     */
    private static final String ARMED_TABLES =
            "'companies', 'product_variants', 'stock_movement', 'stock_allocation', 'stock_balance', "
                    + "'stock_position', 'stock_valuation'";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_ledger_backfill_" + UUID.randomUUID().toString().replace("-", "");
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

    // ──────────────────────────────────────
    // Path A: one ADJUSTMENT_IN per location that holds stock
    // ──────────────────────────────────────

    @Test
    @DisplayName("a location holding stock becomes exactly one ADJUSTMENT_IN, and the position is the old store stock")
    void aLocationWithStockBecomesOneMovement() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var seeded = seedCompany("USD");
        var variant = insertVariant(seeded.company());
        var stocked = insertLocation(seeded.company(), seeded.store(), "A-01");
        var empty = insertLocation(seeded.company(), seeded.store(), "A-02");
        var stockedBalance = insertLocationStock(seeded.company(), variant, stocked, "12.00");
        insertLocationStock(seeded.company(), variant, empty, "0.00");
        // The store row agrees with its locations, so this case is only about the movement Path A writes.
        insertStoreStock(seeded.company(), variant, seeded.store(), "12.00", "70.00");

        migrateToHead();

        var movements = movementsOf(seeded.company());
        assertThat(movements)
                .as("one row per location that holds stock — and none for the location at zero, because "
                        + "ck_movement_quantity (V60:145) refuses a row that moves nothing and a balance of "
                        + "zero is not a fact the ledger should record")
                .hasSize(1);

        var movement = movements.get(0);
        assertThat(movement.type())
                .as("Path A synthesizes an inbound adjustment (ADR-0001 :217-221)")
                .isEqualTo("ADJUSTMENT_IN");
        assertThat(movement.quantity())
                .as("quantity = stock, so the ledger's sum equals today's balance by construction")
                .isEqualByComparingTo("12");
        assertThat(movement.unitCost())
                .as("unit_cost is the cost_price product_variant_store_stock records for that (variant, store)")
                .isEqualByComparingTo("70");
        assertThat(movement.currency())
                .as("the company's operating currency (companies.currency, V57) and not a literal: the "
                        + "fixture states USD, which is not the code V57 seeds")
                .isEqualTo("USD");
        assertThat(movement.sourceType())
                .as("the discriminator an operator undoes the backfill by")
                .isEqualTo("MIGRATION");
        assertThat(movement.sourceId())
                .as("the balance row itself — the product_variant_locations row Path A replays — which "
                        + "satisfies uq_movement_source one-to-one")
                .isEqualTo(stockedBalance);
        assertThat(movement.reason())
                .as("the row says where it came from, so it is not a fact nobody can trace")
                .contains("synthesized");

        assertThat(balanceOf(seeded.company(), stocked, variant))
                .as("the location projection is the old location balance")
                .isEqualByComparingTo("12");
        assertThat(positionOf(seeded.company(), seeded.store(), variant))
                .as("and the per-store projection is the number the operator saw in the store row")
                .isEqualByComparingTo("12");

        // The store row is not rewritten by this file. The column the claim used to read is gone from T4's V74 on,
        // so it is asserted in the form that still exists: the column is not there, and the number the operator
        // read in it is the position asserted above. A backfill that also rewrote the store row would be two facts
        // in one diff, and a column dropped by a later file is how that claim survives its subject.
        assertThat(countScoped(
                        seeded.company(),
                        "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_name = 'product_variant_store_stock' AND column_name = 'stock'"))
                .as("product_variant_store_stock.stock is dropped by V74, not by V62")
                .isZero();

        assertThat(unarmedArmedTables())
                .as("the NO FORCE window is closed again: all seven tables are ENABLE + FORCE, or the next "
                        + "migration inherits a tree whose owner reads across tenants")
                .isZero();
    }

    @Test
    @DisplayName("a location with no recorded cost becomes a zero-cost movement that says the cost is unknown")
    void aLocationWithNoCostBecomesAZeroCostMovementThatSaysSo() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var seeded = seedCompany("MXN");
        var noCostPrice = insertVariant(seeded.company());
        var noStoreRow = insertVariant(seeded.company());
        var firstLocation = insertLocation(seeded.company(), seeded.store(), "B-01");
        var secondLocation = insertLocation(seeded.company(), seeded.store(), "B-02");
        insertLocationStock(seeded.company(), noCostPrice, firstLocation, "4.00");
        insertLocationStock(seeded.company(), noStoreRow, secondLocation, "2.00");
        // cost_price is nullable (`V13:29`) and this tree is full of rows where nobody filled it.
        insertStoreStock(seeded.company(), noCostPrice, seeded.store(), "4.00", null);
        // The second variant has no store row at all: it holds stock in a location the store never knew about.

        var notices = runV62AndCaptureNotices();

        var movements = movementsOf(seeded.company());
        assertThat(movements)
                .as("both locations hold stock, and both are synthesized")
                .hasSize(2);
        assertThat(movements)
                .as("ck_movement_inbound_cost (V60:147-149) demands a cost on an inbound row, and the honest "
                        + "value of an unrecorded cost is zero plus a reason — not a migration that refuses to "
                        + "run on a tree where nobody filled the field")
                .allSatisfy(movement -> {
                    assertThat(movement.unitCost()).isEqualByComparingTo("0");
                    assertThat(movement.reason())
                            .as("the zero says so in the row itself, where whoever reads the movement will see it")
                            .contains("unknown");
                });
        assertThat(notices)
                .as("and the count is printed rather than left to be discovered (Path A's cost decides "
                        + "valuation, so how many rows have no cost is a number an operator must see)")
                .anySatisfy(
                        notice -> assertThat(notice).contains("unit_cost = 0").contains("2 of 2"));
    }

    // ──────────────────────────────────────
    // the two divergences: one aborts, one is reported
    // ──────────────────────────────────────

    @Test
    @DisplayName("store stock with no location in that store aborts and names the pair")
    void storeStockWithNoLocationAbortsNamingThePair() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var seeded = seedCompany("MXN");
        var variant = insertVariant(seeded.company());
        // The store claims 5 units of a variant no location in it holds: the three-representation tree's
        // `1 = SUM(2)` is already broken here, and Path A has no location to attribute it to.
        insertStoreStock(seeded.company(), variant, seeded.store(), "5.00", "10.00");

        assertThatThrownBy(this::migrateToHead)
                .as("Path A attributes the ledger to locations, so this balance has nothing to hang on and "
                        + "inventing a location writes a fact nobody recorded — the same report-then-fix shape "
                        + "V55__variant_measure_unit_required.sql:53-62 uses for the unit it refuses to guess")
                .hasStackTraceContaining("V62 aborted")
                .hasStackTraceContaining("no product_variant_locations row")
                .hasStackTraceContaining(variant.toString())
                .hasStackTraceContaining(seeded.store().toString());

        assertThat(countScoped(seeded.company(), "SELECT count(*) FROM stock_movement"))
                .as("the refusal stored nothing: a migration that fails on its own report must leave no ledger")
                .isZero();
        assertThat(storeStockOf(seeded.company(), variant, seeded.store()))
                .as("and it did not quietly rewrite the store row either")
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("a divergent pair is reported with both numbers and the position follows the locations")
    void theDivergentPairIsReportedAndThePositionFollowsTheLocations() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var seeded = seedCompany("MXN");
        var variant = insertVariant(seeded.company());
        var first = insertLocation(seeded.company(), seeded.store(), "C-01");
        var second = insertLocation(seeded.company(), seeded.store(), "C-02");
        insertLocationStock(seeded.company(), variant, first, "4.00");
        insertLocationStock(seeded.company(), variant, second, "3.00");
        // The store row says 99 and its locations sum to 7: exactly the case where the number the operator
        // sees changes, which is why it is reported instead of silently decided.
        insertStoreStock(seeded.company(), variant, seeded.store(), "99.00", "10.00");

        var notices = runV62AndCaptureNotices();

        assertThat(notices)
                .as("both numbers are printed, so an operator learns which pairs now read differently "
                        + "instead of discovering it on a screen")
                .anySatisfy(notice -> assertThat(notice).contains("99.00").contains("7.00"));

        assertThat(positionOf(seeded.company(), seeded.store(), variant))
                .as("Path A proceeds from the locations: the ledger is their sum, not the store row's number")
                .isEqualByComparingTo("7");
        assertThat(movementsOf(seeded.company()))
                .as("both locations became a movement, whichever order the store row disagreed in")
                .hasSize(2);
        assertThat(storeStockOf(seeded.company(), variant, seeded.store()))
                .as("the stale store number stays where it is until T6 drops the column — this file reports "
                        + "the divergence, it does not repair a number it does not own")
                .isEqualByComparingTo("99");
    }

    @Test
    @DisplayName("I2 and I6 are empty once the backfill has been rebuilt and checked")
    void i2AndI6AreEmptyAfterTheBackfill() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var seeded = seedCompany("MXN");
        var firstVariant = insertVariant(seeded.company());
        var secondVariant = insertVariant(seeded.company());
        var firstLocation = insertLocation(seeded.company(), seeded.store(), "D-01");
        var secondLocation = insertLocation(seeded.company(), seeded.store(), "D-02");
        insertLocationStock(seeded.company(), firstVariant, firstLocation, "6.00");
        insertLocationStock(seeded.company(), secondVariant, secondLocation, "3.00");
        insertStoreStock(seeded.company(), firstVariant, seeded.store(), "6.00", "25.50");
        insertStoreStock(seeded.company(), secondVariant, seeded.store(), "3.00", "8.75");

        migrateToHead();

        assertThat(movementsOf(seeded.company()))
                .as("the backfill ran, so the two empty answers below are about a ledger that has rows and a "
                        + "replay that had to agree with them")
                .hasSize(2);

        assertThat(countScoped(
                        seeded.company(), "SELECT count(*) FROM verify_stock_projections('" + seeded.company() + "')"))
                .as("ADR-0001 invariant I2 (:181): balance, position and valuation equal a full replay of the "
                        + "ledger. A migration that reports success on a projection it cannot verify is the "
                        + "failure the ADR exists to remove")
                .isZero();

        assertThat(countScoped(
                        seeded.company(),
                        "SELECT count(*) FROM unallocated_stock_movements('" + seeded.company() + "')"))
                .as("ADR-0001 invariant I6 (:185): the backfill writes inbound-only rows, so an outbound row "
                        + "here would be a defect in the file rather than in the data")
                .isZero();

        assertThat(valuationOf(seeded.company(), seeded.store(), firstVariant))
                .as("and the valuation is the cost Path A copied: 6 units at 25.50")
                .isEqualByComparingTo("153");
    }

    // ──────────────────────────────────────
    // the migration, both ways
    // ──────────────────────────────────────

    /**
     * Runs the chain to head through Flyway, as delivery does.
     *
     * <p>In the abort case this is the statement that must fail, and Flyway's own transaction is what makes
     * the failure atomic: the scratch database is left at {@code V61} with nothing written.</p>
     */
    private void migrateToHead() {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    /**
     * Executes the migration file itself and returns the notices it printed.
     *
     * <p>One {@code execute} of the whole file, which the server parses as a single simple-query message:
     * PostgreSQL runs those statements in one implicit transaction, so the file keeps the atomicity Flyway
     * gives it. The notices are read back from the statement's {@link SQLWarning} chain, which is where
     * PostgreSQL's driver puts the server's notice channel.</p>
     */
    private List<String> runV62AndCaptureNotices() throws Exception {
        var sql = new String(new ClassPathResource(MIGRATION_FILE).getContentAsByteArray(), StandardCharsets.UTF_8);
        var notices = new ArrayList<String>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
            for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                notices.add(String.valueOf(warning.getMessage()));
            }
        }
        assertThat(notices)
                .as("%s must print what it decided instead of leaving it to be discovered", MIGRATION_FILE)
                .isNotEmpty();
        return notices;
    }

    // ──────────────────────────────────────
    // fixtures: the pre-V62 tree, written as the role delivery migrates with
    // ──────────────────────────────────────

    private record Seeded(UUID company, UUID store) {}

    /** A company in its own currency, with one store at the bottom of the hierarchy the keys require. */
    private Seeded seedCompany(String currency) throws SQLException {
        var company = UUID.randomUUID();
        execute(
                null,
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('" + currency + "', 2, '$', true)"
                        + " ON CONFLICT (code) DO NOTHING");
        execute(
                company,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + company + "', 'V62-"
                        + company.toString().substring(0, 8) + "', 'V62 Company', 'V62"
                        + company.toString().replace("-", "").substring(0, 10).toUpperCase() + "', '" + currency
                        + "')");

        // One row per (company, country): V1's UNIQUE(company_id, country_id) refuses a second one.
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
                        + region.toString().substring(0, 6) + "', 'V62 Region')");

        var companyZone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name) VALUES ('"
                        + companyZone + "', '" + company + "', '" + region + "', '"
                        + companyZone.toString().substring(0, 6) + "', 'V62 Zone')");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + companyZone + "', 'V62 Store', true)");

        var area = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled) VALUES ('" + area
                        + "', '" + store + "', '" + area.toString().substring(0, 6) + "', 'V62 Area', true)");

        return new Seeded(company, store);
    }

    /** One location of a store. Its {@code company_store_id} is derived by {@code V59}'s trigger. */
    private UUID insertLocation(UUID company, UUID store, String code) throws SQLException {
        var area = readScoped(company, "SELECT id FROM store_areas WHERE company_store_id = '" + store + "'");
        var zone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_zones (id, store_area_id, zone_code, zone_name, enabled) VALUES ('" + zone + "', '"
                        + area + "', '" + zone.toString().substring(0, 6) + "', 'V62 Store Zone', true)");

        var location = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_locations (id, store_zone_id, location_code, location_name, enabled) VALUES ('"
                        + location + "', '" + zone + "', '" + code + "', 'V62 Location " + code + "', true)");
        return location;
    }

    /** One variant of a {@code SIMPLE} product, with the unit {@code V55} made mandatory. */
    private UUID insertVariant(UUID company) throws SQLException {
        var product = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id) VALUES ('" + product + "', 'V62-"
                        + product.toString().substring(0, 8) + "', 'V62 Article', '" + company + "')");

        var variant = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name, measure_unit_id)"
                        + " VALUES ('" + variant + "', '" + company + "', '" + product + "', 'V62-BAR-"
                        + variant.toString().substring(0, 12) + "', 'Unica', '" + seededUnitId() + "')");
        return variant;
    }

    /** A location balance, in the table the whole backfill reads; returns the balance row's own id. */
    private UUID insertLocationStock(UUID company, UUID variant, UUID location, String stock) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO product_variant_locations (id, product_variant_id, store_location_id, stock) VALUES ('"
                        + id + "', '" + variant + "', '" + location + "', " + stock + ")");
        return id;
    }

    /** The store row as {@code V13} holds it: stock, price and cost in one row. */
    private void insertStoreStock(UUID company, UUID variant, UUID store, String stock, String costPrice)
            throws SQLException {
        execute(
                company,
                "INSERT INTO product_variant_store_stock (product_variant_id, company_store_id, stock, cost_price)"
                        + " VALUES ('" + variant + "', '" + store + "', " + stock + ", "
                        + (costPrice == null ? "NULL" : "'" + costPrice + "'") + ")");
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
     * <p>The scope is not a convenience: {@code companies} is isolated by {@code id} and every catalog table
     * by {@code company_id}, so without it a write is refused by the policy's {@code WITH CHECK}. The tables
     * this suite seeds that carry no {@code company_id} ({@code product_variant_locations},
     * {@code product_variant_store_stock}, the location tree) need none and ignore it.</p>
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

    // ──────────────────────────────────────
    // reads: through the scope, so a policy that hides the row fails the case instead of passing it
    // ──────────────────────────────────────

    private String readScoped(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
            }
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                assertThat(resultSet.next())
                        .as("the scoped read of %s answered no row", sql)
                        .isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private int countScoped(UUID scope, String sql) throws SQLException {
        return Integer.parseInt(readScoped(scope, sql));
    }

    private BigDecimal balanceOf(UUID company, UUID location, UUID variant) throws SQLException {
        return decimalScoped(
                company,
                "SELECT quantity FROM stock_balance WHERE store_location_id = '" + location
                        + "' AND product_variant_id = '" + variant + "'");
    }

    private BigDecimal positionOf(UUID company, UUID store, UUID variant) throws SQLException {
        return decimalScoped(
                company,
                "SELECT quantity FROM stock_position WHERE company_store_id = '" + store
                        + "' AND product_variant_id = '" + variant + "'");
    }

    private BigDecimal valuationOf(UUID company, UUID store, UUID variant) throws SQLException {
        return decimalScoped(
                company,
                "SELECT total_value FROM stock_valuation WHERE company_store_id = '" + store
                        + "' AND product_variant_id = '" + variant + "'");
    }

    /**
     * The legacy store column, read where a case deliberately stops before {@code V74} drops it.
     *
     * <p>Two cases below assert that a migration did not rewrite the store row, and both leave the schema at a
     * version where the column still exists: one because {@code V62} refused and the head was never reached, the
     * other because it runs {@code V62}'s own file and nothing after it. The case that migrates to the head cannot
     * read it any more and says so where it stands.</p>
     */
    private BigDecimal storeStockOf(UUID company, UUID variant, UUID store) throws SQLException {
        return decimalScoped(
                company,
                "SELECT stock FROM product_variant_store_stock WHERE product_variant_id = '" + variant
                        + "' AND company_store_id = '" + store + "'");
    }

    private BigDecimal decimalScoped(UUID scope, String sql) throws SQLException {
        var value = readScoped(scope, sql);
        return value == null ? null : new BigDecimal(value);
    }

    /** Every movement the backfill wrote for a company, in a stable order. */
    private List<Movement> movementsOf(UUID company) throws SQLException {
        var movements = new ArrayList<Movement>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + company + "', false)");
            }
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "SELECT movement_type, quantity, unit_cost, currency, source_type, source_id, reason"
                                    + " FROM stock_movement ORDER BY source_id")) {
                while (resultSet.next()) {
                    movements.add(new Movement(
                            resultSet.getString(1),
                            resultSet.getBigDecimal(2),
                            resultSet.getBigDecimal(3),
                            resultSet.getString(4),
                            resultSet.getString(5),
                            resultSet.getObject(6, UUID.class),
                            resultSet.getString(7)));
                }
            }
        }
        return movements;
    }

    /**
     * How many of the seven tables the migration touched are not {@code ENABLE + FORCE} — the number the
     * "window closed" post-condition has to leave at zero.
     */
    private int unarmedArmedTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT count(*) FROM pg_class WHERE relname IN (" + ARMED_TABLES
                        + ") AND relkind = 'r'" + " AND NOT (relrowsecurity AND relforcerowsecurity)")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /** One row of the ledger as the migration wrote it. */
    private record Movement(
            String type,
            BigDecimal quantity,
            BigDecimal unitCost,
            String currency,
            String sourceType,
            UUID sourceId,
            String reason) {}

    // ──────────────────────────────────────
    // the scratch database's own administration
    // ──────────────────────────────────────

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
