package com.lifecontrol.api.support;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one sale fixture: a company with its store hierarchy, one product and one variant, a sale with two
 * lines, and the {@code status_transition} creation row each of those documents needs.
 *
 * <p><b>Why it is shared and not written twice.</b> Two suites need it —
 * {@code FiscalDocumentSchemaMigrationIntegrationTest} and
 * {@code FiscalDocumentSnapshotMigrationIntegrationTest} — and the second one's subject is a comparison
 * <b>between two documents</b>: the fiscal document and the sale it names. A fixture that fabricated the
 * sale's amounts would be comparing the document with the constants the suite just wrote, which is the one
 * thing {@code V109}'s judgement must not be able to do. So the sale is a real row set whose money the
 * derivations produce ({@code V81}, {@code V84}, {@code V90}), and both suites read it back.</p>
 *
 * <p><b>Why one copy matters here more than usual.</b> {@code V109} made the document's frozen receiver and
 * issuer identity, its place of issue, its currency and its money parts {@code NOT NULL}, and its constraint
 * trigger judges a {@code STAMPED} row at commit against the sale its {@code source_id} names. A fixture that
 * has to satisfy both a required-column set and a commit-time comparison is a fixture that can be wrong in
 * two ways at once: a second copy would be free to drift, and the drift would surface as a failing comparison
 * in whichever case happened to run after it, naming a rule the case is not about.</p>
 *
 * <p><b>The amounts are the ones the derivations produce, not constants stated to be compared with.</b>
 * {@code 2 x 100.00 - 10.00} at 16% is {@code 30.4000} of tax on {@code 200.0000}, and {@code 3 x 50.50 -
 * 1.50} is {@code 24.0000} on {@code 151.5000}, which the sale's own writer adds up to {@code 351.5000} of
 * subtotal, {@code 11.5000} of discount, {@code 54.4000} of tax and {@code 394.4000} of total. The suites read
 * the sale back where it matters, so a derivation that changed would fail the comparison for the honest
 * reason instead of passing against a literal.</p>
 *
 * <p><b>The sale is {@code PENDING} on purpose.</b> A completion transition posts revenue through
 * {@code V100}'s {@code trg_status_transition_post_revenue}, which needs a chart of accounts and its posting
 * rules for the company — a fixture of the accounting slice that has nothing to do with a fiscal document.</p>
 *
 * <p><b>Not Spring, not a pool, and no transaction of its own.</b> Both callers are JDBC suites that own their
 * own credentials and their own transactions, and the boundary matters: the sale and its history rows have to
 * commit <b>together</b>, because {@code assert_document_transition_recorded} ({@code V68}) is
 * {@code DEFERRABLE INITIALLY DEFERRED} and a sale written in autocommit with its creation row written after
 * it is a commit refused for a reason that has nothing to do with the case. {@link Statements} is that
 * boundary, so a caller that hands one transaction per call gets exactly the fixture it would have written
 * itself, and a caller that hands one transaction per batch gets the whole chain in a single commit.</p>
 */
public final class SaleFixtures {

    /** The currency every sale below states, and the one {@code V57} seeds with two minor units. */
    public static final String CURRENCY = "MXN";

    /** The sale's four money parts, as {@code V84}'s constraints leave them: the total is the other three. */
    public static final String SALE_SUBTOTAL = "351.5000";

    public static final String SALE_DISCOUNT = "11.5000";
    public static final String SALE_TAX_TOTAL = "54.4000";
    public static final String SALE_TOTAL = "394.4000";

    /** The two sale lines' derived amounts, as {@code V81} and {@code V90} derive them. */
    public static final String FIRST_LINE_TAX = "30.4000";

    public static final String SECOND_LINE_TAX = "24.0000";

    /** A company with the store hierarchy, one product and one variant, at head. */
    public record Tree(UUID company, UUID store, UUID variant) {}

    /** One sale with its two lines, all in one company, as the tree's writer leaves them. */
    public record Sale(UUID company, UUID store, UUID sale, UUID firstLine, UUID secondLine) {}

    /** Runs statements as one unit of work; whether that is one transaction is the caller's decision. */
    @FunctionalInterface
    public interface Statements {
        void execute(String... statements) throws SQLException;
    }

    /** A one-row, one-column read: a catalog id, never a row of the document under test. */
    @FunctionalInterface
    public interface Scalar {
        String read(String sql) throws SQLException;
    }

    private final Statements statements;
    private final Scalar scalar;

    public SaleFixtures(Statements statements, Scalar scalar) {
        this.statements = statements;
        this.scalar = scalar;
    }

