package com.lifecontrol.api.accounting.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the copies of the starter accounting catalog to each other: {@link StarterAccountingCatalog} in Java,
 * the accounts of {@code V98__accounting_starter_catalog.sql} and the rule lines of
 * {@code V99__posting_rule_lines.sql}.
 *
 * <p><b>S9 of {@code odd/tasks/derived-accounting.md} says the catalogs are seeded, not user-invented</b>, and
 * the seed has two halves that cannot reach each other: the migration reaches the companies that exist when it
 * runs, the Java list reaches the companies created afterwards. Both write the same eight accounts, the same
 * three posting rules and the same seven lines, so the only thing keeping them from diverging is this test. It
 * reads the migrations as classpath resources, which is what makes it check the files that actually ship
 * instead of a copy the test owns.</p>
 *
 * <p><b>Two migrations and not one, because the rule changed shape.</b> {@code V98} is history: it has run on
 * every deployed database, so it is never edited, and it is still the file that states the accounts and the
 * three anchors. {@code V99} is where a rule became N lines (L13) and where the two account columns were
 * dropped, so the lines are pinned against it and the rules' identity is pinned against both — a rule the Java
 * catalog declares that {@code V98} never seeded would be a rule no existing tenant receives.</p>
 *
 * <p><b>Why the comparison is textual and bidirectional.</b> A structural comparison (same codes, same
 * accounts) would pass while a name, a kind, a side or a selector differed — and a seeded account whose
 * {@code kind} is {@code ASSET} on one side and {@code LIABILITY} on the other makes the trial balance lie by
 * sign while still summing to zero. Rendering each row exactly like a migration value row and comparing the two
 * sets in both directions turns any of those differences into a red build, the shape
 * {@code StarterProductCategoryCatalogTest} established for V35.</p>
 *
 * <p><b>This is a plain JUnit test on purpose.</b> The catalog is a static list, so a Spring context or a
 * database would add nothing. The database half of S9 — that the migration seeds the tenants that exist, that
 * {@code V99} backfills their lines, and that the applier seeds the companies created later — is measured by the
 * integration suites beside this file.</p>
 */
@DisplayName("Starter Accounting Catalog Tests")
class StarterAccountingCatalogTest {

    private static final String ACCOUNT_MIGRATION = "/db/migration/V98__accounting_starter_catalog.sql";

    private static final String RULE_LINE_MIGRATION = "/db/migration/V99__posting_rule_lines.sql";

    @Test
    @DisplayName("holds exactly the eight accounts S9 lists, with their codes, names and kinds")
    void holdsTheEightAccounts() {
        assertThat(StarterAccountingCatalog.accounts())
                .extracting(StarterAccountingCatalog.AccountEntry::code)
                .as("the chart of accounts is closed: eight codes, in the order a book reads them")
                .containsExactly("1101", "1102", "1201", "1202", "1301", "2101", "4101", "5101");

        assertThat(StarterAccountingCatalog.accounts())
                .extracting(StarterAccountingCatalog.AccountEntry::name)
                .as("the tenant's own words, in Spanish, because the chart is the tenant's data")
                .containsExactly(
                        "Caja",
                        "Bancos",
                        "Cuentas por cobrar",
                        "IVA acreditable",
                        "Inventario",
                        "IVA trasladado",
                        "Ingresos por ventas",
                        "Costo de ventas");

        assertThat(StarterAccountingCatalog.accounts())
                .extracting(StarterAccountingCatalog.AccountEntry::kind)
                .as("IVA trasladado is a liability and IVA acreditable an asset: they are not the same kind, "
                        + "and collapsing them would make the trial balance lie by sign while still balancing")
                .containsExactly("ASSET", "ASSET", "ASSET", "ASSET", "ASSET", "LIABILITY", "REVENUE", "EXPENSE");
    }

    @Test
    @DisplayName("holds exactly the three posting rules S9 lists, each declaring its side, selector and account")
    void holdsTheThreePostingRules() {
        assertThat(StarterAccountingCatalog.postingRules())
                .extracting(
                        StarterAccountingCatalog.PostingRuleEntry::sourceType,
                        StarterAccountingCatalog.PostingRuleEntry::posting)
                .containsExactly(
                        tuple("STATUS_TRANSITION", "REVENUE"),
                        tuple("SALE_PAYMENT", "COLLECTION"),
                        tuple("STOCK_ALLOCATION", "COST_OF_SALES"));

        assertThat(declaredLines())
                .as("the seven lines: the revenue rule carries three parts of the document (S11) and the other "
                        + "two move one amount from one account to another")
                .containsExactly(
                        "STATUS_TRANSITION:REVENUE:DEBIT:TOTAL:1201",
                        "STATUS_TRANSITION:REVENUE:CREDIT:REVENUE:4101",
                        "STATUS_TRANSITION:REVENUE:CREDIT:TAX:2101",
                        "SALE_PAYMENT:COLLECTION:DEBIT:SOURCE_AMOUNT:1101",
                        "SALE_PAYMENT:COLLECTION:CREDIT:SOURCE_AMOUNT:1201",
                        "STOCK_ALLOCATION:COST_OF_SALES:DEBIT:ALLOCATED_COST:5101",
                        "STOCK_ALLOCATION:COST_OF_SALES:CREDIT:ALLOCATED_COST:1301");
    }

