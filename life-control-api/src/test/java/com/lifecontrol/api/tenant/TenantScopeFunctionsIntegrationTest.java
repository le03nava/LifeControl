package com.lifecontrol.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verification of ADR-0005's <b>machinery</b> — the scope functions, the tenant-table list with its three
 * kinds, {@code apply_tenant_isolation} and the query the startup assertion will run — against real
 * PostgreSQL with Flyway enabled, which is the only stack where Row-Level Security exists at all.
 *
 * <p>What this suite deliberately does <b>not</b> assert is a policy on a real table: the record
 * {@code odd/tasks/tenant-isolation-rls.md} puts the policies in <b>T5</b> and the transaction scope in
 * <b>T4</b>, because a policy without a scope reads zero rows everywhere. The machinery lands first, and
 * this is the slice that proves it is correct without it being armed.</p>
 *
 * <p>The two cases that carry the record's weight:</p>
 *
 * <ul>
 *   <li>{@code unprotectedTenantTablesAreExactlyTheFifteenTheRecordNames} is the <b>regression pin</b>.
 *       S2 decided the policy set is «solo las 15 tablas que ya tienen company_id», so the list is frozen
 *       literally here: a sixteenth table that gains the column shows up in this assertion instead of
 *       being either silently protected or silently unprotected. When T5 applies the policies the same
 *       assertion flips to empty — the pin is the same fact from the other side.</li>
 *   <li>{@code theScopeSettingsDieWithTheTransaction} is invariant <b>T6</b> in the shape T2 can observe
 *       it: the value is readable inside the transaction that set it and {@code NULL} after the commit,
 *       so a pooled connection cannot carry one tenant into the next request.</li>
 * </ul>
 *
 * <p>Every test that creates anything does so inside one transaction it rolls back, in the container all
 * 64 sibling suites share: a leftover table, policy or list row would leak into them, and a leftover row
 * in {@code tenant_table} would make "the list is empty right after the migration" false for the next
 * reader of this suite. The probe tables are generated from a lower-case alphabet, so no identifier here
 * needs quoting.</p>
 */
