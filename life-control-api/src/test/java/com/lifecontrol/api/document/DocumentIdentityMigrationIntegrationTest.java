package com.lifecontrol.api.document;

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
 * Verification of {@code V64__document_identity_per_tenant.sql} and {@code V65__Document_identity_render},
 * the schema and render halves of ADR-0003.
 *
 * <p><b>Why a scratch database.</b> Three facts this unit asserts are about a <b>rewrite of existing
 * rows</b>: the per-tenant, per-period numbering rebuilt from each document's own date, the receipt whose
 * number follows its parent's new number, and the counter seeded to the next value. The shared test
 * database boots straight to head, where every row already carries its parts, so a green run there would
 * say the columns exist and nothing about the rewrite. Here the database is held at {@code V63} — the
 * pre-unit head — a document history is written in the old shape, and the chain then runs to head.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}</b> and not as the container's superuser.
 * {@code V52} calls {@code ENABLE} <b>and {@code FORCE}</b> on every table it arms and {@code V53} arms
 * {@code purchase_orders}, so the policy applies to the table's <b>owner</b> — the role Flyway runs as in
 * delivery. A superuser bypasses policies whatever they say, so a backfill asserted under it would report
 * the rewrite working while delivery's {@code NOSUPERUSER} owner matched zero rows in silence. That is the
 * premise {@code V64} step 0 and {@code V65} open their {@code NO FORCE} windows for, and case 6 below
 * pins it from the other side.</p>
 */
