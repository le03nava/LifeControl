package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of the store hierarchy's tenancy migrations against real PostgreSQL,
 * with Flyway enabled and {@code ddl-auto=validate}, so a green run proves the schema landed exactly as
 * the migrations declare it.
 *
 * <p>The rule under test is issue <b>#673</b>'s: every level of the hierarchy stores its owner, derived
 * from its parent, and the database refuses a row whose owner disagrees with that parent's. The cases are
 * written the way {@code SupplierSchemaMigrationIntegrationTest} writes them — the same rows with a
 * consistent company are accepted, and the same rows with a disagreeing one are refused <b>by the
 * constraint's name</b>, which is what makes the assertion about the rule rather than about "some
 * error" — and each refusal also asserts that nothing was stored.</p>
 *
 * <p>The suite is organized one section per level, in migration order: the region's contract
 * ({@code V44}/{@code V45}), the zone's ({@code V46}/{@code V47}), the store's ({@code V48}/{@code V49})
 * and the purchase-order key the store level makes expressible ({@code V51}).</p>
 *
 * <p>The {@code NOT NULL} each contract lands is not what fills the column for a caller that omits it:
 * {@code V50}'s {@code BEFORE INSERT} triggers derive it from the parent's stored owner. The three cases
 * where a row arrives with no company at all therefore assert the <b>derivation</b>, one per level, while
 * the refusals stay where a caller <em>did</em> name a company that disagrees with its parent's.</p>
 */
