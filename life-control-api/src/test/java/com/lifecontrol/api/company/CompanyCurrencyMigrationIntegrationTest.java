package com.lifecontrol.api.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verification of {@code V57__company_currency.sql}, the schema half of ADR-0002 decision 2 (D-c).
 *
 * <p><b>Why a scratch database.</b> The one fact this migration asserts that a shared database cannot
 * show is the <b>backfill</b>: a company that exists before the column does must come out of the
 * migration holding the seeded code. The shared test database boots straight to head, where every
 * company a suite creates already states a currency, so a green run there would prove the column exists
 * and say nothing about the rows the migration had to reach. Here the database is held at the pre-unit
 * head — where {@code companies.currency} does not exist — a company is seeded the way production has
 * them, and the chain then runs to head.</p>
 *
 * <p><b>Why the head before this file is {@code 56}.</b> {@code V54}–{@code V56} are the catalog-identity
 * migrations and none of them touches {@code companies}, so the fixture below sees the same table it would
 * see at {@code V53} — and the suite starts where the file it verifies starts.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}.</b> {@code V52} calls {@code ENABLE} <b>and
 * {@code FORCE}</b> on every table it arms and {@code V53} arms {@code companies}, so {@code FORCE} is
 * what makes the policy apply to the table's owner — the role Flyway runs as in delivery. The container's
 * own user is a superuser, which bypasses the policies whatever they say, so a backfill asserted under it
 * would report the migration working while delivery's {@code NOSUPERUSER} owner matched zero rows in
 * silence. That is exactly what {@code V57}'s {@code NO FORCE} window exists for, and the premise is
 * pinned by its own case below.</p>
 */
