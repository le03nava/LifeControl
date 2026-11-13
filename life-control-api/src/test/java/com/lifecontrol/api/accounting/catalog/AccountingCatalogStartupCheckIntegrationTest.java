package com.lifecontrol.api.accounting.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.accounting.PostingAnchor;
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
 * S10's catalog assertion on PostgreSQL: it passes against the schema the migrations build, and each of the
 * four ways the catalog can be short is refused with the offending item named.
 *
 * <p><b>What the four refusals measure, one clause each.</b> S10 asks the <b>catalog</b>, and never a tenant's
 * rows, because no runtime credential in this stack can read across tenants — {@code lifecontrol_owner} is
 * {@code NOSUPERUSER} without {@code BYPASSRLS} and {@code FORCE} reaches it, {@code lifecontrol_app} is
 * {@code NOBYPASSRLS} — so a per-tenant query would answer zero rows at boot and pass while the book was
 * short. The clauses are: every declared anchor has a rule posting what the anchor declares (cases 2 and 3
 * here), every rule names two <b>different</b> accounts the chart declares (cases 3 and 4), every anchor's
 * table resolves in {@code pg_catalog} (case 5), and the database's closed {@code source_type} vocabulary is
 * exactly the anchors the code declares (case 6).</p>
 *
 * <p><b>Why each refusal is provoked directly instead of by breaking the tree.</b> A suite cannot delete a
 * rule from a static list nor rename a table in a shared container without failing sixty-odd sibling suites,
 * so every refusal is observed by calling the assertion with a reduced or altered input — which is why the
 * assertions take their inputs as arguments instead of reading the constants. The control assertion in each
 * case calls the same method with the tree's real catalog first, so "refused" is never a method that refuses
 * everything.</p>
 *
 * <p><b>Why the passing half needs a case at all.</b> The check runs during context refresh, so every
 * PostgreSQL suite in this JVM is implicitly a case of the assertion passing. That is a strong but
 * <i>negative</i> observation — the absence of a failure is also what a check that never ran produces — so
 * this suite asserts the bean's own verdict, and the verdict is only {@code true} if the bean was created
 * after Flyway: the probe is cached for the life of the bean, so the value below is the one the startup path
 * computed.</p>
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
                .as("and running it again refuses nothing: V97/V98 ship the three rules and the eight accounts, "
                        + "and the three anchor tables exist, so this case is the only one written down and "
                        + "every sibling PostgreSQL suite is an implicit copy of it")
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
                        ? new StarterAccountingCatalog.PostingRuleEntry(
                                rule.sourceType(), "REVENUE", rule.debitCode(), rule.creditCode())
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
    @DisplayName("a rule naming an account the chart does not declare is refused, naming the code")
    void aRuleNamingAnUndeclaredAccountCodeIsRefused() {
        var rulesWithAnUnknownDebit = StarterAccountingCatalog.postingRules().stream()
                .map(rule -> "SALE_PAYMENT".equals(rule.sourceType())
                        ? new StarterAccountingCatalog.PostingRuleEntry(
                                rule.sourceType(), rule.posting(), "1103", rule.creditCode())
                        : rule)
                .toList();

        assertThatCode(() -> AccountingCatalogStartupCheck.assertEveryRuleNamesTwoDeclaredAccounts(
                        StarterAccountingCatalog.accounts(), StarterAccountingCatalog.postingRules()))
                .as("the control: every rule of this tree names two codes the chart declares")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> AccountingCatalogStartupCheck.assertEveryRuleNamesTwoDeclaredAccounts(
                        StarterAccountingCatalog.accounts(), rulesWithAnUnknownDebit))
                .as("a rule that names an account the seed does not create is a rule the applier cannot "
                        + "insert and a posting that would resolve to nothing")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1103")
                .hasMessageContaining("SALE_PAYMENT")
                .hasMessageContaining("StarterAccountingCatalog");
    }

    @Test
    @DisplayName("a rule that debits and credits one account is refused: it would post zero and still balance")
    void aRuleWithTheSameDebitAndCreditCodeIsRefused() {
        var rulesWithOneSidedPair = StarterAccountingCatalog.postingRules().stream()
                .map(rule -> "STATUS_TRANSITION".equals(rule.sourceType())
                        ? new StarterAccountingCatalog.PostingRuleEntry(
                                rule.sourceType(), rule.posting(), "1201", "1201")
                        : rule)
                .toList();

        assertThatThrownBy(() -> AccountingCatalogStartupCheck.assertEveryRuleNamesTwoDeclaredAccounts(
                        StarterAccountingCatalog.accounts(), rulesWithOneSidedPair))
                .as("both codes exist, so only the `the two differ` half of S10's clause can refuse this — "
                        + "and it has to, because the resulting entry balances while moving nothing")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1201")
                .hasMessageContaining("STATUS_TRANSITION");
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
        var rulesWithAnUndelaredCreditAndNoCostRule = StarterAccountingCatalog.postingRules().stream()
                .filter(rule -> !"COST_OF_SALES".equals(rule.posting()))
                .map(rule -> "SALE_PAYMENT".equals(rule.sourceType())
                        ? new StarterAccountingCatalog.PostingRuleEntry(
                                rule.sourceType(), rule.posting(), rule.debitCode(), "1103")
                        : rule)
                .toList();

        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> check.assertCatalogIsWired(
                            connection,
                            PostingAnchor.anchors(),
                            StarterAccountingCatalog.accounts(),
                            rulesWithAnUndelaredCreditAndNoCostRule))
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
}