@DisplayName("Document identity per tenant (V64, V65)")
class DocumentIdentityMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: no document carries an identity in parts. */
    private static final String PRE_UNIT_HEAD = "63";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName =
                "scratch_document_identity_" + UUID.randomUUID().toString().replace("-", "");
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

    // ─── cases ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each tenant numbers from its own counter, and two tenants hold the same rendered number")
    void numbersEachTenantFromItsOwnCounter() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var first = seedCompany();
        var second = seedCompany();
        var firstOrder = insertSalesOrder(first, "SO-20261029-00007", "2026-10-05 10:00:00");
        var secondOrder = insertSalesOrder(second, "SO-20261029-00031", "2026-10-06 10:00:00");

        migrateToHead();

        assertThat(docNumber(first, "sales_orders", firstOrder))
                .as("the first document of a tenant and period starts at 1, whatever the global history held")
                .isEqualTo("SO-202610-00001");
        assertThat(docNumber(second, "sales_orders", secondOrder))
                .as("a second tenant is not pushed past the first one's volume: the number is per tenant")
                .isEqualTo("SO-202610-00001");
        // Read per tenant and not in one unscoped query: `sales_orders` is armed from this unit on, so an
        // unscoped `count(*)` answers zero over a table that does hold both rows.
        assertThat(countScoped(first, "SELECT count(*) FROM sales_orders WHERE doc_number = 'SO-202610-00001'"))
                .as("the first tenant holds its own first document")
                .isEqualTo(1);
        assertThat(countScoped(second, "SELECT count(*) FROM sales_orders WHERE doc_number = 'SO-202610-00001'"))
                .as("the second tenant holds the same string — the global UNIQUE is gone, which is the point")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the rebuilt sequence follows the order documents were written, not their old printed number")
    void ordersByCreationNotByTheOldPrintedNumber() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = seedCompany();
        var writtenFirst = insertSalesOrder(company, "SO-20261029-00099", "2026-10-01 09:00:00");
        var writtenSecond = insertSalesOrder(company, "SO-20261029-00002", "2026-10-02 09:00:00");

        migrateToHead();

        assertThat(docNumber(company, "sales_orders", writtenFirst))
                .as("the earlier document takes the lower value even though its old number was higher")
                .isEqualTo("SO-202610-00001");
        assertThat(docNumber(company, "sales_orders", writtenSecond)).isEqualTo("SO-202610-00002");
    }

    @Test
    @DisplayName("the period comes from each document's own date, so one tenant has two independent counters")
    void derivesThePeriodFromTheDocumentDate() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = seedCompany();
        var october = insertSalesOrder(company, "SO-20261029-00001", "2026-10-31 23:59:00");
        var november = insertSalesOrder(company, "SO-20261101-00002", "2026-11-01 00:01:00");

        migrateToHead();

        assertThat(docNumber(company, "sales_orders", october)).isEqualTo("SO-202610-00001");
        assertThat(docNumber(company, "sales_orders", november))
                .as("a new period starts at 1 with no reset job")
                .isEqualTo("SO-202611-00001");
    }

    @Test
    @DisplayName("a document whose old number cannot be parsed is renumbered instead of refused")
    void renumbersAnUnparseableNumber() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = seedCompany();
        var legacy = insertSalesOrder(company, "SO-LEGACY-7", "2026-10-05 10:00:00");

        migrateToHead();

        assertThat(docNumber(company, "sales_orders", legacy))
                .as("the pre-flight reports it and the migration renumbers it: no history is dropped")
                .isEqualTo("SO-202610-00001");
    }

    @Test
    @DisplayName("a receipt names its parent's frozen number and keeps its own ordinal")
    void rendersReceiptsFromTheParentsFrozenNumber() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = seedCompany();
        var order = insertPurchaseOrder(company, "PO-20261029-00007", "2026-10-03 10:00:00");
        var firstReceipt = insertReceipt(company, order, "GR-PO-20261029-00007-01", "2026-10-04 10:00:00");
        var secondReceipt = insertReceipt(company, order, "GR-PO-20261029-00007-02", "2026-10-05 10:00:00");

        migrateToHead();

        var parentNumber = readScoped(company, "SELECT doc_number FROM purchase_orders WHERE id = '" + order + "'");
        assertThat(parentNumber).isEqualTo("PO-202610-00001");
        assertThat(docNumber(company, "goods_receipts", firstReceipt))
                .as("the receipt's number follows the parent it was renumbered with, in the same migration")
                .isEqualTo("GR-PO-202610-00001-01");
        assertThat(docNumber(company, "goods_receipts", secondReceipt)).isEqualTo("GR-PO-202610-00001-02");
        assertThat(readScoped(company, "SELECT company_id FROM goods_receipts WHERE id = '" + firstReceipt + "'"))
                .as("a receipt's tenant is its order's, which is what the composite key will pin")
                .isEqualTo(company.toString());
    }

    @Test
    @DisplayName("the counters are seeded to the next value, so the first allocation does not repeat a number")
    void seedsTheCounterToTheNextValue() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = seedCompany();
        insertSalesOrder(company, "SO-20261029-00001", "2026-10-01 09:00:00");
        insertSalesOrder(company, "SO-20261029-00002", "2026-10-02 09:00:00");
        insertPurchaseOrder(company, "PO-20261029-00001", "2026-10-02 09:00:00");

        migrateToHead();

        assertThat(readScoped(
                        company,
                        "SELECT next_value FROM document_sequence WHERE doc_type = 'SALES_ORDER'"
                                + " AND period = '202610'"))
                .as("max(seq_value) + 1 and not count(*): a gap in the history is a value already consumed")
                .isEqualTo("3");
        assertThat(readScoped(
                        company,
                        "SELECT next_value FROM document_sequence WHERE doc_type = 'PURCHASE_ORDER'"
                                + " AND period = '202610'"))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("the render ran as a Java migration, and the three tables joined ADR-0005's list")
    void ranTheJavaMigrationAndArmedTheThreeTables() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        seedSalesOrderHistory();
        migrateToHead();

        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '65'"))
                .as("a Java migration that Flyway never discovered would leave no row and fail nothing")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tenant_table WHERE table_name::text IN"
                        + " ('document_sequence', 'sales_orders', 'goods_receipts')"))
                .as("the arming is what stops TenantIsolationStartupCheck from refusing to boot (N9)")
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM pg_class WHERE relname IN"
                        + " ('document_sequence', 'sales_orders', 'goods_receipts')"
                        + " AND relkind = 'r' AND relrowsecurity AND relforcerowsecurity"))
                .as("listed is not enough: each table is enabled AND forced")
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM unprotected_tenant_tables()"))
                .as("V65 asserts this itself; asked here so the answer is part of the evidence")
                .isZero();
    }

    @Test
    @DisplayName("doc_type is a closed vocabulary, and a wrong value is refused by the schema")
    void refusesADocTypeOutsideTheVocabulary() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var order = insertSalesOrder(company, "SO-20261029-00001", "2026-10-05 10:00:00");
        migrateToHead();

        assertThatThrownBy(() -> execute(
                        company, "UPDATE sales_orders SET doc_type = 'GOODS_RECEIPT' WHERE id = '" + order + "'"))
                .as("a sales order cannot be filed as another document type")
                .hasStackTraceContaining("ck_sales_orders_doc_type");
        assertThatThrownBy(() -> execute(
                        company,
                        "INSERT INTO document_sequence (company_id, doc_type, period, next_value)" + " VALUES ('"
                                + company + "', 'GOODS_RECEIPT', '202610', 2)"))
                .as("a derived document must not open a counter of its own")
                .hasStackTraceContaining("ck_document_sequence_type");
    }

    @Test
    @DisplayName("two documents of one tenant and period cannot share the sequence value")
    void refusesADuplicateIdentity() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        insertSalesOrder(company, "SO-20261029-00001", "2026-10-01 09:00:00");
        migrateToHead();

        assertThatThrownBy(() -> execute(
                        company,
                        // The state is the only state column since `V71`: the fixture writes it and the insert
                        // fails on the identity, which is the constraint this case is about.
                        "INSERT INTO sales_orders (id, order_number, customer_id, company_store_id, order_date,"
                                + " status, company_id, doc_type, seq_period, seq_value, doc_number)"
                                + " VALUES ('" + UUID.randomUUID() + "', 'SO-202610-00001', '" + customerId()
                                + "', '" + storeOf(company) + "', '2026-10-20 10:00:00', 'DRAFT', '"
                                + company + "', 'SALES_ORDER', '202610', 1, 'SO-202610-00001')"))
                .as("the identity is the parts, and a duplicate insert is what fails")
                .hasStackTraceContaining("uq_sales_orders_doc_identity");
    }

    // ─── migrations ────────────────────────────────────────────────────────────────────────────────

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

    // ─── fixtures: the pre-unit tree, written as the role delivery migrates with ───────────────────

    /** A company with one store at the bottom of the hierarchy the composite keys require. */
    private UUID seedCompany() throws SQLException {
        var company = UUID.randomUUID();
        execute(
                null,
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('MXN', 2, '$', true)"
                        + " ON CONFLICT (code) DO NOTHING");
        execute(
                company,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + company + "', 'V64-"
                        + company.toString().substring(0, 8) + "', 'V64 Company', 'V64"
                        + company.toString().replace("-", "").substring(0, 10).toUpperCase() + "', 'MXN')");
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
                        + region.toString().substring(0, 6) + "', 'V64 Region')");

        var companyZone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name) VALUES ('"
                        + companyZone + "', '" + company + "', '" + region + "', '"
                        + companyZone.toString().substring(0, 6) + "', 'V64 Zone')");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + companyZone + "', 'V64 Store', true)");

        var area = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled) VALUES ('" + area
                        + "', '" + store + "', '" + area.toString().substring(0, 6) + "', 'V64 Area', true)");

        // A receiving location, because a receipt names one and `V59`'s trigger derives the store from the
        // zone chain: the fixture states the chain and lets the database derive the rest.
        var zone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_zones (id, store_area_id, zone_code, zone_name, enabled) VALUES ('" + zone + "', '"
                        + area + "', '" + zone.toString().substring(0, 6) + "', 'V64 Store Zone', true)");
        var location = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_locations (id, store_zone_id, location_code, location_name, enabled)" + " VALUES ('"
                        + location + "', '" + zone + "', 'V64-LOC', 'V64 Receiving', true)");
        return company;
    }

    private UUID insertSalesOrder(UUID company, String orderNumber, String orderDate) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO sales_orders (id, order_number, customer_id, company_store_id, order_date, status_id)"
                        + " VALUES ('" + id + "', '" + orderNumber + "', '" + customerId() + "', '" + storeOf(company)
                        + "', '" + orderDate + "', '" + statusId("SALES_ORDER") + "')");
        return id;
    }

    private UUID insertPurchaseOrder(UUID company, String orderNumber, String createdAt) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO purchase_orders (id, order_number, supplier_id, company_store_id, company_id,"
                        + " payment_method_id, status_id, created_at)"
                        + " VALUES ('" + id + "', '" + orderNumber + "', '" + supplierId(company) + "', '"
                        + storeOf(company) + "', '" + company + "', '" + paymentMethodId() + "', '"
                        + statusId("PURCHASE_ORDER") + "', '" + createdAt + "')");
        return id;
    }

    private UUID insertReceipt(UUID company, UUID purchaseOrder, String receiptNumber, String receivedAt)
            throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO goods_receipts (id, receipt_number, purchase_order_id, company_store_id,"
                        + " receiving_location_id, status_id, received_at)"
                        + " VALUES ('" + id + "', '" + receiptNumber + "', '" + purchaseOrder + "', '"
                        + storeOf(company) + "', '" + locationOf(company) + "', '" + statusId("GOODS_RECEIPT") + "', '"
                        + receivedAt + "')");
        return id;
    }

    /** One sales order in the old shape, for the cases that only need the migration to have rows to rewrite. */
    private void seedSalesOrderHistory() throws SQLException {
        var company = seedCompany();
        insertSalesOrder(company, "SO-20261029-00001", "2026-10-01 09:00:00");
        insertPurchaseOrder(company, "PO-20261029-00001", "2026-10-01 09:00:00");
        insertReceipt(
                company,
                readUuid(company, "SELECT id FROM purchase_orders LIMIT 1"),
                "GR-PO-20261029-00001-01",
                "2026-10-02 09:00:00");
    }

    private UUID storeOf(UUID company) throws SQLException {
        return readUuid(company, "SELECT id FROM company_stores WHERE company_id = '" + company + "'");
    }

    private UUID locationOf(UUID company) throws SQLException {
        return readUuid(
                company,
                "SELECT l.id FROM store_locations l JOIN store_zones z ON z.id = l.store_zone_id"
                        + " JOIN store_areas a ON a.id = z.store_area_id WHERE a.company_store_id = '"
                        + storeOf(company) + "' LIMIT 1");
    }

    private UUID customerId() throws SQLException {
        var existing = readUnscoped("SELECT id FROM customers LIMIT 1", true);
        return existing != null ? UUID.fromString(existing) : insertCustomer();
    }

    private UUID insertCustomer() throws SQLException {
        var id = UUID.randomUUID();
        execute(null, "INSERT INTO customers (id, name, sales_channel) VALUES ('" + id + "', 'V64 Customer', 'STORE')");
        return id;
    }

    private UUID supplierId(UUID company) throws SQLException {
        var existing = readUnscoped("SELECT id FROM suppliers WHERE company_id = '" + company + "' LIMIT 1", true);
        if (existing != null) {
            return UUID.fromString(existing);
        }
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO suppliers (id, supplier_name, rfc, company_id) VALUES ('" + id + "', 'V64 Supplier', 'V64"
                        + id.toString().replace("-", "").substring(0, 10).toUpperCase() + "', '" + company + "')");
        return id;
    }

    private UUID paymentMethodId() throws SQLException {
        var existing = readUnscoped("SELECT id FROM payment_methods LIMIT 1", false);
        if (existing != null) {
            return UUID.fromString(existing);
        }
        var id = UUID.randomUUID();
        execute(
                null,
                "INSERT INTO payment_methods (id, payment_method_name, payment_method_short_name)" + " VALUES ('" + id
                        + "', 'V64 Cash', 'V64CASH')");
        return id;
    }

    private UUID statusId(String typeName) throws SQLException {
        var id = readUnscoped(
                "SELECT s.id FROM statuses s JOIN status_types st ON st.id = s.status_type_id"
                        + " WHERE st.status_type_name = '" + typeName + "' AND s.status_name IN ('Draft', 'Registered')"
                        + " ORDER BY s.status_name LIMIT 1",
                false);
        if (id == null) {
            throw new IllegalStateException("V3 seeds no Draft/Registered status for " + typeName);
        }
        return UUID.fromString(id);
    }

    // ─── reads ─────────────────────────────────────────────────────────────────────────────────────

    private int countScoped(UUID scope, String sql) throws SQLException {
        return Integer.parseInt(readScoped(scope, sql));
    }

    private String docNumber(UUID company, String table, UUID id) throws SQLException {
        return readScoped(company, "SELECT doc_number FROM " + table + " WHERE id = '" + id + "'");
    }

    private UUID readUuid(UUID scope, String sql) throws SQLException {
        return UUID.fromString(readScoped(scope, sql));
    }

    /** A read of a tenant table with the scope set: without it the policies answer with no row at all. */
    private String readScoped(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
            }
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("no row for: " + sql);
                }
                return resultSet.getString(1);
            }
        }
    }

    /** A read of a table with no policy (currency, customers, payment methods, statuses). */
    private String readUnscoped(String sql, boolean mayBeEmpty) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                if (mayBeEmpty) {
                    return null;
                }
                throw new IllegalStateException("no row for: " + sql);
            }
            return resultSet.getString(1);
        }
    }

    private int count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    // ─── writes ────────────────────────────────────────────────────────────────────────────────────

    /**
     * One statement on one connection with the tenant scope set first.
     *
     * <p>The scope is not a convenience: {@code companies} is isolated by {@code id} and every tenant table
     * by {@code company_id}, so without it the write is refused by the policy's {@code WITH CHECK}.</p>
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

    // ─── the scratch database's own administration ─────────────────────────────────────────────────

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
