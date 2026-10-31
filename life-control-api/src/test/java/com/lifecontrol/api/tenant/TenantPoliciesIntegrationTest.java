package com.lifecontrol.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verification of ADR-0005's <b>armed</b> state — the sixteen tables {@code V53} puts under Row-Level
 * Security, one case per rule the ADR states for them.
 *
 * <p><b>Every case arms nothing.</b> The policies are the migration's, which is the point of this suite:
 * a case that applied {@code apply_tenant_isolation} itself would prove the helper works, and that is
 * {@code TenantScopeFunctionsIntegrationTest}'s job on throwaway tables. What is observed here is the
 * deployment's own state — so a migration that forgets a table, arms one with the wrong kind or forgets
 * {@code FORCE} fails this suite rather than a request in production.</p>
 *
 * <p><b>Why it can only be observed here.</b> Row-Level Security exists on PostgreSQL and nowhere else, and a
 * superuser bypasses every policy whatever it says — the same trap that made ADR-0005 drop {@code SUPERUSER}
 * from the owner in delivery. So each behavioural case opens its <b>own</b> connection as the container's own
 * user (a superuser), inserts the fixtures the case needs — which is what lets them exist at all, and what
 * makes the “both rows are there” control unfiltered — and then switches to {@code lifecontrol_app} with
 * {@code SET LOCAL ROLE}, the role the pool logs in as, to observe what the policy answers. The connection is
 * opened explicitly in {@link #beginTransaction()} rather than taken from the pool, because since T6b-4 the
 * pool <b>is</b> the application role: this suite's premise needs the privilege the pool no longer has, so it
 * states it instead of assuming it. Everything is rolled back in
 * {@link #rollBackEverythingThisTestWrote()}, because sixty-odd sibling suites share this container and none of
 * them asked for these rows.</p>
 *
 * <p>Fixtures are the minimum each row's {@code NOT NULL} columns ask for; nothing here builds a
 * department tree or a store hierarchy, because no rule of the ADR differs for those tables. The tables
 * without a behavioural case are covered structurally by
 * {@link #everyTenantTableIsArmedWithItsPolicy()}: it asserts, per table, that the policy exists, is
 * {@code FOR ALL}, has one predicate for reads and writes, and that the list names the kind and key the
 * predicate is built from.</p>
 */
@SpringBootTest
@DisplayName("The sixteen armed tenant tables")
class TenantPoliciesIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The role the pool connects as in delivery, and the only one a policy applies to (ADR decision 3). */
    private static final String APPLICATION_ROLE = "lifecontrol_app";

    /**
     * The role that owns the objects in delivery: {@code FORCE} is what keeps it from being exempt.
     *
     * <p><b>Which role each case runs as, and why.</b> {@code everyTenantTableIsArmedWithItsPolicy} reads
     * {@code pg_class}, {@code pg_policies} and {@code tenant_table}, so it answers for the pool's role as
     * well as any other. The behavioural cases need a connection the policies do <b>not</b> filter, and only
     * to establish the state they then observe: the fixtures must exist before the policy can hide them, the
     * “both rows are there” control has to be unfiltered to mean anything, and moving a table's ownership (the
     * {@code FORCE} case) is DDL that only an owner or a superuser may run. That connection is the container's
     * own login — {@code lifecontrol}, a SUPERUSER — and it is opened explicitly below instead of being
     * inherited, which is the point: since T6b-4 the pool is {@code lifecontrol_app}, so a suite that needs
     * the superuser says so. From there each case switches down with {@code SET LOCAL ROLE lifecontrol_app},
     * the way delivery's pool already runs.
     */
    private static final String OWNER_ROLE = "lifecontrol_owner";

    /**
     * The sixteen rows {@code V53} writes, as the three facts a policy is built from. Frozen literally:
     * a table armed with the wrong kind or the wrong key column filters the wrong thing in silence, and
     * the {@code tenant_table} row is what the policy was generated from.
     */
    private static final List<ArmedTable> ARMED_TABLES = List.of(
            new ArmedTable("companies", "tenant", "id"),
            new ArmedTable("company_countries", "tenant", "company_id"),
            new ArmedTable("company_regions", "tenant", "company_id"),
            new ArmedTable("company_stores", "tenant", "company_id"),
            new ArmedTable("company_zones", "tenant", "company_id"),
            new ArmedTable("departments", "tenant", "company_id"),
            new ArmedTable("employees", "tenant", "company_id"),
            new ArmedTable("product_attribute_profile_roots", "shared", "company_id"),
            new ArmedTable("product_attribute_profiles", "shared", "company_id"),
            new ArmedTable("product_categories", "tenant", "company_id"),
            new ArmedTable("product_suppliers", "tenant", "company_id"),
            new ArmedTable("product_variants", "tenant", "company_id"),
            new ArmedTable("products", "tenant", "company_id"),
            new ArmedTable("purchase_orders", "tenant", "company_id"),
            new ArmedTable("suppliers", "tenant", "company_id"),
            new ArmedTable("user_preferences", "subject", "keycloak_user_id"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** One connection per case at most, opened with {@link #beginTransaction()} and closed below. */
    private Connection transaction;

    @AfterEach
    void rollBackEverythingThisTestWrote() throws SQLException {
        if (transaction == null) {
            return;
        }
        try {
            transaction.rollback();
            transaction.setAutoCommit(true);
        } finally {
            transaction.close();
            transaction = null;
        }
    }

    // ──────────────────────────────────────
    // 1. The armed state itself: one policy per table, forced, of the kind the list names
    // ──────────────────────────────────────

    @Test
    @DisplayName("all sixteen tables carry one forced FOR ALL policy, of the kind and key the list names")
    void everyTenantTableIsArmedWithItsPolicy() {
        assertThat(ARMED_TABLES)
                .as("the count is stated once, here: fifteen company_id tables plus `companies`, which is "
                        + "isolated by its own id (ADR decision 5 and migration group 3)")
                .hasSize(16);

        var listed = listedTables();

        for (var expected : ARMED_TABLES) {
            var table = expected.table();

            assertThat(rlsFlags(table))
                    .as(
                            "%s: ENABLE turns the policy on and FORCE stops the table's owner from being "
                                    + "exempt (ADR decision 3, invariant T1)",
                            table)
                    .containsExactly(true, true);

            var policies = policiesOn(table);
            assertThat(policies)
                    .as("%s: exactly one policy, so no second predicate nobody wrote is ANDed in", table)
                    .hasSize(1);

            var policy = policies.get(0);
            assertThat(policy.name())
                    .as("%s: the name apply_tenant_isolation derives from the table", table)
                    .isEqualTo("p_tenant_" + table);
            assertThat(policy.command())
                    .as("%s: FOR ALL, so a SELECT, UPDATE and DELETE cannot disagree", table)
                    .isEqualTo("ALL");
            assertThat(policy.predicate())
                    .as("%s: the predicate compares the key the list names", table)
                    .contains(expected.keyColumn());
            assertThat(policy.withCheck())
                    .as(
                            "%s: USING and WITH CHECK are the same predicate, so a write cannot name a tenant "
                                    + "the reader could not see (invariant T4)",
                            table)
                    .isEqualTo(policy.predicate());

            assertThat(listed)
                    .as(
                            "%s: the list carries the kind and key the policy was generated from — a wrong "
                                    + "one here is a policy filtering the wrong thing",
                            table)
                    .containsEntry(table, expected.kind() + ":" + expected.keyColumn());
        }
    }

    // ──────────────────────────────────────
    // 2. The read half: another tenant's row is invisible, with no WHERE clause naming a company
    // ──────────────────────────────────────

    @Test
    @DisplayName("a tenant table hides another tenant's row: company_countries")
    void aTenantTableHidesAnotherTenantsRow() throws SQLException {
        var connection = beginTransaction();
        var companyA = insertCompany(connection, "COUNTRY-A");
        var companyB = insertCompany(connection, "COUNTRY-B");
        var country = countryId(connection, "MX");
        var rowA = insertCompanyCountry(connection, companyA, country);
        var rowB = insertCompanyCountry(connection, companyB, country);

        assertThat(ids(connection, "SELECT id FROM company_countries WHERE id IN (?::uuid, ?::uuid)", rowA, rowB))
                .as("the control: both rows exist, and this count runs as the container's superuser, "
                        + "which bypasses every policy — so the zero below is the predicate filtering and "
                        + "not an empty table")
                .containsExactlyInAnyOrder(rowA.toString(), rowB.toString());

        exec(connection, "SET LOCAL ROLE " + APPLICATION_ROLE);
        exec(connection, "SELECT set_tenant_scope(?::uuid)", companyA.toString());

        assertThat(ids(connection, "SELECT id FROM company_countries WHERE id IN (?::uuid, ?::uuid)", rowA, rowB))
                .as("scoped to A, the table answers with A's rows only and no WHERE clause says so")
                .containsExactly(rowA.toString());
        assertThat(ids(connection, "SELECT id FROM company_countries WHERE id = ?::uuid", rowB))
                .as("and B's row is invisible even by primary key: fail-closed, not a wider scope " + "(invariant T3)")
                .isEmpty();
    }

    // ──────────────────────────────────────
    // 3. The write half: a row naming another tenant is refused, not filtered
    // ──────────────────────────────────────

    @Test
    @DisplayName("a cross-tenant insert is refused on the same table with SQLSTATE 42501")
    void aCrossTenantInsertIsRefused() throws SQLException {
        var connection = beginTransaction();
        var companyA = insertCompany(connection, "REFUSED-A");
        var companyB = insertCompany(connection, "REFUSED-B");
        var country = countryId(connection, "CO");

        exec(connection, "SET LOCAL ROLE " + APPLICATION_ROLE);
        exec(connection, "SELECT set_tenant_scope(?::uuid)", companyA.toString());

        var refusal = refusalInSavepoint(connection, """
                INSERT INTO company_countries (id, company_id, country_id)
                VALUES ('%s', '%s', '%s')
                """.formatted(UUID.randomUUID(), companyB, country));

        assertThat(refusal.getSQLState())
                .as("invariant T4: another tenant's company_id is refused by the WITH CHECK half of the "
                        + "policy, not stored and hidden afterwards")
                .isEqualTo("42501");

        exec(connection, "RESET ROLE");
        assertThat(rowCount(
                        connection,
                        "SELECT count(*) FROM company_countries WHERE company_id = ?::uuid",
                        companyB.toString()))
                .as("and the refused row stored nothing, read back as the superuser so the count is not "
                        + "the policy answering again")
                .isZero();
    }

    // ──────────────────────────────────────
    // 4. The one table whose key is not company_id: the root, isolated by its own id
    // ──────────────────────────────────────

    @Test
    @DisplayName("companies is isolated by its own id, which is also why the unscoped admin read is gone")
    void companiesIsIsolatedByItsOwnId() throws SQLException {
        var connection = beginTransaction();
        var companyA = insertCompany(connection, "ROOT-A");
        var companyB = insertCompany(connection, "ROOT-B");

        assertThat(ids(connection, "SELECT id FROM companies WHERE id IN (?::uuid, ?::uuid)", companyA, companyB))
                .as("the control: both roots exist, read as the superuser")
                .containsExactlyInAnyOrder(companyA.toString(), companyB.toString());

        exec(connection, "SET LOCAL ROLE " + APPLICATION_ROLE);
        exec(connection, "SELECT set_tenant_scope(?::uuid)", companyA.toString());

        assertThat(ids(connection, "SELECT id FROM companies WHERE id IN (?::uuid, ?::uuid)", companyA, companyB))
                .as("`companies` has no company_id column, so its predicate compares the row's own id to "
                        + "the scope — which is what makes the create path able to state a tenant at all")
                .containsExactly(companyA.toString());

        var refusal = refusalInSavepoint(connection, """
                INSERT INTO companies (id, company_key, company_name, rfc)
                VALUES ('%s', '%s', 'Root Of Another Tenant', '%s')
                """.formatted(companyB, keyOf("ROOT-C"), rfcOf()));

        assertThat(refusal.getSQLState())
                .as("a row whose id is another tenant is refused: the same predicate is USING and WITH "
                        + "CHECK, so `id` is both what you can read and what you may write")
                .isEqualTo("42501");
        assertThat(ids(connection, "SELECT id FROM companies WHERE id = ?::uuid", companyB))
                .as("and this is the cost T5b paid for it: an administrator's unscoped `findAll` on the "
                        + "list endpoint answered every company and now answers one tenant's, so the "
                        + "company list resolves one read per claim and a token with no claim gets an "
                        + "empty list instead of every company")
                .isEmpty();
    }

    // ──────────────────────────────────────
    // 5. The shared catalogs: a global row for everyone, an override only for its owner
    // ──────────────────────────────────────

    @Test
    @DisplayName("a shared row is readable by every tenant and its override only by the owner")
    void aSharedRowIsReadableByEveryTenantAndAnOverrideOnlyByItsOwner() throws SQLException {
        var connection = beginTransaction();
        var companyA = insertCompany(connection, "SHARED-A");
        var companyB = insertCompany(connection, "SHARED-B");
        var global = insertProfile(connection, null, "policy_shared_global");
        var override = insertProfile(connection, companyA, "policy_shared_override");

        exec(connection, "SET LOCAL ROLE " + APPLICATION_ROLE);
        exec(connection, "SELECT set_tenant_scope(?::uuid)", companyA.toString());

        assertThat(ids(
                        connection,
                        "SELECT id FROM product_attribute_profiles WHERE id IN (?::uuid, ?::uuid)",
                        global,
                        override))
                .as("S3's shared kind: the NULL owner means every tenant (ADR decision 5), and the "
                        + "override is readable by the tenant that owns it")
                .containsExactlyInAnyOrder(global.toString(), override.toString());

        exec(connection, "SELECT set_tenant_scope(?::uuid)", companyB.toString());

        assertThat(ids(
                        connection,
                        "SELECT id FROM product_attribute_profiles WHERE id IN (?::uuid, ?::uuid)",
                        global,
                        override))
                .as("and the override is invisible to another tenant while the global row stays: a policy "
                        + "that ignored the NULL owner would empty the shared catalog for everyone")
                .containsExactly(global.toString());
    }

    // ──────────────────────────────────────
    // 6. The one table that belongs to a person, not to a company
    // ──────────────────────────────────────

    @Test
    @DisplayName("user_preferences follows app.user_id, not the tenant")
    void userPreferencesFollowsTheSubjectAndNotTheTenant() throws SQLException {
        var connection = beginTransaction();
        var companyA = insertCompany(connection, "SUBJECT-A");
        var preferenceA = insertPreference(connection, "policy-subject-a", companyA);
        var preferenceB = insertPreference(connection, "policy-subject-b", companyA);

        exec(connection, "SET LOCAL ROLE " + APPLICATION_ROLE);
        exec(connection, "SELECT set_user_scope(?)", "policy-subject-a");

        assertThat(ids(
                        connection,
                        "SELECT id FROM user_preferences WHERE id IN (?::uuid, ?::uuid)",
                        preferenceA,
                        preferenceB))
                .as("the subject scope alone answers with that person's row: `user_preferences` is read "
                        + "during authentication, before any company is known (record S3)")
                .containsExactly(preferenceA.toString());

        exec(connection, "SELECT set_user_scope(?)", "policy-subject-b");

        assertThat(ids(
                        connection,
                        "SELECT id FROM user_preferences WHERE id IN (?::uuid, ?::uuid)",
                        preferenceA,
                        preferenceB))
                .as("another subject reads its own row and not this one")
                .containsExactly(preferenceB.toString());

        // A session that never carried a subject reads NULL, and one that set and committed one reads the
        // empty string (measured by V52's suite): both are "no subject", and the predicate compares a
        // VARCHAR, so both match no row. Stated explicitly so the case does not depend on which
        // connection the pool hands out.
        exec(connection, "SELECT set_config('app.user_id', '', true)");
        exec(connection, "SELECT set_tenant_scope(?::uuid)", companyA.toString());

        assertThat(ids(
                        connection,
                        "SELECT id FROM user_preferences WHERE id IN (?::uuid, ?::uuid)",
                        preferenceA,
                        preferenceB))
                .as("a tenant scope is not a substitute for the subject: with no subject stated the "
                        + "table answers nothing, even though both rows name this company")
                .isEmpty();
    }

    // ──────────────────────────────────────
    // 7. Invariant T5: the owner is not implicitly exempt
    // ──────────────────────────────────────

    @Test
    @DisplayName("the table's owner is filtered too: FORCE applies the policy to it as well")
    void theOwnerIsNotImplicitlyExempt() throws SQLException {
        var connection = beginTransaction();
        var companyA = insertCompany(connection, "OWNER-A");
        var companyB = insertCompany(connection, "OWNER-B");
        var country = countryId(connection, "US");
        var rowA = insertCompanyCountry(connection, companyA, country);

        exec(connection, "SET LOCAL ROLE " + APPLICATION_ROLE);
        exec(connection, "SELECT set_tenant_scope(?::uuid)", companyB.toString());

        assertThat(ids(connection, "SELECT id FROM company_countries WHERE id = ?::uuid", rowA))
                .as("the application role is filtered, which is what the previous cases already show")
                .isEmpty();

        // `FORCE` is a fact about the table's OWNER, and this container's tables are owned by its own
        // user, a superuser that bypasses every policy by attribute — the trap ADR-0005 removed in
        // delivery. So the ownership is moved to the role the delivery gives it, inside this rolled-back
        // transaction, which is the only way a non-superuser can be the owner here.
        exec(connection, "RESET ROLE");
        exec(connection, "GRANT CREATE ON SCHEMA public TO " + OWNER_ROLE);
        exec(connection, "ALTER TABLE company_countries OWNER TO " + OWNER_ROLE);
        exec(connection, "SET LOCAL ROLE " + OWNER_ROLE);

        exec(connection, "SELECT set_tenant_scope(?::uuid)", companyA.toString());
        assertThat(ids(connection, "SELECT id FROM company_countries WHERE id = ?::uuid", rowA))
                .as("the owner reads its own tenant's row, so the zero below is the policy and not a "
                        + "`permission denied`")
                .containsExactly(rowA.toString());

        exec(connection, "SELECT set_tenant_scope(?::uuid)", companyB.toString());
        assertThat(ids(connection, "SELECT id FROM company_countries WHERE id = ?::uuid", rowA))
                .as("invariant T5: with another tenant's scope the OWNER reads zero rows. Without FORCE "
                        + "it would read A's row — policies do not apply to a table's owner — and the "
                        + "role that runs every migration would see every tenant")
                .isEmpty();
    }

    // ──────────────────────────────────────
    // helpers
    // ──────────────────────────────────────

    private record ArmedTable(String table, String kind, String keyColumn) {}

    private record Policy(String name, String command, String predicate, String withCheck) {}

    /**
     * One connection per case at most, opened here and closed below.
     *
     * <p>The container's <b>own</b> login on purpose: this suite's premise is a connection that bypasses the
     * policies, and since T6b-4 the pool's does not. The role is therefore a parameter of this suite and not
     * an ambient fact — the same reason the cases switch down to {@code lifecontrol_app} with an explicit
     * {@code SET LOCAL ROLE} when the policy is what they want to observe.</p>
     */
    private Connection beginTransaction() throws SQLException {
        transaction =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        transaction.setAutoCommit(false);
        return transaction;
    }

    /** The two {@code pg_class} flags, in the order ENABLE then FORCE. Read as the superuser. */
    private List<Boolean> rlsFlags(String table) {
        return jdbcTemplate
                .query(
                        "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = ?::regclass",
                        (result, index) -> List.of(result.getBoolean(1), result.getBoolean(2)),
                        table)
                .get(0);
    }

    private List<Policy> policiesOn(String table) {
        return jdbcTemplate.query(
                """
                SELECT policyname, cmd, qual, with_check
                  FROM pg_policies
                 WHERE schemaname = 'public' AND tablename = ?
                """,
                (result, index) ->
                        new Policy(result.getString(1), result.getString(2), result.getString(3), result.getString(4)),
                table);
    }

    /** Every row of {@code tenant_table}, as {@code table -> kind:key}, read as the superuser. */
    private Map<String, String> listedTables() {
        return jdbcTemplate.query("SELECT table_name::text, policy_kind, key_column FROM tenant_table", result -> {
            var listed = new HashMap<String, String>();
            while (result.next()) {
                listed.put(result.getString(1), result.getString(2) + ":" + result.getString(3));
            }
            return listed;
        });
    }

    private static void exec(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.execute();
        }
    }

    private static List<String> ids(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var result = statement.executeQuery()) {
                var values = new ArrayList<String>();
                while (result.next()) {
                    values.add(result.getString(1));
                }
                return values;
            }
        }
    }

    private static int rowCount(Connection connection, String sql, String parameter) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /**
     * Runs {@code sql} under a savepoint and returns the refusal it produced, rolling back to the
     * savepoint so the caller can keep reading the same transaction. PostgreSQL aborts the whole
     * transaction on a refused statement, so without the savepoint there would be nothing left to check.
     */
    private static SQLException refusalInSavepoint(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SAVEPOINT before_refusal");
            SQLException refusal;
            try {
                statement.execute(sql);
                throw new AssertionError("the write was accepted and should have been refused: " + sql);
            } catch (SQLException refused) {
                refusal = refused;
            }
            statement.execute("ROLLBACK TO SAVEPOINT before_refusal");
            return refusal;
        }
    }

    // ─── fixtures: the NOT NULL columns each row asks for, and nothing else ───

    /** A company, which is also a tenant in this schema: the root every other fixture hangs from. */
    private static UUID insertCompany(Connection connection, String tag) throws SQLException {
        var id = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO companies (id, company_key, company_name, rfc) VALUES (?::uuid, ?, ?, ?)",
                id,
                keyOf(tag),
                "Company " + tag,
                rfcOf());
        return id;
    }

    /** A seeded country, so a `company_countries` fixture needs no second insert. */
    private static UUID countryId(Connection connection, String countryCode) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM countries WHERE country_code = ?")) {
            statement.setString(1, countryCode);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).as("V2 seeds %s", countryCode).isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static UUID insertCompanyCountry(Connection connection, UUID companyId, UUID countryId)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO company_countries (id, company_id, country_id) VALUES (?::uuid, ?::uuid, ?::uuid)",
                id,
                companyId,
                countryId);
        return id;
    }

    private static UUID insertProfile(Connection connection, UUID companyId, String profileKey) throws SQLException {
        var id = UUID.randomUUID();
        if (companyId == null) {
            exec(
                    connection,
                    "INSERT INTO product_attribute_profiles (id, profile_key, label) VALUES (?::uuid, ?, 'Global')",
                    id,
                    profileKey);
        } else {
            exec(
                    connection,
                    "INSERT INTO product_attribute_profiles (id, company_id, profile_key, label) "
                            + "VALUES (?::uuid, ?::uuid, ?, 'Override')",
                    id,
                    companyId,
                    profileKey);
        }
        return id;
    }

    private static UUID insertPreference(Connection connection, String keycloakUserId, UUID companyId)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO user_preferences (id, keycloak_user_id, company_id) VALUES (?::uuid, ?, ?::uuid)",
                id,
                keycloakUserId,
                companyId);
        return id;
    }

    /** {@code company_key} is {@code NOT NULL UNIQUE} and {@code rfc} is 13 characters at most. */
    private static String keyOf(String tag) {
        return "KEY-" + tag + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    private static String rfcOf() {
        return ("RFC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)).toUpperCase();
    }
}
