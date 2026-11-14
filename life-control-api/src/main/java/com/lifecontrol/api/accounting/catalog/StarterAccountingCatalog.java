package com.lifecontrol.api.accounting.catalog;

import java.util.List;

/**
 * The starter accounting catalog: the eight accounts of the minimum chart and the three posting rules that name
 * them, each rule declaring the lines it produces.
 *
 * <p><b>This class is the source of truth for companies created after the starter migrations</b> (S9 of
 * {@code odd/tasks/derived-accounting.md}). The migrations reach only the companies that exist when they run, so
 * a company created tomorrow is seeded by {@link StarterAccountingCatalogListener} and it reads the catalog from
 * here — a tenant added tomorrow does not arrive without a book.</p>
 *
 * <p><b>The catalog therefore exists twice</b> — once as SQL ({@code V98} for the accounts and the rules,
 * {@code V99} for their lines) and once as these lists — and the duplication is pinned by
 * {@link com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogTest} through
 * {@link #toMigrationTuple(AccountEntry)} and {@link #toMigrationTuple(PostingRuleEntry, PostingRuleLine)}, which
 * render rows exactly like a migration value row: the test parses the migrations and compares both directions.
 * <b>A change here needs a new migration, never an edit to an existing one</b>: they have already run on every
 * deployed database, so editing one changes nothing there while these lists change for every company created
 * afterwards, and the two halves would diverge silently — which is the failure that test exists to convert into a
 * red build.</p>
 *
 * <p><b>A rule is N lines and not a debit/credit pair (L13).</b> The ADR's first shape gave a rule one debit
 * account and one credit account, and S11 asks the entry a sale produces to carry three parts of the document:
 * the revenue, the {@code IVA trasladado} and the receivable. A pair cannot express three, so {@code 2101 IVA
 * trasladado} was in the chart named by no rule and the tax of every sale had nowhere to post. A line names the
 * side, the account and <b>which amount of the source</b> it moves; the pair is now derivable from the lines
 * instead of being a second copy of them.</p>
 *
 * <p><b>What the values mean, and why each is not a default:</b></p>
 *
 * <ul>
 *   <li>The codes are what a posting rule and a report address; the names are presentation and are Spanish
 *       because they are the tenant's data in a Mexican fiscal domain, the convention {@code TASA_0}/{@code
 *       EXENTO} already follows. All code, comments and test names stay English.</li>
 *   <li>{@code kind} is one of the five classes {@code ck_account_kind} (`V97`) accepts, and the catalog uses
 *       four of them. {@code IVA trasladado} is a {@code LIABILITY} because it is collected and owed;
 *       {@code IVA acreditable} is an {@code ASSET} for the mirror reason. They are not the same kind, and
 *       collapsing them would make a trial balance lie by sign while still summing to zero.</li>
 *   <li>The selector vocabulary is closed and shared by the three anchors (L13(a)): {@code TOTAL},
 *       {@code REVENUE} and {@code TAX} of the document, {@code SOURCE_AMOUNT} of a payment,
 *       {@code ALLOCATED_COST} of the ledger. One vocabulary for three anchors, because a per-anchor enum would
 *       triple the {@code CHECK}s and still say nothing about which amount a posting reads.</li>
 *   <li>{@code REVENUE} is {@code total_amount − tax_total} and not the bare base, because the rounding is
 *       folded into it (L13(b)): {@code ck_order_total} states {@code total = subtotal − discount + tax +
 *       rounding}, a line carrying the rounding goes negative on a discount-heavy document and
 *       {@code ck_account_entry_line_non_negative} refuses it, so folding it is what keeps {@code TOTAL =
 *       REVENUE + TAX} balanced by construction.</li>
 *   <li>Every line names its account by <b>code</b>, never by id: the ids are {@code gen_random_uuid()} values
 *       the migration creates per tenant, so the applier resolves them by {@code (company_id, code)} inside the
 *       company it is seeding.</li>
 *   <li>There are exactly three rules and no {@code REVERSAL}, because a reversal mirrors the lines of the entry
 *       it names and needs no lines of its own. {@code COLLECTION} debits cash as a <b>stated limitation</b>,
 *       not a derivation: a card or transfer payment has no rule of its own until the collection rule splits by
 *       payment method.</li>
 * </ul>
 */
