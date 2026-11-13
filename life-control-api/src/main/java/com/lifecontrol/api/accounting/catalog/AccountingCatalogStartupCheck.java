package com.lifecontrol.api.accounting.catalog;

import com.lifecontrol.api.accounting.PostingAnchor;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * S10's catalog assertion: the application <b>does not start</b> when the book's catalog and the code that reads
 * it disagree about which facts move money.
 *
 * <p><b>What it protects, and why nothing cheaper would do.</b> A missing posting rule produces no error at read
 * time: the source fact keeps being written, no entry appears, and the books simply come out short. No report
 * can distinguish that from a period with less revenue, and no test that only reads what was posted can either.
 * The failure is invisible at read time and expensive at audit time, which is exactly the shape that belongs at
 * startup — before the process accepts a request — rather than in a warning an operator can scroll past.</p>
 *
 * <p><b>It asserts the catalog, and reads no tenant's rows, because in this stack nothing can.</b> S10 originally
 * asked for a per-tenant question — does every company have a rule for every money-moving table — and that
 * question cannot be answered here: {@code lifecontrol_owner} is {@code NOSUPERUSER} without {@code BYPASSRLS}
 * and the {@code FORCE}d policies of {@code posting_rule} and {@code companies} reach it, {@code lifecontrol_app}
 * is {@code NOBYPASSRLS}, and {@code lifecontrol_bootstrap} has its password nulled. A plain {@code SELECT} would
 * return zero rows at boot and pass while the book was short, and the only escape left was {@code
 * SECURITY DEFINER}, which {@code V61:109-119} and {@code V62:69} refuse in writing. So the guarantee splits in
 * two, and this class is the half that can be stated about a schema: the per-tenant half lives in the transaction
 * that creates the company ({@code StarterAccountingCatalogListener} runs the seed {@code BEFORE_COMMIT}, so a
 * failure rolls the creation back and "a company exists" implies "its book exists").</p>
 *
 * <p><b>The four clauses, all read from the catalog and from the code:</b></p>
 *
 * <ol>
 *   <li>every anchor {@link PostingAnchor} declares has a rule in {@link StarterAccountingCatalog} with exactly
 *       that {@code sourceType} and that {@code posting} — an anchor wired to the wrong posting is an anchor no
 *       read path looks for, so it counts as unwired;</li>
 *   <li>every rule's debit and credit codes exist in the catalog's accounts <b>and differ</b> — a rule whose two
 *       sides are one account balances while moving nothing;</li>
 *   <li>every anchor's table exists, as {@code to_regclass('public.<table>')} — a renamed or dropped anchor table
 *       leaves a rule that can never fire, because the posting looks for a source row in a table nothing
 *       writes;</li>
 *   <li>the database's closed {@code source_type} vocabulary, read from {@code ck_posting_rule_source_type} via
 *       {@code pg_get_constraintdef}, is exactly the anchors the code declares. This is the clause S10 exists
 *       for: a future migration adding a money-moving source type to the {@code CHECK} while the code stays
 *       silent would otherwise deploy green over a book that must come out short.</li>
 * </ol>
 *
 * <p><b>Why {@link #afterPropertiesSet} orders after Flyway, and why that ordering is the whole requirement.</b>
 * This bean takes the JPA {@link EntityManagerFactory} as a constructor argument and never uses it: the
 * dependency <i>is</i> the ordering. Spring Boot makes {@code entityManagerFactory} depend on database
 * initialization ({@code FlywayMigrationInitializer}), so requiring it here means Spring cannot create this bean
 * before the migrations of the very schema it is asserting. The two wrong answers a differently placed check
 * would produce are both silent: a check that ran before Flyway would refuse every <b>fresh</b> deployment — the
 * schema is empty, so {@code to_regclass} resolves nothing — and would pass a <b>broken</b> one on its next
 * restart. Failing during context refresh, and not from a runner, is the second half of the same choice: the
 * refusal happens before the servlet container starts.</p>
 *
 * <p><b>It is a no-op where the catalog functions do not exist, and the database is asked, not the dialect.</b>
 * {@code application-test.properties} makes H2 the default test stack with Flyway disabled and sixty-odd suites
 * boot there; H2 has no {@code to_regclass}, no {@code pg_constraint} and no {@code posting_rule}, so an
 * unconditional call would fail every one of those contexts with a syntax error — a build that breaks instead of
 * a catalog that fails. The question is asked once, through the pooled {@link DataSource}, and cached for the
 * life of the bean, the same shape {@code TenantIsolationStartupCheck} uses so the two startup assertions cannot
 * disagree about which stack they are on. The probe measures two things, and the second is the precise one: the
 * product name (H2's parser does not know {@code to_regprocedure}), then {@code
 * to_regprocedure('pg_catalog.to_regclass(text)')} — read rather than called, so the answer is about the catalog
 * function this class needs and never a call that fails.</p>
 *
 * <p><b>The refusal reports the set, never a count.</b> Every offending item is listed with the fix beside it,
 * because the exception is read at boot with no other context: the operator has the log and nothing else. The
 * four clauses are aggregated into <b>one</b> {@link IllegalStateException}, so a catalog with two defects names
 * both instead of sending the operator through two deploy cycles. That is also why each assertion below is
 * callable on its own with an input the caller supplies: a suite provokes a refusal by passing a reduced or
 * altered list, without reflection and without breaking the tree the other sixty suites boot on.</p>
 */
@Component
public class AccountingCatalogStartupCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AccountingCatalogStartupCheck.class);

    /**
     * The precise question, and the reason it is a question about the catalog and not a call to the function: a
     * probe that calls what it is probing cannot tell "the function is absent" from "the function failed".
     */
    private static final String CATALOG_PROBE = "select to_regprocedure('pg_catalog.to_regclass(text)') is not null";

    /** Clause 3's probe: {@code to_regclass} answers {@code null} for a name that resolves to no relation. */
    private static final String ANCHOR_TABLE_PROBE = "select to_regclass(?)::text as anchor_table";

    /**
     * Clause 4's read: the constraint's definition, scoped to the {@code CHECK} on {@code public.posting_rule}
     * and not merely to its name — a constraint of that name on another table, or a primary key rather than a
     * {@code CHECK}, would answer a different question than the one S10 asks.
     */
    private static final String SOURCE_TYPE_VOCABULARY = """
            select pg_get_constraintdef(c.oid) as definition
              from pg_constraint c
             where c.conname = 'ck_posting_rule_source_type'
               and c.conrelid = to_regclass('public.posting_rule')
               and c.contype = 'c'
            """;

    /**
     * The literals of a {@code CHECK} definition. PostgreSQL renders `x IN ('A', 'B')` as `(x)::text = ANY
     * (ARRAY['A'::character varying, 'B'::character varying])`, so the admitted values are exactly the
     * single-quoted literals — the cast types are unquoted and are not read as values.
     */
    private static final Pattern CONSTRAINT_LITERAL = Pattern.compile("'([^']*)'");

    private final DataSource dataSource;

    /**
     * {@code null} until the first question is asked. A boxed field rather than a primitive for the same reason
     * its sibling gives: "not decided yet" and "decided no" are different facts, and only the first one costs a
     * round trip.
     */
    private volatile Boolean assertionApplies;

    /**
     * @param entityManagerFactory required and deliberately never stored: depending on the JPA factory is what
     *     makes Spring create this bean after the schema the migrations build (see the class javadoc), and
     *     holding it would suggest this class reads through JPA, which it must not — every clause is plain SQL
     *     against {@code pg_catalog} on a connection the caller owns
     * @param dataSource the pooled {@link DataSource} the probe is asked of and the connection the assertion runs
     *     on
     */
    public AccountingCatalogStartupCheck(EntityManagerFactory entityManagerFactory, DataSource dataSource) {
        Objects.requireNonNull(entityManagerFactory, "the EntityManagerFactory is what orders this check after Flyway");
        this.dataSource = Objects.requireNonNull(dataSource, "the pooled DataSource the assertion runs on");
    }

    /** Runs the four clauses once, on a pooled connection, and ends the startup when one of them fails. */
    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            if (!appliesToThisDatabase(connection)) {
                log.debug("S10: this database has no PostgreSQL catalog, so the accounting catalog assertion does "
                        + "not apply");
                return;
            }
            assertCatalogIsWired(
                    connection,
                    PostingAnchor.anchors(),
                    StarterAccountingCatalog.accounts(),
                    StarterAccountingCatalog.postingRules());
            log.info(
                    "S10: every declared money-moving anchor has its posting rule, every rule names two different "
                            + "accounts of the chart, every anchor table resolves, and ck_posting_rule_source_type "
                            + "admits exactly the {} declared anchors",
                    PostingAnchor.anchors().size());
        } catch (SQLException cannotAsk) {
            throw new IllegalStateException(
                    "S10 of odd/tasks/derived-accounting.md cannot assert that the accounting catalog is wired to "
                            + "every money-moving anchor this code declares: the database did not answer the "
                            + "catalog questions (pg_constraint, pg_get_constraintdef or to_regclass), so there "
                            + "is no way to know whether this deployment would record money-moving facts and "
                            + "never post them. A book that silently comes out short is not visible at read time.",
                    cannotAsk);
        }
    }

    /**
     * Whether this database has the catalog functions the assertion is written in, decided once per bean and
     * cached.
     *
     * <p>Public because it is the fact the startup path observed and a suite has to be able to state it: "the
     * guard did not fail" is also what a guard that never ran produces, so a passing PostgreSQL suite asserts
     * this {@code true} — which is only true if the check ran after {@code V97}/{@code V98} — and an H2 suite
     * asserts it {@code false}.</p>
     */
    public boolean appliesToThisDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return appliesToThisDatabase(connection);
        } catch (SQLException cannotAsk) {
            throw new IllegalStateException(
                    "S10 of odd/tasks/derived-accounting.md cannot decide whether this database can answer the "
                            + "catalog assertion: the database product could not be read, so there is no way to "
                            + "know whether the assertion applies and the startup check cannot be skipped safely.",
                    cannotAsk);
        }
    }

    /**
     * The decision, asked on a connection the caller already holds — and that is a correctness requirement, not
     * an optimisation.
     *
     * <p>The sibling {@code TenantIsolationStartupCheck} records the measured defect this signature exists to
     * prevent: a helper that opens its <b>own</b> connection while the caller already holds one blocks a
     * {@code maximum-pool-size=1} deployment until the pool timeout and then fails a startup that had nothing
     * wrong. The answer is still cached per bean, so the extra statement costs one round trip on the first
     * question only; what changes is which connection answers it.</p>
     */
    private boolean appliesToThisDatabase(Connection connection) {
        Boolean decided = assertionApplies;
        if (decided == null) {
            decided = hasCatalogFunctions(connection);
            assertionApplies = decided;
        }
        return decided;
    }

    private boolean hasCatalogFunctions(Connection connection) {
        try {
            if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                // The precise question cannot even be parsed here (H2 has no to_regprocedure), and this is not a
                // catalog question anyway: without PostgreSQL there is no book to be short.
                return false;
            }
            try (var statement = connection.prepareStatement(CATALOG_PROBE);
                    ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException cannotAsk) {
            throw new IllegalStateException(
                    "S10 of odd/tasks/derived-accounting.md cannot decide whether this database can answer the "
                            + "catalog assertion: the database product could not be read, so there is no way to "
                            + "know whether the assertion applies and the startup check cannot be skipped "
                            + "safely.",
                    cannotAsk);
        }
    }

    /**
     * The four clauses over the inputs the caller supplies, aggregated into <b>one</b> refusal.
     *
     * <p>The aggregate is what makes the message usable: two independent defects are named together and one
     * deploy cycle finds both, instead of an operator fixing the first and meeting the second on the next boot.
     * Every input is an argument, so a suite can provoke the refusal with a catalog it altered — the tree's own
     * catalog cannot be broken without breaking the sixty-odd contexts that boot on it — and the production
     * caller, {@link #afterPropertiesSet}, passes the declared constants.</p>
     *
     * <p>The two catalog clauses run only where the PostgreSQL catalog exists: on H2 there is no {@code
     * pg_constraint} and no {@code to_regclass}, so those questions are not asked at all. The two code clauses
     * need no database and are answered from the lists.</p>
     *
     * @throws IllegalStateException naming every offending item and the fix for it, once
     */
    public void assertCatalogIsWired(
            Connection connection,
            List<PostingAnchor.Anchor> anchors,
            List<StarterAccountingCatalog.AccountEntry> accounts,
            List<StarterAccountingCatalog.PostingRuleEntry> rules)
            throws SQLException {
        var problems = new ArrayList<String>();
        problems.addAll(anchorWithoutRuleProblems(anchors, rules));
        problems.addAll(ruleNamingUndeclaredCodeProblems(accounts, rules));
        if (appliesToThisDatabase(connection)) {
            problems.addAll(anchorTableMissingProblems(connection, anchors));
            problems.addAll(vocabularyMismatchProblems(connection, anchors));
        }
        refuseIfAny(problems);
    }

    /**
     * Clause 1, on the two lists the caller supplies: every anchor has a rule with exactly its {@code sourceType}
     * and its {@code posting}.
     *
     * <p>Static and argument-taking so a suite can provoke the refusal with a reduced list — the tree itself
     * cannot be broken without breaking the sixty-odd contexts that boot on it — and so the method states the
     * question in terms of the catalog rather than of a database.</p>
     *
     * @throws IllegalStateException naming every anchor with no rule and the entry that would fix it
     */
    public static void assertEveryAnchorHasItsRule(
            List<PostingAnchor.Anchor> anchors, List<StarterAccountingCatalog.PostingRuleEntry> rules) {
        refuseIfAny(anchorWithoutRuleProblems(anchors, rules));
    }

    /**
     * Clause 2, on the two lists the caller supplies: every rule's debit and credit codes are declared by the
     * chart, and the two differ.
     *
     * @throws IllegalStateException naming every undeclared code and every rule that moves one account against
     *     itself
     */
    public static void assertEveryRuleNamesTwoDeclaredAccounts(
            List<StarterAccountingCatalog.AccountEntry> accounts,
            List<StarterAccountingCatalog.PostingRuleEntry> rules) {
        refuseIfAny(ruleNamingUndeclaredCodeProblems(accounts, rules));
    }

    /**
     * Clause 3, on the anchors the caller supplies: each one's table resolves in {@code pg_catalog}.
     *
     * <p>Takes the connection instead of opening one because the question is about <b>this</b> session's view of
     * the catalog, which is also why the caller passes the anchors: the production caller is {@link
     * #afterPropertiesSet} with the declared list, and a suite passes a name that does not resolve.</p>
     *
     * @throws IllegalStateException naming every anchor whose table {@code to_regclass} could not resolve
     */
    public void assertEveryAnchorTableExists(Connection connection, List<PostingAnchor.Anchor> anchors)
            throws SQLException {
        refuseIfAny(anchorTableMissingProblems(connection, anchors));
    }

    /**
     * Clause 4's read, on its own: the {@code source_type} values {@code ck_posting_rule_source_type} admits,
     * parsed out of its definition.
     *
     * <p>Public because it is the measurable half of the clause: the equality with {@link PostingAnchor#anchors()}
     * is asserted by a suite from this value, so a refusal can never be the only evidence that the vocabulary was
     * read at all. A missing constraint answers the empty set rather than throwing — an empty vocabulary is a
     * disagreement the refusal names, not a database that cannot be asked.</p>
     */
    public Set<String> sourceTypesDeclaredByDatabase(Connection connection) throws SQLException {
        var values = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement(SOURCE_TYPE_VOCABULARY);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Matcher literals = CONSTRAINT_LITERAL.matcher(result.getString("definition"));
                while (literals.find()) {
                    values.add(literals.group(1));
                }
            }
        }
        return values;
    }

    /**
     * Clause 4, on the anchors the caller supplies: the database's closed vocabulary and the declared anchors are
     * the same set, in both directions.
     *
     * @throws IllegalStateException naming both sets when an admitted source type has no anchor behind it, or a
     *     declared anchor the vocabulary refuses
     */
    public void assertVocabularyMatchesDeclaredAnchors(Connection connection, List<PostingAnchor.Anchor> anchors)
            throws SQLException {
        refuseIfAny(vocabularyMismatchProblems(connection, anchors));
    }

    /**
     * Every anchor of {@code anchors} that no rule of {@code rules} wires to <b>exactly</b> the {@code posting}
     * the anchor declares.
     *
     * <p>The posting is part of the match on purpose: a rule that posts {@code REVENUE} from the cost anchor is a
     * row the posting code of B3 does not look for, so counting it as wired would hide the very gap the anchor
     * exists to name.</p>
     */
    private static List<String> anchorWithoutRuleProblems(
            List<PostingAnchor.Anchor> anchors, List<StarterAccountingCatalog.PostingRuleEntry> rules) {
        var problems = new ArrayList<String>();
        for (var anchor : anchors) {
            var wired = rules.stream()
                    .anyMatch(rule -> anchor.sourceType().equals(rule.sourceType())
                            && anchor.posting().equals(rule.posting()));
            if (wired) {
                continue;
            }
            problems.add("anchor " + anchor.sourceType() + " reads its money from public." + anchor.table()
                    + " and has no posting rule for " + anchor.posting() + ": the fact keeps being written and"
                    + " nothing posts it, so the books come out short with no error at read time and no report"
                    + " that can tell that from a quiet period. Add a PostingRuleEntry(sourceType = \""
                    + anchor.sourceType() + "\", posting = \"" + anchor.posting() + "\", debitCode = …, creditCode"
                    + " = …) to StarterAccountingCatalog, and seed it in a new migration: V98 has already run on"
                    + " every deployed database, so editing it reaches no tenant that exists.");
        }
        return problems;
    }

    /** Every rule side that names a code the chart does not declare, and every rule whose two sides are one. */
    private static List<String> ruleNamingUndeclaredCodeProblems(
            List<StarterAccountingCatalog.AccountEntry> accounts,
            List<StarterAccountingCatalog.PostingRuleEntry> rules) {
        var declaredCodes = accounts.stream()
                .map(StarterAccountingCatalog.AccountEntry::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        var problems = new ArrayList<String>();
        for (var rule : rules) {
            var anchor = rule.sourceType() + "/" + rule.posting();
            if (!declaredCodes.contains(rule.debitCode())) {
                problems.add("posting rule " + anchor + " debits account code " + rule.debitCode() + ", which the"
                        + " chart of accounts in StarterAccountingCatalog does not declare: the applier cannot"
                        + " insert that rule and the posting would resolve to no account. Declare the account in"
                        + " StarterAccountingCatalog (and in a new migration) or point the rule at a code the"
                        + " chart already has.");
            }
            if (!declaredCodes.contains(rule.creditCode())) {
                problems.add("posting rule " + anchor + " credits account code " + rule.creditCode() + ", which the"
                        + " chart of accounts in StarterAccountingCatalog does not declare: the applier cannot"
                        + " insert that rule and the posting would resolve to no account. Declare the account in"
                        + " StarterAccountingCatalog (and in a new migration) or point the rule at a code the"
                        + " chart already has.");
            }
            if (rule.debitCode().equals(rule.creditCode())) {
                problems.add("posting rule " + anchor + " debits and credits the same account code "
                        + rule.debitCode() + ": both sides are one account, so the entry balances while moving"
                        + " nothing and the trial balance sums to zero over a book that never recorded the fact."
                        + " Give the rule two different accounts in StarterAccountingCatalog.");
            }
        }
        return problems;
    }

    /** Every anchor whose table {@code to_regclass('public.<table>')} cannot resolve. */
    private static List<String> anchorTableMissingProblems(Connection connection, List<PostingAnchor.Anchor> anchors)
            throws SQLException {
        var problems = new ArrayList<String>();
        if (anchors.isEmpty()) {
            return problems;
        }
        try (var statement = connection.prepareStatement(ANCHOR_TABLE_PROBE)) {
            for (var anchor : anchors) {
                statement.setString(1, "public." + anchor.table());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getString("anchor_table") != null) {
                        continue;
                    }
                }
                problems.add("anchor " + anchor.sourceType() + " declares its money-moving table as public."
                        + anchor.table() + ", and to_regclass('public." + anchor.table() + "') resolved to no"
                        + " relation: the table was renamed or dropped, so the rule for this anchor can never"
                        + " fire — the posting looks for a source row in a table nothing writes. Name the table"
                        + " the anchor is really read from in PostingAnchor, or restore it in a migration.");
            }
        }
        return problems;
    }

    /**
     * The vocabulary disagreement, in both directions.
     *
     * <p>Both directions matter and they are different defects. An admitted value with no anchor behind it is the
     * failure S10 exists for — a migration made a source type postable and the code stayed silent. A declared
     * anchor the vocabulary refuses is a rule the constraint will not let anyone store, which is a boot that
     * would otherwise pass and a seeding that would fail per tenant at runtime.</p>
     */
    private List<String> vocabularyMismatchProblems(Connection connection, List<PostingAnchor.Anchor> anchors)
            throws SQLException {
        var declared = anchors.stream()
                .map(PostingAnchor.Anchor::sourceType)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var admitted = sourceTypesDeclaredByDatabase(connection);
        if (declared.equals(admitted)) {
            return List.of();
        }

        var unasserted = new LinkedHashSet<>(admitted);
        unasserted.removeAll(declared);
        var unreachable = new LinkedHashSet<>(declared);
        unreachable.removeAll(admitted);

        var problems = new ArrayList<String>();
        if (!unasserted.isEmpty()) {
            problems.add("ck_posting_rule_source_type admits the source type(s) " + unasserted
                    + ", which PostingAnchor does not declare: a money-moving source nothing asserts, so a rule"
                    + " can be seeded for it and no startup check will notice that nothing posts from it."
                    + " Declare the anchor in PostingAnchor (with its table and its posting) or remove the value"
                    + " from the constraint in a new migration.");
        }
        if (!unreachable.isEmpty()) {
            problems.add("PostingAnchor declares the source type(s) " + unreachable
                    + ", which ck_posting_rule_source_type does not admit: the rule for that anchor cannot be"
                    + " stored at all, so the seed fails per tenant at runtime instead of here. Add the value to"
                    + " the constraint in a new migration or drop the anchor from PostingAnchor.");
        }
        problems.add("the closed vocabulary the database reports is " + admitted + " and the anchors this code"
                + " declares are " + declared + "; they are asserted equal because an anchor the code does not"
                + " know about and an anchor the vocabulary refuses are both ways for money to move without a"
                + " posting.");
        return problems;
    }

    /** One refusal for one problem set, so every path builds the same message. */
    private static void refuseIfAny(List<String> problems) {
        if (problems.isEmpty()) {
            return;
        }
        var items =
                problems.stream().map(problem -> "  - " + problem).collect(Collectors.joining(System.lineSeparator()));
        throw new IllegalStateException("S10 of odd/tasks/derived-accounting.md refuses to start: the accounting"
                + " catalog is not wired to the money-moving anchors this code declares. "
                + problems.size() + " problem(s), each one a way for a fact to be recorded and never posted — the"
                + " book comes out short and no read path reports it:"
                + System.lineSeparator() + items + System.lineSeparator()
                + "Fix every item above. The chart of accounts and the posting rules are StarterAccountingCatalog;"
                + " the anchors, the tables they derive from and the posting each one feeds are PostingAnchor;"
                + " the closed source_type vocabulary is ck_posting_rule_source_type in V98. Change the code and"
                + " add a new migration — never edit V98, which has already run on every deployed database, so an"
                + " edit there reaches no existing tenant while the code changes for every tenant created"
                + " afterwards. Nothing here is a warning: a missing posting produces no error at read time,"
                + " which is why the application does not start on it.");
    }
}