@DisplayName("Company currency migration (V57)")
class CompanyCurrencyMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * The head this unit starts from: no {@code currency} catalog and no {@code companies.currency}.
     * {@code 56} is the last migration before this file, and the catalog-identity migrations it names do not
     * touch {@code companies}.
     */
    private static final String PRE_UNIT_HEAD = "56";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The one code the product states today. */
    private static final String SEEDED_CODE = "MXN";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName =
                "scratch_company_currency_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            // OWNER and not the superuser: the policies of ADR-0005 apply to whichever role owns these
            // tables, and this suite exists to observe that they apply to the delivery one.
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
    @DisplayName("every company that existed before the column is backfilled, and the column then refuses NULL")
    void backfillsEveryExistingCompany() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var existing = insertCompany("V57-EXISTING");
        var other = insertCompany("V57-EXISTING-2");

        migrateToHead();

        assertThat(currencyOf(existing))
                .as("the company that existed before the column holds the seeded code — the backfill "
                        + "records what the product has always stated instead of choosing a value")
                .isEqualTo(SEEDED_CODE);
        assertThat(currencyOf(other))
                .as("and it is every row, not the first one")
                .isEqualTo(SEEDED_CODE);

        assertThat(column("is_nullable"))
                .as("the column is NOT NULL after the backfill, which is what makes a later company unable "
                        + "to be created without a currency")
                .isEqualTo("NO");
        assertThat(column("character_maximum_length"))
                .as("and it is CHAR(3), the shape the catalog's key has")
                .isEqualTo("3");

        // A company created afterwards without a value is refused by the schema, not by a caller that
        // could forget: the column's discontinuity from NULL is the whole point of the three steps. The
        // refused row states its own id as the scope, because the policy's `WITH CHECK` answers before the
        // not-null check does (measured: SQLSTATE 42501 on the first run of this case).
        var refusedId = UUID.randomUUID();
        var refusal = refusalOf(
                refusedId,
                "INSERT INTO companies (id, company_key, company_name, rfc) VALUES ('" + refusedId
                        + "', 'V57-NO-CURRENCY', 'V57 No Currency', 'V5701010NC1')");
        assertThat(refusal.getSQLState())
                .as("SQLSTATE 23502: not-null violation on companies.currency")
                .isEqualTo("23502");
    }

    @Test
    @DisplayName("the column references the catalog: an unknown code is refused and a second code is not")
    void theColumnReferencesTheCatalog() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = insertCompany("V57-FK");
        migrateToHead();

        assertThat(currencyForeignKeyCount())
                .as("exactly one foreign key from companies to the currency catalog exists")
                .isEqualTo(1);

        // Same premise as above: the value refused here is the foreign key's, so the scope states the new
        // row's own id and the policy does not answer first.
        var refusedId = UUID.randomUUID();
        var refusal = refusalOf(
                refusedId,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + refusedId
                        + "', 'V57-UNKNOWN', 'V57 Unknown', 'V5701010UK1', 'ZZZ')");
        assertThat(refusal.getSQLState())
                .as("SQLSTATE 23503: a code the catalog does not carry is refused by the foreign key")
                .isEqualTo("23503");

        // The column is a reference to a catalog, not a hardcoded 'MXN': once a second currency row
        // exists, a second company states it. The row is inserted by this case because the seed carries
        // one code on purpose — a second row is a product decision, not a fixture's.
        execute(company, "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('USD', 2, '$', true)");
        var usdCompany = insertCompany("V57-USD", "USD");
        assertThat(currencyOf(usdCompany))
                .as("a company can state a different existing code, which is what makes the column a "
                        + "tenant fact and not a platform constant")
                .isEqualTo("USD");
    }

    @Test
    @DisplayName("the seed is the one code the product states, with its minor units and symbol")
    void theSeedCarriesTheOneCodeTheProductStates() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        insertCompany("V57-SEED");
        migrateToHead();

        assertThat(count("SELECT count(*) FROM currency"))
                .as("one row and no other: a second currency is a product decision nobody took, and the "
                        + "catalog exists so it is an INSERT later instead of a schema change")
                .isEqualTo(1);
        assertThat(readScoped(
                        null, "SELECT code || ':' || minor_units || ':' || symbol || ':' || enabled FROM currency"))
                .as("MXN, two decimals (what the POS prints), the symbol es-MX shows, enabled")
                .isEqualTo("MXN:2:$:true");
    }

    @Test
    @DisplayName("the catalog carries no tenant column and no policy: it is a platform catalog")
    void theCatalogIsPlatformWide() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        insertCompany("V57-PLATFORM");
        migrateToHead();

        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'currency' AND "
                        + "column_name = 'company_id'"))
                .as("no company_id, so there is no tenant column for the startup check to demand a policy for")
                .isZero();
        assertThat(readScoped(null, "SELECT relrowsecurity::text FROM pg_class WHERE relname = 'currency'"))
                .as("and row-level security is off, deliberately: a predicate over a column this table "
                        + "does not have would be the only alternative")
                .isEqualTo("false");
    }

    @Test
    @DisplayName("the premise: without the tenant scope, the owner reads no company at all")
    void theOwnerReadsNoCompanyWithoutTheScope() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        var company = insertCompany("V57-SCOPE");
        migrateToHead();

        assertThat(readScoped(company, "SELECT count(*) FROM companies"))
                .as("with the scope, the fixture's own row is there")
                .isEqualTo("1");
        assertThat(count("SELECT count(*) FROM companies"))
                .as("without it, this owner sees nothing — which is why V57 opens its NO FORCE window "
                        + "instead of trusting an UPDATE to match a row")
                .isZero();
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
                "INSERT INTO companies (id, company_key, company_name, rfc) VALUES ('" + id + "', '" + companyKey
                        + "', '" + companyKey + "', '" + rfcOf(id) + "')");
        return id;
    }

    /**
     * A company that states its currency, for the cases that run after the migration: the column does not
     * exist at {@link #PRE_UNIT_HEAD}, which is the point of the first two cases.
     */
    private UUID insertCompany(String companyKey, String currency) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                id,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + id + "', '"
                        + companyKey + "', '" + companyKey + "', '" + rfcOf(id) + "', '" + currency + "')");
        return id;
    }

    /** A unique 13-character RFC per fixture row, because {@code companies.rfc} is UNIQUE. */
    private static String rfcOf(UUID id) {
        return ("V57" + id.toString().replace("-", "")).substring(0, 13).toUpperCase();
    }

    // --- reads and writes ---

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

    private String currencyOf(UUID company) throws SQLException {
        return readScoped(company, "SELECT currency FROM companies WHERE id = '" + company + "'");
    }

    private String column(String what) throws SQLException {
        return readScoped(
                null,
                "SELECT " + what + " FROM information_schema.columns WHERE table_name = 'companies' "
                        + "AND column_name = 'currency'");
    }

    private int currencyForeignKeyCount() throws SQLException {
        return count("SELECT count(*) FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid = c.conrelid "
                + "JOIN pg_class r ON r.oid = c.confrelid "
                + "WHERE t.relname = 'companies' AND r.relname = 'currency' AND c.contype = 'f'");
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
