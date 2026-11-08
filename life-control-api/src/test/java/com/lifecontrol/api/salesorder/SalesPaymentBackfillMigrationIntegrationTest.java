package com.lifecontrol.api.salesorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLWarning;
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
import org.springframework.core.io.ClassPathResource;

/**
 * Verification of {@code V86__sale_payment_backfill.sql} and
 * {@code V87__sales_orders_drop_payment_method_id.sql}: ADR-0002 migration steps 4 and 6 (T5's owed
 * backfill of {@code odd/tasks/money-first-class.md}).
 *
 * <p><b>Why a scratch database.</b> The subject of both files is what they make of rows that already
 * exist: a {@code COMPLETED} document written at the pre-unit head, where its only statement about how it
 * was paid is the nullable {@code sales_orders.payment_method_id} column. The chain then runs to head and
 * the assertions read the payment rows back. The shared test database boots straight to head, where every
 * document a suite writes is charged by the service and already has its {@code sale_payment} rows, so a
 * green run there would prove the tables exist and nothing about the rows the backfill had to reach.</p>
 *
 * <p><b>Why the head before these two files is {@code 85}.</b> {@code 86} and {@code 87} are the first
 * migrations after it, and everything they read was in place at {@code 85}: {@code sale_payment} and its
 * append-only trigger ({@code V83}), the document's four parts and {@code ck_order_total} ({@code V80},
 * {@code V84}), and the typed state ({@code V66}).</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}.</b> {@code V65} calls {@code ENABLE} <b>and
 * {@code FORCE}</b> on {@code sales_orders}, and {@code V83} arms {@code sale_payment} the same way, so the
 * policy applies to the tables' owner — the role delivery migrates with. The container's own user is a
 * superuser and bypasses every policy, so a backfill asserted under it would report success while
 * delivery's {@code NOSUPERUSER} owner matched zero rows. That is what the {@code NO FORCE} windows in both
 * files exist for, and the last case of the {@code V86} block is the premise: the same owner, with the
 * window closed again, reads no row of either table.</p>
 *
 * <p><b>How the "reported" half of the contract is observed.</b> {@code V86} names the two sets it cannot
 * represent with {@code RAISE NOTICE}, because a migration's only output is a message on the server's
 * notice channel — and Flyway owns its own connection, so its notices are not reachable through the Flyway
 * API. PostgreSQL's driver maps a notice onto {@link SQLWarning} of the statement that produced it, so the
 * cases that assert a report execute the migration file itself, as one simple-query statement (which
 * PostgreSQL runs in a single implicit transaction, exactly as Flyway wraps a migration) and read the
 * warnings back. Everything else — the backfill, the idempotence and the drop — runs through
 * {@link Flyway}, the credential and the path delivery uses.</p>
 *
 * <p><b>What is deliberately not asserted here.</b> Nothing about the charge: {@code chargeSalesOrder}'s
 * rows, its guards and its tender arithmetic are {@code SalesOrderIntegrationTest}'s, and no case below
 * repeats them. This suite asks only what steps 4 and 6 made of the rows that were already in the tree.</p>
 */
