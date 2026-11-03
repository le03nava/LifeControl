package com.lifecontrol.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.goodsreceipt.model.GoodsReceiptState;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetailState;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderState;
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verification of {@code V66__document_state_typed.sql}: the typed {@code status} column on the five
 * document tables, the explicit backfill of each family's catalog names, the {@code CHECK} per family, the
 * {@code code} the catalog gains, and the temporary bridge trigger that keeps today's writers inside the new
 * {@code NOT NULL} while {@code status_id} still exists (ADR-0004 tasks T2; invariant T1 and T5).
 *
 * <p><b>Why a scratch database held at {@code V65}.</b> Four of the facts this unit asserts are about a
 * <b>rewrite of existing rows</b>: a state stored as a catalog row, a catalog that gains a code, a
 * {@code CHECK} that the pre-unit history could not obey, and a window ({@code NO FORCE ROW LEVEL SECURITY})
 * whose whole purpose is that a statement inside it <b>reaches</b> rows. The shared test database boots
 * straight to head, where every document already carries its typed state, so a green run there would say the
 * columns exist and nothing about the rewrite, and would never exercise a document written in the old shape.
 * Here the database is held at the pre-unit head — a document history is written exactly as today's writers
 * write it, with {@code status_id} and nothing else — and the chain then runs to head.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}</b> and not as the container's superuser.
 * {@code V52}'s {@code apply_tenant_isolation} sets <b>both</b> {@code ENABLE} and {@code FORCE ROW LEVEL
 * SECURITY}, and {@code V65} arms {@code sales_orders} and {@code goods_receipts} ({@code V53} already armed
 * {@code purchase_orders}). A forced policy filters the table's <b>owner</b> — the role Flyway runs as in
 * delivery — and a superuser bypasses policies whatever they say. Under the superuser the backfill would
 * report the rewrite working while delivery's {@code NOSUPERUSER} owner matched zero rows in silence; that is
 * the premise {@code V66} step 0 opens its window for, and case 6 pins it from the other side.</p>
 *
 * <p><b>Why the {@code CHECK} case disables the bridge trigger for one statement.</b> While {@code status_id}
 * exists, the bridge is the <b>first</b> thing that answers a wrong {@code status} — it raises before the row
 * ever reaches the {@code CHECK} — so the constraint underneath it is unobservable with the bridge live. The
 * case therefore disables that one trigger on this class's own scratch database (nothing else shares it),
 * asks the question, and re-enables it: what it wants to know is whether the {@code CHECK} exists and names
 * itself, and the answer cannot come from the layer above it. Case 5 is the bridge's own case, and it asserts
 * the opposite verdict.</p>
 */
