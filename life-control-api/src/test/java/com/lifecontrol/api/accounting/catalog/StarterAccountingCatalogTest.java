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
 * Pins the two copies of the starter accounting catalog to each other: {@link StarterAccountingCatalog}
 * in Java and the value rows of {@code V98__accounting_starter_catalog.sql}.
 *
 * <p><b>S9 of {@code odd/tasks/derived-accounting.md} says the catalogs are seeded, not user-invented</b>,
 * and the seed has two halves that cannot reach each other: the migration reaches the companies that exist
 * when it runs, the Java list reaches the companies created afterwards. Both write the same eight accounts
 * and the same three posting rules, so the only thing keeping them from diverging is this test. It reads the
 * migration as a classpath resource, which is what makes it check the file that actually ships instead of a
 * copy the test owns.</p>
 *
 * <p><b>Why the comparison is textual and bidirectional.</b> A structural comparison (same codes, same
 * accounts) would pass while a name, a kind or a debit/credit pair differed — and a seeded account whose
 * {@code kind} is {@code ASSET} on one side and {@code LIABILITY} on the other makes the trial balance lie
 * by sign while still summing to zero. Rendering each entry exactly like a migration value row and comparing
 * the two sets in both directions turns any of those differences into a red build, the shape
 * {@code StarterProductCategoryCatalogTest} established for V35.</p>
 *
 * <p><b>This is a plain JUnit test on purpose.</b> The catalog is a static list, so a Spring context or a
 * database would add nothing. The database half of S9 — that the migration seeds the tenants that exist and
 * the applier seeds the ones created later — is measured by the two integration suites beside this file.</p>
 */
@DisplayName("Starter Accounting Catalog Tests")
class StarterAccountingCatalogTest {

    private static final String MIGRATION_RESOURCE = "/db/migration/V98__accounting_starter_catalog.sql";

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
    @DisplayName("holds exactly the three posting rules S9 lists, each naming its debit and credit by code")
    void holdsTheThreePostingRules() {
        assertThat(StarterAccountingCatalog.postingRules())
                .extracting(
                        StarterAccountingCatalog.PostingRuleEntry::sourceType,
                        StarterAccountingCatalog.PostingRuleEntry::posting,
                        StarterAccountingCatalog.PostingRuleEntry::debitCode,
                        StarterAccountingCatalog.PostingRuleEntry::creditCode)
                .containsExactly(
                        tuple("STATUS_TRANSITION", "REVENUE", "1201", "4101"),
                        tuple("SALE_PAYMENT", "COLLECTION", "1101", "1201"),
                        tuple("STOCK_ALLOCATION", "COST_OF_SALES", "5101", "1301"));
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
    @DisplayName("names only accounts the catalog declares, and only kinds ck_account_kind accepts")
    void everyRuleNamesADeclaredAccount() {
        var codes = StarterAccountingCatalog.accounts().stream()
                .map(StarterAccountingCatalog.AccountEntry::code)
                .toList();

        var undeclared = StarterAccountingCatalog.postingRules().stream()
                .flatMap(rule -> java.util.stream.Stream.of(rule.debitCode(), rule.creditCode()))
                .filter(code -> !codes.contains(code))
                .distinct()
                .toList();

        assertThat(undeclared)
                .as("a rule that names an account the seed does not create is a rule that cannot be inserted")
                .isEmpty();
        assertThat(StarterAccountingCatalog.accounts())
                .extracting(StarterAccountingCatalog.AccountEntry::kind)
                .as("ck_account_kind is the closed set V97 states, and the catalog may not exceed it")
                .allMatch(kind -> List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE")
                        .contains(kind));
    }

    @Test
    @DisplayName("matches V98 in both directions as a set of rows, whatever their indentation")
    void matchesTheMigrationInBothDirections() throws IOException {
        var migration = readMigration();

        // Both directions compare stripped rows, so re-indenting V98 is not a false RED while a real
        // difference in any value — a code, a name, a kind, a debit, a credit — is.
        var declaredInJava = new LinkedHashSet<String>();
        StarterAccountingCatalog.accounts().stream()
                .map(StarterAccountingCatalog::toMigrationTuple)
                .map(String::trim)
                .forEach(declaredInJava::add);
        StarterAccountingCatalog.postingRules().stream()
                .map(StarterAccountingCatalog::toMigrationTuple)
                .map(String::trim)
                .forEach(declaredInJava::add);

        var declaredInMigration = new LinkedHashSet<String>();
        declaredInMigration.addAll(migrationRows(migration, "starter_account"));
        declaredInMigration.addAll(migrationRows(migration, "starter_posting_rule"));

        assertThat(declaredInJava)
                .as("every row the Java catalog declares is a value row of V98")
                .isSubsetOf(declaredInMigration);
        assertThat(declaredInMigration)
                .as("and V98 declares no row the Java catalog does not: the two halves are one catalog")
                .isSubsetOf(declaredInJava);
        assertThat(declaredInMigration)
                .as("eleven rows in total — eight accounts and three rules")
                .hasSize(StarterAccountingCatalog.accounts().size()
                        + StarterAccountingCatalog.postingRules().size());
    }

    @Test
    @DisplayName("the migration states its rows exactly as the catalog renders them")
    void rendersTheRowsTheMigrationStates() {
        assertThat(StarterAccountingCatalog.toMigrationTuple(
                        new StarterAccountingCatalog.AccountEntry("1101", "Caja", "ASSET")))
                .isEqualTo("    ('1101', 'Caja', 'ASSET')");
        assertThat(StarterAccountingCatalog.toMigrationTuple(
                        new StarterAccountingCatalog.PostingRuleEntry("STATUS_TRANSITION", "REVENUE", "1201", "4101")))
                .isEqualTo("    ('STATUS_TRANSITION', 'REVENUE', '1201', '4101')");
    }

    private static String readMigration() throws IOException {
        try (InputStream stream = StarterAccountingCatalogTest.class.getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(stream)
                    .as("V98 is on the classpath, which is what makes this a test of the file that ships")
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
        assertThat(start).as("V98 writes the catalog rows of " + table).isGreaterThanOrEqualTo(0);
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
}
