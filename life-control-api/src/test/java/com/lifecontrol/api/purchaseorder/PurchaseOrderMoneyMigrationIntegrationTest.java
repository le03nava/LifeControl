package com.lifecontrol.api.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verification of {@code V92__purchase_order_details_money.sql} and {@code V93__purchase_orders_money.sql},
 * the schema half of {@code odd/tasks/purchase-order-money.md} (T3, specs S3 and S6).
 *
 * <p><b>Why a scratch database.</b> What these two files assert is not that a column exists — the shared test
 * database boots straight to head, where every row a suite writes is already shaped by this derivation — but
 * what they do to rows written <b>before</b> them. A purchase order and its lines are written at the pre-unit
 * head, where neither table has any money but the line's two-decimal {@code total} and the order has no money
 * at all; the chain then runs to head and the assertions read the rows back. The old writer stated
 * {@code round(unit_price * quantity, 2)} in Java ({@code PurchaseOrderService#lineTotal}, deleted by this
 * same unit), so the backfill's restatement is only observable on a row that was written by it.</p>
 *
 * <p><b>Why the head before these files is {@code 91}.</b> {@code 92} and {@code 93} are the first migrations
 * after it, and nothing between {@code V57} (the currency catalog and {@code companies.currency}, which
 * {@code V93}'s backfill reads) and {@code V91} touches either purchase table's money.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}.</b> {@code V52} calls {@code ENABLE} <b>and
 * {@code FORCE}</b> on every table it arms, and {@code V53} arms {@code purchase_orders} and
 * {@code companies}, so the policy applies to their <b>owner</b> — the role delivery migrates with. The
 * container's own user is a superuser and bypasses every policy, so {@code V93}'s currency backfill asserted
 * under it would report success while delivery's {@code NOSUPERUSER} owner matched zero rows and the
 * {@code SET NOT NULL} below it failed. That is the premise of {@code V93}'s two {@code NO FORCE} windows,
 * and it is why the cases here run as the owner the policies apply to.</p>
 *
 * <p><b>What is deliberately asserted through the public side.</b> No case reads
 * {@code purchase_order_details.subtotal} as an intermediate: the document's numbers and the line's
 * {@code total} are read back after the statement that was supposed to move them, because a derivation that
 * ran in the wrong order — or on the wrong set of rows — is invisible in the function's source and obvious in
 * the stored number.</p>
 *
 * <p><b>And one case for {@code V94}</b>, the file that adds the line's three tax columns and the term they
 * contribute ({@code odd/tasks/purchase-order-line-tax.md}, S1/S3/S6). It starts from the head {@code V92} and
 * {@code V93} leave ({@code 93}), because what it has to prove is twofold: a row written <b>before</b> the tax
 * columns existed is not disturbed by them, and a row written after them derives
 * {@code tax_amount = round(subtotal * tax_rate, 4)} from the rate it states — never from a newer row of
 * {@code tax_rate}, which is the difference between a snapshot and a join.</p>
 */
@DisplayName("Purchase order money migration (V92, V93, V94)")
class PurchaseOrderMoneyMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head these two files start from. */
    private static final String PRE_UNIT_HEAD = "91";

    /** The head {@code V94} starts from: the money columns exist, the tax columns do not. */
    private static final String PRE_TAX_HEAD = "93";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The one code every company in this tree operates in, and the catalog row {@code V57} seeds. */
    private static final String SEEDED_CODE = "MXN";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_po_money_" + UUID.randomUUID().toString().replace("-", "");
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

    // --- cases ---

    @Test
    @DisplayName("a seeded order's money is the sum of its own lines at four decimals, and no other number")
    void derivesASeededOrdersMoneyFromItsOwnLines() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var order = seedOrderWithTwoLines();

        // The two-decimals the tree wrote until this unit: 12.34 x 1.25 is 15.425 and 9.99 x 2.50 is 24.975,
        // so the writer's HALF_UP landed on 15.43 and 24.98 and the document, had it existed, would have
        // summed to 40.41 against a true 40.40.
        assertThat(lineMoney(order.firstLine(), "total")).isEqualTo("15.43");
        assertThat(lineMoney(order.secondLine(), "total")).isEqualTo("24.98");

        migrateToHead();

        assertThat(money(order.company(), order.id(), "subtotal"))
                .as("the document states round(unit_price * quantity, 4) summed over its enabled lines: "
                        + "15.4250 + 24.9750")
                .isEqualTo("40.4000");
        assertThat(money(order.company(), order.id(), "tax_total"))
                .as("this slice states no tax (S7), so the part exists and derives to zero rather than being "
                        + "absent and having to be added under rows already stored")
                .isEqualTo("0.0000");
        assertThat(money(order.company(), order.id(), "total_amount"))
                .as("the number no client had: the screen summed rows in the browser with a currency literal "
                        + "instead, a number the server never stated and no other client could reproduce")
                .isEqualTo("40.4000");
        assertThat(readScoped(order.company(), "SELECT currency FROM purchase_orders WHERE id = '" + order.id() + "'"))
                .as("an order that never recorded a currency is given the company's, which is the only fact "
                        + "available about what it was written in (S4, D3)")
                .isEqualTo(SEEDED_CODE);

        assertThat(lineMoney(order.firstLine(), "total"))
                .as("the line's total moved from the 2-decimal 15.43 the Java writer produced to the exact "
                        + "15.4250 — the declared cost of D2, stated instead of hidden")
                .isEqualTo("15.4250");
        assertThat(lineMoney(order.secondLine(), "total")).isEqualTo("24.9750");
        assertThat(lineMoney(order.firstLine(), "subtotal"))
                .as("and the base is its own column from the start, so the successor's tax can be computed on "
                        + "it without re-deriving money that was already derived once")
                .isEqualTo("15.4250");
    }

    @Test
    @DisplayName("a total written by hand is refused instead of stored beside the lines it contradicts")
    void refusesAHandWrittenOrderTotal() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var order = seedOrderWithTwoLines();
        migrateToHead();

        // The statement is scoped: `purchase_orders` is FORCEd since `V65`, so an update that names no company
        // is filtered to zero rows and "accepted" — it has to reach the row for the constraint to judge it.
        assertThat(constraintOf(
                        order.company(),
                        "UPDATE purchase_orders SET total_amount = subtotal + 1 WHERE id = '" + order.id() + "'"))
                .as("ck_po_order_total is what makes the derivation the only writer: the parts add up to the "
                        + "total exactly, with no rounding term for a purchase to hide a difference in, because "
                        + "purchases has no discount (D4)")
                .isEqualTo("ck_po_order_total");
    }

    @Test
    @DisplayName("a line written after head raises the document, and its deletion lowers it again")
    void recomputesTheDocumentWhenALineIsRemoved() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var order = seedOrderWithTwoLines();
        migrateToHead();

        var third = UUID.randomUUID();
        inOneTransaction(
                order.company(),
                insertLine(order.company(), order.id(), third, "5.00", "3.00", "15.00"),
                creationRow(order.company(), "PURCHASE_ORDER_DETAIL", third, "PENDING"));

        assertThat(money(order.company(), order.id(), "total_amount"))
                .as("the derivation is on the line's mutation, not on a service method: 3.00 x 5.00 joins the "
                        + "document without any writer having to remember to recompute it")
                .isEqualTo("55.4000");

        // A hard delete, which is the event the function has to read `OLD` for: an insert has no `OLD` and a
        // delete has no `NEW`, and one expression serves both.
        inOneTransaction(order.company(), "DELETE FROM purchase_order_details WHERE id = '" + third + "'");

        assertThat(money(order.company(), order.id(), "total_amount"))
                .as("and the document falls back to the sum of what is left rather than keeping a line that no "
                        + "longer exists")
                .isEqualTo("40.4000");
    }

    @Test
    @DisplayName("a line the user removed stops being charged to the document")
    void stopsChargingTheDocumentForASoftDeletedLine() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var order = seedOrderWithTwoLines();
        migrateToHead();

        // `deletePurchaseOrderDetail` is a soft delete — `detail.setEnabled(false)` in
        // `PurchaseOrderService#deletePurchaseOrderDetail` — so the row stays and a sum over every row would
        // keep charging the document for a line a user removed. This is the case that summing rows without
        // filtering `enabled` passes; it is the same filter `SalesOrderItemRepository#sumMoneyParts` states for
        // the same reason.
        inOneTransaction(
                order.company(),
                "UPDATE purchase_order_details SET enabled = false WHERE id = '" + order.secondLine() + "'");

        assertThat(money(order.company(), order.id(), "subtotal"))
                .as("the removed line's 24.9750 stops counting, and the disabled row is still there to be "
                        + "read by anyone who asks what the order used to say")
                .isEqualTo("15.4250");
        assertThat(money(order.company(), order.id(), "total_amount")).isEqualTo("15.4250");
        assertThat(readUnscoped(
                        "SELECT enabled::text FROM purchase_order_details WHERE id = '" + order.secondLine() + "'",
                        false))
                .as("the row was disabled, not deleted, which is the whole reason the derivation filters")
                .isEqualTo("false");
    }

    @Test
    @DisplayName("a pre-existing line whose two-decimal total disagrees with its own parts is restated")
    void restatesAPreExistingLinesTotalAtFourDecimals() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        var order = UUID.randomUUID();
        var line = UUID.randomUUID();
        // 33.33 x 1.10 = 36.663 exactly, and the writer that stated this row rounded it to 36.66: three
        // thousandths the two-decimal column could not hold. It is the pair this case is about, chosen
        // because `unit_price` and `quantity` are both `NUMERIC(12,2)` inputs and their product has four
        // decimals to disagree with the stored two.
        inOneTransaction(
                company,
                insertOrder(company, order, 1),
                creationRow(company, "PURCHASE_ORDER", order, "DRAFT"),
                insertLine(company, order, line, "1.10", "33.33", "36.66"),
                creationRow(company, "PURCHASE_ORDER_DETAIL", line, "PENDING"));

        assertThat(lineMoney(line, "total"))
                .as("the row as the old writer left it, 0.003 below the exact product")
                .isEqualTo("36.66");

        migrateToHead();

        assertThat(lineMoney(line, "total"))
                .as("the migration restates it as round(33.33 * 1.10, 4) = 36.6630 instead of leaving two "
                        + "truths for one column that every report would then have to filter by age (D2)")
                .isEqualTo("36.6630");
        assertThat(lineMoney(line, "subtotal")).isEqualTo("36.6630");
        assertThat(money(company, order, "total_amount"))
                .as("and the document it belongs to states the restated number, so the total a report shows is "
                        + "made of the same parts the report reads")
                .isEqualTo("36.6630");
    }

    @Test
    @DisplayName("an order carries its company's currency, and a later change of the company's does not rewrite it")
    void freezesTheOrdersCurrencyAtItsCompany() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = seedCompany();
        migrateToHead();

        var order = UUID.randomUUID();
        inOneTransaction(
                company, insertOrder(company, order, 1), creationRow(company, "PURCHASE_ORDER", order, "DRAFT"));

        assertThat(readScoped(company, "SELECT currency FROM purchase_orders WHERE id = '" + order + "'"))
                .as("the writer states no currency and no column carries a default, so the schema derives it "
                        + "from the company: a default would be the silent `MXN` ADR-0002 decision 2 forbids, one "
                        + "layer below the screen")
                .isEqualTo(SEEDED_CODE);

        execute(
                null,
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('USD', 2, '$', true)"
                        + " ON CONFLICT (code) DO NOTHING");
        execute(company, "UPDATE companies SET currency = 'USD' WHERE id = '" + company + "'");

        assertThat(readScoped(company, "SELECT currency FROM purchase_orders WHERE id = '" + order + "'"))
                .as("`V80` decided this for a sale and the same answer holds here: an order that recomputed its "
                        + "currency from the company would restate a historical document the moment the company "
                        + "changed, which is the shape V57 and #829 already had to repair once (S4, D3)")
                .isEqualTo(SEEDED_CODE);

        // And the freezing is the previous row's fact, not a trigger that stopped working: the next order
        // takes the currency the company states now.
        var later = UUID.randomUUID();
        inOneTransaction(
                company, insertOrder(company, later, 2), creationRow(company, "PURCHASE_ORDER", later, "DRAFT"));

        assertThat(readScoped(company, "SELECT currency FROM purchase_orders WHERE id = '" + later + "'"))
                .as("a frozen currency is frozen per document, not per deployment")
                .isEqualTo("USD");
    }

    // --- migrations ---

    @Test
    @DisplayName("a line that states a rate gets its tax amount and total from the schema, at four decimals, and a"
            + " row written before V94 keeps its zero")
    void theLineTaxIsDerivedFromTheRateTheLineStates() throws Exception {
        migrateTo(PRE_TAX_HEAD);
        var company = seedCompany();
        var order = UUID.randomUUID();
        var legacyLine = UUID.randomUUID();
        inOneTransaction(
                company,
                insertOrder(company, order, 1),
                creationRow(company, "PURCHASE_ORDER", order, "DRAFT"),
                insertLine(company, order, legacyLine, "1.10", "33.33", "36.66"),
                creationRow(company, "PURCHASE_ORDER_DETAIL", legacyLine, "PENDING"));

        assertThat(lineMoney(legacyLine, "total"))
                .as("the row as V92 left it: four decimals, derived, and with no tax column to speak of")
                .isEqualTo("36.6630");

        migrateToHead();

        // The row that existed before `V94`. Its three columns arrive full from their defaults and the extended
        // derivation states the numbers the row already carries, so nothing about this line is rewritten.
        assertThat(lineMoney(legacyLine, "tax_code"))
                .as("a line written before the tax columns existed reads as untaxed, which is what it was")
                .isEqualTo("NA");
        assertThat(lineMoney(legacyLine, "tax_rate")).isEqualTo("0.000000");
        assertThat(lineMoney(legacyLine, "tax_amount"))
                .as("a rate of 0 derives an amount of 0 — the same answer a zero-by-definition code gets, at the "
                        + "only layer that can state the number")
                .isEqualTo("0.0000");
        assertThat(lineMoney(legacyLine, "subtotal")).isEqualTo("36.6630");
        assertThat(lineMoney(legacyLine, "total"))
                .as("the migration backfills nothing: a stored total is not restated to the value it already has")
                .isEqualTo("36.6630");
        assertThat(money(company, order, "tax_total")).isEqualTo("0.0000");
        assertThat(money(company, order, "total_amount")).isEqualTo("36.6630");

        var taxedLine = UUID.randomUUID();
        // The statement T3 makes: the line states the rate it resolved and nothing else. `unit_price` and
        // `quantity` are both two-decimal columns (`V1:343-344`), so the base is 12.35 x 3.05 = 37.6675 and the
        // rate is the six-decimal 0.1625: their product is 6.12096875, whose fourth decimal only exists because
        // the derivation rounds there. Two decimals would store 6.12 and four store 6.1210.
        inOneTransaction(
                company,
                insertTaxedLine(company, order, taxedLine, "3.05", "12.35", "IVA", "0.162500"),
                creationRow(company, "PURCHASE_ORDER_DETAIL", taxedLine, "PENDING"));

        assertThat(lineMoney(taxedLine, "subtotal")).isEqualTo("37.6675");
        assertThat(lineMoney(taxedLine, "tax_amount"))
                .as("round(37.6675 * 0.1625, 4) = 6.1210. The fourth decimal is the point: a derivation rounding "
                        + "at two would store 6.12 and disagree with every other money column on the row")
                .isEqualTo("6.1210");
        assertThat(lineMoney(taxedLine, "total"))
                .as("subtotal + tax_amount = 43.7885, derived in the same function after the amount it reads")
                .isEqualTo("43.7885");
        assertThat(money(company, order, "subtotal"))
                .as("the document states both lines: 36.6630 + 37.6675")
                .isEqualTo("74.3305");
        assertThat(money(company, order, "tax_total"))
                .as("the document's tax is the sum of its lines' tax, not a rate resolved again at read time")
                .isEqualTo("6.1210");
        assertThat(money(company, order, "total_amount"))
                .as("and `ck_po_order_total` states that the parts add up to it exactly")
                .isEqualTo("80.4515");

        // The catalog moves and the document does not. A newer IVA row is published at 5%, and the line is then
        // edited — the quantity, not the article. A derivation that joined `tax_rate` instead of reading the
        // snapshot would answer with this row, which is exactly what S3 forbids.
        execute(
                company,
                "INSERT INTO tax_rate (company_id, tax_code, rate, effective_from) VALUES ('" + company
                        + "', 'IVA', 0.050000, '2026-12-01')");
        inOneTransaction(company, "UPDATE purchase_order_details SET quantity = 6.10 WHERE id = '" + taxedLine + "'");

        assertThat(lineMoney(taxedLine, "tax_rate"))
                .as("the edit did not re-resolve: the snapshot is still the rate the line was written with")
                .isEqualTo("0.162500");
        assertThat(lineMoney(taxedLine, "tax_amount"))
                .as("and the amount is re-derived from THAT rate: round(12.35 * 6.10 * 0.1625, 4) = 12.2419, "
                        + "where the 5% published afterwards would answer 3.7668")
                .isEqualTo("12.2419");
        assertThat(lineMoney(taxedLine, "total")).isEqualTo("87.5769");
        assertThat(money(company, order, "tax_total")).isEqualTo("12.2419");
        assertThat(money(company, order, "total_amount")).isEqualTo("124.2399");
    }

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

    // --- fixtures: the pre-unit tree, written as the role delivery migrates with ---

    /** One order, its two enabled lines and the company they belong to. */
    private record SeededOrder(UUID company, UUID id, UUID firstLine, UUID secondLine) {}

    /**
     * A purchase order with two enabled lines whose four-decimal arithmetic the two-decimal column could not
     * hold: 12.34 x 1.25 and 9.99 x 2.50.
     *
     * <p>Everything happens in one transaction because {@code V69}'s two constraint triggers judge the commit:
     * a document born without its creation row cannot commit, and neither can a line.</p>
     */
    private SeededOrder seedOrderWithTwoLines() throws SQLException {
        var company = seedCompany();
        var order = UUID.randomUUID();
        var firstLine = UUID.randomUUID();
        var secondLine = UUID.randomUUID();
        inOneTransaction(
                company,
                insertOrder(company, order, 1),
                creationRow(company, "PURCHASE_ORDER", order, "DRAFT"),
                insertLine(company, order, firstLine, "1.25", "12.34", "15.43"),
                creationRow(company, "PURCHASE_ORDER_DETAIL", firstLine, "PENDING"),
                insertLine(company, order, secondLine, "2.50", "9.99", "24.98"),
                creationRow(company, "PURCHASE_ORDER_DETAIL", secondLine, "PENDING"));
        return new SeededOrder(company, order, firstLine, secondLine);
    }

    /** A company with one store at the bottom of the hierarchy the document keys require. */
    private UUID seedCompany() throws SQLException {
        var company = UUID.randomUUID();
        var suffix = company.toString().replace("-", "").substring(0, 10).toUpperCase();
        execute(
                null,
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('" + SEEDED_CODE
                        + "', 2, '$', true) ON CONFLICT (code) DO NOTHING");
        execute(
                company,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + company + "', 'V93-"
                        + suffix + "', 'V93 Company', 'V93" + suffix + "', '" + SEEDED_CODE + "')");
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
                        + region.toString().substring(0, 6) + "', 'V93 Region')");

        var zone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name) VALUES ('"
                        + zone + "', '" + company + "', '" + region + "', '"
                        + zone.toString().substring(0, 6) + "', 'V93 Zone')");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + zone + "', 'V93 Store', true)");
        return company;
    }

    /**
     * One order at the head {@code V92}/{@code V93} start from: an identity in parts, the typed state
     * {@code V71} left behind, and no money column at all — which is the shape this unit exists to change.
     *
     * <p>{@code updated_at} is stated so a deferrable read of the row at commit sees an instant instead of a
     * clock, and {@code created_at} is stated because a purchase order's own date <b>is</b>
     * {@code created_at} ({@code V64:187-188}).</p>
     */
    private String insertOrder(UUID company, UUID id, long seq) throws SQLException {
        var number =
                "PO-202611-" + String.format("%05d", seq) + "-" + id.toString().substring(0, 4);
        return "INSERT INTO purchase_orders (id, order_number, supplier_id, company_store_id, payment_method_id,"
                + " status, company_id, doc_type, seq_period, seq_value, doc_number, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + number + "', '" + supplierOf(company) + "', '" + storeOf(company)
                + "', '" + paymentMethodId() + "', 'DRAFT', '" + company + "', 'PURCHASE_ORDER', '202611', " + seq
                + ", '" + number + "', '2026-11-01 10:00:00', '2026-11-01 10:00:00')";
    }

    /**
     * One line at that same head, with the total the Java writer stated: {@code round(unit_price * quantity,
     * 2)}. The column is {@code DECIMAL(12,2)} and accepts it; after {@code V92} it is {@code NUMERIC(19,4)} and
     * the value comes from the row's own parts.
     */
    private String insertLine(UUID company, UUID order, UUID id, String quantity, String unitPrice, String total)
            throws SQLException {
        return "INSERT INTO purchase_order_details (id, purchase_order_id, product_id, product_variant_id,"
                + " quantity, unit_price, total, received_quantity, status, created_at)"
                + " VALUES ('" + id + "', '" + order + "', '" + productOf(company) + "', '" + variantOf(company)
                + "', " + quantity + ", " + unitPrice + ", " + total + ", 0, 'PENDING', '2026-11-01 10:00:00')";
    }

    /**
     * One line at the head {@code V94} starts from, stating its own tax: the code its product declares and the
     * rate the document's date resolved. The three money columns are not named — the trigger derives them, and
     * naming them would be a second writer.
     */
    private String insertTaxedLine(
            UUID company, UUID order, UUID id, String quantity, String unitPrice, String taxCode, String taxRate)
            throws SQLException {
        return "INSERT INTO purchase_order_details (id, purchase_order_id, product_id, product_variant_id,"
                + " quantity, unit_price, tax_code, tax_rate, received_quantity, status, created_at)"
                + " VALUES ('" + id + "', '" + order + "', '" + productOf(company) + "', '" + variantOf(company)
                + "', " + quantity + ", " + unitPrice + ", '" + taxCode + "', " + taxRate
                + ", 0, 'PENDING', '2026-11-01 10:00:00')";
    }

    /** A creation row written the way the recorder writes it: one per document, {@code from_status NULL}. */
    private String creationRow(UUID company, String documentType, UUID document, String state) {
        return "INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,"
                + " actor, occurred_at, reason) VALUES ('" + company + "', '" + documentType + "', '" + document
                + "', NULL, '" + state + "', 'live:actor', '2026-11-01 10:00:00', 'first')";
    }

    /** One supplier of the company, which the order's composite key requires. */
    private UUID supplierOf(UUID company) throws SQLException {
        var supplier = UUID.randomUUID();
        var suffix = supplier.toString().replace("-", "").substring(0, 10).toUpperCase();
        execute(
                company,
                "INSERT INTO suppliers (id, supplier_name, rfc, company_id, enabled) VALUES ('" + supplier
                        + "', 'V93 Supplier', 'V93" + suffix + "', '" + company + "', true)");
        return supplier;
    }

    /** One payment method, which the order requires and which is a global catalog. */
    private UUID paymentMethodId() throws SQLException {
        var existing = readUnscoped("SELECT id FROM payment_methods LIMIT 1", true);
        if (existing != null) {
            return UUID.fromString(existing);
        }
        var id = UUID.randomUUID();
        execute(
                null,
                "INSERT INTO payment_methods (id, payment_method_name, payment_method_short_name, enabled)"
                        + " VALUES ('" + id + "', 'V93 Transfer', 'V93T', true)");
        return id;
    }

    /** One product of the company, created per call because {@code products} is FORCEd and reads as empty. */
    private UUID productOf(UUID company) throws SQLException {
        var product = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id) VALUES ('" + product + "', 'V93-"
                        + product.toString().substring(0, 8) + "', 'V93 Product', '" + company + "')");
        return product;
    }

    /** One variant of that product, which the line's own columns require. */
    private UUID variantOf(UUID company) throws SQLException {
        var variant = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO product_variants (id, product_id, bar_code, variant_name, measure_unit_id, company_id)"
                        + " VALUES ('" + variant + "', '" + productOf(company) + "', 'V93-BC-"
                        + variant.toString().substring(0, 8) + "', 'unica', '" + measureUnitId() + "', '" + company
                        + "')");
        return variant;
    }

    private UUID measureUnitId() throws SQLException {
        return UUID.fromString(readUnscoped("SELECT id FROM measure_units WHERE sat_code = 'H87'", false));
    }

    private UUID storeOf(UUID company) throws SQLException {
        return UUID.fromString(
                readScoped(company, "SELECT id FROM company_stores WHERE company_id = '" + company + "'"));
    }

    // --- writes and reads ---

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

    /** Runs a statement that must be refused and returns the constraint the refusal names. */
    private String constraintOf(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                try {
                    statement.execute(sql);
                } catch (SQLException refused) {
                    var message = String.valueOf(refused.getMessage());
                    var named = Pattern.compile("violates [a-z ]+ constraint \"([^\"]+)\"")
                            .matcher(message);
                    return named.find() ? named.group(1) : message;
                }
                throw new AssertionError("the statement was accepted and should have been refused: " + sql);
            }
        }
    }

    /**
     * One money column of one document. Read with the scope, because {@code purchase_orders} is armed and
     * FORCEd since {@code V53} and an unscoped read answers no row at all.
     */
    private String money(UUID company, UUID order, String column) throws SQLException {
        return readScoped(company, "SELECT " + column + " FROM purchase_orders WHERE id = '" + order + "'");
    }

    /** One column of one line: {@code purchase_order_details} carries no {@code company_id} and no policy. */
    private String lineMoney(UUID line, String column) throws SQLException {
        return readUnscoped("SELECT " + column + " FROM purchase_order_details WHERE id = '" + line + "'", false);
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

    /** A read of a table with no policy (currency, payment methods, the lines themselves). */
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

    // --- the scratch database's own administration ---

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