@SpringBootTest
@DisplayName("Tenant scope functions and the isolation list")
class TenantScopeFunctionsIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The fifteen tables S2 names, frozen literally: this is the regression pin, not a convenience. */
    private static final List<String> S2_TENANT_TABLES = List.of(
            "company_countries",
            "company_regions",
            "company_stores",
            "company_zones",
            "departments",
            "employees",
            "product_attribute_profile_roots",
            "product_attribute_profiles",
            "product_categories",
            "product_suppliers",
            "product_variants",
            "products",
            "purchase_orders",
            "suppliers",
            "user_preferences");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /** One connection per test at most, opened with {@link #beginTransaction()} and closed below. */
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
    // The scope: two settings, both transaction-local
    // ──────────────────────────────────────

    @Test
    @DisplayName("the scope settings are readable inside the transaction and gone after the commit")
    void theScopeSettingsDieWithTheTransaction() throws SQLException {
        var connection = beginTransaction();

        exec(connection, "SELECT set_tenant_scope(?::uuid)", COMPANY_A.toString());
        exec(connection, "SELECT set_user_scope(?)", "subject-tenant-scope");

        assertThat(setting(connection, "app.company_id"))
                .as("a tenant set inside the transaction is what the policies read")
                .isEqualTo(COMPANY_A.toString());
        assertThat(setting(connection, "app.user_id"))
                .as("the subject scope S3 needs for user_preferences")
                .isEqualTo("subject-tenant-scope");

        connection.commit();

        assertThat(setting(connection, "app.company_id"))
                .as("invariant T6: `true` in set_config is SET LOCAL, so the pooled connection cannot "
                        + "carry this tenant into the next request. Measured: PostgreSQL resets the setting to "
                        + "the empty string — not to NULL — on a session that once set it, so the assertion "
                        + "is `null or empty`, and T4 owns what a transaction without a scope then does")
                .isNullOrEmpty();
        assertThat(setting(connection, "app.user_id"))
                .as("the subject scope is transaction-local for the same reason")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("a session that never set the scope reads NULL, not an empty value")
    void aSessionThatNeverSetTheScopeReadsNull() throws SQLException {
        // A brand-new session on purpose: a pooled connection that once set the scope keeps the parameter
        // defined for the rest of its life, and PostgreSQL then resets it to the empty string instead of
        // to NULL — measured, and pinned by the T6 case above. The pool therefore cannot answer this
        // question, which is the whole reason ADR decision 2 passes `true` as current_setting's second
        // argument: a parameter nobody defined is NULL, and a NULL predicate filters the row.
        try (var session = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(setting(session, "app.company_id"))
                    .as("a missing setting is NULL rather than an error, so a transaction without a scope "
                            + "reads no rows instead of failing to parse the predicate")
                    .isNull();
        }
    }

    // ──────────────────────────────────────
    // The list: empty, and closed to a fourth kind
    // ──────────────────────────────────────

    @Test
    @DisplayName("the list is empty right after the migration: it is written by the policy, not by hand")
    void theListIsEmptyRightAfterTheMigration() {
        assertThat(countOfTenantTableRows())
                .as("a pre-populated row would be a table claimed as protected without a policy, so "
                        + "`listed` and `protected` could drift apart")
                .isZero();
    }

    @Test
    @DisplayName("the list refuses a fourth kind with the constraint the vocabulary is stated in")
    void aFourthPolicyKindIsRefusedByTheCheckConstraint() throws SQLException {
        var connection = beginTransaction();
        var probe = createProbeTable(connection, "kind", "id uuid PRIMARY KEY");

        var refusal = refusalInSavepoint(connection, """
                INSERT INTO tenant_table (table_name, policy_kind) VALUES ('%s'::regclass, 'global')
                """.formatted(probe));

        assertThat(refusal.getMessage())
                .as("the three kinds are the closed vocabulary the record decided, not a convention")
                .contains("tenant_table_policy_kind_closed");
        assertThat(countOfTenantTableRows(connection))
                .as("the refused row stored nothing")
                .isZero();
    }

    // ──────────────────────────────────────
    // The assertion query
    // ──────────────────────────────────────

    @Test
    @DisplayName("unprotected_tenant_tables() returns exactly the fifteen tables S2 names")
    void unprotectedTenantTablesAreExactlyTheFifteenTheRecordNames() {
        var unprotected = jdbcTemplate.queryForList(
                "SELECT t::text FROM unprotected_tenant_tables() t", String.class);

        assertThat(unprotected)
                .as("S2 fixed the policy set at the fifteen tables that already carry company_id; a "
                        + "sixteenth is a decision, and this assertion is where it gets made")
                .containsExactlyInAnyOrderElementsOf(S2_TENANT_TABLES);
    }

    // ──────────────────────────────────────
    // The privileges the application role needs
    // ──────────────────────────────────────

    @Test
    @DisplayName("lifecontrol_app holds SELECT/INSERT/UPDATE/DELETE on every table")
    void theApplicationRoleHoldsTheTableGrants() {
        var tablesWithoutTheGrant = jdbcTemplate.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public'
                   AND c.relkind = 'r'
                   AND NOT has_table_privilege('lifecontrol_app', c.oid, 'SELECT,INSERT,UPDATE,DELETE')
                 ORDER BY c.relname
                """, String.class);

        assertThat(tablesWithoutTheGrant)
                .as("ADR decision 3: the pool's role writes and reads, so a table without the grant is a "
                        + "runtime `permission denied`, not a policy refusal")
                .isEmpty();
    }

    @Test
    @DisplayName("every sequence lifecontrol_app could touch is granted USAGE and SELECT")
    void theApplicationRoleHoldsTheSequenceGrants() {
        // The CASE is not decoration: SQL does not fix the evaluation order of WHERE clauses, and
        // `pg_class` holds the TOAST sequences of every table with a large column in the `pg_toast`
        // namespace. Without the guard PostgreSQL calls has_sequence_privilege on one of those first and
        // the query fails with `"pg_toast_16387" is not a sequence` — measured, not assumed.
        var sequencesWithoutTheGrant = jdbcTemplate.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE c.relkind = 'S'
                   AND n.nspname = 'public'
                   AND NOT CASE
                             WHEN n.nspname = 'public' AND c.relkind = 'S'
                             THEN has_sequence_privilege('lifecontrol_app', c.oid, 'USAGE,SELECT')
                             ELSE true
                           END
                 ORDER BY c.relname
                """, String.class);

        // The tree's identifiers are all UUIDs today, so this set is empty and the GRANT ... ON ALL
        // SEQUENCES is unobservable here. The observable half of the sequence contract is the next test:
        // a sequence created AFTER the migration inherits the grant, which is what makes an identity
        // column work on a table added by a later migration (ADR correction 2 and invariant T8).
        assertThat(sequencesWithoutTheGrant)
                .as("a sequence created before the migration is granted, or there is none to grant")
                .isEmpty();
    }

    @Test
    @DisplayName("a table and a sequence created after the migration inherit the grants")
    void objectsCreatedLaterInheritTheGrants() throws SQLException {
        var connection = beginTransaction();
        var probe = createProbeTable(connection, "later", "id uuid PRIMARY KEY, company_id uuid NOT NULL");
        exec(connection, "CREATE SEQUENCE " + probe + "_seq");

        assertThat(booleanQuery(connection,
                "SELECT has_table_privilege('lifecontrol_app', ?::regclass, 'SELECT,INSERT,UPDATE,DELETE')",
                probe))
                .as("ALTER DEFAULT PRIVILEGES ... FOR ROLE CURRENT_USER: a hardcoded lifecontrol_owner "
                        + "would grant for a role that is not the owner here, and the grant would be inert")
                .isTrue();
        assertThat(booleanQuery(connection,
                "SELECT has_sequence_privilege('lifecontrol_app', ?::regclass, 'USAGE,SELECT')",
                probe + "_seq"))
                .as("the sequence grant has an ALTER DEFAULT PRIVILEGES counterpart, so an INSERT whose "
                        + "id comes from an identity column does not fail with `permission denied for sequence`")
                .isTrue();
    }

    // ──────────────────────────────────────
    // The helper, one case per kind
    // ──────────────────────────────────────

    @Test
    @DisplayName("the tenant kind enables and forces RLS, creates one policy and writes the list row")
    void theTenantKindArmsTheTableAndWritesTheListRow() throws SQLException {
        var connection = beginTransaction();
        var probe = createProbeTable(connection, "tenant", "id uuid PRIMARY KEY, company_id uuid NOT NULL");

        exec(connection, "SELECT apply_tenant_isolation('%s'::regclass)".formatted(probe));

        assertThat(rlsFlags(connection, probe))
                .as("ENABLE is not enough: FORCE is what keeps the owner — Flyway — from being exempt, "
                        + "which is ADR decision 3 and invariant T1")
                .containsExactly(true, true);

        var policy = onlyPolicyOn(connection, probe);
        assertThat(policy.name()).isEqualTo("p_tenant_" + probe);
        assertThat(policy.command()).isEqualTo("ALL");
        assertThat(policy.using())
                .as("the predicate is the tenant comparison the ADR fixes")
                .contains("company_id")
                .contains("current_setting('app.company_id'::text, true)")
                .contains("::uuid");
        assertThat(policy.withCheck())
                .as("USING and WITH CHECK are the same predicate, so a write cannot name another tenant")
                .isEqualTo(policy.using());

        assertThat(listRow(connection, probe))
                .as("the list row is written by the call that creates the policy, so `listed` and "
                        + "`protected` cannot drift")
                .containsExactly("tenant", "company_id");
    }

    @Test
    @DisplayName("the shared kind keeps the global row readable: company_id IS NULL OR the tenant")
    void theSharedKindKeepsGlobalRowsVisible() throws SQLException {
        var connection = beginTransaction();
        var probe = createProbeTable(connection, "shared", "id uuid PRIMARY KEY, company_id uuid");

        exec(connection, "SELECT apply_tenant_isolation('%s'::regclass, 'shared')".formatted(probe));

        var policy = onlyPolicyOn(connection, probe);
        assertThat(policy.using())
                .as("ADR decision 5: a NULL owner means \"every tenant\", and a policy that ignored it "
                        + "would empty the shared catalog")
                .contains("company_id IS NULL")
                .contains("OR")
                .contains("current_setting('app.company_id'::text, true)");
        assertThat(policy.withCheck()).isEqualTo(policy.using());
        assertThat(listRow(connection, probe)).containsExactly("shared", "company_id");
    }

    @Test
    @DisplayName("the subject kind compares the key to app.user_id and casts nothing")
    void theSubjectKindComparesTheSubjectWithoutACast() throws SQLException {
        var connection = beginTransaction();
        var probe = createProbeTable(connection, "subject", "id uuid PRIMARY KEY, keycloak_user_id varchar(36) NOT NULL");

        exec(connection, """
                SELECT apply_tenant_isolation('%s'::regclass, 'subject', 'keycloak_user_id')
                """.formatted(probe));

        var policy = onlyPolicyOn(connection, probe);
        assertThat(policy.name()).isEqualTo("p_tenant_" + probe);
        assertThat(policy.using())
                .as("S3: user_preferences is isolated by subject, and its key is a VARCHAR, so a uuid "
                        + "cast would fail on the first read during authentication")
                .contains("keycloak_user_id")
                .contains("current_setting('app.user_id'::text, true)")
                .doesNotContain("::uuid");
        assertThat(policy.withCheck()).isEqualTo(policy.using());
        assertThat(listRow(connection, probe)).containsExactly("subject", "keycloak_user_id");
    }

    @Test
    @DisplayName("a second call replaces the policy instead of stacking one, and updates the list row")
    void theCallIsIdempotentOnASecondRun() throws SQLException {
        var connection = beginTransaction();
        var probe = createProbeTable(connection, "idempotent", "id uuid PRIMARY KEY, company_id uuid NOT NULL");

        exec(connection, "SELECT apply_tenant_isolation('%s'::regclass)".formatted(probe));
        exec(connection, "SELECT apply_tenant_isolation('%s'::regclass)".formatted(probe));

        assertThat(countQuery(connection,
                "SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ?", probe))
                .as("CREATE POLICY would refuse a second policy of the same name, and a stack of two "
                        + "would be an AND nobody wrote")
                .isEqualTo(1);
        assertThat(countQuery(connection,
                "SELECT count(*) FROM tenant_table WHERE table_name = ?::regclass", probe))
                .as("the upsert updates the row rather than adding a second one")
                .isEqualTo(1);
        assertThat(listRow(connection, probe)).containsExactly("tenant", "company_id");
    }

    // ──────────────────────────────────────
    // The pool, not a fresh session: one committed local set, then the next request
    // ──────────────────────────────────────

    @Test
    @DisplayName("a session that committed a scope once reads zero tenant rows, not SQLSTATE 22P02")
    void aCommittedLocalScopeOnAReusedSessionFiltersTenantRows() throws SQLException {
        // The connection a pool hands the second request is the one the first request used, which is why
        // this case never opens a second session: `set_config(..., true)` dies with its transaction, but
        // the PARAMETER stays defined on the session and PostgreSQL resets it to the empty string. That is
        // the ordinary steady state of a pool after its first request, and a bare `::uuid` cast on it
        // raises `invalid input syntax for type uuid: ""` instead of filtering — fail-loud where ADR
        // decision 2 promises fail-closed.
        var session = beginTransaction();

        // ── request 1: a scoped transaction, committed.
        exec(session, "SELECT set_tenant_scope(?::uuid)", COMPANY_A.toString());
        session.commit();
        assertThat(setting(session, "app.company_id"))
                .as("the tenant itself is gone after the commit; the parameter is not, and it is empty")
                .isNullOrEmpty();

        // ── request 2: same session, no scope, and a tenant table whose policy is live.
        var probe = createProbeTable(session, "reuse", "id uuid PRIMARY KEY, company_id uuid NOT NULL");
        insertProbeRow(session, probe, UUID.randomUUID(), COMPANY_A);
        exec(session, "SELECT apply_tenant_isolation('%s'::regclass)".formatted(probe));

        assertThat(rowCount(session, "SELECT count(*) FROM " + probe))
                .as("the control: the row exists, and this count runs as the superuser, which bypasses "
                        + "the policy — so a zero below is the predicate filtering and not an empty table")
                .isEqualTo(1);

        exec(session, "SET ROLE lifecontrol_app");
        assertThat(rowCount(session, "SELECT count(*) FROM " + probe))
                .as("zero rows, not 22P02: the assertion itself fails with the driver's "
                        + "`invalid input syntax for type uuid: \"\"` when the predicate casts without "
                        + "nullif, which is the defect this case exists to pin")
                .isZero();
        exec(session, "RESET ROLE");
    }

    @Test
    @DisplayName("the same reuse on a shared table keeps the global row and drops another tenant's")
    void aCommittedLocalScopeOnAReusedSessionKeepsOnlyTheGlobalSharedRow() throws SQLException {
        // `shared` carries the same cast and therefore the same trap, and its extra `IS NULL` arm is not
        // protection: `NULL OR NULL` is NULL, so the global row filters out as well when the cast has no
        // row to evaluate. A tenant predicate that raises here reads as a broken shared catalog.
        var session = beginTransaction();

        exec(session, "SELECT set_tenant_scope(?::uuid)", COMPANY_A.toString());
        session.commit();

        var probe = createProbeTable(session, "reuseshared", "id uuid PRIMARY KEY, company_id uuid");
        var globalRow = UUID.randomUUID();
        insertProbeRow(session, probe, globalRow, null);
        insertProbeRow(session, probe, UUID.randomUUID(), COMPANY_B);
        exec(session, "SELECT apply_tenant_isolation('%s'::regclass, 'shared')".formatted(probe));

        assertThat(rowCount(session, "SELECT count(*) FROM " + probe))
                .as("the control: the global row and another tenant's row are both stored")
                .isEqualTo(2);

        exec(session, "SET ROLE lifecontrol_app");
        assertThat(ids(session, "SELECT id FROM " + probe))
                .as("the NULL owner means every tenant (ADR decision 5), and the other tenant's row is "
                        + "not this transaction's to read")
                .containsExactly(globalRow.toString());
        exec(session, "RESET ROLE");
    }

    // ──────────────────────────────────────
    // The refusals: a wrong call must not create a policy that silently matches nothing
    // ──────────────────────────────────────

    @Test
    @DisplayName("a table without the key column is refused before anything is enabled or listed")
    void aTableWithoutTheKeyColumnIsRefused() throws SQLException {
        var connection = beginTransaction();
        var probe = createProbeTable(connection, "nokey", "id uuid PRIMARY KEY");

        var refusal = refusalInSavepoint(connection,
                "SELECT apply_tenant_isolation('%s'::regclass)".formatted(probe));

        assertThat(refusal.getMessage())
                .as("the message names the table and the column, because `0 rows` is the failure this "
                        + "function exists to make impossible")
                .contains(probe)
                .contains("has no column company_id");
        assertThat(rlsFlags(connection, probe))
                .as("the check runs before the ALTER, so a refused call leaves the table untouched")
                .containsExactly(false, false);
        assertThat(countQuery(connection,
                "SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ?", probe))
                .isZero();
        assertThat(countOfTenantTableRows(connection))
                .as("and writes no list row")
                .isZero();
    }

    @Test
    @DisplayName("an unknown kind is refused by name, and nothing is created for it")
    void anUnknownKindIsRefused() throws SQLException {
        var connection = beginTransaction();
        var probe = createProbeTable(connection, "badkind", "id uuid PRIMARY KEY, company_id uuid NOT NULL");

        var refusal = refusalInSavepoint(connection, """
                SELECT apply_tenant_isolation('%s'::regclass, 'tenantt')
                """.formatted(probe));

        assertThat(refusal.getMessage())
                .as("a typo in the kind is a refusal, never a silently different predicate")
                .contains("unknown policy kind tenantt")
                .contains("tenant, shared, subject");
        assertThat(rlsFlags(connection, probe)).containsExactly(false, false);
        assertThat(countOfTenantTableRows(connection)).isZero();
    }

    // ──────────────────────────────────────
    // helpers
    // ──────────────────────────────────────

    private record PolicyRow(String name, String command, String using, String withCheck) {}

    private static final UUID COMPANY_A = UUID.fromString("0a000000-0000-4000-8000-00000000000a");

    /**
     * A second company, so a case can be "this tenant's row is filtered and mine is not".
     */
    private static final UUID COMPANY_B = UUID.fromString("0b000000-0000-4000-8000-00000000000b");

    private Connection beginTransaction() throws SQLException {
        transaction = dataSource.getConnection();
        transaction.setAutoCommit(false);
        return transaction;
    }

    private static void exec(Connection connection, String sql, String... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            statement.execute();
        }
    }

    private static String setting(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT current_setting(?::text, true)")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static int countQuery(Connection connection, String sql, String parameter) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static boolean booleanQuery(Connection connection, String sql, String parameter) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static int rowCount(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static List<String> ids(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            var values = new ArrayList<String>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    /** The two {@code pg_class} flags, in the order ENABLE then FORCE. */
    private static List<Boolean> rlsFlags(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = ?::regclass")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("no such table: " + table);
                }
                return List.of(result.getBoolean(1), result.getBoolean(2));
            }
        }
    }

    private static PolicyRow onlyPolicyOn(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT policyname, cmd, qual, with_check
                  FROM pg_policies
                 WHERE schemaname = 'public' AND tablename = ?
                """)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                var rows = new ArrayList<PolicyRow>();
                while (result.next()) {
                    rows.add(new PolicyRow(
                            result.getString(1), result.getString(2), result.getString(3), result.getString(4)));
                }
                assertThat(rows)
                        .as("one policy for the table, named after it")
                        .hasSize(1);
                return rows.get(0);
            }
        }
    }

    private static List<String> listRow(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT policy_kind, key_column FROM tenant_table WHERE table_name = ?::regclass")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).as("the call wrote a list row for %s", table).isTrue();
                return List.of(result.getString(1), result.getString(2));
            }
        }
    }

    private int countOfTenantTableRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_table", Integer.class);
    }

    private static int countOfTenantTableRows(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM tenant_table")) {
            result.next();
            return result.getInt(1);
        }
    }

    /**
     * Creates a throwaway table for one test. The name is generated from a lower-case alphabet so it
     * needs no quoting, and the transaction the caller rolled back takes the table with it.
     */
    private static String createProbeTable(Connection connection, String tag, String columns) throws SQLException {
        var name = "probe_" + tag + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        exec(connection, "CREATE TABLE " + name + " (" + columns + ")");
        return name;
    }

    /** Inserts one row whose owner may be NULL, which is what the `shared` kind is about. */
    private static void insertProbeRow(Connection connection, String table, UUID id, UUID companyId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO " + table + " (id, company_id) VALUES (?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, companyId);
            statement.execute();
        }
    }

    /**
     * Runs {@code sql} under a savepoint and returns the refusal it produced, rolling back to the
     * savepoint so the caller can keep reading the same transaction. Asserting what a refused call did
     * <b>not</b> change is the point: PostgreSQL aborts the whole transaction on a {@code RAISE}, so
     * without the savepoint there would be nothing left to look at.
     */
    private static SQLException refusalInSavepoint(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SAVEPOINT before_refusal");
            SQLException refusal;
            try {
                statement.execute(sql);
                throw new AssertionError("the call was accepted and should have been refused: " + sql);
            } catch (SQLException refused) {
                refusal = refused;
            }
            statement.execute("ROLLBACK TO SAVEPOINT before_refusal");
            return refusal;
        }
    }
}
