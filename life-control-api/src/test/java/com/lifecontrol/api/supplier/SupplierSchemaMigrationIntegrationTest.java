package com.lifecontrol.api.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of the two supplier tenancy migrations against real PostgreSQL, with
 * Flyway enabled and {@code ddl-auto=validate}, so a green run proves the schema landed exactly as the
 * migrations declare it.
 *
 * <p>The suite asserts the <b>CONTRACT</b> the two migrations land: {@code V41} gave {@code suppliers} a
 * nullable owner column and stopped there, because a {@code NOT NULL} column cannot arrive before the
 * write surface knows where the company comes from (the reason {@code V39} states in its own header);
 * {@code V42} is what turns that into a database rule — the column is mandatory, V1's global RFC key is
 * replaced by the per-company one, and the rows neither rule can describe are gone.</p>
 *
 * <p>The per-company RFC case is the one that could not be proved while the expand step was the head: the
 * service refused a duplicate RFC <b>inside</b> the company, but the global key refused a second
 * company's identical RFC at the same time, so inside one transaction the two refusals were
 * indistinguishable. With V42 the database accepts the cross-company pair and refuses the same-company
 * pair by name, which is the rule the catalog actually states.</p>
 *
 * <p>The {@code V43} section proves the same thing one step further out — for the two tables that
 * <b>name</b> a supplier. {@code V41}'s header lists them as the places that cannot ask who owns the
 * supplier, and {@code V42}'s header defers their composite keys to this migration. The cases are
 * written as the pair the schema has to tell apart: the same rows with a consistent company are
 * accepted, and the same rows with a disagreeing one are refused <b>by the constraint's name</b>, which
 * is what makes the assertion about the rule rather than about "some error".</p>
 */
