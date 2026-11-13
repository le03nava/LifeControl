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
 * {@code odd/tasks/derived-accounting.md}).
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

    /** The three rules, read as {@code source_type:posting:debit_code:credit_code}. */
    private static final List<String> EXPECTED_RULES = List.of(
            "SALE_PAYMENT:COLLECTION:1101:1201",
            "STATUS_TRANSITION:REVENUE:1201:4101",
            "STOCK_ALLOCATION:COST_OF_SALES:5101:1301");

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
    @DisplayName("seeds the eight accounts and the three rules into every company that existed before it ran")
    void seedsEveryCompanyThatExistedBeforeTheFileRan() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var first = insertCompany("V98-EXISTING");
        var second = insertCompany("V98-EXISTING-2");

        migrateToHead();

        assertThat(accountsOf(first)).as("the whole chart, verbatim").isEqualTo(EXPECTED_ACCOUNTS);
        assertThat(rulesOf(first))
                .as("the three anchors, each pointing at the right pair")
                .isEqualTo(EXPECTED_RULES);
        assertThat(accountsOf(second))
                .as("and it is every row, not the first company")
                .isEqualTo(EXPECTED_ACCOUNTS);
        assertThat(rulesOf(second)).isEqualTo(EXPECTED_RULES);
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
                        "SELECT count(*) FROM posting_rule r WHERE r.company_id = '" + first
                                + "' AND (r.debit_account_id = r.credit_account_id)"))
                .as("no rule names the same account on both sides: that would be a rule posting nothing")
                .isEqualTo("0");
        assertThat(countScoped(
                        second,
                        "SELECT count(*) FROM posting_rule r JOIN account a ON a.id = r.debit_account_id "
                                + "WHERE r.company_id = '" + second + "' AND a.company_id <> r.company_id"))
                .as("a rule never points at another tenant's account, which is why the seed joins on "
                        + "(company_id, code) instead of naming a hardcoded id")
                .isEqualTo("0");
        assertThat(countScoped(
                        first,
                        "SELECT count(DISTINCT r.company_id) FROM posting_rule r WHERE r.company_id = '" + first + "'"))
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
                        "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                                + "WHERE c.relname IN ('starter_account', 'starter_posting_rule') "
                                + "AND n.nspname LIKE 'pg_temp%'"))
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

        var debit =
                readScoped(company, "SELECT id FROM account WHERE company_id = '" + company + "' AND code = '1101'");
        var credit =
                readScoped(company, "SELECT id FROM account WHERE company_id = '" + company + "' AND code = '1201'");

        var unknownSourceType = refusalOf(company, ruleInsert(company, debit, credit, "'REFUND'", "'COLLECTION'"));
        assertThat(unknownSourceType.getSQLState())
                .as("SQLSTATE 23514: ck_posting_rule_source_type refuses a fourth anchor, so one table cannot "
                        + "be spelled two ways and leave S10 asserting about a row nothing posts to")
                .isEqualTo("23514");

        var unknownPosting = refusalOf(company, ruleInsert(company, debit, credit, "'SALE_PAYMENT'", "'MARGIN'"));
        assertThat(unknownPosting.getSQLState())
                .as("SQLSTATE 23514: ck_posting_rule_posting refuses a posting the book does not read")
                .isEqualTo("23514");
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

    private static String ruleInsert(UUID company, String debit, String credit, String sourceType, String posting) {
        return "INSERT INTO posting_rule "
                + "(id, company_id, source_type, posting, debit_account_id, credit_account_id, enabled) VALUES "
                + "(gen_random_uuid(), '" + company + "', " + sourceType + ", " + posting + ", '" + debit + "', '"
                + credit + "', true)";
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

    private List<String> rulesOf(UUID company) throws SQLException {
        return readList(
                company,
                "SELECT r.source_type || ':' || r.posting || ':' || d.code || ':' || k.code FROM posting_rule r "
                        + "JOIN account d ON d.id = r.debit_account_id "
                        + "JOIN account k ON k.id = r.credit_account_id "
                        + "WHERE r.company_id = '" + company + "' ORDER BY r.source_type");
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
