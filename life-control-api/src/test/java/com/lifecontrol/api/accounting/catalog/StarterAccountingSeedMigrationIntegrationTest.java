package com.lifecontrol.api.accounting.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verification of the half of S9 that a shared database cannot show: <b>the migration seeds the companies
 * that already existed</b> ({@code V98__accounting_starter_catalog.sql}, T4 of
 * {@code odd/tasks/derived-accounting.md}), and of the backfill that gives those rules their lines
 * ({@code V99__posting_rule_lines.sql}, S11/L13).
 *
 * <p><b>Why a scratch database.</b> The shared test database boots straight to head, so every company a
 * suite creates there is created <i>after</i> V98 has run and is seeded by the listener instead of by the
 * migration. A green run there would prove the listener works and say nothing about the tenants the
 * migration had to reach. Here the database is held at {@link #PRE_UNIT_HEAD}, a company is seeded the way
 * production has them, and the chain then runs to head — the shape
 * {@code CompanyCurrencyMigrationIntegrationTest} established for the same question.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}.</b> {@code V97} arms {@code account} and
 * {@code posting_rule} with {@code ENABLE} <b>and {@code FORCE}</b>, and {@code FORCE} is what makes the
 * policy apply to the table's owner — the role Flyway runs as in delivery. The container's own user is a
 * superuser, which bypasses the policies whatever they say, so a seed asserted under it would report
 * success while delivery's {@code NOSUPERUSER} owner inserted zero rows and said nothing. That is exactly
 * what V98's {@code NO FORCE} window exists for, and this suite is the measurement that the window is
 * there and closed again afterwards.</p>
 *
 * <p><b>The reads state a tenant.</b> {@code account} and {@code posting_rule} are forced, so a read under
 * the owner with no {@code app.company_id} answers zero rows for a table that is plainly populated —
 * {@link #theOwnerReadsNothingWithoutTheScope()} is the control that keeps that from being mistaken for a
 * passing seed.</p>
 */
@DisplayName("Accounting starter catalog migration (V98)")
class StarterAccountingSeedMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The last migration before the one this suite verifies: {@code account} and {@code posting_rule} exist. */
    private static final String PRE_UNIT_HEAD = "97";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The chart of accounts, exactly as V98 must leave it, read as {@code code:name:kind:enabled}. */
    private static final List<String> EXPECTED_ACCOUNTS = List.of(
            "1101:Caja:ASSET:true",
            "1102:Bancos:ASSET:true",
            "1201:Cuentas por cobrar:ASSET:true",
            "1202:IVA acreditable:ASSET:true",
            "1301:Inventario:ASSET:true",
            "2101:IVA trasladado:LIABILITY:true",
            "4101:Ingresos por ventas:REVENUE:true",
            "5101:Costo de ventas:EXPENSE:true");

    /**
     * The lines of the three rules, read as {@code source_type:posting:side:amount_selector:account_code}.
     *
     * <p>Seven and not six: {@code V99} says a rule is N lines, and the revenue rule is the one that needed
     * the third — S11 asks the entry to carry the revenue, the {@code IVA trasladado} and the receivable parts
     * of the document, and {@code 2101 IVA trasladado} was the account the seeded vocabulary named nowhere
     * (L13 of {@code odd/tasks/derived-accounting.md}).</p>
     */
    private static final List<String> EXPECTED_LINES = List.of(
            "SALE_PAYMENT:COLLECTION:CREDIT:SOURCE_AMOUNT:1201",
            "SALE_PAYMENT:COLLECTION:DEBIT:SOURCE_AMOUNT:1101",
            "STATUS_TRANSITION:REVENUE:CREDIT:REVENUE:4101",
            "STATUS_TRANSITION:REVENUE:CREDIT:TAX:2101",
            "STATUS_TRANSITION:REVENUE:DEBIT:TOTAL:1201",
            "STOCK_ALLOCATION:COST_OF_SALES:CREDIT:ALLOCATED_COST:1301",
            "STOCK_ALLOCATION:COST_OF_SALES:DEBIT:ALLOCATED_COST:5101");

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_accounting_seed_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            // OWNER and not the superuser: the policies apply to whichever role owns these tables, and the
            // whole question here is whether the seed reaches a row under that role.
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
    @DisplayName("seeds the eight accounts, the three rules and their seven lines into every company that "
            + "existed before it ran")
    void seedsEveryCompanyThatExistedBeforeTheFileRan() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var first = insertCompany("V98-EXISTING");
        var second = insertCompany("V98-EXISTING-2");

        migrateToHead();

        assertThat(accountsOf(first)).as("the whole chart, verbatim").isEqualTo(EXPECTED_ACCOUNTS);
        assertThat(linesOf(first))
                .as("the three anchors with their 3/2/2 lines, each naming its side, its selector and its "
                        + "account by code (S11 and L13)")
                .isEqualTo(EXPECTED_LINES);
        assertThat(accountsOf(second))
                .as("and it is every row, not the first company")
                .isEqualTo(EXPECTED_ACCOUNTS);
        assertThat(linesOf(second))
                .as("the backfill reaches every tenant that existed when the file ran")
                .isEqualTo(EXPECTED_LINES);
    }

    @Test
    @DisplayName("resolves each rule's accounts by code inside the company that owns it")
    void resolvesTheAccountIdsByCodeWithinTheCompany() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var first = insertCompany("V98-RESOLVE");
        var second = insertCompany("V98-RESOLVE-2");
        migrateToHead();

        assertThat(countScoped(
                        first,
                        "SELECT count(*) FROM posting_rule_line d JOIN posting_rule_line k "
                                + "ON k.company_id = d.company_id AND k.rule_id = d.rule_id "
                                + "WHERE d.company_id = '" + first + "' AND d.side = 'DEBIT' "
                                + "AND k.side = 'CREDIT' AND d.account_id = k.account_id"))
                .as("no rule debits and credits one account: that entry balances while moving nothing")
                .isEqualTo("0");
        assertThat(countScoped(
                        second,
                        "SELECT count(*) FROM posting_rule_line l JOIN account a ON a.id = l.account_id "
                                + "WHERE l.company_id = '" + second + "' AND a.company_id <> l.company_id"))
                .as("a line never points at another tenant's account, which is why the backfill joins on "
                        + "(company_id, code) instead of naming a hardcoded id")
                .isEqualTo("0");
        assertThat(countScoped(
                        first,
                        "SELECT count(DISTINCT l.company_id) FROM posting_rule_line l WHERE l.company_id = '" + first
                                + "'"))
                .as("one tenant, its own rows")
                .isEqualTo("1");
    }

    @Test
    @DisplayName("leaves both catalogs forced and no temporary table behind")
    void leavesBothCatalogsArmedAndNoScratchTableBehind() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        insertCompany("V98-ARMED");
        migrateToHead();

        assertThat(readScoped(
                        null,
                        "SELECT relrowsecurity::text || ':' || relforcerowsecurity::text "
                                + "FROM pg_class WHERE relname = 'account'"))
                .as("ENABLE + FORCE, the state V97 left and V98 had to restore")
                .isEqualTo("true:true");
        assertThat(readScoped(
                        null,
                        "SELECT relrowsecurity::text || ':' || relforcerowsecurity::text "
                                + "FROM pg_class WHERE relname = 'posting_rule'"))
                .as("a window left open is a catalog its owner reads across tenants")
                .isEqualTo("true:true");
        assertThat(readScoped(
                        null,
                        "SELECT relrowsecurity::text || ':' || relforcerowsecurity::text "
                                + "FROM pg_class WHERE relname = 'posting_rule_line'"))
                .as("the table V99 creates is armed in the file that creates it, and its window closed")
                .isEqualTo("true:true");
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                                + "WHERE c.relname IN ('starter_account', 'starter_posting_rule', "
                                + "'starter_posting_rule_line') AND n.nspname LIKE 'pg_temp%'"))
                .as("the seed's temporary tables are dropped by the file, not left to the session")
                .isEqualTo("0");
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_constraint WHERE conrelid = 'posting_rule'::regclass "
                                + "AND contype = 'c' AND conname IN "
                                + "('ck_posting_rule_source_type', 'ck_posting_rule_posting')"))
                .as("both closed vocabularies exist by name, which is what S10's assertion spells")
                .isEqualTo("2");
    }

    @Test
    @DisplayName("refuses a source type and a posting outside the closed vocabulary")
    void refusesAValueOutsideTheClosedVocabulary() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = insertCompany("V98-VOCABULARY");
        migrateToHead();

        var unknownSourceType = refusalOf(company, ruleInsert(company, "'REFUND'", "'COLLECTION'"));
        assertThat(unknownSourceType.getSQLState())
                .as("SQLSTATE 23514: ck_posting_rule_source_type refuses a fourth anchor, so one table cannot "
                        + "be spelled two ways and leave S10 asserting about a row nothing posts to")
                .isEqualTo("23514");

        var unknownPosting = refusalOf(company, ruleInsert(company, "'SALE_PAYMENT'", "'MARGIN'"));
        assertThat(unknownPosting.getSQLState())
                .as("SQLSTATE 23514: ck_posting_rule_posting refuses a posting the book does not read")
                .isEqualTo("23514");
    }

    @Test
    @DisplayName("L13: the two account columns are gone from posting_rule and the lines carry both pointers")
    void theTwoAccountColumnsAreGoneAndTheLinesCarryThePointers() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        insertCompany("V99-COLUMNS");
        migrateToHead();

        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                                + "AND table_name = 'posting_rule' "
                                + "AND column_name IN ('debit_account_id', 'credit_account_id')"))
                .as("the ADR's first shape was a debit account and a credit account on the rule; V99 replaced it "
                        + "with lines and dropped the pair, because two copies of one fact are two things that "
                        + "can disagree and the poster would have to pick one")
                .isEqualTo("0");
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_constraint WHERE conrelid = 'posting_rule'::regclass "
                                + "AND contype = 'f' AND confrelid = 'account'::regclass"))
                .as("and the foreign key that pointed at the chart went with the column it was stated on")
                .isEqualTo("0");
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_constraint WHERE conrelid = 'posting_rule'::regclass "
                                + "AND contype = 'u' AND conname = 'uq_posting_rule_company_id' "
                                + "AND strpos(pg_get_constraintdef(oid), '(company_id, id)') > 0"))
                .as("uq_posting_rule_company_id is the target the line's composite pointer needs, the "
                        + "`V88:51-52` shape")
                .isEqualTo("1");
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_constraint WHERE conrelid = 'posting_rule_line'::regclass "
                                + "AND contype = 'c' "
                                + "AND conname IN ('ck_posting_rule_line_side', "
                                + "'ck_posting_rule_line_selector')"))
                .as("both closed vocabularies exist by name, which is what the startup assertion and an "
                        + "operator meeting the refusal both need")
                .isEqualTo("2");
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_constraint WHERE conrelid = 'posting_rule_line'::regclass "
                                + "AND contype = 'f' "
                                + "AND conname IN ('fk_posting_rule_line_rule', "
                                + "'fk_posting_rule_line_account') AND pg_get_constraintdef(oid) "
                                + "LIKE 'FOREIGN KEY (company_id, %'"))
                .as("both pointers are composite over (company_id, <the id>): a referential check bypasses "
                        + "row security, so an id-only pointer lets one tenant name another's account (S7)")
                .isEqualTo("2");
        assertThat(readScoped(
                        null,
                        "SELECT count(*) FROM pg_constraint WHERE conrelid = 'posting_rule_line'::regclass "
                                + "AND contype = 'u' "
                                + "AND conname = 'uq_posting_rule_line_company_rule_side_selector' "
                                + "AND strpos(pg_get_constraintdef(oid), "
                                + "'(company_id, rule_id, side, amount_selector)') > 0"))
                .as("one line per side and selector is what makes a posting deterministic: a second line for "
                        + "the same amount would make the entry depend on which one the poster read first")
                .isEqualTo("1");
    }

    @Test
    @DisplayName("a posting_rule_line is invisible outside its own tenant, and each tenant has its own seven")
    void aPostingRuleLineIsInvisibleOutsideItsTenant() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var first = insertCompany("V99-SCOPE-A");
        var second = insertCompany("V99-SCOPE-B");
        migrateToHead();

        assertThat(countScoped(first, "SELECT count(*) FROM posting_rule_line WHERE company_id = '" + first + "'"))
                .as("with the scope, the backfilled lines are there")
                .isEqualTo("7");
        assertThat(readScoped(null, "SELECT count(*) FROM posting_rule_line"))
                .as("without it, the owner sees no line at all — which is why V99 reads its rows back inside "
                        + "its NO FORCE window and re-reads them per tenant once the policy is back")
                .isEqualTo("0");
        assertThat(countScoped(second, "SELECT count(*) FROM posting_rule_line WHERE company_id = '" + first + "'"))
                .as("naming the first tenant's id from the second scope resolves to no row")
                .isEqualTo("0");
        assertThat(countScoped(second, "SELECT count(*) FROM posting_rule_line WHERE company_id = '" + second + "'"))
                .as("and the second tenant has its own seven, from the same backfill")
                .isEqualTo("7");
    }

    @Test
    @DisplayName("refuses a side, a selector and a second line for one (rule, side, selector)")
    void refusesASideASelectorOrADuplicateLine() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = insertCompany("V99-LINE-VOCABULARY");
        migrateToHead();

        var rule = readScoped(
                company, "SELECT id FROM posting_rule WHERE company_id = '" + company + "' AND posting = 'REVENUE'");
        var account =
                readScoped(company, "SELECT id FROM account WHERE company_id = '" + company + "' AND code = '1201'");

        assertThat(refusalOf(company, lineInsert(company, rule, account, "'D'", "'TOTAL'"))
                        .getSQLState())
                .as("SQLSTATE 23514: ck_posting_rule_line_side admits DEBIT and CREDIT and nothing else, so a "
                        + "typo cannot make a line the poster silently skips")
                .isEqualTo("23514");
        assertThat(refusalOf(company, lineInsert(company, rule, account, "'DEBIT'", "'BASE'"))
                        .getSQLState())
                .as("SQLSTATE 23514: ck_posting_rule_line_selector refuses BASE — the selector is REVENUE "
                        + "because the rounding is folded into it, and a selector outside the five is an amount "
                        + "the poster cannot compute (L13(b))")
                .isEqualTo("23514");
        assertThat(refusalOf(company, lineInsert(company, rule, account, "'DEBIT'", "'TOTAL'"))
                        .getSQLState())
                .as("SQLSTATE 23505: the backfill already wrote the REVENUE rule's DEBIT TOTAL line, so a "
                        + "second one is a second amount for one part of the entry")
                .isEqualTo("23505");
    }

    @Test
    @DisplayName("the premise: without the tenant scope, the owner reads no seeded row at all")
    void theOwnerReadsNothingWithoutTheScope() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = insertCompany("V98-SCOPE");
        migrateToHead();

        assertThat(countScoped(company, "SELECT count(*) FROM account WHERE company_id = '" + company + "'"))
                .as("with the scope, the seeded chart is there")
                .isEqualTo("8");
        assertThat(readScoped(null, "SELECT count(*) FROM account"))
                .as("without it, this owner sees nothing — which is why V98 opens its NO FORCE window "
                        + "instead of trusting an INSERT ... SELECT to match a row")
                .isEqualTo("0");
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

    // --- fixtures: every write states the tenant scope, because the policies apply to the owner ---

    private UUID insertCompany(String companyKey) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                id,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + id + "', '"
                        + companyKey + "', '" + companyKey + "', '" + rfcOf(id) + "', 'MXN')");
        return id;
    }

    private static String ruleInsert(UUID company, String sourceType, String posting) {
        return "INSERT INTO posting_rule (id, company_id, source_type, posting, enabled) VALUES "
                + "(gen_random_uuid(), '" + company + "', " + sourceType + ", " + posting + ", true)";
    }

    private static String lineInsert(UUID company, String rule, String account, String side, String selector) {
        return "INSERT INTO posting_rule_line (id, company_id, rule_id, account_id, side, amount_selector) "
                + "VALUES (gen_random_uuid(), '" + company + "', '" + rule + "', '" + account + "', " + side
                + ", " + selector + ")";
    }

    /** A unique 13-character RFC per fixture row, because {@code companies.rfc} is UNIQUE. */
    private static String rfcOf(UUID id) {
        return ("V98" + id.toString().replace("-", "")).substring(0, 13).toUpperCase();
    }

    // --- reads ---

    private List<String> accountsOf(UUID company) throws SQLException {
        return readList(
                company,
                "SELECT code || ':' || name || ':' || kind || ':' || enabled FROM account WHERE company_id = '"
                        + company + "' ORDER BY code");
    }

    private List<String> linesOf(UUID company) throws SQLException {
        return readList(
                company,
                "SELECT r.source_type || ':' || r.posting || ':' || l.side || ':' || l.amount_selector "
                        + "|| ':' || a.code FROM posting_rule_line l "
                        + "JOIN posting_rule r ON r.company_id = l.company_id AND r.id = l.rule_id "
                        + "JOIN account a ON a.company_id = l.company_id AND a.id = l.account_id "
                        + "WHERE l.company_id = '" + company + "' "
                        + "ORDER BY r.source_type, l.side, l.amount_selector");
    }

    /** One statement on one connection with the tenant scope set first. */
    private void execute(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                statement.execute(sql);
            }
        }
    }

    /** Runs a statement that must be refused and returns the refusal, so its SQLSTATE can be asserted. */
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

    /** A read with the tenant scope set, which a tenant table requires; {@code null} states no scope. */
    private String readScoped(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            if (scope != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                }
            }
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                if (!resultSet.next()) {
                    throw new AssertionError("the query returned no row, and this read expected exactly one: " + sql);
                }
                return resultSet.getString(1);
            }
        }
    }

    private String countScoped(UUID scope, String sql) throws SQLException {
        return readScoped(scope, sql);
    }

    private List<String> readList(UUID scope, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            if (scope != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                }
            }
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
            }
        }
        return values;
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
