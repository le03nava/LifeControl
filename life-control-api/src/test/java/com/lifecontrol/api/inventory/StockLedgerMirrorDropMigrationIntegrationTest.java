package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.inventory.ledger.StockLedgerEngine;
import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schema the stock mirror leaves behind: {@code V74__stock_ledger_drop_legacy_columns.sql} and
 * {@code V75__stock_ledger_drop_inventory_movements.sql} (T4 of {@code odd/tasks/ledger-mirror-retirement.md}).
 *
 * <p><b>What this suite is the witness of.</b> Each case asks one question about the migrated database, and each
 * question is the negative or the positive half of the same claim — that ADR-0001 decision 1 left exactly one
 * authority on quantity standing:</p>
 *
 * <ul>
 *   <li>no {@code stock} column on either table that carried one, because a column nobody maintains is a second
 *       answer waiting to be read;</li>
 *   <li>both tables themselves <b>still there</b>, because what they kept is an identity the ledger's foreign keys
 *       point at and the cost every inbound movement has to state — dropping a quantity is not dropping a row;</li>
 *   <li>{@code inventory_movements} absent from {@code pg_class}, because the ledger holds every quantity fact it
 *       described ({@code V62} replayed them) and the code that read it is gone;</li>
 *   <li>the ledger and its two unique keys present, because a drop that cascaded would leave {@code stock_movement}
 *       booking rows while refusing nothing;</li>
 *   <li>and a receipt through {@link StockLedgerEngine#applyReceipt} still moving {@code stock_balance} and
 *       {@code stock_position}, because a schema with no writer is a schema that would pass every assertion above
 *       while nothing on hand could be received.</li>
 * </ul>
 *
 * <p><b>Why the assertions read the catalog and not the entities.</b> {@code ddl-auto=validate} already fails the
 * context when an entity and a column disagree, so this suite is not a second copy of that: what it pins is the
 * <b>absence</b> of a column and of a relation, which no mapping can state — an entity that no longer names a
 * column passes validation whether or not the column is still there. {@code pg_attribute} and {@code pg_class} are
 * world-readable, so the pool's own role answers them without a tenant scope, and the one positive case states the
 * tenant through {@link TenantFixtures#inTenant} exactly as {@code InventoryLedgerBookingIntegrationTest} does:
 * {@code V60} armed the five ledger tables and {@code FORCE}d them, so an unscoped write is refused by the policy
 * instead of landing.</p>
 */
@SpringBootTest
@DisplayName("The stock mirror's drop, as the schema is left (V74, V75)")
class StockLedgerMirrorDropMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "LEDGER-MIRROR-DROP-A";
    private static final String COMPANY_RFC = "LMD040404EF4";

    /** What the store records for the variant, which is the cost every inbound movement has to state. */
    private static final String RECORDED_COST = "9.75";

    /** The two tables {@code V74} took a column from and deliberately kept. */
    private static final List<String> SURVIVING_TABLES =
            List.of("product_variant_locations", "product_variant_store_stock");

    /** The five tables {@code V60} created, which {@code V75} leaves standing. */
    private static final List<String> LEDGER_TABLES =
            List.of("stock_movement", "stock_balance", "stock_position", "stock_valuation", "stock_allocation");

    /** The two keys the ledger's identity and its one-reversal rule rest on. */
    private static final List<String> LEDGER_KEYS = List.of("uq_movement_source", "uq_movement_one_reversal");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StockLedgerEngine stockLedgerEngine;

    private UUID companyId;
    private UUID storeId;
    private UUID locationId;
    private UUID variantId;

    private final List<InsertedRow> insertedVariants = new ArrayList<>();
    private final List<InsertedRow> insertedProducts = new ArrayList<>();
    private final List<InsertedRow> insertedLocations = new ArrayList<>();
    private final List<InsertedRow> insertedZones = new ArrayList<>();
    private final List<InsertedRow> insertedAreas = new ArrayList<>();
    private final List<InsertedRow> insertedStores = new ArrayList<>();
    private final List<InsertedRow> insertedCompanyZones = new ArrayList<>();
    private final List<InsertedRow> insertedRegions = new ArrayList<>();
    private final List<InsertedRow> insertedCompanyCountries = new ArrayList<>();

    @BeforeEach
    void insertTheTreeEveryCaseHangsFrom() {
        var countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
        companyId = TenantFixtures.insertCompany(
                jdbcTemplate, COMPANY_KEY, "Ledger Mirror Drop Company", COMPANY_RFC, TenantFixtures.SEED_CURRENCY);

        var companyCountryId = insertCompanyCountry(countryId);
        var regionId = insertRegion(companyCountryId);
        var companyZoneId = insertCompanyZone(regionId);
        storeId = insertStore(companyZoneId);
        locationId = insertLocation(insertZone(insertArea(), storeId), storeId);

        insertSettings();
        variantId = insertVariant();
    }

    @AfterEach
    void removeInsertedRows() {
        if (companyId != null) {
            deleteStockLedger(jdbcTemplate, companyId);
            deleteTheStoreRows();
        }
        for (var row : insertedVariants) {
            deleteRow(row, "DELETE FROM product_variants WHERE id = ?");
        }
        insertedVariants.clear();
        for (var row : insertedProducts) {
            deleteRow(row, "DELETE FROM products WHERE id = ?");
        }
        insertedProducts.clear();
        for (var row : insertedLocations) {
            deleteRow(row, "DELETE FROM store_locations WHERE id = ?");
        }
        insertedLocations.clear();
        for (var row : insertedZones) {
            deleteRow(row, "DELETE FROM store_zones WHERE id = ?");
        }
        insertedZones.clear();
        for (var row : insertedAreas) {
            deleteRow(row, "DELETE FROM store_areas WHERE id = ?");
        }
        insertedAreas.clear();
        for (var row : insertedStores) {
            deleteRow(row, "DELETE FROM company_stores WHERE id = ?");
        }
        insertedStores.clear();
        for (var row : insertedCompanyZones) {
            deleteRow(row, "DELETE FROM company_zones WHERE id = ?");
        }
        insertedCompanyZones.clear();
        for (var row : insertedRegions) {
            deleteRow(row, "DELETE FROM company_regions WHERE id = ?");
        }
        insertedRegions.clear();
        for (var row : insertedCompanyCountries) {
            deleteRow(row, "DELETE FROM company_countries WHERE id = ?");
        }
        insertedCompanyCountries.clear();
        if (companyId != null) {
            deleteRow(new InsertedRow(companyId, companyId), "DELETE FROM companies WHERE id = ?");
        }
    }

    // ──────────────────────────────────────
    // V74 — the two legacy stock columns are gone, and what shared their tables is not
    // ──────────────────────────────────────

    @Test
    @DisplayName("neither surviving table carries a stock column any more")
    void neitherSurvivingTableCarriesAStockColumn() {
        for (var table : SURVIVING_TABLES) {
            assertThat(columnExists(table, "stock"))
                    .as(
                            "%s.stock is the last place a quantity could be read from that the ledger does not own, so "
                                    + "V74 dropped it: a reader that still reached it would answer a number nothing "
                                    + "maintains",
                            table)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("both tables survive with the identity the ledger points at and the cost it must state")
    void bothTablesSurviveWithTheirIdentityAndTheCost() {
        for (var table : SURVIVING_TABLES) {
            assertThat(relationExists(table))
                    .as(
                            "%s must survive the drop of the column: ADR-0001 retires a quantity, not an identity, and "
                                    + "the ledger's foreign keys point at these rows",
                            table)
                    .isTrue();
        }

        assertThat(columnExists("product_variant_locations", "product_variant_id")
                        && columnExists("product_variant_locations", "store_location_id"))
                .as("product_variant_locations keeps the pair that identifies a location balance — `V62` replayed "
                        + "each row into a movement, and a movement names that same pair")
                .isTrue();
        assertThat(columnExists("product_variant_store_stock", "cost_price"))
                .as("cost_price is what ck_movement_inbound_cost demands on every row that adds quantity: without "
                        + "it the ledger could not book a receipt after this migration, because the cost a store "
                        + "records for a variant is the only cost this deployment keeps")
                .isTrue();
        assertThat(columnExists("product_variant_store_stock", "product_variant_id")
                        && columnExists("product_variant_store_stock", "company_store_id"))
                .as("and the (variant, store) pair stays unique, which is what makes the row a membership test for "
                        + "a receipt and a serialization point for a per-store edit")
                .isTrue();
    }

    // ──────────────────────────────────────
    // V75 — the mirror is gone from the catalog, and the ledger is intact
    // ──────────────────────────────────────

    @Test
    @DisplayName("inventory_movements is absent from pg_class")
    void inventoryMovementsIsAbsentFromTheCatalog() {
        assertThat(relationExists("inventory_movements"))
                .as("V75 drops the table, its five indexes and its rows together: what ended with it is the "
                        + "row-level provenance of a movement booked before V73, and every quantity fact it "
                        + "described is already a stock_movement row V62 replayed")
                .isFalse();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_class WHERE relname = 'inventory_movements'", Integer.class))
                .as("and nothing of that name is left anywhere in pg_class, not only in the public schema")
                .isZero();
    }

    @Test
    @DisplayName("the ledger and its two unique keys survive the drop of the mirror")
    void theLedgerAndItsTwoKeysSurvive() {
        for (var table : LEDGER_TABLES) {
            assertThat(relationExists(table))
                    .as(
                            "%s is what every reader and every decision answers from (ADR-0001 decisions 1 and 2), so a "
                                    + "drop that took it would leave the tree with no authority on quantity at all",
                            table)
                    .isTrue();
        }

        for (var key : LEDGER_KEYS) {
            assertThat(indexExists(key))
                    .as(
                            "%s is a key the ledger's own correctness rests on — one movement per source write (I5, as "
                                    + "V73 states it) and at most one reversal per movement (I4) — and a cascade into "
                                    + "stock_movement would take it silently",
                            key)
                    .isTrue();
        }
    }

    // ──────────────────────────────────────
    // and the ledger is still written: a receipt moves both projections
    // ──────────────────────────────────────

    @Test
    @DisplayName("a receipt through the engine still moves stock_balance and stock_position")
    void aReceiptStillMovesBothProjections() {
        var receiptLineId = UUID.randomUUID();

        TenantFixtures.inTenant(
                companyId,
                () -> stockLedgerEngine.applyReceipt(
                        variantId,
                        storeId,
                        locationId,
                        new BigDecimal("6"),
                        StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                        receiptLineId,
                        "receiver"));

        assertThat(balanceAt(variantId))
                .as("the per-location projection is derived by V61's trigger from the one movement the receipt "
                        + "booked, which is the whole write the engine now performs")
                .isEqualByComparingTo("6");
        assertThat(positionOf(variantId))
                .as("and the store's position followed it, so the number a sale is gated by still moves with a "
                        + "receipt now that no legacy column does")
                .isEqualByComparingTo("6");
        assertThat(movementCountOf(variantId))
                .as("one receipt line is one fact: the column V74 dropped is not replaced by a second row")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // helpers: the catalog
    // ──────────────────────────────────────

    /**
     * Whether a column physically exists on a table in the {@code public} schema.
     *
     * <p>{@code pg_attribute} and not {@code information_schema.columns}: the latter hides what the current role
     * holds no privilege on, so a column it did not return would be indistinguishable from a column that is gone —
     * and "gone" is exactly the fact this suite exists to state. {@code attisdropped} is filtered out because a
     * dropped column keeps a tombstone row in {@code pg_attribute}.</p>
     */
    private boolean columnExists(String table, String column) {
        var count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public' AND c.relname = ? AND a.attname = ?
                   AND a.attnum > 0 AND NOT a.attisdropped
                """, Integer.class, table, column);
        return count != null && count > 0;
    }

    /** Whether a relation of that name exists in the {@code public} schema, whatever its kind. */
    private boolean relationExists(String name) {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' AND c.relname = ?",
                Integer.class,
                name);
        return count != null && count > 0;
    }

    /** Whether an index of that name exists in the {@code public} schema. */
    private boolean indexExists(String name) {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' AND c.relname = ? AND c.relkind = 'i'",
                Integer.class,
                name);
        return count != null && count > 0;
    }

    // ──────────────────────────────────────
    // helpers: the projections the receipt must move
    // ──────────────────────────────────────

    private BigDecimal balanceAt(UUID variant) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT quantity FROM stock_balance WHERE store_location_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        locationId,
                        variant));
    }

    private BigDecimal positionOf(UUID variant) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT quantity FROM stock_position WHERE company_store_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        storeId,
                        variant));
    }

    private int movementCountOf(UUID variant) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_movement WHERE product_variant_id = ?", Integer.class, variant));
    }

    // ──────────────────────────────────────
    // helpers: the tree every case hangs from
    // ──────────────────────────────────────

    private UUID insertCompanyCountry(UUID countryId) {
        var id = UUID.randomUUID();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                        id,
                        companyId,
                        countryId));
        insertedCompanyCountries.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertRegion(UUID companyCountryId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update("""
                        INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name,
                                                     enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, companyCountryId, companyId, suffix, "Drop region " + suffix));
        insertedRegions.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertCompanyZone(UUID regionId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                        INSERT INTO company_zones (id, company_region_id, company_id, zone_code, zone_name, enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, regionId, companyId, suffix, "Drop zone " + suffix));
        insertedCompanyZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertStore(UUID companyZoneId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO company_stores (id, company_zone_id, company_id, store_name, enabled) "
                                + "VALUES (?, ?, ?, ?, true)",
                        id,
                        companyZoneId,
                        companyId,
                        "Drop store " + suffix));
        insertedStores.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertArea() {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled) "
                                + "VALUES (?, ?, ?, ?, true)",
                        id,
                        storeId,
                        suffix,
                        "Drop area " + suffix));
        insertedAreas.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertZone(UUID areaId, UUID store) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                        INSERT INTO store_zones (id, store_area_id, company_store_id, zone_code, zone_name, enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, areaId, store, suffix, "Drop store zone " + suffix));
        insertedZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertLocation(UUID storeZoneId, UUID store) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                        INSERT INTO store_locations (id, store_zone_id, company_store_id, location_code,
                                                     location_name, enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, storeZoneId, store, suffix, "Drop location " + suffix));
        insertedLocations.add(new InsertedRow(companyId, id));
        return id;
    }

    private void insertSettings() {
        TenantFixtures.inTenant(companyId, () -> jdbcTemplate.update("""
                        INSERT INTO store_inventory_settings (company_store_id, receiving_location_id,
                                                              sales_location_id)
                        VALUES (?, ?, ?)
                        """, storeId, locationId, locationId));
    }

    /**
     * One product, one variant and the per-store row every mover locks: the row carries the cost an inbound movement
     * must state and no stock, because the column that used to hold one is what this suite is about.
     */
    private UUID insertVariant() {
        var productId = UUID.randomUUID();
        var id = UUID.randomUUID();
        var sku = "MIRROR-DROP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        var measureUnitId =
                jdbcTemplate.queryForObject("SELECT id FROM measure_units WHERE sat_code = 'H87'", UUID.class);
        TenantFixtures.inTenant(companyId, () -> {
            jdbcTemplate.update(
                    "INSERT INTO products (id, sku, name, company_id) VALUES (?, ?, ?, ?)",
                    productId,
                    sku,
                    "Drop product " + sku,
                    companyId);
            jdbcTemplate.update("""
                    INSERT INTO product_variants (id, company_id, product_id, variant_name, measure_unit_id)
                    VALUES (?, ?, ?, 'unica', ?)
                    """, id, companyId, productId, measureUnitId);
            jdbcTemplate.update(
                    "INSERT INTO product_variant_store_stock (product_variant_id, company_store_id, cost_price) "
                            + "VALUES (?, ?, ?)",
                    id,
                    storeId,
                    new BigDecimal(RECORDED_COST));
        });
        insertedProducts.add(new InsertedRow(companyId, productId));
        insertedVariants.add(new InsertedRow(companyId, id));
        return id;
    }

    /**
     * The per-store rows this suite's fixture wrote, leaf first: they reference the variant and the location with no
     * cascade, so they have to go before the tree does.
     */
    private void deleteTheStoreRows() {
        TenantFixtures.inTenant(companyId, () -> {
            jdbcTemplate.update("DELETE FROM product_variant_store_stock WHERE company_store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM store_inventory_settings WHERE company_store_id = ?", storeId);
        });
    }

    private record InsertedRow(UUID companyId, UUID id) {}

    private void deleteRow(InsertedRow row, String sql) {
        TenantFixtures.inTenant(row.companyId(), () -> jdbcTemplate.update(sql, row.id()));
    }
}