    /**
     * A company whose currency is the catalog's, which {@code V57} made mandatory on the row.
     *
     * @param label a prefix the caller chooses, so two companies of one case are named for the case; the rest
     *     of {@code company_key} and the whole {@code rfc} come from the generated id, because both are
     *     {@code UNIQUE} and a fixture that reused a literal would collide with the company of the case that
     *     ran before it
     */
    public UUID seedCompany(String label) throws SQLException {
        var company = UUID.randomUUID();
        var suffix = company.toString().replace("-", "").substring(0, 10).toUpperCase();
        statements.execute(
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('" + CURRENCY
                        + "', 2, '$', true) ON CONFLICT (code) DO NOTHING",
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + company + "', '"
                        + label + "-" + suffix + "', '" + label + " Company', 'RFC" + suffix + "', '" + CURRENCY
                        + "')");
        return company;
    }

    /**
     * One company with a country, a region, a zone, a store, a product and a variant: the rows every sale
     * below needs, and the rows a fiscal document line's own keys will name once a writer exists.
     */
    public Tree seedTree(String label) throws SQLException {
        var company = seedCompany(label);
        var suffix = company.toString().replace("-", "").substring(0, 8);
        var country = scalar.read("SELECT id FROM countries WHERE country_code = 'MX'");
        var companyCountry = UUID.randomUUID();
        var region = UUID.randomUUID();
        var zone = UUID.randomUUID();
        var store = UUID.randomUUID();

        statements.execute(
                "INSERT INTO company_countries (id, company_id, country_id) VALUES ('" + companyCountry + "', '"
                        + company + "', '" + country + "')",
                "INSERT INTO company_regions (id, company_id, company_country_id, region_code, region_name) VALUES"
                        + " ('" + region + "', '" + company + "', '" + companyCountry + "', 'R"
                        + suffix.substring(0, 4) + "', '" + label + " Region')",
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name) VALUES ('"
                        + zone + "', '" + company + "', '" + region + "', 'Z" + suffix.substring(0, 4) + "', '"
                        + label + " Zone')",
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + zone + "', '" + label + " Store', true)");

        var product = UUID.randomUUID();
        statements.execute("INSERT INTO products (id, sku, name, company_id, sat_code) VALUES ('" + product + "', '"
                + label + "-" + suffix + "', '" + label + " Product', '" + company + "', '01010101')");

        var variant = UUID.randomUUID();
        statements.execute("INSERT INTO product_variants (id, product_id, bar_code, variant_name, measure_unit_id,"
                + " company_id) VALUES ('" + variant + "', '" + product + "', '" + label + "-BC-" + suffix + "',"
                + " 'unica', '" + scalar.read("SELECT id FROM measure_units WHERE sat_code = 'H87'") + "', '"
                + company + "')");

        return new Tree(company, store, variant);
    }

    /** A brand-new company, its tree and one sale: the shape a case needs when it only needs a sale. */
    public Sale seedSale(String label) throws SQLException {
        return seedSale(seedTree(label), 1);
    }

    /**
     * One more sale of a company that already has one, with its own identity parts: {@code seq_value} is what
     * distinguishes two documents of the same type in the same period (ADR-0003), and the case that needs a
     * second sale is the one about a document line naming the wrong one.
     */
    public Sale seedSale(Tree tree, long sequence) throws SQLException {
        var sale = UUID.randomUUID();
        var firstLine = UUID.randomUUID();
        var secondLine = UUID.randomUUID();
        statements.execute(
                saleStatements(tree, sale, firstLine, secondLine, sequence).toArray(String[]::new));
        return new Sale(tree.company(), tree.store(), sale, firstLine, secondLine);
    }

    /**
     * The sale, its two lines and one {@code status_transition} row per document — the creation rows
     * {@code V68}'s deferred pair requires, without which the commit is refused for a reason that has nothing
     * to do with the document.
     *
     * <p>Returned as statements and not executed, because the case that proves the judgement is taken at
     * commit writes the document <b>before</b> the sale it names: an immediate judgement would find no sale,
     * and a deferred one reads the committed transaction.</p>
     */
    public List<String> saleStatements(Tree tree, UUID sale, UUID firstLine, UUID secondLine, long sequence) {
        var number = "SO-202611-" + String.format("%05d", sequence) + "-"
                + sale.toString().substring(0, 4);
        var statements = new ArrayList<String>();
        statements.add("INSERT INTO sales_orders (id, order_number, customer_id, company_store_id, order_date,"
                + " status, company_id, doc_type, seq_period, seq_value, doc_number, currency, subtotal,"
                + " discount_total, tax_total, rounding_adjustment, total_amount, created_at, updated_at) VALUES ('"
                + sale + "', '" + number + "', NULL, '" + tree.store() + "', '2026-11-20 10:00:00', 'PENDING', '"
                + tree.company() + "', 'SALES_ORDER', '202611', " + sequence + ", '" + number + "', '" + CURRENCY
                + "', " + SALE_SUBTOTAL + ", " + SALE_DISCOUNT + ", " + SALE_TAX_TOTAL + ", 0, " + SALE_TOTAL
                + ", '2026-11-20 10:00:00', '2026-11-20 10:00:00')");
        statements.add(creationRow(tree.company(), "SALES_ORDER", sale, "PENDING"));
        statements.add(saleLine(tree, sale, firstLine, "2.0000", "100.0000", "10.0000"));
        statements.add(creationRow(tree.company(), "SALES_ORDER_ITEM", firstLine, "PENDING"));
        statements.add(saleLine(tree, sale, secondLine, "3.0000", "50.5000", "1.5000"));
        statements.add(creationRow(tree.company(), "SALES_ORDER_ITEM", secondLine, "PENDING"));
        return statements;
    }

    /** One sale line stating only what its writer states: the rest is derived by {@code V81}, {@code V90}. */
    public String saleLine(Tree tree, UUID sale, UUID line, String quantity, String listPrice, String discount) {
        return "INSERT INTO sales_order_items (id, sales_order_id, product_variant_id, quantity, list_price,"
                + " discount_applied, tax_rate, tax_code, status, created_at, updated_at) VALUES ('" + line + "', '"
                + sale + "', '" + tree.variant() + "', " + quantity + ", " + listPrice + ", " + discount
                + ", 0.160000, 'IVA', 'PENDING', '2026-11-20 10:00:00', '2026-11-20 10:00:00')";
    }

    /** A creation row, the shape {@code V68} reads: one per document, {@code from_status NULL}. */
    public String creationRow(UUID company, String documentType, UUID document, String state) {
        return "INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,"
                + " actor, occurred_at, reason) VALUES ('" + company + "', '" + documentType + "', '" + document
                + "', NULL, '" + state + "', 'unknown:fixture', '2026-11-20 10:00:00', 'fixture')";
    }
}