@SpringBootTest
@DisplayName("Store Hierarchy Company Scope Schema Integration Tests")
class StoreHierarchyCompanyScopeSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_A_KEY = "STORE-SCHEMA-A";
    private static final String COMPANY_B_KEY = "STORE-SCHEMA-B";
    private static final String COMPANY_A_RFC = "SSHA010101AB1";
    private static final String COMPANY_B_RFC = "SSHB010101AB2";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyAId;
    private UUID companyBId;
    private UUID countryId;
    private UUID companyCountryId;
    private UUID paymentMethodId;
    private UUID draftPurchaseOrderStatusId;

    private final List<UUID> insertedPurchaseOrders = new ArrayList<>();
    private final List<UUID> insertedStores = new ArrayList<>();
    private final List<UUID> insertedZones = new ArrayList<>();
    private final List<UUID> insertedRegions = new ArrayList<>();
    private final List<UUID> insertedCompanyCountries = new ArrayList<>();
    private final List<UUID> insertedSuppliers = new ArrayList<>();
    private final List<UUID> insertedCompanies = new ArrayList<>();

    @BeforeEach
    void insertCompaniesAndTheirCountry() {
        companyAId = insertCompany(COMPANY_A_KEY, "Store Schema Company A", COMPANY_A_RFC);
        companyBId = insertCompany(COMPANY_B_KEY, "Store Schema Company B", COMPANY_B_RFC);

        // Seeded reference data, read rather than invented: no company country exists without one.
        countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
        companyCountryId = insertCompanyCountry(companyAId);

        // Same two seeded rows `SupplierSchemaMigrationIntegrationTest` reads: a purchase order needs them
        // and neither is what these cases are about.
        paymentMethodId = jdbcTemplate.queryForObject(
                "SELECT id FROM payment_methods ORDER BY payment_method_name LIMIT 1", UUID.class);
        draftPurchaseOrderStatusId = jdbcTemplate.queryForObject("""
                SELECT s.id FROM statuses s
                JOIN status_types st ON st.id = s.status_type_id
                WHERE st.status_type_name = 'PURCHASE_ORDER' AND s.status_name = 'Draft'
                """, UUID.class);
    }

    @AfterEach
    void removeInsertedRows() {
        // Leaf first: every list below references the one after it.
        for (var id : insertedPurchaseOrders) {
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE id = ?", id);
        }
        insertedPurchaseOrders.clear();
        for (var id : insertedStores) {
            jdbcTemplate.update("DELETE FROM company_stores WHERE id = ?", id);
        }
        insertedStores.clear();
        for (var id : insertedZones) {
            jdbcTemplate.update("DELETE FROM company_zones WHERE id = ?", id);
        }
        insertedZones.clear();
        for (var id : insertedRegions) {
            jdbcTemplate.update("DELETE FROM company_regions WHERE id = ?", id);
        }
        insertedRegions.clear();
        for (var id : insertedCompanyCountries) {
            jdbcTemplate.update("DELETE FROM company_countries WHERE id = ?", id);
        }
        insertedCompanyCountries.clear();
        // Suppliers reference companies, so the child goes first.
        for (var id : insertedSuppliers) {
            jdbcTemplate.update("DELETE FROM suppliers WHERE id = ?", id);
        }
        insertedSuppliers.clear();
        for (var id : insertedCompanies) {
            jdbcTemplate.update("DELETE FROM companies WHERE id = ?", id);
        }
        insertedCompanies.clear();
    }

    // ──────────────────────────────────────
    // V44/V45 — the region level
    // ──────────────────────────────────────

    @Test
    @DisplayName("the region level stores its owner: company_regions.company_id is mandatory after V45")
    void regionCompanyIdIsMandatory() {
        var nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company_regions' AND column_name = 'company_id'
                """, String.class);

        assertThat(nullable)
                .as("the owner is stored so the database can refuse a disagreeing one, not derived on read")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("V44 leaves both unique targets the next level's composite keys need")
    void regionLevelUniqueTargetsExist() {
        assertThat(uniqueConstraintCount("company_countries", "uq_company_countries_company_id"))
                .as("uq_company_countries_company_id is the region key's target")
                .isEqualTo(1);
        assertThat(uniqueConstraintCount("company_regions", "uq_company_regions_company_id"))
                .as("uq_company_regions_company_id is the zone key's target")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a region whose company is its country's company is accepted")
    void regionInsideItsCountryIsAccepted() {
        var regionId = insertRegion(companyCountryId, companyAId);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM company_regions WHERE id = ?", UUID.class, regionId))
                .as("the accepted row stores the company it was written with")
                .isEqualTo(companyAId);
    }

    @Test
    @DisplayName("a region whose company disagrees with its country's is refused by the key, storing nothing")
    void regionOfAnotherCompanyIsRefused() {
        var refusedId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, refusedId, companyCountryId, companyBId, suffix, "Foreign region " + suffix))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_company_regions_country_same_company");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM company_regions WHERE id = ?", Integer.class, refusedId))
                .as("the refused insert stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("a region that arrives with no company at all gets its country's, derived by V50's trigger")
    void regionWithoutCompanyIsDerivedFromItsCountry() {
        var regionId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        jdbcTemplate.update("""
                INSERT INTO company_regions (id, company_country_id, region_code, region_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, regionId, companyCountryId, suffix, "Derived region " + suffix);
        insertedRegions.add(regionId);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM company_regions WHERE id = ?", UUID.class, regionId))
                .as("the database is the author of this column: an absent owner is the country's, not a refusal")
                .isEqualTo(companyAId);
    }

    // ──────────────────────────────────────
    // V46/V47 — the zone level
    // ──────────────────────────────────────

    @Test
    @DisplayName("the zone level stores its owner: company_zones.company_id is mandatory after V47")
    void zoneCompanyIdIsMandatory() {
        var nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company_zones' AND column_name = 'company_id'
                """, String.class);

        assertThat(nullable)
                .as("the owner is stored so the database can refuse a disagreeing one, not derived on read")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("V46 leaves the unique target the store level's composite key needs")
    void zoneLevelUniqueTargetExists() {
        assertThat(uniqueConstraintCount("company_zones", "uq_company_zones_company_id"))
                .as("uq_company_zones_company_id is the store key's target")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a zone whose company is its region's company is accepted")
    void zoneInsideItsRegionIsAccepted() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var zoneId = insertZone(regionId, companyAId);

        assertThat(jdbcTemplate.queryForObject("SELECT company_id FROM company_zones WHERE id = ?", UUID.class, zoneId))
                .as("the accepted row stores the company it was written with")
                .isEqualTo(companyAId);
    }

    @Test
    @DisplayName("a zone whose company disagrees with its region's is refused by the key, storing nothing")
    void zoneOfAnotherCompanyIsRefused() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var refusedId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThatThrownBy(() ->
                        jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, company_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, refusedId, regionId, companyBId, suffix, "Foreign zone " + suffix))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_company_zones_region_same_company");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM company_zones WHERE id = ?", Integer.class, refusedId))
                .as("the refused insert stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("a zone that arrives with no company at all gets its region's, derived by V50's trigger")
    void zoneWithoutCompanyIsDerivedFromItsRegion() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var zoneId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, zoneId, regionId, suffix, "Derived zone " + suffix);
        insertedZones.add(zoneId);

        assertThat(jdbcTemplate.queryForObject("SELECT company_id FROM company_zones WHERE id = ?", UUID.class, zoneId))
                .as("the database is the author of this column: an absent owner is the region's, not a refusal")
                .isEqualTo(companyAId);
    }

    // ──────────────────────────────────────
    // V48/V49 — the store level
    // ──────────────────────────────────────

    @Test
    @DisplayName("the store level stores its owner: company_stores.company_id is mandatory after V49")
    void storeCompanyIdIsMandatory() {
        var nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company_stores' AND column_name = 'company_id'
                """, String.class);

        assertThat(nullable)
                .as("the owner is stored so the database can refuse a disagreeing one, not derived on read")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("V48's unique target survives the contract: it is the purchase-order key's target")
    void storeLevelUniqueTargetSurvives() {
        assertThat(uniqueConstraintCount("company_stores", "uq_company_stores_company_id"))
                .as("uq_company_stores_company_id is what V51's composite key references")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a store whose company is its zone's company is accepted")
    void storeInsideItsZoneIsAccepted() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var zoneId = insertZone(regionId, companyAId);
        var storeId = insertStore(zoneId, companyAId);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM company_stores WHERE id = ?", UUID.class, storeId))
                .as("the accepted row stores the company it was written with")
                .isEqualTo(companyAId);
    }

    @Test
    @DisplayName("a store whose company disagrees with its zone's is refused by the key, storing nothing")
    void storeOfAnotherCompanyIsRefused() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var zoneId = insertZone(regionId, companyAId);
        var refusedId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO company_stores (id, company_zone_id, company_id, store_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, refusedId, zoneId, companyBId, "Foreign store " + suffix))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_company_stores_zone_same_company");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM company_stores WHERE id = ?", Integer.class, refusedId))
                .as("the refused insert stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("a store that arrives with no company at all gets its zone's, derived by V50's trigger")
    void storeWithoutCompanyIsDerivedFromItsZone() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var zoneId = insertZone(regionId, companyAId);
        var storeId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        jdbcTemplate.update("""
                INSERT INTO company_stores (id, company_zone_id, store_name, enabled)
                VALUES (?, ?, ?, true)
                """, storeId, zoneId, "Derived store " + suffix);
        insertedStores.add(storeId);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM company_stores WHERE id = ?", UUID.class, storeId))
                .as("the database is the author of this column: an absent owner is the zone's, not a refusal")
                .isEqualTo(companyAId);
    }

    // ──────────────────────────────────────
    // V51 — the purchase order's store key
    // ──────────────────────────────────────

    @Test
    @DisplayName("a purchase order whose company is its store's company and its supplier's is accepted")
    void purchaseOrderInsideOneCompanyIsAccepted() {
        var storeOfA = insertStoreOfCompanyA();
        var supplierOfA = insertSupplier(companyAId, "SPOA");

        var id = insertPurchaseOrder(companyAId, storeOfA, supplierOfA);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE id = ?", Integer.class, id))
                .as("the order whose store and supplier both belong to its company is written")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a purchase order whose company disagrees with its store's is refused by the key, storing nothing")
    void purchaseOrderOfAnotherCompanysStoreIsRefused() {
        // The row V43's header records as the one it could not write: the supplier agrees with the order's
        // company, so the supplier key is satisfied and only the store key can refuse this pair.
        var storeOfA = insertStoreOfCompanyA();
        var supplierOfB = insertSupplier(companyBId, "SPOB");
        var refusedId = UUID.randomUUID();

        assertThatThrownBy(() -> insertPurchaseOrderWithId(refusedId, companyBId, storeOfA, supplierOfB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_purchase_orders_store_same_company");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM purchase_orders WHERE id = ?", Integer.class, refusedId))
                .as("the refused order stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("V51 drops V1's plain store key, so the composite key is the only path from an order to a store")
    void purchaseOrderPlainStoreKeyIsGone() {
        assertThat(foreignKeyConstraintCount("purchase_orders", "purchase_orders_company_store_id_fkey"))
                .as("the plain key beside the composite one would still admit the pair the composite refuses")
                .isZero();
        assertThat(foreignKeyConstraintCount("purchase_orders", "fk_purchase_orders_store_same_company"))
                .as("the composite key is the only path from a purchase order to a company store")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // fixtures
    // ──────────────────────────────────────

    private int uniqueConstraintCount(String table, String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'UNIQUE'
                """, Integer.class, table, constraintName);
    }

    private int foreignKeyConstraintCount(String table, String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'FOREIGN KEY'
                """, Integer.class, table, constraintName);
    }

    private UUID insertCompany(String key, String name, String rfc) {
        var id = TenantFixtures.insertCompany(jdbcTemplate, key, name, rfc);
        insertedCompanies.add(id);
        return id;
    }

    private UUID insertSupplier(UUID companyId, String rfcPrefix) {
        var id = UUID.randomUUID();
        // `suppliers.rfc` is VARCHAR(13): an 8-character suffix keeps the prefix and the total legal.
        var rfc = rfcPrefix
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                VALUES (?, 'Proveedor', ?, ?)
                """, id, rfc, companyId);
        insertedSuppliers.add(id);
        return id;
    }

    private UUID insertPurchaseOrder(UUID companyId, UUID storeId, UUID supplierId) {
        var id = UUID.randomUUID();
        insertPurchaseOrderWithId(id, companyId, storeId, supplierId);
        return id;
    }

    private void insertPurchaseOrderWithId(UUID id, UUID companyId, UUID storeId, UUID supplierId) {
        jdbcTemplate.update(
                """
                INSERT INTO purchase_orders (id, order_number, supplier_id, company_store_id,
                                             payment_method_id, status_id, company_id, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, true)
                """,
                id,
                "SCHEMA-PO-" + UUID.randomUUID().toString().substring(0, 8),
                supplierId,
                storeId,
                paymentMethodId,
                draftPurchaseOrderStatusId,
                companyId);
        insertedPurchaseOrders.add(id);
    }

    private UUID insertCompanyCountry(UUID companyId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                id,
                companyId,
                countryId);
        insertedCompanyCountries.add(id);
        return id;
    }

    private UUID insertRegion(UUID companyCountry, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, companyCountry, companyId, suffix, "Schema region " + suffix);
        insertedRegions.add(id);
        return id;
    }

    private UUID insertZone(UUID companyRegion, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, company_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, companyRegion, companyId, suffix, "Schema zone " + suffix);
        insertedZones.add(id);
        return id;
    }

    private UUID insertStore(UUID companyZone, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO company_stores (id, company_zone_id, company_id, store_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, id, companyZone, companyId, "Schema store " + suffix);
        insertedStores.add(id);
        return id;
    }

    /** A store of company A, through the whole zone chain, reusing the level helpers above. */
    private UUID insertStoreOfCompanyA() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var zoneId = insertZone(regionId, companyAId);
        return insertStore(zoneId, companyAId);
    }
}
