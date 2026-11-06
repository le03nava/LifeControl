package com.lifecontrol.api.salesorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code V83__sale_payment.sql}: the payment rows, their voids, and the four claims that make them a
 * financial record instead of two tables (ADR-0002 decisions 5 and 7).
 *
 * <p>What is proven here, and how, is deliberate:</p>
 *
 * <ul>
 *   <li><b>the append-only grant</b> is exercised, not inspected: the statement runs {@code SET ROLE
 *       lifecontrol_app} and PostgreSQL refuses it with <i>permission denied</i>. That is the half
 *       {@code V52:187} and {@code V52:196-197} would silently undo by granting every new table all four
 *       privileges, and it needs no row to be refused;</li>
 *   <li><b>the append-only trigger</b> is pinned structurally — present, enabled, on both tables, calling
 *       the one function — because it fires on a row and building a sale here would mean reproducing the
 *       whole fixture chain (company, store, customer, order) to prove a pattern two existing suites already
 *       prove with real rows ({@code StockLedgerSchemaMigrationIntegrationTest} for {@code V60},
 *       {@code StatusTransitionRecorderIntegrationTest} for {@code V67});</li>
 *   <li><b>the composite foreign key</b> is exercised with an id that cannot exist: a payment naming an
 *       order that is not there is refused by name, which is what makes a mis-stated {@code company_id} a
 *       rejection instead of a row the policy hides (M9);</li>
 *   <li><b>{@code is_cash}</b> is read back: the backfill of {@code V3:385} left exactly one cash method, and
 *       {@code ck_payment_tender} states the arithmetic M4 needs.</li>
 * </ul>
 *
 * <p>No Spring context: this suite is JDBC against a scratch database, like the sibling schema suites.</p>
 */
