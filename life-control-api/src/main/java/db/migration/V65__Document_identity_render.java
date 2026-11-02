package db.migration;

import com.lifecontrol.api.common.document.DocumentNumberRenderer;
import com.lifecontrol.api.common.document.DocumentType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * ADR-0003 migration step 6: renders {@code doc_number} once, from the parts {@code V64} backfilled.
 *
 * <p><b>Why the render is a Java migration and not SQL.</b> The number the API writes and the number the
 * history receives must be produced by one implementation: two renderers that disagree would split the
 * identity one layer above the constraint that enforces it, which is the defect this ADR removes. The
 * renderer is {@code DocumentNumberRenderer}, a class the API already needs, so Flyway calls it here
 * instead of a SQL function being the second copy. That is the cost the ADR names — this repository had
 * only SQL migrations until this file — and it is cheaper than the alternative.</p>
 *
 * <p><b>The order is sales, purchase orders, receipts</b> and it is not cosmetic: a receipt renders from
 * its parent's <em>frozen</em> {@code doc_number}, so the parent's value has to exist first. Reading the
 * parent's stored column and not its parts is decision 6's whole point — a receipt names the number the
 * order was printed with.</p>
 *
 * <p><b>The {@code NO FORCE} window is its own.</b> {@code V64} closed and re-checked the one it opened, so
 * {@code purchase_orders} is forced again when this file starts; the render has to reach its rows like any
 * other statement, and a window that spanned two files is one a crash can leave open ({@code V55}).</p>
 *
 * <p><b>The arming is last, and it is what makes {@code V64}'s temporary state legal.</b> After the three
 * {@code apply_tenant_isolation} calls, {@code unprotected_tenant_tables()} — the query
 * {@code TenantIsolationStartupCheck} runs at every boot — returns no row, so invariant N9 stops being a
 * claim and becomes a check somebody else's deployment runs. Arming before the cross-tenant render would be
 * the zero-row trap with extra steps, which is why this file arms at the end and asserts the answer.</p>
 */
public class V65__Document_identity_render extends BaseJavaMigration {

    /** Five digits by default; {@code DocumentNumberRenderer} widens when the value needs more. */
    private static final int BATCH_SIZE = 500;

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();

        openNoForceWindow(connection);
        try {
            renderPeriodNumberedDocuments(connection);
            renderDerivedDocuments(connection);

            // The value now exists, so the constraints that require it can be stated. `V64` left these three
            // out on purpose: a `NOT NULL` before the render either fails at once or, worse, succeeds over
            // the empty string of an `UPDATE` that reached no row.
            requireDocNumbers(connection);
            addIdentityConstraints(connection);
            assertNoPartialIdentity(connection);
        } finally {
            closeNoForceWindow(connection);
        }