@SpringBootTest
@DisplayName("Supplier Schema Integration Tests")
class SupplierSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_A_KEY = "SUPPLIER-SCHEMA-A";
    private static final String COMPANY_B_KEY = "SUPPLIER-SCHEMA-B";
    private static final String COMPANY_A_RFC = "SSCA010101AB1";
    private static final String COMPANY_B_RFC = "SSCB010101AB2";

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyAId;
    private UUID companyBId;

    private final List<UUID> insertedSuppliers = new ArrayList<>();
    private final List<UUID> insertedCompanies = new ArrayList<>();
    private final List<UUID> insertedProducts = new ArrayList<>();
    private final List<UUID> insertedProductSuppliers = new ArrayList<>();
    private final List<UUID> insertedPurchaseOrders = new ArrayList<>();
    private final List<UUID> insertedStores = new ArrayList<>();
    private final List<UUID> insertedZones = new ArrayList<>();
    private final List<UUID> insertedRegions = new ArrayList<>();
    private final List<UUID> insertedCompanyCountries = new ArrayList<>();

    private UUID paymentMethodId;
    private UUID draftPurchaseOrderStatusId;

    @BeforeEach
    void insertCompanies() {
        companyAId = insertCompany(COMPANY_A_KEY, "Supplier Schema Company A", COMPANY_A_RFC);
        companyBId = insertCompany(COMPANY_B_KEY, "Supplier Schema Company B", COMPANY_B_RFC);

        // Seeded reference data, read rather than invented: a purchase order needs both, and neither is
        // what these cases are about.
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
        for (var id : insertedProductSuppliers) {
            jdbcTemplate.update("DELETE FROM product_suppliers WHERE id = ?", id);
        }
        insertedProductSuppliers.clear();
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
        for (var id : insertedProducts) {
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", id);
        }
        insertedProducts.clear();
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

    @Test
    @DisplayName("applies every migration on the classpath and leaves no pending migration")
    void flywayHeadIsTheLatestMigration() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
        assertThat(flyway.info().pending()).isEmpty();
    }

    // ──────────────────────────────────────
    // V42 — the contract
    // ──────────────────────────────────────

    @Test
    @DisplayName("V42 makes suppliers.company_id mandatory")
    void companyIdIsMandatory() {
        var nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'suppliers' AND column_name = 'company_id'
                """, String.class);

        assertThat(nullable)
                .as("the owner is a fact of the row, not an optional annotation")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("V42 replaces V1's global RFC key with the per-company one")
    void globalRfcKeyIsGone() {
        var globalKey = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'suppliers_rfc_key'
                """, Integer.class);

        assertThat(globalKey)
                .as("a globally unique RFC is what stopped two businesses from both registering one")
                .isZero();
    }

    @Test
    @DisplayName("uq_suppliers_company_rfc is UNIQUE over exactly (company_id, rfc)")
    void perCompanyRfcKeyCoversCompanyAndRfc() {
        var kind = jdbcTemplate.queryForObject("""
                SELECT constraint_type FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'uq_suppliers_company_rfc'
                """, String.class);
        var columns = jdbcTemplate.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                 AND tc.table_name = kcu.table_name
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = 'suppliers'
                  AND tc.constraint_name = 'uq_suppliers_company_rfc'
                ORDER BY kcu.ordinal_position
                """, String.class);

        assertThat(kind).as("uq_suppliers_company_rfc is a UNIQUE constraint").isEqualTo("UNIQUE");
        assertThat(columns)
                .as("the key covers the company first and the RFC inside it, in that order")
                .containsExactly("company_id", "rfc");
    }

    @Test
    @DisplayName("a supplier with no company is refused and leaves nothing stored")
    void supplierWithoutCompanyIsRefused() {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc)
                VALUES (?, 'Proveedor', 'SCHEMA-NOC-1')
                """, id)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM suppliers WHERE id = ?", Integer.class, id))
                .as("the refused insert stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("the same RFC in two companies is accepted: the key is per company, not global")
    void sameRfcInDifferentCompaniesIsAccepted() {
        var rfc = "SCHEMA-SHARED";
        var inCompanyA = insertSupplier(companyAId, rfc);
        var inCompanyB = insertSupplier(companyBId, rfc);

        var stored = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM suppliers WHERE id IN (?, ?)
                """, Integer.class, inCompanyA, inCompanyB);

        assertThat(stored).as("both businesses registered the same RFC").isEqualTo(2);
    }

    @Test
    @DisplayName("the same RFC twice inside one company is refused by uq_suppliers_company_rfc")
    void sameRfcTwiceInOneCompanyIsRefused() {
        var rfc = "SCHEMA-DUP-1";
        var accepted = insertSupplier(companyAId, rfc);
        var refusedId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                VALUES (?, 'Proveedor', ?, ?)
                """, refusedId, rfc, companyAId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_suppliers_company_rfc");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM suppliers WHERE id = ?", Integer.class, refusedId))
                .as("the refused row stored nothing")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM suppliers WHERE id = ?", Integer.class, accepted))
                .as("the accepted row is the only one holding this RFC in this company")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("fk_suppliers_company survives the contract and rejects a company that does not exist")
    void unknownCompanyIsRejectedByTheForeignKey() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                VALUES (?, 'Proveedor', 'SCHEMA-FK-1', ?)
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_suppliers_company");
    }

    @Test
    @DisplayName("uq_suppliers_company_id survives the contract: it is the next migration's FK target")
    void companyIdUniqueTargetSurvivesTheContract() {
        var uniqueConstraints = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'uq_suppliers_company_id'
                  AND constraint_type = 'UNIQUE'
                """, Integer.class);

        assertThat(uniqueConstraints).as("uq_suppliers_company_id exists").isEqualTo(1);
    }

    // ──────────────────────────────────────
    // V43 — the two tables that name a supplier
    // ──────────────────────────────────────

    @Test
    @DisplayName("V43 makes purchase_orders.company_id and product_suppliers.company_id mandatory")
    void consumersCompanyIdIsMandatory() {
        for (var table : List.of("purchase_orders", "product_suppliers")) {
            var nullable = jdbcTemplate.queryForObject("""
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = 'company_id'
                    """, String.class, table);

            assertThat(nullable)
                    .as("%s stores the owner its composite key needs, it does not derive it on read", table)
                    .isEqualTo("NO");
        }
    }

    @Test
    @DisplayName("a link whose company is its product's company and its supplier's is accepted")
    void productSupplierInsideOneCompanyIsAccepted() {
        var supplierOfA = insertSupplier(companyAId, "SCHEMA-PS-OK");
        var productOfA = insertProduct(companyAId);

        var id = insertProductSupplier(companyAId, productOfA, supplierOfA);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM product_suppliers WHERE id = ?", Integer.class, id))
                .as("the consistent triple is written")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a link whose company is not its product's company is refused by name")
    void productSupplierNamingAnotherCompanysProductIsRefused() {
        var supplierOfB = insertSupplier(companyBId, "SCHEMA-PS-P");
        var productOfA = insertProduct(companyAId);
        var refusedId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO product_suppliers (id, product_id, supplier_id, company_id)
                        VALUES (?, ?, ?, ?)
                        """, refusedId, productOfA, supplierOfB, companyBId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_product_suppliers_product_same_company");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM product_suppliers WHERE id = ?", Integer.class, refusedId))
                .as("the refused link stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("a link whose company is not its supplier's company is refused by name")
    void productSupplierNamingAnotherCompanysSupplierIsRefused() {
        var supplierOfB = insertSupplier(companyBId, "SCHEMA-PS-S");
        var productOfA = insertProduct(companyAId);
        var refusedId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO product_suppliers (id, product_id, supplier_id, company_id)
                        VALUES (?, ?, ?, ?)
                        """, refusedId, productOfA, supplierOfB, companyAId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_product_suppliers_supplier_same_company");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM product_suppliers WHERE id = ?", Integer.class, refusedId))
                .as("the refused link stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("a purchase order of a store and a supplier of the same company is accepted")
    void purchaseOrderInsideOneCompanyIsAccepted() {
        var storeOfA = insertStore(companyAId, "Schema Store A");
        var supplierOfA = insertSupplier(companyAId, "SCHEMA-PO-OK");

        var id = insertPurchaseOrder(companyAId, storeOfA, supplierOfA);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE id = ?", Integer.class, id))
                .as("the order whose supplier belongs to its store's company is written")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a purchase order naming another company's supplier is refused by name")
    void purchaseOrderNamingAnotherCompanysSupplierIsRefused() {
        var storeOfA = insertStore(companyAId, "Schema Store A");
        var supplierOfB = insertSupplier(companyBId, "SCHEMA-PO-S");
        var refusedId = UUID.randomUUID();

        assertThatThrownBy(() -> insertPurchaseOrderWithId(refusedId, companyAId, storeOfA, supplierOfB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_purchase_orders_supplier_same_company");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM purchase_orders WHERE id = ?", Integer.class, refusedId))
                .as("the refused order stored nothing")
                .isZero();
    }

    private UUID insertCompany(String key, String name, String rfc) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO companies (id, company_key, company_name, rfc, enabled)
                VALUES (?, ?, ?, ?, true)
                """, id, key, name, rfc);
        insertedCompanies.add(id);
        return id;
    }

    private UUID insertSupplier(UUID companyId, String rfc) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                VALUES (?, 'Proveedor', ?, ?)
                """, id, rfc, companyId);
        insertedSuppliers.add(id);
        return id;
    }

    private UUID insertProduct(UUID companyId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO products (id, sku, name, company_id, enabled)
                VALUES (?, ?, 'Schema product', ?, true)
                """, id, "SCHEMA-" + UUID.randomUUID().toString().substring(0, 8), companyId);
        insertedProducts.add(id);
        return id;
    }

    private UUID insertProductSupplier(UUID companyId, UUID productId, UUID supplierId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_suppliers (id, product_id, supplier_id, company_id)
                VALUES (?, ?, ?, ?)
                """, id, productId, supplierId, companyId);
        insertedProductSuppliers.add(id);
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

    /**
     * A store of the given company, inserted through the whole zone chain {@code company_stores} hangs
     * off. The store level stores its owner too (V48/V49): every level below the country carries a
     * {@code company_id}, so a raw insert has to name one at each hop.
     */
    private UUID insertStore(UUID companyId, String storeName) {
        var countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);

        var companyCountryId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                companyCountryId,
                companyId,
                countryId);
        insertedCompanyCountries.add(companyCountryId);

        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        var regionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, regionId, companyCountryId, companyId, suffix, "Schema region " + suffix);
        insertedRegions.add(regionId);

        var zoneId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, company_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, zoneId, regionId, companyId, suffix, "Schema zone " + suffix);
        insertedZones.add(zoneId);

        var storeId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO company_stores (id, company_zone_id, company_id, store_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, storeId, zoneId, companyId, storeName + " " + suffix);
        insertedStores.add(storeId);
        return storeId;
    }
}