    @Test
    @DisplayName("the revenue rule's typing is TOTAL, REVENUE and TAX: the rounding is folded into REVENUE")
    void theRevenueRuleCarriesTheThreePartsOfTheDocument() {
        var revenue = rule("REVENUE");

        assertThat(revenue.lines())
                .extracting(
                        StarterAccountingCatalog.PostingRuleLine::side,
                        StarterAccountingCatalog.PostingRuleLine::amountSelector)
                .as("TOTAL is what the customer owes, REVENUE is total minus tax and TAX is the IVA trasladado "
                        + "the document already states. The selector is REVENUE and not BASE because the "
                        + "rounding belongs to it (L13(b)): a fourth line carrying `rounding_adjustment` turns "
                        + "negative on a discount-heavy document and `ck_account_entry_line_non_negative` "
                        + "refuses it, so folding it keeps TOTAL = REVENUE + TAX balanced by construction")
                .containsExactly(tuple("DEBIT", "TOTAL"), tuple("CREDIT", "REVENUE"), tuple("CREDIT", "TAX"));
    }

    @Test
    @DisplayName("declares no REVERSAL rule and one rule per anchor: the closed minimum vocabulary")
    void declaresNoReversalRuleAndOneRulePerAnchor() {
        assertThat(StarterAccountingCatalog.postingRules())
                .extracting(StarterAccountingCatalog.PostingRuleEntry::posting)
                .as("a reversal mirrors the lines of the entry it names and needs no pair of its own, so a "
                        + "REVERSAL row would be a rule nothing reads")
                .doesNotContain("REVERSAL");

        var anchors = new ArrayList<String>();
        var duplicates = new ArrayList<String>();
        for (var rule : StarterAccountingCatalog.postingRules()) {
            var key = rule.sourceType() + "\u0000" + rule.posting();
            if (anchors.contains(key)) {
                duplicates.add(rule.sourceType() + "/" + rule.posting());
            }
            anchors.add(key);
        }

        assertThat(duplicates)
                .as("uq_posting_rule_company_source_posting rejects a second rule for the same anchor")
                .isEmpty();
        assertThat(anchors)
                .as("three anchors and no fourth: a money-moving source with no rule is what S10 refuses")
                .hasSize(3);
    }

    @Test
    @DisplayName("names only accounts the catalog declares, only admitted sides and only admitted selectors")
    void everyLineNamesADeclaredAccount() {
        var codes = StarterAccountingCatalog.accounts().stream()
                .map(StarterAccountingCatalog.AccountEntry::code)
                .toList();

        var undeclared = StarterAccountingCatalog.postingRules().stream()
                .flatMap(rule -> rule.lines().stream())
                .map(StarterAccountingCatalog.PostingRuleLine::accountCode)
                .filter(code -> !codes.contains(code))
                .distinct()
                .toList();

        assertThat(undeclared)
                .as("a line that names an account the seed does not create is a line that cannot be inserted")
                .isEmpty();
        assertThat(StarterAccountingCatalog.accounts())
                .extracting(StarterAccountingCatalog.AccountEntry::kind)
                .as("ck_account_kind is the closed set V97 states, and the catalog may not exceed it")
                .allMatch(kind -> List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE")
                        .contains(kind));
        assertThat(StarterAccountingCatalog.postingRules().stream().flatMap(rule -> rule.lines().stream()))
                .extracting(StarterAccountingCatalog.PostingRuleLine::side)
                .as("ck_posting_rule_line_side admits DEBIT and CREDIT and nothing else")
                .allMatch(side -> List.of("DEBIT", "CREDIT").contains(side));
        assertThat(StarterAccountingCatalog.postingRules().stream().flatMap(rule -> rule.lines().stream()))
                .extracting(StarterAccountingCatalog.PostingRuleLine::amountSelector)
                .as("ck_posting_rule_line_selector is the closed five-value vocabulary L13(a) chose so one "
                        + "selector set serves the three anchors instead of a per-anchor enum tripling the CHECKs")
                .allMatch(selector -> List.of("TOTAL", "REVENUE", "TAX", "SOURCE_AMOUNT", "ALLOCATED_COST")
                        .contains(selector));
    }

