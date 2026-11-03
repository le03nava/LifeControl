package com.lifecontrol.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * Verification of {@code V70__goods_receipt_status_history.sql}, ADR-0004 task T7's schema half: the seed of
 * one {@code status_transition} creation row per existing goods receipt, decision 5's side A trigger on
 * {@code goods_receipts}, and the assertion that the five document tables of the ADR each carry their
 * deferred pair.
 *
 * <p><b>Why a scratch database.</b> The seed's whole question is about rows that already exist when the
 * migration runs, and the shared test database boots straight to head, where the seed has nothing to insert:
 * a green run there would say the file exists and nothing about what it reached. Here the database is held at
 * {@code V69} — the pre-unit head — receipts are written in the shape {@code V66} leaves behind, and the
 * chain then runs to head. The receipts are written at {@code V69} and not at an earlier head on purpose:
 * the purchase family's deferred pair is already armed there, so the fixture gives every purchase order and
 * every line its own creation row, which is what {@code V69} itself requires of a live writer.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}</b> and not as the container's superuser. {@code V52}
 * calls {@code ENABLE} <b>and FORCE</b> on every table it arms, {@code V65} arms {@code goods_receipts} and
 * {@code V68} FORCEs {@code status_transition}, so the policy applies to the table's <b>owner</b> — the role
 * Flyway runs as in delivery. A superuser bypasses policies whatever they say, so a seed asserted under it
 * would report the migration working while delivery's {@code NOSUPERUSER} owner matched zero rows in
 * silence. That is the premise {@code V70} opens its {@code NO FORCE} window for, and
 * {@code leavesBothTablesForced} pins the other half: the window has to close.</p>
 */
