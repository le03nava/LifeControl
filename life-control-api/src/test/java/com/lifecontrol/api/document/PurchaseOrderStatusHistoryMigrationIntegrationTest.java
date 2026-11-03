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
 * Verification of {@code V69__purchase_order_status_history.sql}, ADR-0004 task T6's schema half: the seed
 * of one {@code status_transition} creation row per existing purchase order <b>and</b> per existing line, and
 * decision 5's side A trigger on the two purchase tables.
 *
 * <p><b>Why a scratch database.</b> Three facts this unit asserts are about rows that already exist when the
 * migration runs: the seed has to reach <b>every</b> document and every detail, with that document's own
 * {@code created_at} and its own state, and the tenant of a line has to come from its parent order because
 * {@code purchase_order_details} carries no {@code company_id}. The shared test database boots straight to
 * head, where the seed has nothing to insert, so a green run there would say the migration exists and nothing
 * about the seed. Here the database is held at {@code V68} — the pre-unit head — a purchase history is
 * written in the shape {@code V66} leaves behind, and the chain then runs to head.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}</b> and not as the container's superuser. {@code V52}
 * calls {@code ENABLE} <b>and {@code FORCE}</b> on every table it arms, {@code V53} arms
 * {@code purchase_orders} and {@code V67} arms {@code status_transition}, so the policy applies to the
 * table's <b>owner</b> — the role Flyway runs as in delivery. A superuser bypasses policies whatever they
 * say, so a seed asserted under it would report the migration working while delivery's {@code NOSUPERUSER}
 * owner matched zero rows in silence. That is exactly the premise {@code V69} opens its {@code NO FORCE}
 * window for, and case {@code leavesBothTablesForced} pins the other half: the window has to close.</p>
 */
