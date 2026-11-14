package com.lifecontrol.api.accounting.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.accounting.PostingAnchor;
import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalog.PostingRuleEntry;
import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalog.PostingRuleLine;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * S10's catalog assertion on PostgreSQL: it passes against the schema the migrations build, and each way the
 * catalog can be short is refused with the offending item named.
 *
 * <p><b>What the refusals measure, one clause each.</b> S10 asks the <b>catalog</b>, and never a tenant's rows,
 * because no runtime credential in this stack can read across tenants — {@code lifecontrol_owner} is
 * {@code NOSUPERUSER} without {@code BYPASSRLS} and {@code FORCE} reaches it, {@code lifecontrol_app} is
 * {@code NOBYPASSRLS} — so a per-tenant query would answer zero rows at boot and pass while the book was short.
 * The clauses are: every declared anchor has a rule posting what the anchor declares (cases 2 and 3 here);
 * every line names an account the chart declares (case 4); no rule debits and credits one account (case 5); the
 * revenue rule carries exactly one {@code DEBIT TOTAL}, one {@code CREDIT REVENUE} and one {@code CREDIT TAX}
 * (cases 6 and 7); a collection or cost rule carries one debit and one credit on the same selector (case 8); no
 * rule carries a selector its posting cannot mean (case 9); every anchor's table resolves in {@code pg_catalog}
 * (case 10); and the database's closed {@code source_type} vocabulary is exactly the anchors the code declares
 * (case 12).</p>
 *
 * <p><b>Why the line clauses exist at all.</b> Before {@code V99} a rule was a debit account and a credit
 * account, and the tax of a sale had no way to be posted: {@code 2101 IVA trasladado} sat in the chart named by
 * nothing (L13 of {@code odd/tasks/derived-accounting.md}). A rule is now N lines, and every way that shape can
 * be wrong is a way for the book to record the wrong amount in the wrong place — a revenue rule with no tax
 * line under-posts the liability, a collection rule that credits the same account it debits balances while
 * moving nothing, and a selector the posting cannot compute is an amount no poster can find. Each is invisible
 * at read time, which is why they are refused before the process accepts a request.</p>
 *
 * <p><b>Why each refusal is provoked directly instead of by breaking the tree.</b> A suite cannot delete a rule
 * from a static list nor rename a table in a shared container without failing sixty-odd sibling suites, so every
 * refusal is observed by calling the assertion with a reduced or altered input — which is why the assertions
 * take their inputs as arguments instead of reading the constants. The control assertion in each case calls the
 * same method with the tree's real catalog first, so "refused" is never a method that refuses everything.</p>
 *
 * <p><b>Why the passing half needs a case at all.</b> The check runs during context refresh, so every PostgreSQL
 * suite in this JVM is implicitly a case of the assertion passing. That is a strong but <i>negative</i>
 * observation — the absence of a failure is also what a check that never ran produces — so this suite asserts
 * the bean's own verdict, and the verdict is only {@code true} if the bean was created after Flyway: the probe
 * is cached for the life of the bean, so the value below is the one the startup path computed.</p>
 */