@DisplayName("Goods receipt status history (V70)")
class GoodsReceiptStatusHistoryMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: no publication of the receipt family's history exists yet. */
    private static final String PRE_UNIT_HEAD = "69";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    private static final String MIGRATION_ACTOR = "unknown:migration";

    private static final String MIGRATION_REASON = "MIGRATION";

    /**
     * The five document tables ADR-0004 types, each with the deferred trigger ADR-0004 decision 5 requires.
     *
     * <p>Stated as data and not as five assertions in prose: the assertion block {@code V70} runs is the
     * same list, and this case is what proves the block can fail at all — a migration that never ran the
     * block and a block that found nothing missing print the same green.</p>
     */
    private static final List<String> FIVE_DOCUMENT_TABLES =
            List.of("sales_orders", "sales_order_items", "purchase_orders", "purchase_order_details", "goods_receipts");

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_receipt_history_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("every pre-migration receipt gets exactly one creation row, and it is its own")
    void seedsOneCreationRowPerReceipt() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var first = seedCompany();
        var second = seedCompany();
        var firstReceipt = insertGoodsReceipt(first, 1, "2026-11-01 09:15:00");
        var firstTenantSecondReceipt = insertGoodsReceipt(first, 2, "2026-11-02 10:00:00");
        var otherTenantReceipt = insertGoodsReceipt(second, 1, "2026-11-03 11:30:00");

        migrateToHead();

        // The receipt's own fact: one row, no predecessor, its state and its own instant, and the actor the
        // ADR names for history the migration did not observe.
        assertThat(creationRows(first, firstReceipt))
                .as("one creation row per receipt, carrying the receipt's own created_at and state")
                .containsExactly("REGISTERED|2026-11-01 09:15:00|" + MIGRATION_ACTOR + "|" + MIGRATION_REASON);
        assertThat(creationRows(first, firstTenantSecondReceipt))
                .as("a second receipt of the same tenant is seeded too: the seed is per document and not per "
                        + "tenant")
                .containsExactly("REGISTERED|2026-11-02 10:00:00|" + MIGRATION_ACTOR + "|" + MIGRATION_REASON);
        assertThat(creationRows(second, otherTenantReceipt))
                .as("the second tenant's receipt carries the second tenant's company_id")
                .containsExactly("REGISTERED|2026-11-03 11:30:00|" + MIGRATION_ACTOR + "|" + MIGRATION_REASON);

        // The seed invents no transition: every seeded row of this family has no predecessor, because a
        // receipt is written in one state and never moves.
        assertThat(countScoped(
                        first,
                        "SELECT count(*) FROM status_transition WHERE from_status IS NOT NULL"
                                + " AND document_type = 'GOODS_RECEIPT'"))
                .as("the migration does not fabricate a move: the pre-migration history is one initial row")
                .isZero();
        // Counted with the scope stated: `status_transition` is FORCEd, so an unscoped query answers zero
        // over a table that does hold the three rows.
        assertThat(countScoped(first, "SELECT count(*) FROM status_transition WHERE document_type = 'GOODS_RECEIPT'"))
                .as("two receipts for the first tenant, and not one row more")
                .isEqualTo(2);
        assertThat(countScoped(second, "SELECT count(*) FROM status_transition WHERE document_type = 'GOODS_RECEIPT'"))
                .as("one receipt for the second tenant: no row is invented and none is skipped")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a creation row that already exists is kept instead of duplicated")
    void keepsAnExistingCreationRowInsteadOfDuplicatingIt() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var receipt = insertGoodsReceipt(company, 1, "2026-11-01 09:15:00");
        // The window between V69 and V70 is real: a database that served the application in between already
        // holds the creation row the recorder wrote, and `uq_status_transition_creation` allows exactly one.
        insertCreationRow(company, receipt, "2026-11-01 09:15:00");

        migrateToHead();

        assertThat(creationRows(company, receipt))
                .as("the seed takes the documents that have none and leaves the rest alone, so a migration over "
                        + "a served database neither duplicates a row nor fails on the unique index")
                .hasSize(1);
        assertThat(creationRows(company, receipt).get(0))
                .as("and the row it kept is the live one, written by a request, with its own actor")
                .contains("live:actor");
    }

    @Test
    @DisplayName("a receipt created without its creation row fails at COMMIT, and commits nothing")
    void refusesAReceiptBornWithoutItsCreationRow() throws Exception {
        // One receipt is written before the migration so the seed's own row exists; the refused write below
        // runs at head, where the pair is armed.
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var seeded = insertGoodsReceipt(company, 1, "2026-11-01 09:15:00");
        migrateToHead();

        var orphan = UUID.randomUUID();
        var order = purchaseOrderOf(company);
        assertThatThrownBy(() -> executeInOneTransaction(
                        company,
                        insertGoodsReceiptStatementAtHead(company, orphan, order, 2, "2026-11-07 08:00:00"),
                        "COMMIT"))
                .as("`trg_goods_receipts_transition_recorded` is DEFERRABLE INITIALLY DEFERRED, so the INSERT "
                        + "itself succeeds and only the commit can judge it against the history")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("was created without its status_transition row");
        assertThat(countScoped(company, "SELECT count(*) FROM goods_receipts WHERE company_id = '" + company + "'"))
                .as("the refused commit left no receipt behind: only the seeded one is there")
                .isEqualTo(1);
        assertThat(creationRows(company, orphan)).isEmpty();
        assertThat(creationRows(company, seeded)).hasSize(1);
    }

    @Test
    @DisplayName("a hand-made state change written without its history row fails at COMMIT")
    void refusesAStateChangeWithoutItsRowAtCommit() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var receipt = insertGoodsReceipt(company, 1, "2026-11-01 09:15:00");
        migrateToHead();

        // A receipt has exactly one state, so a *move* has to be made representable before it can be refused.
        // The scratch database loses the guard that makes it unrepresentable — `V66`'s `CHECK`, which lists the
        // single value — and keeps the one this case is about. Side A's UPDATE branch is then the only statement
        // in the schema that can judge this commit, which is exactly the question: a state moved and no row says
        // so. The bridge of `V66` used to be a third guard here; `V71` dropped it with `status_id`, so there is
        // nothing left to disable beside the `CHECK`.
        execute(null, "ALTER TABLE goods_receipts DROP CONSTRAINT ck_goods_receipts_status");

        assertThatThrownBy(() -> executeInOneTransaction(
                        company,
                        "UPDATE goods_receipts SET status = 'CANCELLED' WHERE id = '" + receipt + "'",
                        "COMMIT"))
                .as("side A's UPDATE branch: the state moved and no row says so, so the commit is refused")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("moved REGISTERED -> CANCELLED without its status_transition row");
        assertThat(readScoped(company, "SELECT status FROM goods_receipts WHERE id = '" + receipt + "'"))
                .as("the refused commit stored nothing: the receipt is still where it was")
                .isEqualTo("REGISTERED");
        assertThat(creationRows(company, receipt))
                .as("and no row was invented on the way out")
                .hasSize(1);
    }

    @Test
    @DisplayName("the window closes: both tables are enabled AND forced after the migration")
    void leavesBothTablesForced() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(rows("SELECT relname FROM pg_class WHERE relkind = 'r' AND relrowsecurity"
                        + " AND relforcerowsecurity"
                        + " AND relname IN ('goods_receipts', 'status_transition') ORDER BY relname"))
                .as("a window left open weakens the table for everything that runs after it (V55), and it is "
                        + "invisible in every other assertion this file makes")
                .containsExactly(new String[] {"goods_receipts"}, new String[] {"status_transition"});
    }

    @Test
    @DisplayName("all five document tables carry their deferred transition trigger, and the assertion has a list")
    void allFiveDocumentTablesCarryTheirDeferredPair() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        // The same question `V70`'s assertion block asks, asked from outside: the five tables of ADR-0004,
        // each with one trigger that is deferred, not internal, and points at the one validator function.
        var tables = new ArrayList<String>();
        for (var row : rows("SELECT t AS table_name FROM unnest(ARRAY['sales_orders', 'sales_order_items',"
                + " 'purchase_orders', 'purchase_order_details', 'goods_receipts']) AS t"
                + " WHERE EXISTS (SELECT 1 FROM pg_trigger g WHERE g.tgrelid = t::regclass"
                + "   AND g.tgdeferrable AND NOT g.tgisinternal"
                + "   AND g.tgfoid = 'assert_document_transition_recorded'::regproc)")) {
            tables.add(row[0]);
        }

        assertThat(tables)
                .as("a document table without its pair is a table whose state can move with no history, and the "
                        + "assertion block in V70 is what turns that from a comment into a failed migration")
                .containsExactlyInAnyOrderElementsOf(FIVE_DOCUMENT_TABLES);
        assertThat(rows("SELECT tgname FROM pg_trigger WHERE tgname ="
                        + " 'trg_goods_receipts_transition_recorded' AND tgdeferrable"))
                .as("the fifth pair is the one this unit created, deferred like the other four")
                .hasSize(1);
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

    /** A company with one store and one receiving location at the bottom of the hierarchy a receipt needs. */
    private UUID seedCompany() throws SQLException {
        var company = UUID.randomUUID();
        execute(
                null,
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('MXN', 2, '$', true)"
                        + " ON CONFLICT (code) DO NOTHING");
        execute(
                company,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + company + "', 'V70-"
                        + company.toString().substring(0, 8) + "', 'V70 Company', 'V70"
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
                        + region.toString().substring(0, 6) + "', 'V70 Region')");

        var companyZone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name) VALUES ('"
                        + companyZone + "', '" + company + "', '" + region + "', '"
                        + companyZone.toString().substring(0, 6) + "', 'V70 Zone')");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + companyZone + "', 'V70 Store', true)");

        var area = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_areas (id, company_store_id, area_code, area_name) VALUES ('" + area + "', '" + store
                        + "', 'V70A', 'V70 Area')");
        var storeZone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_zones (id, store_area_id, zone_code, zone_name) VALUES ('" + storeZone + "', '"
                        + area + "', 'V70Z', 'V70 Zone')");
        execute(
                company,
                "INSERT INTO store_locations (id, store_zone_id, location_code, location_name) VALUES ('"
                        + UUID.randomUUID() + "', '" + storeZone + "', 'V70L', 'V70 Receiving')");
        return company;
    }

    /**
     * One goods receipt as {@code V64} and {@code V66} leave the writer: an identity in parts, a
     * {@code status_id} from the catalog and, because that column exists and is required, the typed state the
     * bridge would otherwise derive. {@code created_at} is stated so the seed's instant is an assertion and
     * not a clock.
     *
     * <p>The order it names is written in the same transaction <b>with its own creation row</b>, because
     * {@code V69}'s pair is already armed at {@code PRE_UNIT_HEAD} and a purchase order born without its row
     * cannot commit there.</p>
     */
    private UUID insertGoodsReceipt(UUID company, long seq, String createdAt) throws SQLException {
        var order = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        executeInOneTransaction(
                company,
                insertPurchaseOrderStatement(company, order, seq, createdAt),
                insertPurchaseOrderCreationRow(company, order, createdAt),
                insertGoodsReceiptStatement(company, receipt, order, seq, createdAt),
                "COMMIT");
        return receipt;
    }

    /**
     * The same insert at head, where {@code V71} has dropped {@code status_id}: the typed state is the only
     * column a writer names, and the creation row is still what the commit is judged by.
     */
    private String insertGoodsReceiptStatementAtHead(UUID company, UUID id, UUID order, long seq, String createdAt)
            throws SQLException {
        var number =
                "GR-202611-" + String.format("%05d", seq) + "-" + id.toString().substring(0, 4);
        return "INSERT INTO goods_receipts (id, receipt_number, purchase_order_id, company_store_id,"
                + " receiving_location_id, status, received_by, received_at, comments, enabled,"
                + " company_id, doc_type, doc_number, created_at)"
                + " VALUES ('" + id + "', '" + number + "', '" + order + "', '"
                + storeOf(company) + "', '" + storeLocationOf(company) + "', 'REGISTERED', 'V70 receiver', '"
                + createdAt + "', 'V70 receipt', true, '" + company + "', 'GOODS_RECEIPT', '" + number
                + "', '" + createdAt + "')";
    }

    /**
     * The same insert as one statement with its id stated, so a case can run it inside a transaction and ask
     * what the deferred check does with a receipt that never got its row.
     */
    private String insertGoodsReceiptStatement(UUID company, UUID id, UUID order, long seq, String createdAt)
            throws SQLException {
        var number =
                "GR-202611-" + String.format("%05d", seq) + "-" + id.toString().substring(0, 4);
        return "INSERT INTO goods_receipts (id, receipt_number, purchase_order_id, company_store_id,"
                + " receiving_location_id, status_id, status, received_by, received_at, comments, enabled,"
                + " company_id, doc_type, doc_number, created_at)"
                + " VALUES ('" + id + "', '" + number + "', '" + order + "', '"
                + storeOf(company) + "', '" + storeLocationOf(company) + "', '"
                + statusId("GOODS_RECEIPT", "REGISTERED") + "', 'REGISTERED', 'V70 receiver', '" + createdAt
                + "', 'V70 receipt', true, '" + company + "', 'GOODS_RECEIPT', '" + number + "', '" + createdAt + "')";
    }

    /**
     * One purchase order, as the receipt's documented parent.
     *
     * <p>{@code V69} refuses a purchase order born without its history row, which is why the fixture below
     * states one: the shape a live writer uses, written by a fixture, on the pre-unit head.</p>
     */
    private String insertPurchaseOrderStatement(UUID company, UUID order, long seq, String createdAt)
            throws SQLException {
        var number = "PO-202611-" + String.format("%05d", seq) + "-"
                + order.toString().substring(0, 4);
        return "INSERT INTO purchase_orders (id, order_number, supplier_id, company_store_id, payment_method_id,"
                + " status_id, status, company_id, doc_type, seq_period, seq_value, doc_number, created_at)"
                + " VALUES ('" + order + "', '" + number + "', '" + supplierOf(company) + "', '" + storeOf(company)
                + "', '" + paymentMethodId() + "', '" + statusId("PURCHASE_ORDER", "DRAFT") + "', 'DRAFT', '"
                + company + "', 'PURCHASE_ORDER', '202611', " + seq + ", '" + number + "', '" + createdAt + "')";
    }

    /** The purchase order's own creation row, which {@code V69}'s armed pair requires of any writer. */
    private String insertPurchaseOrderCreationRow(UUID company, UUID order, String createdAt) {
        return "INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,"
                + " actor, occurred_at, reason) VALUES ('" + company + "', 'PURCHASE_ORDER', '" + order
                + "', NULL, 'DRAFT', 'unknown:test', '" + createdAt + "', 'fixture')";
    }

    /** A creation row written the way the recorder writes it, for the window between {@code V69} and {@code V70}. */
    private void insertCreationRow(UUID company, UUID receipt, String at) throws SQLException {
        execute(
                company,
                "INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,"
                        + " actor, occurred_at, reason) VALUES ('" + company + "', 'GOODS_RECEIPT', '" + receipt
                        + "', NULL, 'REGISTERED', 'live:actor', '" + at + "', 'first')");
    }

    /** The order of a company, which the receipt's composite key requires to be one of its own. */
    private UUID purchaseOrderOf(UUID company) throws SQLException {
        return readUuid(company, "SELECT id FROM purchase_orders WHERE company_id = '" + company + "'");
    }

    /** One supplier of the company, which the order's composite key requires. */
    private UUID supplierOf(UUID company) throws SQLException {
        var existing = readUnscoped("SELECT id FROM suppliers WHERE company_id = '" + company + "' LIMIT 1", true);
        if (existing != null) {
            return UUID.fromString(existing);
        }
        var supplier = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO suppliers (id, supplier_name, rfc, company_id, enabled) VALUES ('" + supplier
                        + "', 'V70 Supplier', 'V70"
                        + supplier.toString().replace("-", "").substring(0, 10).toUpperCase()
                        + "', '" + company + "', true)");
        return supplier;
    }

    /** One payment method, which the order requires and which is global. */
    private UUID paymentMethodId() throws SQLException {
        var existing = readUnscoped("SELECT id FROM payment_methods LIMIT 1", true);
        if (existing != null) {
            return UUID.fromString(existing);
        }
        var id = UUID.randomUUID();
        execute(
                null,
                "INSERT INTO payment_methods (id, payment_method_name, payment_method_short_name, enabled)"
                        + " VALUES ('" + id + "', 'V70 Transfer', 'V70T', true)");
        return id;
    }

    private UUID storeOf(UUID company) throws SQLException {
        return readUuid(company, "SELECT id FROM company_stores WHERE company_id = '" + company + "'");
    }

    private UUID storeLocationOf(UUID company) throws SQLException {
        return readUuid(
                company,
                "SELECT l.id FROM store_locations l JOIN store_zones z ON z.id = l.store_zone_id"
                        + " JOIN store_areas a ON a.id = z.store_area_id"
                        + " WHERE a.company_store_id = '" + storeOf(company) + "'");
    }

    private UUID statusId(String typeName, String state) throws SQLException {
        var id = readUnscoped(
                "SELECT s.id FROM statuses s JOIN status_types st ON st.id = s.status_type_id"
                        + " WHERE st.status_type_name = '" + typeName + "' AND s.code = '" + state + "'",
                false);
        return UUID.fromString(id);
    }

    // ─── reads ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Every creation row of one receipt, as {@code to_status|occurred_at|actor|reason}.
     *
     * <p>Read with the scope stated: {@code status_transition} is armed by {@code V67} and FORCEd, so an
     * unscoped read answers no row at all and would make "the seed wrote nothing" and "the seed wrote
     * everything" print the same green.</p>
     */
    private List<String> creationRows(UUID company, UUID receipt) throws SQLException {
        var sql = "SELECT to_status || '|' || to_char(occurred_at, 'YYYY-MM-DD HH24:MI:SS') || '|' || actor"
                + " || '|' || reason FROM status_transition WHERE company_id = '" + company + "'"
                + " AND document_type = 'GOODS_RECEIPT' AND document_id = '" + receipt + "'"
                + " AND from_status IS NULL";
        var rows = new ArrayList<String>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + company + "', false)");
            }
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    rows.add(resultSet.getString(1));
                }
            }
        }
        return rows;
    }

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

    /** A read of a table with no policy (currency, catalogs). */
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

    /** Every row of an unscoped query, one string per column. */
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
     * <p>The scope is not a convenience: {@code companies} is isolated by {@code id} and every tenant table by
     * {@code company_id}, so without it the write is refused by the policy's {@code WITH CHECK}.</p>
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

    /**
     * The statements inside one transaction the caller closes, so the deferred checks of decision 5 run where
     * they are designed to run: at COMMIT, not at the statement.
     *
     * <p>The scope is stated on the connection and not as {@code SET LOCAL}: the deferred trigger reads
     * {@code status_transition} and the document at commit time, and a scope that expired with the statement
     * would make both reads answer nothing — the migration's <em>own</em> failure mode, asserted from the other
     * side.</p>
     */
    private void executeInOneTransaction(UUID scope, String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                }
                for (var sql : statements) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                connection.setAutoCommit(true);
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