@DisplayName("Historical payment backfill migration (V86, V87)")
class SalesPaymentBackfillMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head these two files start from: the payment table exists and holds no row. */
    private static final String PRE_UNIT_HEAD = "85";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The file under test, executed directly by the cases that assert what it prints. */
    private static final String BACKFILL_FILE = "db/migration/V86__sale_payment_backfill.sql";

    /** The one code the product states today, which is every existing company's operating currency. */
    private static final String SEEDED_CODE = "MXN";

    /** ADR-0002 step 4's literal for a payment whose person the migration did not observe. */
    private static final String UNKNOWN_ACTOR = "unknown:migration";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName =
                "scratch_sale_payment_backfill_" + UUID.randomUUID().toString().replace("-", "");
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

    // ──────────────────────────────────────
    // V86: the row a completed sale gets, and the three facts it states
    // ──────────────────────────────────────

    @Test
    @DisplayName("a completed sale with a method and a positive total gets one CHARGE row for its whole amount")
    void aCompletedSaleGetsOneChargeRowForItsTotal() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var method = seedPaymentMethod(tree);
        var order = insertOrder(tree, 1, "COMPLETED", "140.0000", method, "seller-7", "2026-11-01 09:00:00");

        migrateToHead();

        assertThat(countScoped(tree.company(), paymentsOf(order.id())))
                .as("ADR-0002 step 4: «insert one sale_payment per order» — one, not zero and not one per line, "
                        + "because the old column stated one tender for the whole document")
                .isEqualTo(1);
        assertThat(scoped(tree.company(), paymentColumnOf(order.id(), "amount")))
                .as("amount = total_amount, the whole document, at the scale V80 gave both columns")
                .isEqualTo("140.0000");
        assertThat(scoped(tree.company(), paymentColumnOf(order.id(), "direction")))
                .as("money came in: the direction is written by the migration and never taken from a request "
                        + "(D-g states the same thing about the charge)")
                .isEqualTo("CHARGE");
        assertThat(scoped(tree.company(), paymentColumnOf(order.id(), "method_id")))
                .as("the order's own payment_method_id and nothing else: a backfill may not choose a method")
                .isEqualTo(method.toString());
        assertThat(scoped(tree.company(), paymentColumnOf(order.id(), "tendered_amount")))
                .as("no tender is invented: nobody recorded what the customer handed over, and ck_payment_tender "
                        + "accepts the pair being absent together")
                .isNull();
        assertThat(scoped(tree.company(), paymentColumnOf(order.id(), "change_amount")))
                .as("and no change with it")
                .isNull();
        assertThat(scoped(tree.company(), paymentColumnOf(order.id(), "company_id")))
                .as("the payment states the order's tenant, so fk_payment_order_same_company pins the pair the "
                        + "schema itself reads (M9)")
                .isEqualTo(tree.company().toString());
    }

    @Test
    @DisplayName("the payment is dated by the order's own updated_at, never by the day of the deploy")
    void thePaymentIsDatedByTheOrder() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var method = seedPaymentMethod(tree);
        var order = insertOrder(
                tree, 1, "COMPLETED", "140.0000", method, "seller-7", "2026-11-01 09:00:00", "2026-11-03 15:30:00");

        migrateToHead();

        assertThat(countScoped(
                        tree.company(),
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_name = 'sales_orders' AND column_name = 'updated_at'"
                                + " AND is_nullable = 'NO'"))
                .as("the premise of the next assertion: sales_orders.updated_at is NOT NULL (V1:480), so "
                        + "coalesce(updated_at, order_date) can only ever answer its first arm — the ADR's "
                        + "fallback is unreachable in this schema, and a case that faked a NULL there would be "
                        + "asserting a row the database refuses")
                .isEqualTo(1);
        assertThat(scoped(
                        tree.company(),
                        "SELECT p.paid_at = coalesce(o.updated_at, o.order_date)::timestamptz"
                                + " FROM sale_payment p JOIN sales_orders o"
                                + " ON o.company_id = p.company_id AND o.id = p.sales_order_id"
                                + " WHERE p.sales_order_id = '" + order.id() + "'"))
                .as("ADR-0002 step 4: «paid_at = coalesce(o.updated_at, o.order_date) — not now()». A now() "
                        + "would date this payment 2026-11-08 and make every cash report by date wrong from that "
                        + "minute on, so the assertion is the exact instant the order states (2026-11-03 "
                        + "15:30:00) and not a range a same-day now() would also satisfy")
                .isEqualTo("t");
        assertThat(scoped(
                        tree.company(),
                        "SELECT to_char(p.paid_at AT TIME ZONE current_setting('TimeZone'),"
                                + " 'YYYY-MM-DD HH24:MI:SS') FROM sale_payment p"
                                + " WHERE p.sales_order_id = '" + order.id() + "'"))
                .as("and read as the instant itself, in the server's own zone, which is what makes the equality "
                        + "above more than a comparison with the same mistake on both sides. A UTC form would pin "
                        + "the container's timezone instead of the invariant: the server here runs in the host's "
                        + "zone, so the naive 15:30:00 lands at that wall clock and at 21:30:00Z — either way it "
                        + "is the document's instant, and a now() would read 2026-11-08")
                .isEqualTo("2026-11-03 15:30:00");
    }

    @Test
    @DisplayName("the actor is the order's user_id, and the ADR's literal where that column is null")
    void theActorIsTheOrdersUserOrDefaultsToTheLiteral() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var method = seedPaymentMethod(tree);
        var known = insertOrder(tree, 1, "COMPLETED", "140.0000", method, "seller-7", "2026-11-01 09:00:00");
        var unknown = insertOrder(tree, 2, "COMPLETED", "80.0000", method, null, "2026-11-01 09:00:00");

        migrateToHead();

        assertThat(scoped(tree.company(), paymentColumnOf(known.id(), "actor")))
                .as("«The actor is the order's user_id»: the column is NOT NULL, and a person the document "
                        + "already names is not invented by copying it")
                .isEqualTo("seller-7");
        assertThat(scoped(tree.company(), paymentColumnOf(unknown.id(), "actor")))
                .as("«or the literal 'unknown:migration' where it is null, because the column is NOT NULL and a "
                        + "backfill is not allowed to invent a person» — the one literal in the whole row")
                .isEqualTo(UNKNOWN_ACTOR);
    }

    @Test
    @DisplayName("running the backfill again appends no second row")
    void theBackfillAppendsNoSecondRow() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var method = seedPaymentMethod(tree);
        var order = insertOrder(tree, 1, "COMPLETED", "140.0000", method, "seller-7", "2026-11-01 09:00:00");

        // The file executed twice by hand, and then a third time by Flyway when the chain runs to head: a
        // re-run of the file, a retried deploy and a migrated database whose Flyway history was lost all take
        // the same path into the same INSERT.
        runBackfillFile();
        runBackfillFile();
        migrateToHead();

        assertThat(countScoped(tree.company(), paymentsOf(order.id())))
                .as("`sale_payment` is append-only — the trigger refuses UPDATE and DELETE for every role and "
                        + "the app role holds SELECT, INSERT only — so a wrong row cannot be fixed afterwards: "
                        + "the WHERE NOT EXISTS is what makes the backfill re-runnable, and this is the row that "
                        + "would have been doubled")
                .isEqualTo(1);
        assertThat(countScoped(tree.company(), "SELECT count(*) FROM sale_payment"))
                .as("the same statement, from the table's side: three executions of one file, one payment")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // V86: the two sets it refuses to invent, and the report that names them
    // ──────────────────────────────────────

    @Test
    @DisplayName("a completed sale with no method is named by the migration and gets no row")
    void aCompletedSaleWithNoMethodIsNamedAndNotInvented() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var order = insertOrder(tree, 1, "COMPLETED", "140.0000", null, "seller-7", "2026-11-01 09:00:00");

        var notices = runBackfillFile();

        assertThat(countScoped(tree.company(), paymentsOf(order.id())))
                .as("sale_payment.method_id is NOT NULL (V83:80) and the order states no method, so the two "
                        + "cannot both hold: a sentinel row here would state how a sale was paid when the data "
                        + "never did — D-a's reasoning, and correction (c) of the record")
                .isZero();
        assertThat(notices)
                .as("the refusal is printed instead of being left to be discovered: this is the set the "
                        + "preflight report of docker/scripts/preflight-payment-methods.sh measures before a "
                        + "deploy")
                .anySatisfy(notice -> assertThat(notice)
                        .contains("no payment method")
                        .contains(order.id().toString()));
    }

    @Test
    @DisplayName("a completed sale whose total is zero is named by the migration and gets no row")
    void aCompletedSaleWithNoPositiveTotalIsNamedAndNotInvented() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var method = seedPaymentMethod(tree);
        var order = insertOrder(tree, 1, "COMPLETED", "0", method, "seller-7", "2026-11-01 09:00:00");

        var notices = runBackfillFile();

        assertThat(countScoped(tree.company(), paymentsOf(order.id())))
                .as("sale_payment.amount has CHECK (amount > 0) (V83:83): a zero row is refused by the schema, "
                        + "and inventing a ten-thousandth to make it fit would state money nobody was paid")
                .isZero();
        assertThat(notices)
                .as("named, with the count, so the operator learns the documents the payment sheet cannot "
                        + "state rather than finding an empty list on a screen")
                .anySatisfy(notice ->
                        assertThat(notice).contains("total").contains(order.id().toString()));
    }

    @Test
    @DisplayName("the backfilled row is visible only under a scope, and both tables stay armed")
    void theBackfilledRowIsVisibleOnlyUnderTheScope() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var method = seedPaymentMethod(tree);
        var order = insertOrder(tree, 1, "COMPLETED", "140.0000", method, "seller-7", "2026-11-01 09:00:00");

        migrateToHead();

        assertThat(scoped(tree.company(), "SELECT count(*) FROM sales_orders"))
                .as("with the scope, the fixture's own row is there")
                .isEqualTo("1");
        assertThat(countScoped(tree.company(), paymentsOf(order.id())))
                .as("and the row V86 wrote is there too, so the two zeroes below are about the policy and not "
                        + "about an empty ledger")
                .isEqualTo(1);
        assertThat(readUnscoped("SELECT count(*) FROM sales_orders"))
                .as("without it, this owner sees nothing — which is why V86 and V87 open a NO FORCE window "
                        + "instead of trusting an INSERT and a guard read to match a row")
                .isEqualTo("0");
        assertThat(readUnscoped("SELECT count(*) FROM sale_payment"))
                .as("and the payment table hides its rows the same way, which is the half that would make the "
                        + "backfill write nothing and report success")
                .isEqualTo("0");
        assertThat(readUnscoped("SELECT count(*) FROM pg_class c WHERE c.relname IN ('sales_orders',"
                        + " 'sale_payment') AND c.relrowsecurity AND c.relforcerowsecurity"))
                .as("ENABLE + FORCE on both tables once the window closes, or the next migration inherits a "
                        + "tree whose owner reads across tenants (ADR-0005 correction 3)")
                .isEqualTo("2");
    }

    // ──────────────────────────────────────
    // V87: the drop, and the population it requires first
    // ──────────────────────────────────────

    @Test
    @DisplayName("the drop refuses to run while a completed sale has no payment row, and names it")
    void theDropRefusesWhileACompletedSaleHasNoPaymentRow() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var orphan = insertOrder(tree, 1, "COMPLETED", "140.0000", null, "seller-7", "2026-11-01 09:00:00");

        assertThatThrownBy(this::migrateToHead)
                .as("ADR-0002 step 6: «Drop sales_orders.payment_method_id once the payment rows are "
                        + "populated». Dropping it while a completed sale has no row is how the only record that "
                        + "the sale was paid disappears — and the guard is the half that makes the drop safe on a "
                        + "dataset this repository has never seen, including a restored dump")
                .hasStackTraceContaining("V87 refuses")
                .hasStackTraceContaining(orphan.id().toString());

        assertThat(readUnscoped("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name = 'sales_orders' AND column_name = 'payment_method_id'"))
                .as("the refusal stored nothing: the column is still there, because Flyway rolls the file back "
                        + "and this file's guard runs before its DROP")
                .isEqualTo("1");
        assertThat(scoped(tree.company(), "SELECT count(*) FROM sales_orders"))
                .as("and the document itself is untouched")
                .isEqualTo("1");
    }

    @Test
    @DisplayName("with every completed sale backfilled the column is gone, and the index with it")
    void theColumnIsDroppedOnceTheRowsArePopulated() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var method = seedPaymentMethod(tree);
        var order = insertOrder(tree, 1, "COMPLETED", "140.0000", method, "seller-7", "2026-11-01 09:00:00");

        migrateToHead();

        assertThat(readUnscoped("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name = 'sales_orders' AND column_name = 'payment_method_id'"))
                .as("step 6's second half: every reader moved (T7a reports the payments, T8 renders them), the "
                        + "rows are populated by V86, and the column that stated one tender for a document that "
                        + "can have many is gone")
                .isEqualTo("0");
        assertThat(readUnscoped("SELECT count(*) FROM pg_indexes"
                        + " WHERE tablename = 'sales_orders' AND indexname = 'idx_so_payment_method'"))
                .as("the index V1:489 gave that column is dropped explicitly, because DROP COLUMN removes its "
                        + "indexes anyway and the file states the two files' worth of schema it is undoing")
                .isEqualTo("0");
        assertThat(countScoped(tree.company(), paymentsOf(order.id())))
                .as("and the row that replaced it is still there — the drop moves no payment")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // M3, in the state the backfill leaves
    // ──────────────────────────────────────

    @Test
    @DisplayName("the backfilled document's net paid equals its total")
    void theBackfilledPaymentIsTheDocumentsNetPaid() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var tree = seedTree();
        var method = seedPaymentMethod(tree);
        var order = insertOrder(tree, 1, "COMPLETED", "140.0000", method, "seller-7", "2026-11-01 09:00:00");

        migrateToHead();

        assertThat(scoped(
                        tree.company(),
                        "SELECT COALESCE(SUM(CASE WHEN p.direction = 'CHARGE' THEN p.amount ELSE -p.amount END), 0)"
                                + " FROM sale_payment p WHERE p.company_id = '" + tree.company()
                                + "' AND p.sales_order_id = '" + order.id() + "' AND NOT EXISTS ("
                                + " SELECT 1 FROM sale_payment_void v"
                                + " WHERE v.payment_id = p.id AND v.company_id = p.company_id)"))
                .as("ADR-0002 invariant M3, read through the query shape SalePaymentRepository.netPaid uses and "
                        + "the guard of V87 repeats: Σ CHARGE − Σ REFUND over the non-voided rows. One CHARGE for "
                        + "the whole amount is the only shape that makes it equal the total, which is what the "
                        + "charge guard will hold every later document to")
                .isEqualTo("140.0000");
        assertThat(scoped(tree.company(), "SELECT total_amount FROM sales_orders WHERE id = '" + order.id() + "'"))
                .as("and the total it equals is the number the document already carried: the backfill states a "
                        + "payment, it never restates a sale")
                .isEqualTo("140.0000");
    }

    // ──────────────────────────────────────
    // the migrations, both ways
    // ──────────────────────────────────────

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    /**
     * Runs the chain to head through Flyway, as delivery does.
     *
     * <p>In the guard case this is the statement that must fail, and Flyway's own transaction is what makes
     * the failure atomic: the scratch database is left where it was with nothing dropped.</p>
     */
    private void migrateToHead() {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    /**
     * Executes the backfill file itself and returns the notices it printed.
     *
     * <p>One {@code execute} of the whole file, which the server parses as a single simple-query message:
     * PostgreSQL runs those statements in one implicit transaction, so the file keeps the atomicity Flyway
     * gives it. The notices are read back from the statement's {@link SQLWarning} chain, which is where
     * PostgreSQL's driver puts the server's notice channel.</p>
     */
    private List<String> runBackfillFile() throws Exception {
        var sql = new String(new ClassPathResource(BACKFILL_FILE).getContentAsByteArray(), StandardCharsets.UTF_8);
        var notices = new ArrayList<String>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
            for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                notices.add(String.valueOf(warning.getMessage()));
            }
        }
        return notices;
    }

    // ──────────────────────────────────────
    // fixtures: the pre-unit tree, written as the role delivery migrates with
    // ──────────────────────────────────────

    /** A company with one store and one customer: what a document keys require and nothing else. */
    private record Tree(UUID company, UUID storeId, UUID customerId) {}

    /** One document, and the method its old column stated (possibly none). */
    private record Order(UUID id, UUID method) {}

    /**
     * A company in the one currency the product states, with the geography down to a store.
     *
     * <p>Seeded at {@code V85}, so it is written through the same policies and the same triggers the tree's
     * own writer goes through: the currency is derived from the company, the typed state needs its creation
     * row, and the four parts add up to the total ({@code ck_order_total}, {@code V84}).</p>
     */
    private Tree seedTree() throws SQLException {
        var company = UUID.randomUUID();
        var store = UUID.randomUUID();
        var customer = UUID.randomUUID();
        var region = UUID.randomUUID();
        var zone = UUID.randomUUID();
        var suffix = company.toString().replace("-", "").substring(0, 8);

        inOneTransaction(
                company,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + company
                        + "', 'V86-" + suffix + "', 'V86 Company', 'V86" + suffix.toUpperCase() + "', '"
                        + SEEDED_CODE + "')",
                "INSERT INTO company_countries (id, company_id, country_id)"
                        + " SELECT gen_random_uuid(), '" + company + "', c.id FROM countries c"
                        + " WHERE c.country_code = 'MX'",
                "INSERT INTO company_regions (id, company_id, company_country_id, region_code, region_name)"
                        + " SELECT '" + region + "', '" + company + "', cc.id, 'R" + suffix + "', 'V86 Region'"
                        + " FROM company_countries cc WHERE cc.company_id = '" + company + "'",
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name)"
                        + " VALUES ('" + zone + "', '" + company + "', '" + region + "', 'Z" + suffix
                        + "', 'V86 Zone')",
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled)" + " VALUES ('"
                        + store + "', '" + company + "', '" + zone + "', 'V86 Store', true)",
                "INSERT INTO customers (id, name, sales_channel) VALUES ('" + customer + "', 'V86 Customer',"
                        + " 'STORE')");

        return new Tree(company, store, customer);
    }

    /** One row of the platform catalog, with a name no seed states so the fixture names only its own row. */
    private UUID seedPaymentMethod(Tree tree) throws SQLException {
        var method = UUID.randomUUID();
        var suffix = method.toString().replace("-", "").substring(0, 8);
        inOneTransaction(
                tree.company(),
                "INSERT INTO payment_methods (id, payment_method_name, payment_method_short_name, is_cash)"
                        + " VALUES ('" + method + "', 'V86 Method " + suffix + "', 'V86" + suffix + "', true)");
        return method;
    }

    private Order insertOrder(
            Tree tree, long seq, String status, String total, UUID method, String userId, String orderDate)
            throws SQLException {
        return insertOrder(tree, seq, status, total, method, userId, orderDate, orderDate);
    }

    /**
     * One document at the pre-unit head, in the shape the tree's writer leaves: an identity in parts (no
     * {@code status_id}, which {@code V71} dropped), a typed state with its creation row, and the four parts
     * adding up to the total.
     */
    private Order insertOrder(
            Tree tree,
            long seq,
            String status,
            String total,
            UUID method,
            String userId,
            String orderDate,
            String updatedAt)
            throws SQLException {
        var id = UUID.randomUUID();
        var number =
                "SO-202611-" + String.format("%05d", seq) + "-" + id.toString().substring(0, 4);
        inOneTransaction(
                tree.company(),
                "INSERT INTO sales_orders (id, order_number, customer_id, company_store_id, user_id, order_date,"
                        + " status, total_amount, subtotal, payment_method_id, company_id, doc_type, seq_period,"
                        + " seq_value, doc_number, created_at, updated_at)"
                        + " VALUES ('" + id + "', '" + number + "', '" + tree.customerId() + "', '" + tree.storeId()
                        + "', " + (userId == null ? "NULL" : "'" + userId + "'") + ", '" + orderDate + "', '"
                        + status + "', " + total + ", " + total + ", "
                        + (method == null ? "NULL" : "'" + method + "'") + ", '" + tree.company()
                        + "', 'SALES_ORDER', '202611', " + seq + ", '" + number + "', '" + orderDate + "', '"
                        + updatedAt + "')",
                "INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,"
                        + " actor, occurred_at, reason) VALUES ('" + tree.company() + "', 'SALES_ORDER', '" + id
                        + "', NULL, '" + status + "', '" + UNKNOWN_ACTOR + "', '" + orderDate + "', 'first')");
        return new Order(id, method);
    }

    // ──────────────────────────────────────
    // reads
    // ──────────────────────────────────────

    /** Every payment row of one document, counted, because a doubled row is the failure a re-run causes. */
    private String paymentsOf(UUID order) {
        return "SELECT count(*) FROM sale_payment WHERE sales_order_id = '" + order + "'";
    }

    /** One column of one document's single payment row — the row itself is asserted to be one by its case. */
    private String paymentColumnOf(UUID order, String column) {
        return "SELECT " + column + " FROM sale_payment WHERE sales_order_id = '" + order + "'";
    }

    /** A read of a tenant table with the scope set: without it the policies answer with no row at all. */
    private String scoped(UUID scope, String sql) throws SQLException {
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

    private int countScoped(UUID scope, String sql) throws SQLException {
        return Integer.parseInt(scoped(scope, sql));
    }

    /** A read of a table with no policy (catalogs, {@code information_schema}, {@code pg_class}). */
    private String readUnscoped(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("no row for: " + sql);
            }
            return resultSet.getString(1);
        }
    }

    /** Runs statements in one transaction with the tenant scope set first, so deferred checks judge them. */
    private void inOneTransaction(UUID scope, String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                for (var sql : statements) {
                    statement.execute(sql);
                }
            }
            connection.commit();
        }
    }

    // ──────────────────────────────────────
    // the scratch database's own administration
    // ──────────────────────────────────────

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