@DisplayName("Sale payment schema (V83)")
class SalePaymentSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String OWNER = "lifecontrol_owner";
    private static final String OWNER_PASSWORD = "lifecontrol_owner";
    private static final String APP_ROLE = "lifecontrol_app";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_sale_payment_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName + " OWNER " + OWNER);
        }
        jdbcUrl = jdbcUrlFor(databaseName);
        migrateToHead();
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

    // --- the payment rows are append-only, and the grant is what says so for the pool ---

    @Test
    @DisplayName("the application role may read and insert a payment, and may neither update nor delete one")
    void theApplicationRoleCannotRewriteAPayment() throws Exception {
        for (var table : new String[] {"sale_payment", "sale_payment_void"}) {
            assertThat(refusalAsApp("UPDATE " + table + " SET company_id = company_id"))
                    .as(table + ": V52:187 grants all four privileges on every new table, so the restrictive "
                            + "half has to be written — this is the statement that proves it was")
                    .contains("permission denied");
            assertThat(refusalAsApp("DELETE FROM " + table))
                    .as(table + ": a void is a row in sale_payment_void, never a deletion")
                    .contains("permission denied");
            assertThat(asApp("SELECT has_table_privilege('" + APP_ROLE + "', '" + table
                            + "'::regclass, 'SELECT')::text || has_table_privilege('" + APP_ROLE + "', '" + table
                            + "'::regclass, 'INSERT')::text"))
                    .as(table + ": and the pool still reads and writes through, or the ABM could not record a "
                            + "payment at all")
                    .isEqualTo("truetrue");
        }
    }

    @Test
    @DisplayName("the trigger that refuses a rewrite is on both tables, enabled, and one function")
    void theTriggerIsOnBothTables() throws Exception {
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                                + "WHERE NOT t.tgisinternal AND c.relname IN ('sale_payment', 'sale_payment_void') "
                                + "AND t.tgenabled <> 'D' AND t.tgfoid = 'sale_payment_append_only'::regproc"))
                .as("two tables, one function, neither disabled: the trigger is the half that refuses the "
                        + "owner and the superuser, which the grant cannot")
                .isEqualTo("2");
    }

    // --- tenancy ---

    @Test
    @DisplayName("both payment tables are armed as tenant tables keyed by company_id")
    void bothTablesAreArmed() throws Exception {
        assertThat(readScoped(
                        null,
                        "SELECT string_agg(table_name::text || ':' || policy_kind || ':' || key_column, ' ' "
                                + "ORDER BY table_name::text) FROM tenant_table "
                                + "WHERE table_name IN ('sale_payment'::regclass, 'sale_payment_void'::regclass)"))
                .isEqualTo("sale_payment:tenant:company_id sale_payment_void:tenant:company_id");
        for (var table : new String[] {"sale_payment", "sale_payment_void"}) {
            assertThat(readScoped(
                            null, "SELECT relforcerowsecurity::text FROM pg_class WHERE relname = '" + table + "'"))
                    .as(table + ": FORCE, because Flyway's owner is subject to the policy too")
                    .isEqualTo("true");
        }
    }

    // --- the composite key, and the FK that makes a mis-stated company a refusal ---

    @Test
    @DisplayName("the order key exists, and a payment naming an order that is not there is refused by name")
    void theCompositeForeignKeyIsLive() throws Exception {
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_sales_orders_company_id' "
                                + "AND conrelid = 'sales_orders'::regclass AND contype = 'u'"))
                .as("the target of fk_payment_order_same_company, which ADR-0002 adds in this unit because "
                        + "V64 gave the pair to purchase_orders and goods_receipts but not to sales_orders")
                .isEqualTo("1");

        var company = UUID.randomUUID();
        var methodId = readScoped(null, "SELECT id::text FROM payment_methods ORDER BY payment_method_name LIMIT 1");
        assertThat(refusalOf("INSERT INTO sale_payment (company_id, sales_order_id, method_id, direction, "
                        + "amount, paid_at, actor) VALUES ('" + company + "', '" + UUID.randomUUID() + "', '"
                        + methodId + "', 'CHARGE', 10, now(), 'V83')"))
                .as("M9: a payment states its company and its order together, so the schema refuses instead "
                        + "of letting the policy filter it — a filtered row is not a rejected write")
                .contains("fk_payment_order_same_company");
    }

    // --- is_cash, M4's fact ---

    @Test
    @DisplayName("exactly one seeded method is cash, and the tender arithmetic M4 needs is a constraint")
    void theCashMethodAndTheTenderArithmetic() throws Exception {
        assertThat(readScoped(null, "SELECT count(*) FROM payment_methods WHERE is_cash"))
                .as("D-b: the backfill is by the seed's exact name (Efectivo, V3:385). More than one row "
                        + "would mean the name matched something it should not")
                .isEqualTo("1");
        assertThat(readScoped(
                        null,
                        "SELECT string_agg(payment_method_name, ',' ORDER BY payment_method_name) "
                                + "FROM payment_methods WHERE is_cash"))
                .isEqualTo("Efectivo");

        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_payment_tender' "
                                + "AND conrelid = 'sale_payment'::regclass AND contype = 'c' "
                                + "AND pg_get_constraintdef(oid) ~ 'change_amount = \\(*tendered_amount - amount\\)*'"))
                .as("a card payment cannot hold a change value: tender and change agree with the amount, or "
                        + "neither is stated. The cash-only half is ADR-0004's guard, because a CHECK cannot "
                        + "read the payment-method catalog")
                .isEqualTo("1");
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_void_replacement_differs' "
                                + "AND conrelid = 'sale_payment_void'::regclass"))
                .as("a void that names itself as its replacement is not a correction")
                .isEqualTo("1");
    }

    // --- the scratch database's own administration ---

    private void migrateToHead() {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                // 0 and not 1: delivery baselines at 0 (#722), and a scratch database is empty anyway.
                .baselineVersion("0")
                // The tree numbers this unit 83 and the tax unit 82, which lands from its own branch: the gap
                // is Flyway's to tolerate and the head is the maximum of the versions present.
                .load()
                .migrate();
    }

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

    /** A read with no scope, as the owner: what a migration that forgot the policy would be looking at. */
    private String readScoped(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            if (scope != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                }
            }
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    /** Runs {@code sql} as the pool's role and returns the refusal's message; empty when accepted. */
    private String refusalAsApp(String sql) throws SQLException {
        return asAppExpecting(sql, true);
    }

    /** Runs {@code sql} as the pool's role and returns the answer of a one-row, one-column query. */
    private String asApp(String sql) throws SQLException {
        return asAppExpecting(sql, false);
    }

    private String asAppExpecting(String sql, boolean expectRefusal) throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            // SET ROLE and not a second login: the app role's password belongs to the container's fixture
            // and a superuser may assume any role, so the statement runs with exactly the app's privileges.
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + APP_ROLE);
            }
            try (Statement statement = connection.createStatement()) {
                if (expectRefusal) {
                    statement.execute(sql);
                    return "";
                }
                try (var resultSet = statement.executeQuery(sql)) {
                    resultSet.next();
                    return resultSet.getString(1);
                }
            } catch (SQLException refused) {
                if (!expectRefusal) {
                    throw refused;
                }
                return refused.getMessage() == null ? refused.getSQLState() : refused.getMessage();
            }
        }
    }

    /**
     * Runs {@code sql} as the container's superuser and returns the refusal's message; empty when accepted.
     *
     * <p>The superuser and not the owner on purpose: row security is bypassed for a superuser, so the only
     * refusal a statement can get here is the constraint under test. As the owner, {@code FORCE} would have
     * refused the write for having no tenant scope and the case would have passed for the wrong reason.</p>
     */
    private String refusalOf(String sql) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            try {
                statement.execute(sql);
                return "";
            } catch (SQLException refused) {
                return refused.getMessage() == null ? refused.getSQLState() : refused.getMessage();
            }
        }
    }
}
