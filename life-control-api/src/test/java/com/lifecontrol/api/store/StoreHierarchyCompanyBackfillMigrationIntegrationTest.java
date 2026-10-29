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
 * <b>nothing is discarded</b>, which is what separates this unit from {@code V40} and {@code V42}. The
 * purchase-order case is the one that proves the <b>absence</b> of a derivation: {@code V51} adds the
 * store key and re-derives nothing, so its order is seeded already consistent and asserted to survive
 * untouched.
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
 * purchase-order case asserts survival alone — there is no parent-derived value to compare, because the
 * migration under test does not derive one. The
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

    @Test
    @DisplayName("V47 derives every zone's company from its region and discards no zone")
    void zoneContractDerivesFromRegionAndDiscardsNothing() {
        migrateTo(PRE_UNIT_HEAD);

        var seeded = seedCompanyTree();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM company_zones", Integer.class))
                .as("the fixture wrote the zone at V43, where the table still has no owner column")
                .isEqualTo(1);

        migrateToHead();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM company_zones WHERE id = ?", UUID.class, seeded.zoneId()))
                .as("the owner is the fact of the row's own region — the second hop of the same walk")
                .isEqualTo(seeded.companyId());

        assertThat(jdbcTemplate.queryForList("SELECT id FROM company_zones", UUID.class))
                .as("the contract derives rather than discards: the seeded zone is still there")
                .isEqualTo(List.of(seeded.zoneId()));

        assertThat(jdbcTemplate.queryForList("SELECT id FROM company_regions", UUID.class))
                .as("and the region the zone derives from survives too")
                .isEqualTo(List.of(seeded.regionId()));
    }

    @Test
    @DisplayName("V49 derives every store's company from its zone and discards no store")
    void storeContractDerivesFromZoneAndDiscardsNothing() {
        migrateTo(PRE_UNIT_HEAD);

        var seeded = seedCompanyTree();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM company_stores", Integer.class))
                .as("the fixture wrote the store at V43, where the table still has no owner column")
                .isEqualTo(1);

        migrateToHead();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM company_stores WHERE id = ?", UUID.class, seeded.storeId()))
                .as("the owner is the fact of the row's own zone — the last hop of the same walk")
                .isEqualTo(seeded.companyId());

        assertThat(jdbcTemplate.queryForList("SELECT id FROM company_stores", UUID.class))
                .as("the contract derives rather than discards: the seeded store is still there")
                .isEqualTo(List.of(seeded.storeId()));

        assertThat(jdbcTemplate.queryForList("SELECT id FROM company_zones", UUID.class))
                .as("and the zone the store derives from survives too")
                .isEqualTo(List.of(seeded.zoneId()));

        assertThat(jdbcTemplate.queryForList("SELECT id FROM company_regions", UUID.class))
                .as("and the region the zone derives from survives as well")
                .isEqualTo(List.of(seeded.regionId()));
    }

    @Test
    @DisplayName("V51 adds the store key and leaves a consistent purchase order's owner untouched")
    void purchaseOrderStoreKeyLandsAndLeavesTheOwnerUntouched() {
        migrateTo(PRE_UNIT_HEAD);

        var seeded = seedCompanyTree();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_orders", Integer.class))
                .as("the fixture wrote a consistent order at V43, where nothing tied it to its store's company")
                .isEqualTo(1);

        migrateToHead();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM purchase_orders WHERE id = ?", UUID.class, seeded.purchaseOrderId()))
                .as("V51 adds a key and re-derives nothing: the order keeps the owner it was written with")
                .isEqualTo(seeded.companyId());

        assertThat(jdbcTemplate.queryForList("SELECT id FROM purchase_orders", UUID.class))
                .as("the guard refuses a database that contradicts itself, not a consistent row")
                .isEqualTo(List.of(seeded.purchaseOrderId()));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM company_stores WHERE id = ?", UUID.class, seeded.storeId()))
                .as("the store the new key points at still carries the company V49 derived for it")
                .isEqualTo(seeded.companyId());

        assertThat(jdbcTemplate.queryForList("SELECT id FROM company_zones", UUID.class))
                .as("and the zone the store derives from survives")
                .isEqualTo(List.of(seeded.zoneId()));

        assertThat(jdbcTemplate.queryForList("SELECT id FROM company_regions", UUID.class))
                .as("and the region the zone derives from survives as well")
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

    private record Seeded(UUID companyId, UUID regionId, UUID zoneId, UUID storeId, UUID purchaseOrderId) {}

    /**
     * Seeds exactly what the pre-unit schema could hold: a company tree whose three levels below the
     * country carry no {@code company_id} (the column does not exist yet at V43), plus one purchase order
     * that is <b>consistent</b> with the three keys already in force and with the one {@code V51} adds —
     * its supplier, its store and its own {@code company_id} all name the same company.
     *
     * <p>The order cannot be seeded contradicting itself, and that is the point: {@code V43} pins
     * {@code purchase_orders.company_id} to its supplier through
     * {@code fk_purchase_orders_supplier_same_company}, so the only contradiction writable here is with
     * the <b>store</b> — and {@code V51} <b>refuses</b> exactly that row instead of repairing it, because
     * no value of {@code company_id} satisfies both keys. A migration that refuses cannot be observed
     * through a fixture it rejects, so this fixture seeds the row that legitimately survives and the suite
     * asserts the migration left it alone.</p>
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

        var zoneId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, zone_code, zone_name, enabled)
                VALUES (?, ?, 'BFZ', 'Backfill Zone', true)
                """, zoneId, regionId);

        var storeId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO company_stores (id, company_zone_id, store_name, enabled)
                VALUES (?, ?, 'Backfill Store', true)
                """, storeId, zoneId);

        var supplierId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                VALUES (?, 'Backfill Supplier', 'BFSU010101AB3', ?)
                """, supplierId, companyId);

        var paymentMethodId = jdbcTemplate.queryForObject(
                "SELECT id FROM payment_methods ORDER BY payment_method_name LIMIT 1", UUID.class);
        var draftStatusId = jdbcTemplate.queryForObject("""
                SELECT s.id FROM statuses s
                JOIN status_types st ON st.id = s.status_type_id
                WHERE st.status_type_name = 'PURCHASE_ORDER' AND s.status_name = 'Draft'
                """, UUID.class);

        var purchaseOrderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO purchase_orders (id, order_number, supplier_id, company_store_id,
                                             payment_method_id, status_id, company_id, enabled)
                VALUES (?, 'BACKFILL-PO-1', ?, ?, ?, ?, ?, true)
                """, purchaseOrderId, supplierId, storeId, paymentMethodId, draftStatusId, companyId);

        return new Seeded(companyId, regionId, zoneId, storeId, purchaseOrderId);
    }
}
