package com.lifecontrol.api.salesorder;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Verification of {@code V80__sales_orders_money_breakdown.sql} and
 * {@code V81__sales_order_items_money_breakdown.sql}, the schema half of ADR-0002 decision 1 and 3 (T2).
 *
 * <p><b>Why a scratch database.</b> The one fact these two files assert that a shared database cannot show
 * is what they do to a row that already exists: a legacy document and its lines are written at the
 * pre-unit head — where no currency, no breakdown and no four-decimal column exists — the chain then runs
 * to head, and the assertions read the row back. The shared test database boots straight to head, where
 * every document a suite writes is already shaped by these columns, so a green run there would prove the
 * columns exist and nothing about the rows the backfill had to reach.</p>
 *
 * <p><b>Why the head before these files is {@code 79}.</b> {@code 80} and {@code 81} are the first
 * migrations after it, and nothing between {@code V57} (the currency catalog and {@code companies.currency})
 * and {@code V79} touches the two sales tables' money columns.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}.</b> {@code V52} calls {@code ENABLE} <b>and
 * {@code FORCE}</b> on every table it arms, so the policy applies to the tables' owner — the role delivery
 * migrates with. The container's own user is a superuser and bypasses every policy, so a backfill asserted
 * under it would report success while delivery's {@code NOSUPERUSER} owner matched zero rows. That is what
 * the {@code NO FORCE} windows in {@code V80} and {@code V81} exist for, and the currency case below pins
 * the other half of it: the same owner, with the window closed again, reads no company at all.</p>
 *
 * <p><b>What the {@code D-f} cases are.</b> {@code final_price} is the <b>unit</b> net price today
 * ({@code SalesOrderService:555}: {@code finalPrice = listPrice - discountApplied}), not a line total, so
 * these files record a legacy document as «no breakdown, and this total» instead of re-deriving one from
 * the lines. The first case is the assertion of that decision: the total does not move, and the line's
 * {@code final_price} is not rewritten to the number {@code ck_item_total} would have demanded.</p>
 */
