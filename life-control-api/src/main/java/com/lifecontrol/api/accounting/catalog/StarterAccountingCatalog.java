package com.lifecontrol.api.accounting.catalog;

import java.util.List;

/**
 * The starter accounting catalog: the eight accounts of the minimum chart and the three posting rules that
 * name them, as two immutable lists.
 *
 * <p><b>This class is the source of truth for companies created after
 * {@code V98__accounting_starter_catalog.sql}</b> (S9 of {@code odd/tasks/derived-accounting.md}). The
 * migration reaches only the companies that exist when it runs, so a company created tomorrow is seeded by
 * {@link StarterAccountingCatalogListener} and it reads the catalog from here — a tenant added tomorrow does
 * not arrive without a book.</p>
 *
 * <p><b>The catalog therefore exists twice — once as SQL in V98, once as these lists — and the duplication is
 * pinned by {@link com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogTest}</b> through
 * {@link #toMigrationTuple(AccountEntry)} and {@link #toMigrationTuple(PostingRuleEntry)}, which render an
 * entry exactly like a V98 value row: the test parses the migration and compares both directions. <b>A change
 * here needs a new migration, never an edit to V98</b>: V98 has already run on every deployed database, so
 * editing it changes nothing there while this list changes for every company created afterwards, and the two
 * halves would diverge silently — which is the failure that test exists to convert into a red build.</p>
 *
 * <p><b>What the values mean, and why each is not a default:</b></p>
 *
 * <ul>
 *   <li>The codes are what a posting rule and a report address; the names are presentation and are Spanish
 *       because they are the tenant's data in a Mexican fiscal domain, the convention
 *       {@code TASA_0}/{@code EXENTO} already follows. All code, comments and test names stay English.</li>
 *   <li>{@code kind} is one of the five classes {@code ck_account_kind} (`V97`) accepts, and the catalog uses
 *       four of them. {@code IVA trasladado} is a {@code LIABILITY} because it is collected and owed;
 *       {@code IVA acreditable} is an {@code ASSET} for the mirror reason. They are not the same kind, and
 *       collapsing them would make a trial balance lie by sign while still summing to zero.</li>
 *   <li>A posting rule names its two accounts by <b>code</b>, never by id: the ids are
 *       {@code gen_random_uuid()} values the migration creates per tenant, so the applier resolves the pair by
 *       {@code (company_id, code)} inside the company it is seeding.</li>
 *   <li>There are exactly three rules and no {@code REVERSAL}, because a reversal mirrors the lines of the
 *       entry it names and needs no pair of its own. {@code COLLECTION} debits cash as a <b>stated
 *       limitation</b>, not a derivation: a card or transfer payment has no rule of its own until the
 *       collection rule splits by payment method.</li>
 * </ul>
 */
public final class StarterAccountingCatalog {

    /** One account of the chart: {@code kind} is one of {@code ck_account_kind}'s five values. */
    public record AccountEntry(String code, String name, String kind) {}

    /** One posting rule: the anchor it belongs to and the pair of accounts it moves, both named by code. */
    public record PostingRuleEntry(String sourceType, String posting, String debitCode, String creditCode) {}

    private static final List<AccountEntry> ACCOUNTS = List.of(
            new AccountEntry("1101", "Caja", "ASSET"),
            new AccountEntry("1102", "Bancos", "ASSET"),
            new AccountEntry("1201", "Cuentas por cobrar", "ASSET"),
            new AccountEntry("1202", "IVA acreditable", "ASSET"),
            new AccountEntry("1301", "Inventario", "ASSET"),
            new AccountEntry("2101", "IVA trasladado", "LIABILITY"),
            new AccountEntry("4101", "Ingresos por ventas", "REVENUE"),
            new AccountEntry("5101", "Costo de ventas", "EXPENSE"));

    private static final List<PostingRuleEntry> POSTING_RULES = List.of(
            new PostingRuleEntry("STATUS_TRANSITION", "REVENUE", "1201", "4101"),
            new PostingRuleEntry("SALE_PAYMENT", "COLLECTION", "1101", "1201"),
            new PostingRuleEntry("STOCK_ALLOCATION", "COST_OF_SALES", "5101", "1301"));

    private StarterAccountingCatalog() {}

    /** The whole chart of accounts, in the order V98 lists it. */
    public static List<AccountEntry> accounts() {
        return ACCOUNTS;
    }

    /** The three posting rules, in the order V98 lists them. */
    public static List<PostingRuleEntry> postingRules() {
        return POSTING_RULES;
    }

    /**
     * Renders one account exactly like a V98 {@code starter_account} value row, so
     * {@link com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogTest} can compare the two copies
     * literally instead of structurally. The trailing comma of a value row is omitted: SQL cannot terminate
     * the last row of a {@code VALUES} list with one, so the rendered line is asserted as a member of the
     * migration's rows and every row matches, the last included.
     */
    public static String toMigrationTuple(AccountEntry entry) {
        return "    (" + quote(entry.code()) + ", " + quote(entry.name()) + ", " + quote(entry.kind()) + ")";
    }

    /** Renders one posting rule exactly like a V98 {@code starter_posting_rule} value row. */
    public static String toMigrationTuple(PostingRuleEntry entry) {
        return "    (" + quote(entry.sourceType())
                + ", " + quote(entry.posting())
                + ", " + quote(entry.debitCode())
                + ", " + quote(entry.creditCode())
                + ")";
    }

    private static String quote(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }
}