public final class StarterAccountingCatalog {

    /** One account of the chart: {@code kind} is one of {@code ck_account_kind}'s five values. */
    public record AccountEntry(String code, String name, String kind) {}

    /**
     * One part of one rule: the side it posts on, which amount of the source it moves, and the account it moves
     * it to or from, named by code.
     *
     * <p>{@code side} is {@code DEBIT} or {@code CREDIT} — the two values {@code ck_posting_rule_line_side}
     * admits — and {@code amountSelector} comes from the closed five-value vocabulary, so a selector no posting
     * can compute refuses the boot instead of posting an amount nobody can find.</p>
     */
    public record PostingRuleLine(String side, String amountSelector, String accountCode) {}

    /**
     * One posting rule: the anchor it belongs to and the lines it produces.
     *
     * <p>The lines are copied on construction, so a rule handed to the startup assertion or the applier cannot be
     * altered afterwards by whoever built it — the catalog is a static declaration and a mutable list here would
     * make a boot depend on the order of a caller's edits.</p>
     */
    public record PostingRuleEntry(String sourceType, String posting, List<PostingRuleLine> lines) {
        public PostingRuleEntry {
            lines = List.copyOf(lines);
        }
    }

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
            new PostingRuleEntry(
                    "STATUS_TRANSITION",
                    "REVENUE",
                    List.of(
                            new PostingRuleLine("DEBIT", "TOTAL", "1201"),
                            new PostingRuleLine("CREDIT", "REVENUE", "4101"),
                            new PostingRuleLine("CREDIT", "TAX", "2101"))),
            new PostingRuleEntry(
                    "SALE_PAYMENT",
                    "COLLECTION",
                    List.of(
                            new PostingRuleLine("DEBIT", "SOURCE_AMOUNT", "1101"),
                            new PostingRuleLine("CREDIT", "SOURCE_AMOUNT", "1201"))),
            new PostingRuleEntry(
                    "STOCK_ALLOCATION",
                    "COST_OF_SALES",
                    List.of(
                            new PostingRuleLine("DEBIT", "ALLOCATED_COST", "5101"),
                            new PostingRuleLine("CREDIT", "ALLOCATED_COST", "1301"))));

    private StarterAccountingCatalog() {}

    /** The whole chart of accounts, in the order {@code V98} lists it. */
    public static List<AccountEntry> accounts() {
        return ACCOUNTS;
    }

    /** The three posting rules, in the order the migrations seed them. */
    public static List<PostingRuleEntry> postingRules() {
        return POSTING_RULES;
    }

    /** How many lines the whole catalog declares: seven, three of them belonging to the revenue rule. */
    public static int postingRuleLineCount() {
        return POSTING_RULES.stream().mapToInt(rule -> rule.lines().size()).sum();
    }

    /**
     * Renders one account exactly like a {@code V98} {@code starter_account} value row, so
     * {@link com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogTest} can compare the two copies
     * literally instead of structurally. The trailing comma of a value row is omitted: SQL cannot terminate
     * the last row of a {@code VALUES} list with one, so the rendered line is asserted as a member of the
     * migration's rows and every row matches, the last included.
     */
    public static String toMigrationTuple(AccountEntry entry) {
        return "    (" + quote(entry.code()) + ", " + quote(entry.name()) + ", " + quote(entry.kind()) + ")";
    }

    /**
     * Renders one line of one rule exactly like a {@code V99} {@code starter_posting_rule_line} value row: the
     * anchor of the rule it belongs to, then the line's side, selector and account code.
     *
     * <p>The anchor is part of the row because a line on its own says nothing about which source it posts — and
     * the migration states it the same way, for the same reason.</p>
     */
    public static String toMigrationTuple(PostingRuleEntry rule, PostingRuleLine line) {
        return "    (" + quote(rule.sourceType())
                + ", " + quote(rule.posting())
                + ", " + quote(line.side())
                + ", " + quote(line.amountSelector())
                + ", " + quote(line.accountCode())
                + ")";
    }

    private static String quote(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }
}