        armTenantTables(connection);
        assertNoUnprotectedTenantTable(connection);
    }

    // ─── step 0 of this file: the window, opened and checked ─────────────────────────────────────────

    private void openNoForceWindow(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE purchase_orders NO FORCE ROW LEVEL SECURITY");
        if (isForced(connection, "purchase_orders")) {
            throw new IllegalStateException(
                    "V65 could not open its NO FORCE window on purchase_orders: the render would reach no row "
                            + "and report success, leaving every purchase order without a doc_number. See "
                            + "ADR-0005 correction 3 and ADR-0003 migration step 6.");
        }
    }

    private void closeNoForceWindow(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE purchase_orders FORCE ROW LEVEL SECURITY");
        if (!isForced(connection, "purchase_orders")) {
            throw new IllegalStateException(
                    "V65 left purchase_orders without FORCE ROW LEVEL SECURITY: a table whose owner is exempt "
                            + "from its own policy is the state ADR-0005 exists to remove.");
        }
    }

    private boolean isForced(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT relforcerowsecurity FROM pg_class WHERE relname = ? AND relkind = 'r'")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("V65 could not find table " + table);
                }
                return rows.getBoolean(1);
            }
        }
    }

    // ─── the render, both shapes ────────────────────────────────────────────────────────────────────

    private void renderPeriodNumberedDocuments(Connection connection) throws SQLException {
        renderFromParts(connection, DocumentType.SALES_ORDER, "sales_orders");
        renderFromParts(connection, DocumentType.PURCHASE_ORDER, "purchase_orders");
    }

    private void renderFromParts(Connection connection, DocumentType type, String table) throws SQLException {
        var rendered = new ArrayList<Object[]>();
        try (var statement = connection.prepareStatement(
                "SELECT id, seq_period, seq_value FROM " + table + " WHERE doc_number IS NULL")) {
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    rendered.add(new Object[] {
                        DocumentNumberRenderer.renderPeriodNumber(type, rows.getString(2), rows.getLong(3)),
                        rows.getObject(1)
                    });
                }
            }
        }
        writeDocNumbers(connection, table, rendered);
    }

    /**
     * A receipt renders {@code GR-{parent.doc_number}-{NN}}, and its ordinal is its position within that
     * parent in reception order.
     *
     * <p>A position and not the suffix the old generator wrote: the old suffix is the one the pre-flight
     * report calls "non-numeric ordinal" when it is not a number, and re-deriving the sequence from the
     * receptions is the only reading that cannot collide with itself. The ADR allows it — a receipt is
     * re-rendered only together with its parent, and this is that migration.</p>
     */
    private void renderDerivedDocuments(Connection connection) throws SQLException {
        var rendered = new ArrayList<Object[]>();
        var sql = """
                SELECT gr.id,
                       po.doc_number,
                       row_number() OVER (PARTITION BY gr.purchase_order_id
                                          ORDER BY gr.received_at, gr.created_at, gr.id) AS position
                  FROM goods_receipts gr
                  JOIN purchase_orders po ON po.id = gr.purchase_order_id
                 WHERE gr.doc_number IS NULL
                """;
        try (var statement = connection.prepareStatement(sql);
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                rendered.add(new Object[] {
                    DocumentNumberRenderer.renderDerivedNumber(
                            DocumentType.GOODS_RECEIPT, rows.getString(2), rows.getLong(3)),
                    rows.getObject(1)
                });
            }
        }
        writeDocNumbers(connection, "goods_receipts", rendered);
    }

    private void writeDocNumbers(Connection connection, String table, List<Object[]> rendered) throws SQLException {
        if (rendered.isEmpty()) {
            return;
        }
        try (var statement = connection.prepareStatement("UPDATE " + table + " SET doc_number = ? WHERE id = ?")) {
            var pending = 0;
            for (var row : rendered) {
                statement.setObject(1, row[0]);
                statement.setObject(2, row[1]);
                statement.addBatch();
                if (++pending == BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    // ─── the constraints that need the value to exist ──────────────────────────────────────────────

    private void requireDocNumbers(Connection connection) throws SQLException {
        for (var table : List.of("sales_orders", "purchase_orders", "goods_receipts")) {
            execute(connection, "ALTER TABLE " + table + " ALTER COLUMN doc_number SET NOT NULL");
        }
    }

    private void addIdentityConstraints(Connection connection) throws SQLException {
        // One value per table, not a set: a sales order cannot be filed as a receipt, and a CHECK that
        // admitted three values would let it. The vocabulary is `DocumentType.code()`'s spelling, which is
        // what `document_sequence` admits and what ADR-0004's `status_transition.document_type` must reuse.
        execute(
                connection,
                "ALTER TABLE sales_orders ADD CONSTRAINT ck_sales_orders_doc_type CHECK (doc_type = 'SALES_ORDER')");
        execute(
                connection,
                "ALTER TABLE purchase_orders ADD CONSTRAINT ck_purchase_orders_doc_type CHECK (doc_type = 'PURCHASE_ORDER')");
        execute(
                connection,
                "ALTER TABLE goods_receipts ADD CONSTRAINT ck_goods_receipts_doc_type CHECK (doc_type = 'GOODS_RECEIPT')");

        // The identity is the parts; the rendered string is presentation with a second net over it
        // (decision 5). The rendered key is `(company_id, doc_type, doc_number)` and not the string alone:
        // two tenants may print the same number, so a global key would refuse the second tenant's document
        // — the defect this unit exists to remove, moved one layer up.
        execute(
                connection,
                "CREATE UNIQUE INDEX uq_sales_orders_doc_number ON sales_orders (company_id, doc_type, doc_number)");
        execute(
                connection,
                "CREATE UNIQUE INDEX uq_purchase_orders_doc_number ON purchase_orders (company_id, doc_type, doc_number)");
        execute(
                connection,
                "CREATE UNIQUE INDEX uq_goods_receipts_doc_number ON goods_receipts (company_id, doc_type, doc_number)");

        // The three columns are all set or all none. Written as an equivalence on purpose: a pair of
        // `IS NOT NULL` predicates would admit the state this exists to refuse.
        execute(
                connection,
                "ALTER TABLE sales_orders ADD CONSTRAINT ck_sales_orders_doc_identity "
                        + "CHECK ((doc_number IS NULL) = (seq_period IS NULL) "
                        + "AND (seq_period IS NULL) = (seq_value IS NULL))");
        execute(
                connection,
                "ALTER TABLE purchase_orders ADD CONSTRAINT ck_purchase_orders_doc_identity "
                        + "CHECK ((doc_number IS NULL) = (seq_period IS NULL) "
                        + "AND (seq_period IS NULL) = (seq_value IS NULL))");
    }

    /**
     * "The render reached nothing" and "the render reached everything" print the same green; only a question
     * about the rows separates them, and this one also proves the {@code NO FORCE} window did its job.
     */
    private void assertNoPartialIdentity(Connection connection) throws SQLException {
        var missing = new ArrayList<String>();
        for (var table : List.of("sales_orders", "purchase_orders")) {
            try (var statement = connection.prepareStatement("SELECT count(*) FROM " + table
                    + " WHERE doc_number IS NULL OR seq_period IS NULL " + "OR seq_value IS NULL")) {
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) > 0) {
                        missing.add(table + " " + rows.getLong(1));
                    }
                }
            }
        }
        try (var statement =
                connection.prepareStatement("SELECT count(*) FROM goods_receipts WHERE doc_number IS NULL")) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) > 0) {
                    missing.add("goods_receipts " + rows.getLong(1));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("V65 rendered the document identity and these rows still carry none: "
                    + String.join(", ", missing) + ". Either the NO FORCE window did not take or the render read "
                    + "no row — both report success under FORCE ROW LEVEL SECURITY.");
        }
    }

    // ─── step 7: the three tables join ADR-0005's list ─────────────────────────────────────────────

    private void armTenantTables(Connection connection) throws SQLException {
        for (var table : List.of("document_sequence", "sales_orders", "goods_receipts")) {
            execute(connection, "SELECT apply_tenant_isolation('" + table + "')");
        }
    }

    /**
     * The startup assertion asked once, here, so invariant N9 is observed during the deploy instead of at the
     * first boot of a process that would have refused to start.
     */
    private void assertNoUnprotectedTenantTable(Connection connection) throws SQLException {
        var unprotected = new ArrayList<String>();
        // The column of a `SETOF regclass` function is named after the function, so the alias is what makes
        // this readable instead of `unprotected_tenant_tables::text`.
        try (var statement = connection.prepareStatement("SELECT u::text FROM unprotected_tenant_tables() u");
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                unprotected.add(rows.getString(1));
            }
        }
        if (!unprotected.isEmpty()) {
            throw new IllegalStateException("V65 armed the three document tables and these tables still carry a "
                    + "company_id with no policy: " + String.join(", ", unprotected)
                    + ". TenantIsolationStartupCheck refuses to boot on this answer.");
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