@SpringBootTest
@DisplayName("The accounting catalog startup assertion on PostgreSQL")
class AccountingCatalogStartupCheckIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private AccountingCatalogStartupCheck check;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("the check ran after the migrations and the tree's own catalog passes every clause")
    void theCheckRanAfterTheMigrationsAndThisTreePasses() {
        assertThat(check.appliesToThisDatabase())
                .as("the probe's answer, computed by the startup path itself and cached since: `true` means "
                        + "`to_regprocedure('pg_catalog.to_regclass(text)')` found the catalog function, which "
                        + "only exists on a migrated PostgreSQL database")
                .isTrue();

        assertThatCode(check::afterPropertiesSet)
                .as("and running it again refuses nothing: V97/V98/V99 ship the three rules with their seven "
                        + "lines and the eight accounts, and the three anchor tables exist, so this case is the "
                        + "only one written down and every sibling PostgreSQL suite is an implicit copy of it")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a declared anchor with no posting rule is refused and the anchor, its table and its posting "
            + "are named — the failure S10 exists for")
    void anAnchorWithNoPostingRuleIsRefusedAndNamed() {
        var rulesWithoutTheCostAnchor = StarterAccountingCatalog.postingRules().stream()
                .filter(rule -> !"COST_OF_SALES".equals(rule.posting()))
                .toList();

        assertThatCode(() -> AccountingCatalogStartupCheck.assertEveryAnchorHasItsRule(
                        PostingAnchor.anchors(), StarterAccountingCatalog.postingRules()))
                .as("the control: this tree's own rules wire every declared anchor, so the refusal below is "
                        + "about the rule this case removed and not about a check that refuses everything")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> AccountingCatalogStartupCheck.assertEveryAnchorHasItsRule(
                        PostingAnchor.anchors(), rulesWithoutTheCostAnchor))
                .as("a money-moving table with no rule posts nothing and raises nothing at read time: the "
                        + "books come out short and no report can tell that from a quiet period")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STOCK_ALLOCATION")
                .hasMessageContaining("stock_allocation")
                .hasMessageContaining("COST_OF_SALES")
                .as("and it says where the fix goes, because the operator has no other context at boot")
                .hasMessageContaining("StarterAccountingCatalog");
    }

    @Test
    @DisplayName("a rule that posts the wrong piece of its anchor is refused: the anchor must be wired to the "
            + "one posting it declares")
    void aRuleWiredToTheWrongPostingIsRefused() {
        var rulesWithTheCostAnchorAsRevenue = StarterAccountingCatalog.postingRules().stream()
                .map(rule -> "STOCK_ALLOCATION".equals(rule.sourceType())
                        ? new PostingRuleEntry(rule.sourceType(), "REVENUE", rule.lines())
                        : rule)
                .toList();

        assertThatThrownBy(() -> AccountingCatalogStartupCheck.assertEveryAnchorHasItsRule(
                        PostingAnchor.anchors(), rulesWithTheCostAnchorAsRevenue))
                .as("the anchor is declared as COST_OF_SALES, so a rule that posts REVENUE from it is a rule "
                        + "no read path looks for — the anchor would count as unwired, which is exactly right")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STOCK_ALLOCATION")
                .hasMessageContaining("COST_OF_SALES");
    }

    @Test
    @DisplayName("a line naming an account the chart does not declare is refused, naming the code")
    void aLineNamingAnUndeclaredAccountCodeIsRefused() {
        var rulesWithAnUnknownDebit = List.of(replace(rule("COLLECTION"), line("DEBIT", "SOURCE_AMOUNT", "1103")));

        assertThatCode(() -> AccountingCatalogStartupCheck.assertEveryLineNamesADeclaredAccount(
                        StarterAccountingCatalog.accounts(), StarterAccountingCatalog.postingRules()))
                .as("the control: every line of this tree names a code the chart declares")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> AccountingCatalogStartupCheck.assertEveryLineNamesADeclaredAccount(
                        StarterAccountingCatalog.accounts(), rulesWithAnUnknownDebit))
                .as("a line that names an account the seed does not create is a line the applier cannot insert "
                        + "and a posting that would resolve to nothing")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1103")
                .hasMessageContaining("SALE_PAYMENT")
                .hasMessageContaining("StarterAccountingCatalog");
    }

    @Test
    @DisplayName("a rule that debits and credits one account is refused: it would post zero and still balance")
    void aRuleThatDebitsAndCreditsOneAccountIsRefused() {
        var rulesWithOneAccount = List.of(replace(rule("REVENUE"), line("CREDIT", "REVENUE", "1201")));

        assertThatCode(() -> AccountingCatalogStartupCheck.assertNoRuleDebitsAndCreditsOneAccount(
                        StarterAccountingCatalog.postingRules()))
                .as("the control: no rule of this tree moves one account against itself")
                .doesNotThrowAnyException();

        assertThatThrownBy(
                        () -> AccountingCatalogStartupCheck.assertNoRuleDebitsAndCreditsOneAccount(rulesWithOneAccount))
                .as("both codes exist, so only this clause can refuse it — and it has to, because the resulting "
                        + "entry balances while moving nothing and the trial balance sums to zero over a book "
                        + "that never recorded the fact")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1201")
                .hasMessageContaining("STATUS_TRANSITION");
    }

    @Test
    @DisplayName("a revenue rule without its tax line is refused: the IVA trasladado would never be posted")
    void aRevenueRuleWithoutItsTaxLineIsRefused() {
        var revenueWithoutTax = List.of(
                withLines(rule("REVENUE"), List.of(line("DEBIT", "TOTAL", "1201"), line("CREDIT", "REVENUE", "4101"))));

        assertThatCode(() -> AccountingCatalogStartupCheck.assertRevenueRuleCarriesItsThreeParts(
                        StarterAccountingCatalog.postingRules()))
                .as("the control: the seeded revenue rule is exactly the three parts S11 asks the entry to carry")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> AccountingCatalogStartupCheck.assertRevenueRuleCarriesItsThreeParts(revenueWithoutTax))
                .as("L13 measured this: before a rule was N lines, `2101 IVA trasladado` sat in the chart named "
                        + "by no rule, so the tax part of every sale had nowhere to post and the liability "
                        + "under-reported with nothing raising")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STATUS_TRANSITION")
                .hasMessageContaining("REVENUE")
                .hasMessageContaining("TAX")
                .hasMessageContaining("2101");
    }

    @Test
    @DisplayName("a revenue rule with a second line for one of its parts is refused: one part, one amount")
    void aRevenueRuleWithTwoLinesForOnePartIsRefused() {
        var revenueWithTwoCredits = List.of(withLines(
                rule("REVENUE"),
                List.of(
                        line("DEBIT", "TOTAL", "1201"),
                        line("CREDIT", "REVENUE", "4101"),
                        line("CREDIT", "TAX", "2101"),
                        line("CREDIT", "TAX", "4101"))));

        assertThatThrownBy(() ->
                        AccountingCatalogStartupCheck.assertRevenueRuleCarriesItsThreeParts(revenueWithTwoCredits))
                .as("`uq_posting_rule_line_company_rule_side_selector` refuses a second line for one part at "
                        + "insert time, and this clause refuses the same defect before the seed runs: an amount "
                        + "that belongs to two accounts depends on which line the poster read first")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TAX")
                .hasMessageContaining("2");
    }

    @Test
    @DisplayName("a collection or cost rule whose two lines read two selectors is refused")
    void aPairedRuleWithTwoSelectorsIsRefused() {
        var collectionAcrossTwoSelectors = List.of(withLines(
                rule("COLLECTION"), List.of(line("DEBIT", "SOURCE_AMOUNT", "1101"), line("CREDIT", "TOTAL", "1201"))));

        assertThatCode(() -> AccountingCatalogStartupCheck.assertPairedRulesCarryTwoAccountsOnOneSelector(
                        StarterAccountingCatalog.postingRules()))
                .as("the control: both paired rules move one amount from one account to another")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> AccountingCatalogStartupCheck.assertPairedRulesCarryTwoAccountsOnOneSelector(
                        collectionAcrossTwoSelectors))
                .as("a collection moves the payment's amount and nothing else, so the two sides have to read "
                        + "one selector: two of them would post a debit of one amount against a credit of "
                        + "another and the entry would not balance — or would balance only by coincidence")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SALE_PAYMENT")
                .hasMessageContaining("SOURCE_AMOUNT")
                .hasMessageContaining("TOTAL");
    }

    @Test
    @DisplayName("a rule carrying a selector its posting cannot mean is refused, naming both")
    void aRuleCarryingASelectorItsPostingCannotMeanIsRefused() {
        var costReadingTheDocumentTax = List.of(withLines(
                rule("COST_OF_SALES"),
                List.of(line("DEBIT", "ALLOCATED_COST", "5101"), line("CREDIT", "TAX", "1301"))));

        assertThatCode(() -> AccountingCatalogStartupCheck.assertNoRuleCarriesASelectorItsPostingCannotMean(
                        StarterAccountingCatalog.postingRules()))
                .as("the control: every selector of this tree is one its own posting understands")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> AccountingCatalogStartupCheck.assertNoRuleCarriesASelectorItsPostingCannotMean(
                        costReadingTheDocumentTax))
                .as("the selector vocabulary is closed and shared by three anchors (L13(a)), and the map is "
                        + "what keeps one anchor from reading an amount that belongs to another: a cost rule "
                        + "reading TAX would post the sale's IVA to inventory")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COST_OF_SALES")
                .hasMessageContaining("TAX")
                .hasMessageContaining("ALLOCATED_COST");
    }

    @Test
    @DisplayName("a declared anchor whose table does not resolve is refused, naming the table")
    void aDeclaredAnchorWhoseTableDoesNotExistIsRefused() throws SQLException {
        var absent =
                new PostingAnchor.Anchor("STOCK_ALLOCATION", "t5_anchor_table_that_does_not_exist", "COST_OF_SALES");

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> check.assertEveryAnchorTableExists(connection, PostingAnchor.anchors()))
                    .as("the control: the three tables the anchors name are the three the migrations create")
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> check.assertEveryAnchorTableExists(connection, List.of(absent)))
                    .as("a renamed or dropped anchor table leaves a rule that can never fire — the posting "
                            + "looks for a source row in a table nothing writes, and no read path says so")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("t5_anchor_table_that_does_not_exist")
                    .hasMessageContaining("STOCK_ALLOCATION")
                    .hasMessageContaining("PostingAnchor");

            assertThatCode(() -> check.assertEveryAnchorTableExists(connection, PostingAnchor.anchors()))
                    .as("and the refusal is about the probe's argument, not about the connection: the same "
                            + "connection passes again once the real anchors are asked about")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("two independent defects are named in one refusal, so one boot reports both")
    void twoDefectsAreNamedInOneRefusal() throws SQLException {
        var rulesWithAnUndeclaredDebitAndNoCostRule = new ArrayList<PostingRuleEntry>();
        for (var rule : StarterAccountingCatalog.postingRules()) {
            if ("COST_OF_SALES".equals(rule.posting())) {
                continue;
            }
            rulesWithAnUndeclaredDebitAndNoCostRule.add(
                    "SALE_PAYMENT".equals(rule.sourceType())
                            ? replace(rule, line("CREDIT", "SOURCE_AMOUNT", "1103"))
                            : rule);
        }

        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> check.assertCatalogIsWired(
                            connection,
                            PostingAnchor.anchors(),
                            StarterAccountingCatalog.accounts(),
                            rulesWithAnUndeclaredDebitAndNoCostRule))
                    .as("the aggregate is the boot path's message: an operator has the log and nothing else, so "
                            + "a catalog with two defects has to name both instead of one per deploy cycle")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STOCK_ALLOCATION")
                    .hasMessageContaining("1103")
                    .as("and it says how many items it found, so a truncated read of the log is visible as one")
                    .hasMessageContaining("2 problem(s)");
        }
    }

    @Test
    @DisplayName("the database's closed source_type vocabulary is exactly the anchors the code declares")
    void theDatabaseVocabularyIsTheDeclaredAnchors() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(check.sourceTypesDeclaredByDatabase(connection))
                    .as("read from pg_get_constraintdef and not counted by name: a CHECK that exists with the "
                            + "wrong values would otherwise pass while admitting an anchor no code asserts")
                    .containsExactlyInAnyOrder("STATUS_TRANSITION", "SALE_PAYMENT", "STOCK_ALLOCATION");

            assertThatCode(() -> check.assertVocabularyMatchesDeclaredAnchors(connection, PostingAnchor.anchors()))
                    .as("the control: the vocabulary V98 closes and the anchors this code declares agree")
                    .doesNotThrowAnyException();

            var anchorsWithAnExtraSourceType = new ArrayList<>(PostingAnchor.anchors());
            anchorsWithAnExtraSourceType.add(
                    new PostingAnchor.Anchor("INVENTORY_ADJUSTMENT", "stock_movement", "REVERSAL"));

            assertThatThrownBy(() ->
                            check.assertVocabularyMatchesDeclaredAnchors(connection, anchorsWithAnExtraSourceType))
                    .as("this is the failure mode S10 exists for: a migration adds a money-moving source type "
                            + "and the code stays silent, so the deploy succeeds over a book that will come "
                            + "out short")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INVENTORY_ADJUSTMENT")
                    .hasMessageContaining("ck_posting_rule_source_type");
        }
    }

    // --- fixtures: the catalog with one line altered, which is how each clause is provoked ---

    private static PostingRuleEntry rule(String posting) {
        return StarterAccountingCatalog.postingRules().stream()
                .filter(rule -> posting.equals(rule.posting()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the catalog declares no rule for " + posting));
    }

    private static PostingRuleLine line(String side, String selector, String code) {
        return new PostingRuleLine(side, selector, code);
    }

    /**
     * {@code target} with the line that has the replacement's (side, selector) swapped for it, every other line
     * untouched — so a clause is asked about one altered line and not about a rule that lost the rest.
     */
    private static PostingRuleEntry replace(PostingRuleEntry target, PostingRuleLine replacement) {
        var altered = new ArrayList<PostingRuleLine>();
        for (var line : target.lines()) {
            altered.add(
                    line.side().equals(replacement.side())
                                    && line.amountSelector().equals(replacement.amountSelector())
                            ? replacement
                            : line);
        }
        return withLines(target, altered);
    }

    /** {@code target} with its lines replaced, so a clause is asked about one altered rule. */
    private static PostingRuleEntry withLines(PostingRuleEntry target, List<PostingRuleLine> lines) {
        return new PostingRuleEntry(target.sourceType(), target.posting(), lines);
    }
}