@DisplayName("Purchase order status history (V69)")
class PurchaseOrderStatusHistoryMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: no publication of the purchase family's history exists yet. */
    private static final String PRE_UNIT_HEAD = "68";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    private static final String MIGRATION_ACTOR = "unknown:migration";

    private static final String MIGRATION_REASON = "MIGRATION";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName =
                "scratch_purchase_history_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("every pre-migration order and every detail gets exactly one creation row, and it is its own")
    void seedsOneCreationRowPerDocumentAndPerDetail() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var first = seedCompany();
        var second = seedCompany();
        var twoLineOrder = insertPurchaseOrder(first, 1, "DRAFT", "2026-11-01 09:15:00");
        var firstDetail = insertPurchaseOrderDetail(first, twoLineOrder, "PENDING", "2026-11-01 09:16:00");
        var secondDetail = insertPurchaseOrderDetail(first, twoLineOrder, "PARTIAL_RECEIVED", "2026-11-01 09:17:00");
        var emptyOrder = insertPurchaseOrder(first, 2, "ACCEPTED", "2026-11-02 10:00:00");
        var otherTenantOrder = insertPurchaseOrder(second, 1, "IN_TRANSIT", "2026-11-03 11:30:00");
        var otherTenantDetail =
                insertPurchaseOrderDetail(second, otherTenantOrder, "IN_PROCESS", "2026-11-03 11:31:00");

        migrateToHead();

        // The document's own fact: one row, no predecessor, its state and its instant, and the actor the
        // ADR names for history the migration did not observe.
        assertThat(creationRows(first, "PURCHASE_ORDER", twoLineOrder))
                .as("one creation row per document, and the document's own created_at and state")
                .containsExactly("DRAFT|2026-11-01 09:15:00|" + MIGRATION_ACTOR + "|" + MIGRATION_REASON);
        assertThat(creationRows(first, "PURCHASE_ORDER", emptyOrder))
                .as("an order with no lines is still a document and still gets its row")
                .containsExactly("ACCEPTED|2026-11-02 10:00:00|" + MIGRATION_ACTOR + "|" + MIGRATION_REASON);
        assertThat(creationRows(second, "PURCHASE_ORDER", otherTenantOrder))
                .as("the second tenant's order carries the second tenant's company_id")
                .containsExactly("IN_TRANSIT|2026-11-03 11:30:00|" + MIGRATION_ACTOR + "|" + MIGRATION_REASON);

        // The line's fact: `purchase_order_details` carries no company_id, so the tenant has to come from the
        // parent order — a row seeded under any other company_id is refused by decision 6's trigger, and one
        // whose company_id were NULL would be a history nobody could read.
        assertThat(creationRows(first, "PURCHASE_ORDER_DETAIL", firstDetail))
                .as("the detail's state, not its order's, and the detail's own created_at")
                .containsExactly("PENDING|2026-11-01 09:16:00|" + MIGRATION_ACTOR + "|" + MIGRATION_REASON);
        assertThat(creationRows(first, "PURCHASE_ORDER_DETAIL", secondDetail))
                .as("a partially received line is seeded in the state the row already held: the seed does not "
                        + "reconstruct history")
                .containsExactly("PARTIAL_RECEIVED|2026-11-01 09:17:00|" + MIGRATION_ACTOR + "|" + MIGRATION_REASON);
        assertThat(creationRows(second, "PURCHASE_ORDER_DETAIL", otherTenantDetail))
                .as("a detail's tenant is its parent order's company_id")
                .containsExactly("IN_PROCESS|2026-11-03 11:31:00|" + MIGRATION_ACTOR + "|" + MIGRATION_REASON);

        // Counted with the scope stated, like every other read of an armed table: the unscoped query answers
        // zero over a table that does hold the six rows, which is the failure mode this file's own migration
        // window exists to defeat.
        assertThat(countScoped(first, "SELECT count(*) FROM status_transition"))
                .as("two documents and two details for the first tenant, and not one row more")
                .isEqualTo(4);
        assertThat(countScoped(second, "SELECT count(*) FROM status_transition"))
                .as("one document and one detail for the second tenant: no row is invented and none is skipped")
                .isEqualTo(2);
        assertThat(countScoped(first, "SELECT count(*) FROM status_transition WHERE from_status IS NULL"))
                .as("every seeded row is a creation row: the migration does not fabricate transitions")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("a creation row that already exists is kept instead of duplicated")
    void keepsAnExistingCreationRowInsteadOfDuplicatingIt() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var order = insertPurchaseOrder(company, 1, "DRAFT", "2026-11-01 09:15:00");
        // The window between V68 and V69 is real: a database that served the application in between already
        // holds the creation row the recorder wrote, and `uq_status_transition_creation` allows exactly one.
        insertCreationRow(company, "PURCHASE_ORDER", order, "DRAFT", "2026-11-01 09:15:00");

        migrateToHead();

        assertThat(creationRows(company, "PURCHASE_ORDER", order))
                .as("the seed takes the documents that have none and leaves the rest alone, so a migration over "
                        + "a served database neither duplicates a row nor fails on the unique index")
                .hasSize(1);
        assertThat(creationRows(company, "PURCHASE_ORDER", order).get(0))
                .as("and the row it kept is the live one, written by a request, with its own actor")
                .contains("live:actor");
    }

    @Test
    @DisplayName("a state change written without its history row fails at COMMIT, and commits nothing")
    void refusesAStateChangeWithoutItsRowAtCommit() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var order = insertPurchaseOrder(company, 1, "DRAFT", "2026-11-01 09:15:00");
        var detail = insertPurchaseOrderDetail(company, order, "PENDING", "2026-11-01 09:16:00");
        migrateToHead();

        assertThatThrownBy(() -> executeInOneTransaction(
                        company,
                        // Both columns move, as a writer that forgot only the history row would move them: the
                        // bridge of V66 stays silent, and the only thing that can refuse this is side A.
                        "UPDATE purchase_orders SET status = 'SENT', status_id = '" + statusId("PURCHASE_ORDER", "SENT")
                                + "' WHERE id = '" + order + "'",
                        "COMMIT"))
                .as("`trg_purchase_orders_transition_recorded` is DEFERRABLE INITIALLY DEFERRED, so the UPDATE "
                        + "itself succeeds and only the commit can judge it against the history")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("moved DRAFT -> SENT without its status_transition row");
        assertThat(readScoped(company, "SELECT status FROM purchase_orders WHERE id = '" + order + "'"))
                .as("the refused commit stored nothing: the document is still where it was")
                .isEqualTo("DRAFT");

        assertThatThrownBy(() -> executeInOneTransaction(
                        company,
                        "UPDATE purchase_order_details SET status = 'IN_PROCESS', status_id = '"
                                + statusId("PURCHASE_ORDER_DETAIL", "IN_PROCESS") + "' WHERE id = '" + detail + "'",
                        "COMMIT"))
                .as("the detail table has its own pair of triggers and its own deferred check")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("moved PENDING -> IN_PROCESS without its status_transition row");
        assertThat(readScoped(company, "SELECT status FROM purchase_order_details WHERE id = '" + detail + "'"))
                .as("the detail was not moved either")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a document created without its creation row fails at COMMIT, and the same goes for a detail")
    void refusesADocumentBornWithoutItsCreationRow() throws Exception {
        // The order is written before the migration so it carries its seeded row, and the two refused writes
        // below are the ones this case is about: they run at head, where the pair is armed.
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var order = insertPurchaseOrder(company, 1, "DRAFT", "2026-11-06 08:00:00");
        migrateToHead();

        assertThatThrownBy(() -> executeInOneTransaction(
                        company,
                        insertPurchaseOrderStatement(company, UUID.randomUUID(), 2, "DRAFT", "2026-11-07 08:00:00"),
                        "COMMIT"))
                .as("side A's INSERT branch: a document born with no creation row cannot commit")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("was created without its status_transition row");
        assertThat(countScoped(company, "SELECT count(*) FROM purchase_orders WHERE company_id = '" + company + "'"))
                .as("the refused commit left no document behind: only the seeded one is there")
                .isEqualTo(1);

        assertThatThrownBy(() -> executeInOneTransaction(
                        company,
                        insertPurchaseOrderDetailStatement(
                                company, order, UUID.randomUUID(), "PENDING", "2026-11-06 08:10:00"),
                        "COMMIT"))
                .as("the line's own pair refuses a line born without its row, exactly as it refuses a document")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("was created without its status_transition row");
        assertThat(countScoped(
                        company,
                        "SELECT count(*) FROM purchase_order_details WHERE purchase_order_id = '" + order + "'"))
                .as("the refused commit left no line behind")
                .isZero();
    }

    @Test
    @DisplayName("the window closes: both tables are enabled AND forced after the migration")
    void leavesBothTablesForced() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(rows("SELECT relname FROM pg_class WHERE relkind = 'r' AND relrowsecurity"
                        + " AND relforcerowsecurity"
                        + " AND relname IN ('purchase_orders', 'status_transition') ORDER BY relname"))
                .as("a window left open weakens the table for everything that runs after it (V55), and it is "
                        + "invisible in every other assertion this file makes")
                .containsExactly(new String[] {"purchase_orders"}, new String[] {"status_transition"});
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

    /** A company with one store at the bottom of the hierarchy the document keys require. */
    private UUID seedCompany() throws SQLException {
        var company = UUID.randomUUID();
        execute(
                null,
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('MXN', 2, '$', true)"
                        + " ON CONFLICT (code) DO NOTHING");
        execute(
                company,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + company + "', 'V69-"
                        + company.toString().substring(0, 8) + "', 'V69 Company', 'V69"
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
                        + region.toString().substring(0, 6) + "', 'V69 Region')");

        var companyZone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name) VALUES ('"
                        + companyZone + "', '" + company + "', '" + region + "', '"
                        + companyZone.toString().substring(0, 6) + "', 'V69 Zone')");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + companyZone + "', 'V69 Store', true)");
        return company;
    }

    /**
     * One purchase order as {@code V64} and {@code V66} leave the writer: an identity in parts, a
     * {@code status_id} from the catalog and, because that column exists and is required, the typed state the
     * bridge would otherwise derive. {@code created_at} is stated so the seed's instant is an assertion and not
     * a clock.
     */
    private UUID insertPurchaseOrder(UUID company, long seq, String state, String createdAt) throws SQLException {
        var id = UUID.randomUUID();
        execute(company, insertPurchaseOrderStatement(company, id, seq, state, createdAt));
        return id;
    }

    /**
     * The same insert as one statement with its id stated, so a case can run it inside a transaction and ask
     * what the deferred checks do with a document that never got its row.
     */
    private String insertPurchaseOrderStatement(UUID company, UUID id, long seq, String state, String createdAt)
            throws SQLException {
        var number =
                "PO-202611-" + String.format("%05d", seq) + "-" + id.toString().substring(0, 4);
        return "INSERT INTO purchase_orders (id, order_number, supplier_id, company_store_id, payment_method_id,"
                + " status_id, status, company_id, doc_type, seq_period, seq_value, doc_number, created_at)"
                + " VALUES ('" + id + "', '" + number + "', '" + supplierOf(company) + "', '" + storeOf(company)
                + "', '" + paymentMethodId() + "', '" + statusId("PURCHASE_ORDER", state) + "', '" + state + "', '"
                + company + "', 'PURCHASE_ORDER', '202611', " + seq + ", '" + number + "', '" + createdAt + "')";
    }

    /** A creation row written the way the recorder writes it, for the window between {@code V68} and {@code V69}. */
    private void insertCreationRow(UUID company, String documentType, UUID document, String state, String at)
            throws SQLException {
        execute(
                company,
                "INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,"
                        + " actor, occurred_at, reason) VALUES ('" + company + "', '" + documentType + "', '"
                        + document + "', NULL, '" + state + "', 'live:actor', '" + at + "', 'first')");
    }

    /** One line: no {@code company_id} on this table, which is what makes the seed derive it from the order. */
    private UUID insertPurchaseOrderDetail(UUID company, UUID purchaseOrder, String state, String createdAt)
            throws SQLException {
        var id = UUID.randomUUID();
        execute(company, insertPurchaseOrderDetailStatement(company, purchaseOrder, id, state, createdAt));
        return id;
    }

    private String insertPurchaseOrderDetailStatement(
            UUID company, UUID purchaseOrder, UUID id, String state, String createdAt) throws SQLException {
        return "INSERT INTO purchase_order_details (id, purchase_order_id, product_id, product_variant_id,"
                + " quantity, unit_price, total, received_quantity, status_id, status, created_at)"
                + " VALUES ('" + id + "', '" + purchaseOrder + "', '" + productOf(company) + "', '"
                + variantOf(company) + "', 2, 50.00, 100.00, 0, '" + statusId("PURCHASE_ORDER_DETAIL", state) + "', '"
                + state + "', '" + createdAt + "')";
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
                        + "', 'V69 Supplier', 'V69"
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
                        + " VALUES ('" + id + "', 'V69 Transfer', 'V69T', true)");
        return id;
    }

    /** One product of the company, created on first use so every company has its own. */
    private UUID productOf(UUID company) throws SQLException {
        var existing = readUnscoped("SELECT id FROM products WHERE company_id = '" + company + "' LIMIT 1", true);
        if (existing != null) {
            return UUID.fromString(existing);
        }
        var product = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id) VALUES ('" + product + "', 'V69-"
                        + product.toString().substring(0, 8) + "', 'V69 Product', '" + company + "')");
        return product;
    }

    /** One variant of that product, which the line's own columns require. */
    private UUID variantOf(UUID company) throws SQLException {
        var existing =
                readUnscoped("SELECT id FROM product_variants WHERE company_id = '" + company + "' LIMIT 1", true);
        if (existing != null) {
            return UUID.fromString(existing);
        }
        var variant = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO product_variants (id, product_id, bar_code, variant_name, measure_unit_id, company_id)"
                        + " VALUES ('" + variant + "', '" + productOf(company) + "', 'V69-BC-"
                        + variant.toString().substring(0, 8) + "', 'unica', '" + measureUnitId() + "', '" + company
                        + "')");
        return variant;
    }

    private UUID measureUnitId() throws SQLException {
        return UUID.fromString(readUnscoped("SELECT id FROM measure_units WHERE sat_code = 'H87'", false));
    }

    private UUID storeOf(UUID company) throws SQLException {
        return readUuid(company, "SELECT id FROM company_stores WHERE company_id = '" + company + "'");
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
     * Every creation row of one document, as {@code to_status|occurred_at|actor|reason}.
     *
     * <p>Read with the scope stated: {@code status_transition} is armed by {@code V67} and FORCEd, so an
     * unscoped read answers no row at all and would make "the seed wrote nothing" and "the seed wrote
     * everything" print the same green.</p>
     */
    private List<String> creationRows(UUID company, String documentType, UUID document) throws SQLException {
        var sql = "SELECT to_status || '|' || to_char(occurred_at, 'YYYY-MM-DD HH24:MI:SS') || '|' || actor"
                + " || '|' || reason FROM status_transition WHERE company_id = '" + company + "'"
                + " AND document_type = '" + documentType + "' AND document_id = '" + document + "'"
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

    /** A read of a table with no policy (currency, catalogs, lines). */
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
     * One statement inside a transaction the caller closes, so the deferred checks of decision 5 run where they
     * are designed to run: at COMMIT, not at the statement.
     *
     * <p>The scope is stated on the connection and not as {@code SET LOCAL}: the deferred trigger reads
     * {@code status_transition} and the document at commit time, and a scope that expired with the statement
     * would make both reads answer nothing — the migration's <em>own</em> failure mode, asserted from the other
     * side.</p>
     */
    private void executeInOneTransaction(UUID scope, String sql, String close) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute(close);
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