    @Test
    @DisplayName("matches V98's accounts and V99's lines in both directions, whatever their indentation")
    void matchesTheMigrationInBothDirections() throws IOException {
        var accounts = readMigration(ACCOUNT_MIGRATION);
        var ruleLines = readMigration(RULE_LINE_MIGRATION);

        // Both directions compare stripped rows, so re-indenting a migration is not a false RED while a real
        // difference in any value — a code, a name, a kind, a side, a selector — is.
        var declaredInJava = new LinkedHashSet<String>();
        StarterAccountingCatalog.accounts().stream()
                .map(StarterAccountingCatalog::toMigrationTuple)
                .map(String::trim)
                .forEach(declaredInJava::add);
        for (var rule : StarterAccountingCatalog.postingRules()) {
            for (var line : rule.lines()) {
                declaredInJava.add(
                        StarterAccountingCatalog.toMigrationTuple(rule, line).trim());
            }
        }

        var declaredInMigration = new LinkedHashSet<String>();
        declaredInMigration.addAll(migrationRows(accounts, "starter_account"));
        declaredInMigration.addAll(migrationRows(ruleLines, "starter_posting_rule_line"));

        assertThat(declaredInJava)
                .as("every row the Java catalog declares is a value row of V98 (accounts) or V99 (lines)")
                .isSubsetOf(declaredInMigration);
        assertThat(declaredInMigration)
                .as("and the migrations declare no row the Java catalog does not: the two halves are one " + "catalog")
                .isSubsetOf(declaredInJava);

        assertThat(migrationRows(accounts, "starter_account"))
                .as("eight accounts")
                .hasSize(StarterAccountingCatalog.accounts().size());
        assertThat(migrationRows(ruleLines, "starter_posting_rule_line"))
                .as("and seven lines — three, two and two")
                .hasSize(7);
    }

    @Test
    @DisplayName("the rules the Java catalog declares are the three anchors V98 seeded")
    void declaresTheAnchorsTheAccountMigrationSeeded() throws IOException {
        var seededAnchors = new LinkedHashSet<String>();
        for (var row : migrationRows(readMigration(ACCOUNT_MIGRATION), "starter_posting_rule")) {
            var values = values(row);
            seededAnchors.add(values.get(0) + ":" + values.get(1));
        }

        assertThat(StarterAccountingCatalog.postingRules().stream()
                        .map(rule -> rule.sourceType() + ":" + rule.posting())
                        .toList())
                .as("V98 has run on every deployed database and cannot be edited, so a rule only V99 knows "
                        + "about would be a rule no existing tenant receives; the pair of account columns V98 "
                        + "also wrote is history, and V99 is where the accounts moved to")
                .containsExactlyInAnyOrderElementsOf(seededAnchors);
    }

    @Test
    @DisplayName("the migration states its rows exactly as the catalog renders them")
    void rendersTheRowsTheMigrationStates() {
        assertThat(StarterAccountingCatalog.toMigrationTuple(
                        new StarterAccountingCatalog.AccountEntry("1101", "Caja", "ASSET")))
                .isEqualTo("    ('1101', 'Caja', 'ASSET')");
        assertThat(StarterAccountingCatalog.toMigrationTuple(
                        new StarterAccountingCatalog.PostingRuleEntry("STATUS_TRANSITION", "REVENUE", List.of()),
                        new StarterAccountingCatalog.PostingRuleLine("CREDIT", "TAX", "2101")))
                .isEqualTo("    ('STATUS_TRANSITION', 'REVENUE', 'CREDIT', 'TAX', '2101')");
    }

    private static StarterAccountingCatalog.PostingRuleEntry rule(String posting) {
        return StarterAccountingCatalog.postingRules().stream()
                .filter(rule -> posting.equals(rule.posting()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the catalog declares no rule for " + posting));
    }

    /** Every line of every rule, flattened as {@code source:posting:side:selector:code}. */
    private static List<String> declaredLines() {
        var lines = new ArrayList<String>();
        for (var rule : StarterAccountingCatalog.postingRules()) {
            for (var line : rule.lines()) {
                lines.add(rule.sourceType() + ":" + rule.posting() + ":" + line.side() + ":" + line.amountSelector()
                        + ":" + line.accountCode());
            }
        }
        return lines;
    }

    private static String readMigration(String resource) throws IOException {
        try (InputStream stream = StarterAccountingCatalogTest.class.getResourceAsStream(resource)) {
            assertThat(stream)
                    .as("%s is on the classpath, which is what makes this a test of the file that ships", resource)
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The value rows of one temporary table, stripped of indentation and of the trailing comma that SQL may
     * not put after the last row. Only rows that begin with {@code (} count, so the column list of the
     * {@code CREATE TEMPORARY TABLE} above is not read as data.
     */
    private static Set<String> migrationRows(String migration, String table) {
        var start = migration.indexOf("INSERT INTO " + table);
        assertThat(start)
                .as("the migration writes the catalog rows of " + table)
                .isGreaterThanOrEqualTo(0);
        var end = migration.indexOf(';', start);
        assertThat(end)
                .as("the INSERT INTO " + table + " statement is terminated")
                .isGreaterThan(start);

        var rows = new LinkedHashSet<String>();
        for (var line : migration.substring(start, end).split("\n")) {
            var row = line.trim();
            if (!row.startsWith("(")) {
                continue;
            }
            rows.add(row.endsWith(",") ? row.substring(0, row.length() - 1) : row);
        }
        return rows;
    }

    /** The quoted values of one rendered value row, in order. */
    private static List<String> values(String row) {
        var body = row.substring(1, row.length() - 1);
        var values = new ArrayList<String>();
        for (var part : body.split(", ")) {
            values.add(part.substring(1, part.length() - 1));
        }
        return values;
    }
}