@DisplayName("Sales money breakdown migration (V80, V81)")
class SalesMoneyBreakdownMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head these two files start from. */
    private static final String PRE_UNIT_HEAD = "79";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The one code the product states today, which is every existing company's operating currency. */
    private static final String SEEDED_CODE = "MXN";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_sales_money_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("a legacy document keeps its total cent by cent and is recorded as having no breakdown")
    void keepsTheLegacyTotalAndInventsNoBreakdown() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var document = seedLegacyDocument();
        migrateToHead();

        // The total the old writer computed: the sum of the lines' `final_price`, which is a unit price.
        assertThat(money(document.company(), document.orderId(), "total_amount"))
                .as("the document's total is the number it already carried — ADR-0002 step 3 says it keeps "
                        + "its value, and a financial migration may not lose that")
                .isEqualTo("140.0000");
        assertThat(money(document.company(), document.orderId(), "subtotal"))
                .as("it is recorded as its own subtotal, because there is no breakdown to record: the "
                        + "legacy lines sum to 190.00 in line totals and 140.00 in the unit prices the total "
                        + "was actually made of (D-f)")
                .isEqualTo("140.0000");
        assertThat(money(document.company(), document.orderId(), "discount_total"))
                .as("no part is invented to make the sum work")
                .isEqualTo("0.0000");
        assertThat(money(document.company(), document.orderId(), "tax_total")).isEqualTo("0.0000");
        assertThat(money(document.company(), document.orderId(), "rounding_adjustment"))
                .as("and the rounding is 0 rather than the 0.00 a re-derivation would have needed to hide "
                        + "the difference between unit prices and line totals")
                .isEqualTo("0.0000");
        assertThat(readScoped(
                        document.company(),
                        "SELECT currency FROM sales_orders WHERE id = '" + document.orderId() + "'"))
                .as("the document inherits the operating currency of the company that owns it, which V57 "
                        + "already made mandatory")
                .isEqualTo(SEEDED_CODE);

        assertThat(lineMoney(document.lineA(), "final_price"))
                .as("the line's stored price is not rewritten: ck_item_total would have demanded 190.0000 "
                        + "here, and this file does not add a constraint against history (D-f)")
                .isEqualTo("90.0000");
        assertThat(lineMoney(document.lineA(), "subtotal"))
                .as("the line's own subtotal is price times quantity, the one number this file can state "
                        + "about a legacy line without reading anything but the row")
                .isEqualTo("200.0000");
        assertThat(lineMoney(document.lineB(), "discount_applied"))
                .as("a NULL discount becomes 0 before the NOT NULL lands, because a NULL in a CHECK passes "
                        + "instead of refusing")
                .isEqualTo("0.0000");
        assertThat(readUnscoped("SELECT tax_code || ':' || tax_rate || ':' || tax_amount FROM sales_order_items"
                        + " WHERE id = '" + document.lineA() + "'"))
                .as("no rate existed when the sale was made, and a migration does not invent one for a sale "
                        + "that was never taxed")
                .isEqualTo("NA:0.000000:0.0000");
        assertThat(readUnscoped("SELECT product_name_snapshot || '|' || variant_name_snapshot"
                        + " FROM sales_order_items WHERE id = '" + document.lineA() + "'"))
                .as("the line says what it sold, read from the catalog through the variant it names")
                .isEqualTo(document.productName() + "|" + document.variantName());
    }

    @Test
    @DisplayName("the money columns carry four decimals and the total is no longer optional")
    void theMoneyColumnsCarryFourDecimals() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        seedLegacyDocument();
        migrateToHead();

        assertThat(readUnscoped("SELECT data_type || ':' || numeric_precision || ':' || numeric_scale || ':'"
                        + " || is_nullable FROM information_schema.columns"
                        + " WHERE table_name = 'sales_orders' AND column_name = 'total_amount'"))
                .as("total_amount is NUMERIC(19,4) NOT NULL: decision 1's scale and the NOT NULL a NULL "
                        + "total could not satisfy")
                .isEqualTo("numeric:19:4:NO");
        assertThat(readUnscoped("SELECT string_agg(column_name || ':' || numeric_scale, ',' ORDER BY column_name)"
                        + " FROM information_schema.columns WHERE table_name = 'sales_order_items'"
                        + " AND column_name IN ('quantity', 'list_price', 'discount_applied', 'final_price')"))
                .as("the four line amounts are at the same scale — quantities and prices included, because "
                        + "a per-gram price implies a quantity two decimals cannot hold")
                .isEqualTo("discount_applied:4,final_price:4,list_price:4,quantity:4");
        assertThat(readUnscoped("SELECT is_nullable FROM information_schema.columns"
                        + " WHERE table_name = 'sales_order_items' AND column_name = 'discount_applied'"))
                .as("discount_applied is NOT NULL now: it was the nullable one, and a NULL is a check that " + "passes")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("a writer that states no currency gets the company's, and one that states another is refused")
    void derivesTheCurrencyFromTheCompany() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var document = seedLegacyDocument();
        migrateToHead();

        // The tree's own writer (`SalesOrderService.createSalesOrder`) does not send a currency: T7 is the
        // surface that will. Without the derivation the NOT NULL would refuse every order it creates.
        var silent = UUID.randomUUID();
        inOneTransaction(
                document.company(),
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('USD', 2, '$', true)"
                        + " ON CONFLICT (code) DO NOTHING",
                insertOrderStatement(document, silent, 2, "0"),
                creationRow(document.company(), "SALES_ORDER", silent, "DRAFT"));
        assertThat(readScoped(document.company(), "SELECT currency FROM sales_orders WHERE id = '" + silent + "'"))
                .as("the document inherits the company's operating currency instead of a platform default "
                        + "no company stated")
                .isEqualTo(SEEDED_CODE);

        var refused = UUID.randomUUID();
        var refusal = refusalOf(
                document.company(),
                "INSERT INTO sales_orders (id, order_number, customer_id, company_store_id, order_date, status,"
                        + " company_id, doc_type, seq_period, seq_value, doc_number, currency)"
                        + " VALUES ('" + refused + "', 'SO-202611-90009', '" + document.customerId() + "', '"
                        + document.storeId() + "', '2026-11-05 10:00:00', 'DRAFT', '" + document.company()
                        + "', 'SALES_ORDER', '202611', 9, 'SO-202611-90009', 'USD')");
        assertThat(refusal.getMessage())
                .as("a document cannot be in a currency other than its company's operating currency, even "
                        + "when that currency exists in the catalog (decision 2, M7)")
                .contains("cannot be in a currency other than the company's operating currency")
                .contains("USD")
                .contains(SEEDED_CODE);
    }

    @Test
    @DisplayName("the order's constraints refuse parts that do not add up, a negative part and a wide rounding")
    void theOrderConstraintsRefuse() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var document = seedLegacyDocument();
        migrateToHead();

        assertThat(constraintOf(
                        document.company(),
                        "UPDATE sales_orders SET subtotal = 142, rounding_adjustment = 2, total_amount = 144"
                                + " WHERE id = '" + document.orderId() + "'"))
                .as("the parts add up here, and the rounding is wider than one minor unit — the local half "
                        + "of M8, which reads no catalog and is wrong in every currency")
                .isEqualTo("ck_order_rounding_bounded");

        assertThat(constraintOf(
                        document.company(),
                        "UPDATE sales_orders SET subtotal = -1, total_amount = -1 WHERE id = '" + document.orderId()
                                + "'"))
                .as("a negative part is refused rather than flipped so a deploy can pass")
                .isEqualTo("ck_order_parts_not_negative");
    }

    @Test
    @DisplayName("the breakdown's arithmetic is constrained: a part that stops adding up to the total is refused")
    void theBreakdownArithmeticIsConstrained() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var document = seedLegacyDocument();
        migrateToHead();

        // `V84` adds `ck_order_total` (M1) in the slice that gives it a writer, `recomputeBreakdown`. Until that
        // slice this case stated the debt instead — the write was accepted and the case said so, because
        // `V80`'s own service summed the lines' unit prices into `total_amount` and left the parts at zero, so
        // the constraint would have refused the tree's own writer. What used to be accepted is now refused by
        // name: the difference between a schema nobody bounded and one that cannot hold a document whose parts
        // disagree with its total.
        // The tenant is stated: `sales_orders` is `FORCE ROW LEVEL SECURITY`, so an update that names no company is
        // filtered to zero rows and "accepted" — the statement has to reach the row for the constraint to judge it.
        assertThat(constraintOf(
                        document.company(),
                        "UPDATE sales_orders SET subtotal = subtotal + 1 WHERE id = '" + document.orderId() + "'"))
                .as("M1: total_amount = subtotal - discount_total + tax_total + rounding_adjustment, exact — "
                        + "a ten-thousandth is a bug and not a rounding artifact (decision 3)")
                .isEqualTo("ck_order_total");

        // And the statement that states a part states the total with it, which is how the writer moves the two
        // together.
        inOneTransaction(
                document.company(),
                "UPDATE sales_orders SET subtotal = 100, total_amount = 100 WHERE id = '" + document.orderId() + "'");

        assertThat(money(document.company(), document.orderId(), "subtotal"))
                .as("a document whose parts add up to its total is accepted")
                .isEqualTo("100.0000");
    }

    @Test
    @DisplayName("a document written after the parts existed is restated instead of aborting the deploy")
    void restatesTheDocumentsWrittenAfterThePartsExisted() throws Exception {
        // The head `V80` and `V81` leave: the four parts exist, `ck_order_total` does not, and the writer
        // that has been in production ever since (`origin/main`'s `recalculateTotalAmount`) creates
        // documents in exactly this shape — `total_amount` from the lines' unit prices, the four parts at
        // their `DEFAULT 0`, because it never touches one. `V80:193` restated the rows the table held when
        // `V80` ran, and these are the rows it could not have seen.
        migrateTo("83");
        var document = seedDocumentAfterThePartsExisted();
        assertThat(money(document.company(), document.orderId(), "total_amount"))
                .as("the shape the post-V80 writer produces: a stated total")
                .isEqualTo("140.0000");
        assertThat(money(document.company(), document.orderId(), "subtotal"))
                .as("and no part stated at all, which is the row `ck_order_total` refuses")
                .isEqualTo("0.0000");

        // `V84` adds `ck_order_total`. Without the backfill this call throws, and the exception names the
        // constraint instead of the row — the deploy aborting on production data that ADR-0002 step 2
        // forbids. The pre-flight report does not cover it: `preflight-money-parts.sh` compares the total
        // with the lines, and these rows' parts agree with nothing at all.
        migrateToHead();

        assertThat(money(document.company(), document.orderId(), "subtotal"))
                .as("the row is stated the way V80 stated the legacy ones: its total is its subtotal")
                .isEqualTo("140.0000");
        assertThat(money(document.company(), document.orderId(), "total_amount"))
                .as("and the total it already carried does not move — a financial migration may not "
                        + "restate a stored amount")
                .isEqualTo("140.0000");
        assertThat(money(document.company(), document.orderId(), "discount_total"))
                .as("no part is invented to make the sum work")
                .isEqualTo("0.0000");
        assertThat(money(document.company(), document.orderId(), "tax_total")).isEqualTo("0.0000");
        assertThat(money(document.company(), document.orderId(), "rounding_adjustment"))
                .as("the rounding stays 0: it is not inflated to hide a difference nothing computed")
                .isEqualTo("0.0000");
    }

    @Test
    @DisplayName("the line's subtotal is refused when it is not its price times its quantity")
    void theLineSubtotalIsRefused() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var document = seedLegacyDocument();
        migrateToHead();

        assertThat(constraintOf(
                        null, "UPDATE sales_order_items SET subtotal = 199 WHERE id = '" + document.lineA() + "'"))
                .as("ck_item_subtotal states the expression the backfill wrote, so the column cannot drift "
                        + "from the two numbers it is made of")
                .isEqualTo("ck_item_subtotal");

        assertThat(constraintOf(
                        null,
                        "UPDATE sales_order_items SET tax_rate = 0.16, tax_amount = 1 WHERE id = '" + document.lineA()
                                + "'"))
                .as("ck_item_tax holds the taxable base times the rate copied onto the line")
                .isEqualTo("ck_item_tax");
    }

    @Test
    @DisplayName("a line inserted without a subtotal gets round(price x quantity, 4) from the schema")
    void theLineSubtotalIsDerivedOnInsert() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var document = seedLegacyDocument();
        migrateToHead();

        var variant =
                readUnscoped("SELECT product_variant_id FROM sales_order_items WHERE id = '" + document.lineA() + "'");
        var order = UUID.randomUUID();
        var line = UUID.randomUUID();
        // The writer that exists today sends no subtotal (`SalesOrderService:225,351,364`), so this is the
        // path every order in the tree takes: the trigger derives the column and the constraint agrees.
        inOneTransaction(
                document.company(),
                insertOrderStatement(document, order, 3, "0"),
                creationRow(document.company(), "SALES_ORDER", order, "DRAFT"),
                "INSERT INTO sales_order_items (id, sales_order_id, product_variant_id, quantity, list_price,"
                        + " final_price, status, created_at) VALUES ('" + line + "', '" + order + "', '" + variant
                        + "', 3.0000, 12.3456, 12.3456, 'PENDING', '2026-11-06 10:00:00')",
                creationRow(document.company(), "SALES_ORDER_ITEM", line, "PENDING"));

        assertThat(lineMoney(line, "subtotal"))
                .as("price times quantity at four decimals, derived by the schema instead of trusted to the "
                        + "caller")
                .isEqualTo("37.0368");
    }

    @Test
    @DisplayName("a document cannot be restated into another currency by an update")
    void theCurrencyCannotBeRestated() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var document = seedLegacyDocument();
        migrateToHead();

        var refusal = refusalOf(
                document.company(), "UPDATE sales_orders SET currency = 'USD' WHERE id = '" + document.orderId() + "'");

        assertThat(refusal.getMessage())
                .as("the derivation runs on update too, so decision 2 is not a rule about inserts only")
                .contains("cannot be in a currency other than the company's operating currency")
                .contains("USD");
    }

    @Test
    @DisplayName("a rounding of exactly one unit is accepted")
    void theRoundingBoundAcceptsOneUnit() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var document = seedLegacyDocument();
        migrateToHead();

        // The boundary of `abs(rounding_adjustment) <= 1` is inclusive, and this is the case a `abs(…) < 1`
        // would break while the refusal case above still passed.
        inOneTransaction(
                document.company(),
                "UPDATE sales_orders SET rounding_adjustment = 1, subtotal = 139, total_amount = 140" + " WHERE id = '"
                        + document.orderId() + "'");

        assertThat(money(document.company(), document.orderId(), "rounding_adjustment"))
                .as("one minor unit is the widest bound any currency allows, and it is accepted")
                .isEqualTo("1.0000");
    }

    @Test
    @DisplayName("the premise: with the window closed again, the owner reads no company")
    void theOwnerReadsNoCompanyWithoutTheScope() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var document = seedLegacyDocument();
        migrateToHead();

        assertThat(readScoped(
                        document.company(), "SELECT count(*) FROM companies WHERE id = '" + document.company() + "'"))
                .as("with the scope, the fixture's own row is there")
                .isEqualTo("1");
        assertThat(count("SELECT count(*) FROM companies"))
                .as("without it, this owner sees nothing — which is why V80 opens a NO FORCE window instead "
                        + "of trusting its UPDATE to match a row")
                .isZero();
    }

    // --- migrations ---

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

    /** One legacy document, its two lines and the catalog they name. */
    private record LegacyDocument(
            UUID company,
            UUID storeId,
            UUID customerId,
            UUID orderId,
            UUID lineA,
            UUID lineB,
            String productName,
            String variantName) {}

    /**
     * A company with one store, a customer, one product and one variant, one order whose total is the sum
     * of its lines' <b>unit</b> prices (140.00) and two lines: one with a discount, one whose
     * {@code discount_applied} is `NULL`, which is the shape `V1:502` allows.
     *
     * <p>Everything happens in one transaction because {@code V68}'s two constraint triggers judge the
     * commit: a document born without its creation row cannot commit, and neither can a line.</p>
     */
    private LegacyDocument seedLegacyDocument() throws SQLException {
        return seedDocument(false);
    }

    /**
     * The same document written at the head {@code V80} and {@code V81} leave behind, which is the shape
     * every order created after that release carries: {@code total_amount} stated and the four parts at
     * their {@code DEFAULT 0}, because the writer that has run since then never states one. The only
     * difference from {@link #seedLegacyDocument()} is the second line's discount: {@code V81} makes
     * {@code discount_applied} {@code NOT NULL}, so the {@code NULL} this fixture writes at the pre-unit
     * head is written as {@code 0} here.
     */
    private LegacyDocument seedDocumentAfterThePartsExisted() throws SQLException {
        return seedDocument(true);
    }

    private LegacyDocument seedDocument(boolean afterTheParts) throws SQLException {
        var company = UUID.randomUUID();
        var store = UUID.randomUUID();
        var customer = UUID.randomUUID();
        var product = UUID.randomUUID();
        var variant = UUID.randomUUID();
        var order = UUID.randomUUID();
        var lineA = UUID.randomUUID();
        var lineB = UUID.randomUUID();
        var suffix = company.toString().replace("-", "").substring(0, 8);

        var document = new LegacyDocument(company, store, customer, order, lineA, lineB, "V80 Product", "unica");
        var statements = new java.util.ArrayList<String>();
        statements.add("INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + company
                + "', 'V80-" + suffix + "', 'V80 Company', 'V80" + suffix.toUpperCase() + "', '"
                + SEEDED_CODE + "')");
        statements.add("INSERT INTO company_countries (id, company_id, country_id)"
                + " SELECT gen_random_uuid(), '" + company + "', c.id FROM countries c"
                + " WHERE c.country_code = 'MX'");
        statements.addAll(java.util.List.of(hierarchyStatements(company, store)));
        statements.add(
                "INSERT INTO customers (id, name, sales_channel) VALUES ('" + customer + "', 'V80 Customer', 'STORE')");
        statements.add("INSERT INTO products (id, sku, name, company_id) VALUES ('" + product + "', 'V80-" + suffix
                + "', 'V80 Product', '" + company + "')");
        statements.add("INSERT INTO product_variants (id, product_id, bar_code, variant_name, measure_unit_id,"
                + " company_id) VALUES ('" + variant + "', '" + product + "', 'V80-BC-" + suffix
                + "', 'unica', '" + measureUnitId() + "', '" + company + "')");
        statements.add(insertOrderStatement(document, order, 1, "140.00"));
        statements.add(creationRow(company, "SALES_ORDER", order, "DRAFT"));
        statements.add("INSERT INTO sales_order_items (id, sales_order_id, product_variant_id, quantity,"
                + " list_price, discount_applied, final_price, status, created_at) VALUES ('" + lineA + "', '"
                + order + "', '" + variant + "', 2.00, 100.00, 10.00, 90.00, 'PENDING',"
                + " '2026-11-01 10:00:00')");
        statements.add(creationRow(company, "SALES_ORDER_ITEM", lineA, "PENDING"));
        statements.add("INSERT INTO sales_order_items (id, sales_order_id, product_variant_id, quantity,"
                + " list_price, discount_applied, final_price, status, created_at) VALUES ('" + lineB + "', '"
                + order + "', '" + variant + "', 1.00, 50.00, " + (afterTheParts ? "0.00" : "NULL")
                + ", 50.00, 'PENDING', '2026-11-01 10:00:00')");
        statements.add(creationRow(company, "SALES_ORDER_ITEM", lineB, "PENDING"));
        inOneTransaction(company, statements.toArray(String[]::new));
        return document;
    }

    /** The company's geography down to a store, which the document keys require. */
    private String[] hierarchyStatements(UUID company, UUID store) {
        var region = UUID.randomUUID();
        var zone = UUID.randomUUID();
        var suffix = company.toString().replace("-", "").substring(0, 6);
        return new String[] {
            "INSERT INTO company_regions (id, company_id, company_country_id, region_code, region_name)"
                    + " SELECT '" + region + "', '" + company + "', cc.id, 'R" + suffix + "', 'V80 Region'"
                    + " FROM company_countries cc WHERE cc.company_id = '" + company + "'",
            "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name)"
                    + " VALUES ('" + zone + "', '" + company + "', '" + region + "', 'Z" + suffix
                    + "', 'V80 Zone')",
            "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled)" + " VALUES ('" + store
                    + "', '" + company + "', '" + zone + "', 'V80 Store', true)"
        };
    }

    /**
     * One order at the pre-unit head: an identity in parts and a typed state (no {@code status_id}, which
     * {@code V71} dropped), with {@code total_amount} stated and {@code currency} not yet a column.
     */
    private String insertOrderStatement(LegacyDocument document, UUID id, long seq, String totalAmount) {
        var number =
                "SO-202611-" + String.format("%05d", seq) + "-" + id.toString().substring(0, 4);
        return "INSERT INTO sales_orders (id, order_number, customer_id, company_store_id, order_date, status,"
                + " company_id, doc_type, seq_period, seq_value, doc_number, total_amount, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + number + "', '" + document.customerId() + "', '"
                + document.storeId() + "', '2026-11-01 10:00:00', 'DRAFT', '" + document.company()
                + "', 'SALES_ORDER', '202611', " + seq + ", '" + number + "', " + totalAmount
                + ", '2026-11-01 10:00:00', '2026-11-01 10:00:00')";
    }

    /** A creation row written the way the recorder writes it: one per document, {@code from_status NULL}. */
    private String creationRow(UUID company, String documentType, UUID document, String state) {
        return "INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,"
                + " actor, occurred_at, reason) VALUES ('" + company + "', '" + documentType + "', '" + document
                + "', NULL, '" + state + "', 'unknown:migration', '2026-11-01 10:00:00', 'first')";
    }

    private UUID measureUnitId() throws SQLException {
        return UUID.fromString(readUnscoped("SELECT id FROM measure_units WHERE sat_code = 'H87'"));
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

    /** Runs a statement that must be refused and returns the constraint the refusal names. */
    private String constraintOf(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                if (scope != null) {
                    statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                }
                try {
                    statement.execute(sql);
                } catch (SQLException refused) {
                    var message = String.valueOf(refused.getMessage());
                    var named = message.contains("ck_order_total")
                            ? "ck_order_total"
                            : message.contains("ck_order_rounding_bounded")
                                    ? "ck_order_rounding_bounded"
                                    : message.contains("ck_order_parts_not_negative")
                                            ? "ck_order_parts_not_negative"
                                            : message.contains("ck_item_subtotal")
                                                    ? "ck_item_subtotal"
                                                    : message.contains("ck_item_tax") ? "ck_item_tax" : message;
                    return named;
                }
                throw new AssertionError("the statement was accepted and should have been refused: " + sql);
            }
        }
    }

    /** The same refusal, returned whole, for the cases that assert on a trigger's message. */
    private SQLException refusalOf(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                try {
                    statement.execute(sql);
                } catch (SQLException refused) {
                    return refused;
                }
                throw new AssertionError("the statement was accepted and should have been refused: " + sql);
            }
        }
    }

    /**
     * One money column of one document, as the database prints it: the scale is part of the assertion.
     * Read with the scope, because {@code sales_orders} is armed and FORCEd since {@code V65} and an
     * unscoped read answers no row at all.
     */
    private String money(UUID company, UUID order, String column) throws SQLException {
        return readScoped(company, "SELECT " + column + " FROM sales_orders WHERE id = '" + order + "'");
    }

    /** One money column of one line. */
    private String lineMoney(UUID line, String column) throws SQLException {
        return readUnscoped("SELECT " + column + " FROM sales_order_items WHERE id = '" + line + "'");
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

    /** A read of a table with no policy (currency, customers, catalogs, the lines themselves). */
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

    private int count(String sql) throws SQLException {
        return Integer.parseInt(readUnscoped(sql));
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
