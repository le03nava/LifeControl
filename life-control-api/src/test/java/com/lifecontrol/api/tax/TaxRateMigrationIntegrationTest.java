package com.lifecontrol.api.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code V82__tax_rate.sql}: the tax-code catalog, the tenant rate table, and the difference between them
 * (ADR-0002 decision 6, D-d of {@code odd/tasks/money-first-class.md}, decided 2026-11-05).
 *
 * <p>Every case runs against a scratch database owned by {@code lifecontrol_owner} — the role Flyway runs as
 * in delivery, and the role the policies apply to — migrated to {@code 81} first and to head afterwards, so
 * what is observed is the migration this unit adds and not the tree it inherits.</p>
 *
 * <p>The five things pinned here are the five a later reader cannot derive from the code:</p>
 *
 * <ol>
 *   <li>the catalog is seeded with exactly four codes, because {@code V81:87} defaults every line to
 *       {@code NA} and because CFDI's Tasa 0% and Exento are two different zeroes;</li>
 *   <li>{@code tax_code} carries <b>no</b> policy and no {@code tenant_table} row — it is a platform catalog
 *       like {@code currency}, and arming it would be a predicate over a column it does not have;</li>
 *   <li>{@code tax_rate} <b>is</b> armed, as {@code tenant} keyed by {@code company_id}, and one company's
 *       rate is invisible to another's scope;</li>
 *   <li>a rate's {@code tax_code} must exist in the catalog (the FK), which is what turns a typo into a
 *       refusal instead of a line taxed at zero;</li>
 *   <li>the line keeps <b>no</b> FK to the catalog, by decision: a snapshot has to survive the retirement of
 *       the code it names.</li>
 * </ol>
 *
 * <p>No Spring context: this suite is JDBC against a scratch database, like
 * {@code CompanyCurrencyMigrationIntegrationTest}.</p>
 */