@DisplayName("Document state typed (V66)")
class DocumentStateSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: no document carries a typed state and the catalog has no code. */
    private static final String PRE_UNIT_HEAD = "65";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The five families ADR-0004 types, spelled as {@code status_types.status_type_name} carries them. */
    private static final String IN_SCOPE_FAMILIES =
            "'SALES_ORDER', 'SALES_ORDER_ITEM', 'PURCHASE_ORDER'," + " 'PURCHASE_ORDER_DETAIL', 'GOODS_RECEIPT'";

    /** The two families the same ADR puts out of scope <b>by decision</b>, so their rows carry no code. */
    private static final String OUT_OF_SCOPE_FAMILIES = "'APPOINTMENT', 'EMPLOYEE_STATUS'";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_document_state_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("T1: each family refuses a state its enum does not have, and the message names its CHECK")
    void refusesAStateTheEnumDoesNotHave() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var salesOrder = insertSalesOrder(company, 1, "Draft");
        var salesOrderItem = insertSalesOrderItem(company, salesOrder, "Added");
        var purchaseOrder = insertPurchaseOrder(company, 1, "Draft");
        var purchaseDetail = insertPurchaseDetail(company, purchaseOrder, "Pending");
        var receipt = insertReceipt(company, purchaseOrder, 1, "Registered");
        migrateToHead();

        assertCheckRefusesTheValue(company, "sales_orders", salesOrder, "SHIPPED", "ck_sales_orders_status");
        assertCheckRefusesTheValue(
                company, "sales_order_items", salesOrderItem, "IN_PROCESS", "ck_sales_order_items_status");
        assertCheckRefusesTheValue(
                company, "purchase_orders", purchaseOrder, "PARTIAL_RECEIVED", "ck_purchase_orders_status");
        // 'ADDED' is a real state of the sales-order ITEM machine and no purchase-order detail has ever been
        // able to hold it — the defect an earlier draft of the ADR carried, refused here by the schema.
        assertCheckRefusesTheValue(
                company, "purchase_order_details", purchaseDetail, "ADDED", "ck_purchase_order_details_status");
        // A receipt is typed without inventing a cancellation the product never asked for.
        assertCheckRefusesTheValue(company, "goods_receipts", receipt, "CANCELLED", "ck_goods_receipts_status");
    }

    @Test
    @DisplayName("the backfill maps each catalog name to its constant instead of defaulting or deriving it")
    void backfillsEachFamilyFromItsOwnCatalogNames() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var salesOrder = insertSalesOrder(company, 1, "Completed");
        var salesOrderItem = insertSalesOrderItem(company, salesOrder, "Added");
        var purchaseOrder = insertPurchaseOrder(company, 1, "Billed");
        var purchaseDetail = insertPurchaseDetail(company, purchaseOrder, "Partial Received");
        var receipt = insertReceipt(company, purchaseOrder, 1, "Registered");
        migrateToHead();

        assertThat(readScoped(company, "SELECT status FROM sales_orders WHERE id = '" + salesOrder + "'"))
                .as("'Completed' becomes COMPLETED and not the family's first constant")
                .isEqualTo("COMPLETED");
        assertThat(readUnscoped("SELECT status FROM sales_order_items WHERE id = '" + salesOrderItem + "'", false))
                .as("the item family maps its own names, one table away from the order family")
                .isEqualTo("ADDED");
        assertThat(readScoped(company, "SELECT status FROM purchase_orders WHERE id = '" + purchaseOrder + "'"))
                .as("a name shared across families cannot cross-match: this is the PURCHASE_ORDER mapping")
                .isEqualTo("BILLED");
        assertThat(readUnscoped("SELECT status FROM purchase_order_details WHERE id = '" + purchaseDetail + "'", false))
                .as("the detail machine has its own tail, so the space in the name becomes the underscore")
                .isEqualTo("PARTIAL_RECEIVED");
        assertThat(readScoped(company, "SELECT status FROM goods_receipts WHERE id = '" + receipt + "'"))
                .as("the one seeded receipt state is typed, not defaulted")
                .isEqualTo("REGISTERED");
    }

    @Test
    @DisplayName("T5: the catalog carries a code inside the five in-scope families and only there")
    void codesTheCatalogInsideTheScopeTheAdrClaims() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        seedCompany();
        migrateToHead();

        assertThat(count("SELECT count(*) FROM statuses s JOIN status_types st ON st.id = s.status_type_id"
                        + " WHERE st.status_type_name IN (" + IN_SCOPE_FAMILIES + ") AND s.code IS NULL"))
                .as("every row of an in-scope family received a code; the migration asserts it too")
                .isZero();
        assertThat(count("SELECT count(*) FROM statuses s JOIN status_types st ON st.id = s.status_type_id"
                        + " WHERE st.status_type_name IN (" + OUT_OF_SCOPE_FAMILIES + ")"))
                .as("the two out-of-scope families do have rows, so the assertion below is not vacuous")
                .isEqualTo(9);
        assertThat(count("SELECT count(*) FROM statuses s JOIN status_types st ON st.id = s.status_type_id"
                        + " WHERE st.status_type_name IN (" + OUT_OF_SCOPE_FAMILIES + ") AND s.code IS NOT NULL"))
                .as("a code for a family with no domain enum would be a constant that does not exist")
                .isZero();

        var constants = constantsByFamily();
        for (var row : rows("SELECT st.status_type_name, s.code FROM statuses s"
                + " JOIN status_types st ON st.id = s.status_type_id"
                + " WHERE st.status_type_name IN (" + IN_SCOPE_FAMILIES + ") AND s.code IS NOT NULL")) {
            assertThat(constants.get(row[0]))
                    .as("%s carries %s, which its enum must declare", row[0], row[1])
                    .contains(row[1]);
        }
    }

    @Test
    @DisplayName("the window closes: the three document tables are forced again after the migration")
    void leavesTheThreeDocumentTablesForced() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        seedCompany();
        migrateToHead();

        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '66'"))
                .as("the close below belongs to this unit: without it the question is about V65's own window")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pg_class WHERE relkind = 'r' AND relforcerowsecurity"
                        + " AND relname IN ('sales_orders', 'purchase_orders', 'goods_receipts')"))
                .as("a window left open weakens the table for everything that runs after it")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the bridge derives the state from the catalog, refuses a divergence and fails closed on a gap")
    void derivesTheStateFromTheCatalogWhileStatusIdLives() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        migrateToHead();

        // A writer of today sets `status_id` and nothing else; the new `NOT NULL` would refuse it wholesale
        // without this trigger, which is the only reason the column can be required before T8 drops the old one.
        var derived = insertSalesOrder(company, 1, statusId("SALES_ORDER", "Draft"), null);
        assertThat(readScoped(company, "SELECT status FROM sales_orders WHERE id = '" + derived + "'"))
                .as("an insert that names only status_id is derived from the catalog's code")
                .isEqualTo("DRAFT");

        assertThatThrownBy(() -> insertSalesOrder(company, 2, statusId("SALES_ORDER", "Draft"), "ACTIVE"))
                .as("a stored state that disagrees with the catalog cannot exist while both columns do")
                .hasStackTraceContaining("sales_orders: stored status ACTIVE disagrees with the catalog code DRAFT");

        assertThatThrownBy(() -> insertSalesOrder(company, 3, statusId("APPOINTMENT", "Scheduled"), null))
                .as("a status_id whose catalog row has no code leaves status null, and NOT NULL then refuses it")
                .hasStackTraceContaining("null value in column \"status\"");
    }

    @Test
    @DisplayName("the history reached the backfill: the seeded documents exist and carry a state")
    void theSeededDocumentsSurviveWithAState() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var salesOrder = insertSalesOrder(company, 1, "Completed");
        var purchaseOrder = insertPurchaseOrder(company, 1, "Billed");
        var receipt = insertReceipt(company, purchaseOrder, 1, "Registered");
        migrateToHead();

        assertThat(countScoped(
                        company,
                        "SELECT count(*) FROM sales_orders WHERE id = '" + salesOrder + "' AND status IS NOT NULL"))
                .as("a file that backfilled nothing and one that backfilled everything print the same green")
                .isEqualTo(1);
        assertThat(countScoped(
                        company,
                        "SELECT count(*) FROM purchase_orders WHERE id = '" + purchaseOrder
                                + "' AND status IS NOT NULL"))
                .isEqualTo(1);
        assertThat(countScoped(
                        company,
                        "SELECT count(*) FROM goods_receipts WHERE id = '" + receipt + "' AND status IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a status from another family aborts the migration, and the abort leaves the database at V65")
    void refusesAStatusFromAnotherFamilyAndLeavesTheDatabaseAtThePreUnitHead() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        // `Billed` is seeded by `V3` for `PURCHASE_ORDER` only, so this order's state is a real catalog row
        // that the SALES_ORDER `VALUES` list does not carry. A join on `status_id` alone would map it — the
        // cross-match step 2's family filter exists to refuse — and the row would be typed with a state its
        // own enum does not have.
        var billed = statusId("PURCHASE_ORDER", "Billed");
        var salesOrder = insertSalesOrder(company, 1, billed, null);

        assertThatThrownBy(this::migrateToHead)
                .as("an unmapped name must abort the file instead of leaving a NULL status behind")
                .hasStackTraceContaining("could not map these catalog names")
                .hasStackTraceContaining("sales_orders (Billed)");

        // The file is one transaction and PostgreSQL's DDL rolls back with it, so what the next attempt
        // starts from is the pre-unit database and not a half-migrated one. Each half is asked separately:
        // a rollback that restored the rows and lost the policy, or the reverse, is a different defect.
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '66'"))
                .as("a migration that aborted records no version, so the next run retries it")
                .isZero();
        assertThat(count("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = 'sales_orders'"
                        + " AND column_name = 'status'"))
                .as("step 1's column rolled back with the abort")
                .isZero();
        assertThat(count("SELECT count(*) FROM pg_class WHERE relkind = 'r' AND relforcerowsecurity"
                        + " AND relname IN ('sales_orders', 'purchase_orders', 'goods_receipts')"))
                .as("step 0's NO FORCE window rolled back too: a failed file must not leave it open")
                .isEqualTo(3);
        assertThat(readScoped(company, "SELECT status_id FROM sales_orders WHERE id = '" + salesOrder + "'"))
                .as("the seeded row is untouched, which is what makes the abort a refusal and not a rewrite")
                .isEqualTo(billed.toString());
    }

    // ─── the CHECK, observed underneath the bridge ─────────────────────────────────────────────────

    /**
     * Asks the {@code CHECK} its own question, with the bridge out of the way for exactly one statement.
     *
     * <p>The bridge raises on any {@code status} that disagrees with the catalog's code, and every value a
     * {@code CHECK} refuses disagrees with it by construction — so with the bridge live the constraint is
     * unreachable and its existence is unpinned. Disabling <b>that one trigger</b>, on this class's own
     * scratch database, is what lets the case below observe the net that stays after T8 drops the bridge; the
     * trigger is re-enabled in a {@code finally} so a failing assertion cannot leave the table weaker than the
     * next statement expects.</p>
     */
    private void assertCheckRefusesTheValue(UUID company, String table, UUID id, String value, String constraint)
            throws SQLException {
        var trigger = "trg_" + table + "_status_bridge";
        execute(company, "ALTER TABLE " + table + " DISABLE TRIGGER " + trigger);
        try {
            assertThatThrownBy(() -> execute(
                            company, "UPDATE " + table + " SET status = '" + value + "' WHERE id = '" + id + "'"))
                    .as("%s must refuse %s, a value no enum of that family declares", table, value)
                    .hasStackTraceContaining(constraint);
        } finally {
            execute(company, "ALTER TABLE " + table + " ENABLE TRIGGER " + trigger);
        }
    }

    /** Every constant of each family's enum, keyed by the {@code status_types} name that family carries. */
    private Map<String, List<String>> constantsByFamily() {
        Map<String, List<String>> constants = new LinkedHashMap<>();
        constants.put("SALES_ORDER", names(SalesOrderState.values()));
        constants.put("SALES_ORDER_ITEM", names(SalesOrderItemState.values()));
        constants.put("PURCHASE_ORDER", names(PurchaseOrderState.values()));
        constants.put("PURCHASE_ORDER_DETAIL", names(PurchaseOrderDetailState.values()));
        constants.put("GOODS_RECEIPT", names(GoodsReceiptState.values()));
        return constants;
    }

    private List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toList());
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
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + company + "', 'V66-"
                        + company.toString().substring(0, 8) + "', 'V66 Company', 'V66"
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
                        + region.toString().substring(0, 6) + "', 'V66 Region')");

        var companyZone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name) VALUES ('"
                        + companyZone + "', '" + company + "', '" + region + "', '"
                        + companyZone.toString().substring(0, 6) + "', 'V66 Zone')");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + companyZone + "', 'V66 Store', true)");

        var area = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled) VALUES ('" + area
                        + "', '" + store + "', '" + area.toString().substring(0, 6) + "', 'V66 Area', true)");

        // A receiving location, because a receipt names one and `V59`'s trigger derives the store from the
        // zone chain: the fixture states the chain and lets the database derive the rest.
        var zone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_zones (id, store_area_id, zone_code, zone_name, enabled) VALUES ('" + zone + "', '"
                        + area + "', '" + zone.toString().substring(0, 6) + "', 'V66 Store Zone', true)");
        var location = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_locations (id, store_zone_id, location_code, location_name, enabled)" + " VALUES ('"
                        + location + "', '" + zone + "', 'V66-LOC', 'V66 Receiving', true)");
        return company;
    }

    /** The catalog row of one family and one name: what today's writers set, and the backfill's only input. */
    private UUID statusId(String typeName, String statusName) throws SQLException {
        var id = readUnscoped(
                "SELECT s.id FROM statuses s JOIN status_types st ON st.id = s.status_type_id"
                        + " WHERE st.status_type_name = '" + typeName + "' AND s.status_name = '" + statusName + "'",
                false);
        return UUID.fromString(id);
    }

    /**
     * One sales order, in the shape {@code V65} leaves behind and today's writer writes: an identity in parts
     * (ADR-0003) and one {@code status_id} from the catalog.
     *
     * @param status the typed state, or {@code null} to omit the column entirely — which is how a pre-unit
     *     document is written, and how the bridge's derivation case is written
     */
    private UUID insertSalesOrder(UUID company, long seq, UUID statusId, String status) throws SQLException {
        var id = UUID.randomUUID();
        var number = "SO-202610-" + String.format("%05d", seq);
        var columns = new StringBuilder("INSERT INTO sales_orders (id, order_number, customer_id, company_store_id,"
                + " order_date, status_id, company_id, doc_type, seq_period, seq_value, doc_number");
        var values = new StringBuilder(" VALUES ('" + id + "', '" + number + "', '" + customerId() + "', '"
                + storeOf(company) + "', '2026-10-05 10:00:00', '" + statusId + "', '" + company
                + "', 'SALES_ORDER', '202610', " + seq + ", '" + number + "'");
        if (status != null) {
            columns.append(", status");
            values.append(", '").append(status).append("'");
        }
        execute(company, columns + ") " + values + ")");
        return id;
    }

    private UUID insertSalesOrder(UUID company, long seq, String statusName) throws SQLException {
        return insertSalesOrder(company, seq, statusId("SALES_ORDER", statusName), null);
    }

    private UUID insertSalesOrderItem(UUID company, UUID salesOrder, String statusName) throws SQLException {
        var id = UUID.randomUUID();
        var catalog = seedVariant(company);
        execute(
                company,
                "INSERT INTO sales_order_items (id, sales_order_id, product_variant_id, quantity, list_price,"
                        + " final_price, status_id) VALUES ('" + id + "', '" + salesOrder + "', '"
                        + catalog.variantId() + "', 1, 100.00, 100.00, '" + statusId("SALES_ORDER_ITEM", statusName)
                        + "')");
        return id;
    }

    private UUID insertPurchaseOrder(UUID company, long seq, String statusName) throws SQLException {
        var id = UUID.randomUUID();
        var number = "PO-202610-" + String.format("%05d", seq);
        execute(
                company,
                "INSERT INTO purchase_orders (id, order_number, supplier_id, company_store_id, company_id,"
                        + " payment_method_id, status_id, created_at, seq_period, seq_value, doc_number)"
                        + " VALUES ('" + id + "', '" + number + "', '" + supplierId(company) + "', '"
                        + storeOf(company) + "', '" + company + "', '" + paymentMethodId() + "', '"
                        + statusId("PURCHASE_ORDER", statusName) + "', '2026-10-05 10:00:00', '202610', " + seq + ", '"
                        + number + "')");
        return id;
    }

    private UUID insertPurchaseDetail(UUID company, UUID purchaseOrder, String statusName) throws SQLException {
        var id = UUID.randomUUID();
        var catalog = seedVariant(company);
        execute(
                company,
                "INSERT INTO purchase_order_details (id, purchase_order_id, product_id, product_variant_id, quantity,"
                        + " unit_price, total, status_id) VALUES ('" + id + "', '" + purchaseOrder + "', '"
                        + catalog.productId() + "', '" + catalog.variantId() + "', 1, 100.00, 100.00, '"
                        + statusId("PURCHASE_ORDER_DETAIL", statusName) + "')");
        return id;
    }

    private UUID insertReceipt(UUID company, UUID purchaseOrder, long ordinal, String statusName) throws SQLException {
        var id = UUID.randomUUID();
        var parent = readScoped(company, "SELECT doc_number FROM purchase_orders WHERE id = '" + purchaseOrder + "'");
        var number = "GR-" + parent + "-" + String.format("%02d", ordinal);
        execute(
                company,
                "INSERT INTO goods_receipts (id, receipt_number, purchase_order_id, company_store_id,"
                        + " receiving_location_id, status_id, received_at, company_id, doc_number)"
                        + " VALUES ('" + id + "', '" + number + "', '" + purchaseOrder + "', '" + storeOf(company)
                        + "', '" + locationOf(company) + "', '" + statusId("GOODS_RECEIPT", statusName)
                        + "', '2026-10-06 10:00:00', '" + company + "', '" + number + "')");
        return id;
    }

    /** A record rather than two lookups: a purchase detail names the product and the variant in one row. */
    private record Catalog(UUID productId, UUID variantId) {}

    /** One product and one variant of it, owned by the company: what an item and a detail line must name. */
    private Catalog seedVariant(UUID company) throws SQLException {
        var product = UUID.randomUUID();
        var suffix = product.toString().substring(0, 8);
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id) VALUES ('" + product + "', 'V66-" + suffix
                        + "', 'V66 Product', '" + company + "')");
        var variant = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO product_variants (id, product_id, bar_code, variant_name, measure_unit_id, company_id)"
                        + " VALUES ('" + variant + "', '" + product + "', 'V66-BC-" + suffix + "', 'unica', '"
                        + measureUnitId() + "', '" + company + "')");
        return new Catalog(product, variant);
    }

    private UUID measureUnitId() throws SQLException {
        return UUID.fromString(readUnscoped("SELECT id FROM measure_units WHERE sat_code = 'H87'", false));
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
        execute(null, "INSERT INTO customers (id, name, sales_channel) VALUES ('" + id + "', 'V66 Customer', 'STORE')");
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
                "INSERT INTO suppliers (id, supplier_name, rfc, company_id) VALUES ('" + id + "', 'V66 Supplier', 'V66"
                        + id.toString().replace("-", "").substring(0, 10).toUpperCase() + "', '" + company + "')");
        return id;
    }

    private UUID paymentMethodId() throws SQLException {
        var existing = readUnscoped("SELECT id FROM payment_methods LIMIT 1", false);
        return UUID.fromString(existing);
    }

    // ─── reads ─────────────────────────────────────────────────────────────────────────────────────

    private int countScoped(UUID scope, String sql) throws SQLException {
        return Integer.parseInt(readScoped(scope, sql));
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

    /** A read of a table with no policy (currency, customers, payment methods, catalogs, items, details). */
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

    /** Every row of an unscoped query, as strings: the catalog is not a tenant table. */
    private List<String[]> rows(String sql) throws SQLException {
        var result = new ArrayList<String[]>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            var columns = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                var row = new String[columns];
                for (int column = 0; column < columns; column++) {
                    row[column] = resultSet.getString(column + 1);
                }
                result.add(row);
            }
        }
        return result;
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