@DisplayName("Tax catalog and tax rate migration (V82)")
class TaxRateMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: no {@code tax_code} catalog and no {@code tax_rate} table. */
    private static final String PRE_UNIT_HEAD = "81";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The four codes {@code V82} seeds, and the only vocabulary the catalog has today. */
    private static final String SEEDED_CODES = "EXENTO,IVA,NA,TASA_0";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_tax_rate_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
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

    // --- the catalog ---

    @Test
    @DisplayName("the catalog is seeded with exactly the four codes, and none of them states an amount")
    void seedsTheFourCodes() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(readScoped(null, "SELECT string_agg(code, ',' ORDER BY code) FROM tax_code"))
                .isEqualTo(SEEDED_CODES);
        assertThat(readScoped(null, "SELECT count(*) FROM tax_code WHERE label IS NULL"))
                .isEqualTo("0");
        // The vocabulary names WHAT the tax is. How much is a rate, and a rate is the company's: a column
        // here would be a second source of truth for the one number that must never be duplicated.
        assertThat(columnCount("tax_code", "rate"))
                .as("tax_code states no rate: the amount lives in tax_rate, or a rate change would rewrite "
                        + "the catalog and every document that quotes it")
                .isZero();
        assertThat(columnCount("tax_code", "company_id"))
                .as("tax_code carries no company_id: it is a platform catalog, like currency")
                .isZero();
    }

    @Test
    @DisplayName("tax_code is a platform catalog: no policy, no tenant_table row, no FORCE")
    void theCatalogIsNotArmed() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(policyCount("tax_code"))
                .as("a policy over a table with no company_id would be a predicate over a column that does "
                        + "not exist")
                .isZero();
        assertThat(count("SELECT count(*) FROM tenant_table WHERE table_name = 'tax_code'::regclass"))
                .as("tax_code is shared, like currency: a tenant_table row here would imply a tenancy column "
                        + "the table is not supposed to have")
                .isZero();
        assertThat(readScoped(null, "SELECT relrowsecurity::text FROM pg_class WHERE relname = 'tax_code'"))
                .isEqualTo("false");
    }

    // --- the tenant table ---

    @Test
    @DisplayName("tax_rate is armed as a tenant table keyed by company_id, with one forced policy")
    void theRateTableIsArmed() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(readScoped(
                        null,
                        "SELECT policy_kind || ':' || key_column FROM tenant_table "
                                + "WHERE table_name = 'tax_rate'::regclass"))
                .isEqualTo("tenant:company_id");
        assertThat(policyCount("tax_rate"))
                .as("one FOR ALL policy, generated by apply_tenant_isolation")
                .isEqualTo(1);
        assertThat(readScoped(null, "SELECT relforcerowsecurity::text FROM pg_class WHERE relname = 'tax_rate'"))
                .as("FORCE, because the owner is Flyway's role and a policy the owner bypasses enforces " + "nothing")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("one company's rate is invisible to another company's scope")
    void theRateIsIsolated() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var first = insertCompany("V82-FIRST");
        var second = insertCompany("V82-SECOND");
        insertRate(first, "IVA", "0.160000", LocalDate.of(2026, 1, 1));

        assertThat(readScoped(first, "SELECT count(*) FROM tax_rate")).isEqualTo("1");
        assertThat(readScoped(second, "SELECT count(*) FROM tax_rate"))
                .as("the second company reads nothing: the policy filters the row instead of hiding a "
                        + "mis-stated company_id")
                .isEqualTo("0");
        assertThat(refusalOf(
                        second,
                        "INSERT INTO tax_rate (company_id, tax_code, rate, effective_from) VALUES " + "('" + second
                                + "', 'IVA', 0.5, DATE '2026-01-01')"))
                .as("and the WITH CHECK accepts the scope's own company, which is the one this session writes")
                .isEmpty();
    }

    // --- the constraints that make the catalog a control instead of a comment ---

    @Test
    @DisplayName("a rate naming a code the catalog does not have is refused by the FK")
    void refusesAnUnknownCode() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var company = insertCompany("V82-FK");
        var refused = refusalOf(
                company,
                "INSERT INTO tax_rate (company_id, tax_code, rate, effective_from) VALUES ('" + company
                        + "', 'IVA16', 0.16, DATE '2026-01-01')");

        assertThat(refused)
                .as("the typo is the whole reason D-d chose a catalog: a free VARCHAR would have accepted "
                        + "this row and taxed every line that names IVA16 at nothing")
                .isNotEmpty();
        assertThat(refused).contains("fk");
        assertThat(readScoped(company, "SELECT count(*) FROM tax_rate"))
                .as("and nothing was stored")
                .isEqualTo("0");
    }

    @Test
    @DisplayName("a rate is a fraction between 0 and 1, and one company cannot state two for the same day")
    void boundsTheRateAndTheEffectiveDate() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var company = insertCompany("V82-BOUNDS");
        assertThat(refusalOf(company, rateInsert(company, "1.010000", "2026-01-01")))
                .as("1.01 is not a rate, it is 101%")
                .isNotEmpty();
        assertThat(refusalOf(company, rateInsert(company, "-0.010000", "2026-01-01")))
                .as("a negative rate would refund tax on every line")
                .isNotEmpty();

        insertRate(company, "IVA", "0.000000", LocalDate.of(2026, 1, 1));
        insertRate(company, "TASA_0", "1.000000", LocalDate.of(2026, 1, 1));
        assertThat(readScoped(company, "SELECT count(*) FROM tax_rate"))
                .as("both bounds are legal values: 0 is a declared zero and 1 is a full rate")
                .isEqualTo("2");

        assertThat(refusalOf(company, rateInsert(company, "0.160000", "2026-01-01")))
                .as("a second rate for the same company, code and day would make 'the latest rate' depend "
                        + "on insertion order")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the line keeps no FK to the catalog: a snapshot survives the retirement of its code")
    void theLineCarriesNoForeignKeyToTheCatalog() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(count("SELECT count(*) FROM pg_constraint c "
                        + "JOIN pg_class t ON t.oid = c.conrelid "
                        + "JOIN pg_class r ON r.oid = c.confrelid "
                        + "WHERE t.relname = 'sales_order_items' AND r.relname = 'tax_code' AND c.contype = 'f'"))
                .as("D-d: the line snapshots the code as text. An FK would turn 'this code is withdrawn' "
                        + "into 'these historical documents cannot exist'")
                .isZero();
        assertThat(readScoped(
                        null,
                        "SELECT coalesce(column_default, '') FROM information_schema.columns "
                                + "WHERE table_name = 'sales_order_items' AND column_name = 'tax_code'"))
                .as("and the default the tree already carries is one of the four seeded codes")
                .contains("NA");
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
        // `currency` is NOT NULL since V57 and this scratch database is already past it: the code is the
        // one the seed declares.
        execute(
                id,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + id + "', '"
                        + companyKey + "', '" + companyKey + "', '" + rfcOf(id) + "', 'MXN')");
        return id;
    }

    private void insertRate(UUID company, String taxCode, String rate, LocalDate from) throws SQLException {
        execute(
                company,
                "INSERT INTO tax_rate (company_id, tax_code, rate, effective_from) VALUES ('" + company + "', '"
                        + taxCode + "', " + rate + ", DATE '" + from + "')");
    }

    private String rateInsert(UUID company, String rate, String from) {
        return "INSERT INTO tax_rate (company_id, tax_code, rate, effective_from) VALUES ('" + company + "', 'IVA', "
                + rate + ", DATE '" + from + "')";
    }

    private String rfcOf(UUID id) {
        return "RFC" + id.toString().replace("-", "").substring(0, 10).toUpperCase();
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

    private void execute(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            setScope(connection, scope);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    /**
     * Runs {@code sql} scoped and returns the refusal's message, or an empty string when it was accepted.
     *
     * <p>A {@code String} and not the {@code SQLException} itself: {@code SQLException} implements
     * {@code Iterable<Throwable>}, so {@code assertThat(exception)} resolves to both the {@code Iterable} and
     * the {@code Object} overload and does not compile. The message is also what the cases assert on.</p>
     */
    private String refusalOf(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            setScope(connection, scope);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException refused) {
                return refused.getMessage() == null ? refused.getSQLState() : refused.getMessage();
            }
            return "";
        }
    }

    private void setScope(Connection connection, UUID scope) throws SQLException {
        if (scope == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
        }
    }

    private String readScoped(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            setScope(connection, scope);
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    /** The same read with no scope: what a migration that forgot the window would be looking at. */
    private int count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int policyCount(String table) throws SQLException {
        return count("SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = '" + table + "'");
    }

    private int columnCount(String table, String column) throws SQLException {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_name = '" + table
                + "' AND column_name = '" + column + "'");
    }
}
