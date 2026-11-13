package com.lifecontrol.api.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verification of {@code V97__accounting_book.sql} — S1, S7 and S8 of
 * {@code odd/tasks/derived-accounting.md} (T1) — against real PostgreSQL with Flyway enabled, which is the
 * only stack where Row-Level Security, composite foreign keys and partial unique indexes exist at all.
 *
 * <p><b>What each case proves, and why the database is where it has to be proved.</b> Every rule this slice
 * states is enforced by the schema and by nothing else: a table is armed by {@code apply_tenant_isolation},
 * a pointer carries its tenant through a composite foreign key, and a cost center is unique through two
 * partial indexes. A service cannot demonstrate any of them — the application role the pool logs in as is
 * filtered by the policy it is trying to observe, and a referential check bypasses row security precisely
 * where S7 says it must not.</p>
 *
 * <p><b>Which role each case runs as.</b> The structural cases read {@code pg_class}, {@code pg_policies},
 * {@code information_schema} and {@code tenant_table}, which the application role may read like any other.
 * The behavioural cases need a connection the policies do <b>not</b> filter, because their fixtures have to
 * exist before a policy could hide them and their controls ("the row is there, so the refusal below is the
 * constraint and not a zero") only mean something unfiltered — so they open the container's own login,
 * {@code lifecontrol}, a superuser, exactly as {@code TenantPoliciesIntegrationTest} does and for the same
 * reason. Everything those cases write is rolled back in {@link #rollBackEverythingThisTestWrote()}, because
 * sixty-odd sibling suites share this container and none of them asked for these rows.</p>
 *
 * <p><b>What this class deliberately does not cover.</b> The rules the book cannot break — the append-only
 * pair (S2), the deferred balance and minimum-two-lines triggers (S3, S4), the row {@code CHECK}s (S5) and
 * the one-posting-per-source key (S6) — are the subject of the second pull request of this slice, and their
 * tests arrive with them: a case here would assert a constraint this migration does not create. The seed,
 * the posting rules, the period close and the read models (S9–S18, T4–T12) are later slices of the same
 * unit, and their tests belong with them.</p>
 */
@SpringBootTest
@DisplayName("Accounting schema integration tests")
class AccountingSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The six tables of S1, in the order the migration creates them. */
    private static final List<String> ACCOUNTING_TABLES = List.of(
            "account", "cost_center", "accounting_period", "posting_rule", "account_entry", "account_entry_line");

    @Autowired
    private Flyway flyway;

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
    // The head
    // ──────────────────────────────────────

    @Test
    @DisplayName("applies every migration on the classpath and leaves no pending migration")
    void flywayHeadIsTheLatestMigration() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
        assertThat(flyway.info().pending()).isEmpty();
    }

    // ──────────────────────────────────────
    // S1 — six tenant tables, each carrying its own company
    // ──────────────────────────────────────

    @Test
    @DisplayName("S1: the six accounting tables exist and each carries company_id")
    void theSixAccountingTablesExistAndEachCarriesCompanyId() {
        for (var table : ACCOUNTING_TABLES) {
            var tables = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class,
                    table);
            assertThat(tables).as("table %s exists", table).isEqualTo(1);

            var companyColumns = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = ? AND column_name = 'company_id'
                       AND is_nullable = 'NO'
                    """, Integer.class, table);
            assertThat(companyColumns)
                    .as(
                            "%s: company_id is the column every policy on this book reads, and S1 makes it a "
                                    + "column of the row rather than a fact derived through a parent",
                            table)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("S1: every accounting table is forced-armed with exactly one tenant policy over company_id")
    void everyAccountingTableIsArmedWithOneForcedTenantPolicy() {
        var listed = listedTables();

        for (var table : ACCOUNTING_TABLES) {
            assertThat(rlsFlags(table))
                    .as(
                            "%s: ENABLE turns the policy on and FORCE stops the owner — the role Flyway "
                                    + "migrates as — from being exempt (ADR-0005)",
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
                    .as("%s: the predicate compares company_id, the key the list names", table)
                    .contains("company_id");
            assertThat(policy.withCheck())
                    .as(
                            "%s: USING and WITH CHECK are the same predicate, so a write cannot name a "
                                    + "tenant the reader could not see",
                            table)
                    .isEqualTo(policy.predicate());

            assertThat(listed)
                    .as(
                            "%s: the registry row the startup assertion reads, with the kind and key the "
                                    + "policy was generated from",
                            table)
                    .containsEntry(table, "tenant:company_id");
        }
    }

    @Test
    @DisplayName("S1: a line cannot carry a company its entry does not belong to")
    void aLineCannotCarryACompanyItsEntryDoesNotBelongTo() throws SQLException {
        var connection = beginTransaction();
        var companyA = insertCompany(connection, "LINE-A");
        var companyB = insertCompany(connection, "LINE-B");
        var accountA = insertAccount(connection, companyA, "A1");
        // The line states company B and an account of B, so the *only* pointer that disagrees with the row
        // is the entry: a generic refusal would not say which pointer the schema closed.
        var accountB = insertAccount(connection, companyB, "B1");
        var entryA = insertEntry(connection, companyA);

        var refusal =
                refusal(connection, """
                INSERT INTO account_entry_line
                    (id, company_id, entry_id, account_id, debit, credit, currency)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 100.0000, 0, ?)
                """, UUID.randomUUID(), companyB, entryA, accountB, TenantFixtures.SEED_CURRENCY);

        assertThat(refusal.getMessage())
                .as("A7: company_id is not derivable through the entry. The line carries its own company "
                        + "and the composite key to account_entry refuses the row where the two "
                        + "disagree — an id-only pointer would have written a line of B against an "
                        + "entry of A, which no policy on either table can see")
                .contains("violates foreign key constraint \"fk_account_entry_line_entry\"")
                .contains("Detail: Key (company_id, entry_id)=(%s, %s) is not present in table \"account_entry\""
                        .formatted(companyB, entryA));
        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("the refused row stored nothing")
                .isZero();

        insertLine(connection, companyA, entryA, accountA, null);

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("the same row with the entry's own company is what the schema accepts")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // S7 — a pointer that bypasses row security is never an id
    // ──────────────────────────────────────

    @Test
    @DisplayName("S7: a cost center cannot name another tenant's department and a line cannot name its entry, "
            + "its account or its cost center")
    void aPointerToAnotherTenantsRowIsRefusedByItsCompositeKey() throws SQLException {
        var connection = beginTransaction();
        var companyA = insertCompany(connection, "POINTER-A");
        var companyB = insertCompany(connection, "POINTER-B");
        var departmentA = insertDepartment(connection, companyA, "PTR", "Pointers A");
        var departmentB = insertDepartment(connection, companyB, "PTR", "Pointers B");

        var refusedDepartment = refusal(connection, """
                INSERT INTO cost_center (id, company_id, code, name, department_id)
                VALUES (?::uuid, ?::uuid, 'A-PTR', 'Center of A naming B', ?::uuid)
                """, UUID.randomUUID(), companyA, departmentB);

        assertThat(refusedDepartment.getMessage())
                .as("A11: a referential check bypasses row security, so an id-only pointer to the "
                        + "department catalog would let one tenant name another's department and no "
                        + "policy could refuse it")
                .contains("fk_cost_center_department");
        assertThat(rowCount(connection, "SELECT COUNT(*) FROM cost_center")).isZero();

        var costCenterA = insertCostCenter(connection, companyA, departmentA, "A-CC");
        var costCenterB = insertCostCenter(connection, companyB, departmentB, "B-CC");
        var accountA = insertAccount(connection, companyA, "A1");
        var accountB = insertAccount(connection, companyB, "B1");
        var entryA = insertEntry(connection, companyA);
        var entryB = insertEntry(connection, companyB);

        assertThat(refusal(
                                connection,
                                lineSql(),
                                UUID.randomUUID(),
                                companyA,
                                entryB,
                                accountA,
                                null,
                                TenantFixtures.SEED_CURRENCY)
                        .getMessage())
                .as("A7: a line of A naming an entry of B")
                .contains("fk_account_entry_line_entry");

        assertThat(refusal(
                                connection,
                                lineSql(),
                                UUID.randomUUID(),
                                companyA,
                                entryA,
                                accountB,
                                null,
                                TenantFixtures.SEED_CURRENCY)
                        .getMessage())
                .as("A9: a line of A naming an account of B")
                .contains("fk_account_entry_line_account");

        assertThat(refusal(
                                connection,
                                lineSql(),
                                UUID.randomUUID(),
                                companyA,
                                entryA,
                                accountA,
                                costCenterB,
                                TenantFixtures.SEED_CURRENCY)
                        .getMessage())
                .as("A9: a line of A naming a cost center of B")
                .contains("fk_account_entry_line_cost_center");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("three refused writes stored nothing")
                .isZero();

        insertLine(connection, companyA, entryA, accountA, costCenterA);
        insertLine(connection, companyB, entryB, accountB, costCenterB);

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("the same rows, each naming its own tenant's catalog rows, are accepted")
                .isEqualTo(2);
    }

    // ──────────────────────────────────────
    // S8 — a center is unique per store and department, and per bare department
    // ──────────────────────────────────────

    @Test
    @DisplayName("S8: two centers for one store and department are refused, two storeless centers for one "
            + "department are refused, and two stores may each hold one")
    void aCostCenterIsUniquePerStoreAndDepartmentAndPerBareDepartment() throws SQLException {
        var connection = beginTransaction();
        var companyId = insertCompany(connection, "CENTER");
        var departmentId = insertDepartment(connection, companyId, "CC", "Cost centers");
        var otherDepartmentId = insertDepartment(connection, companyId, "CC2", "Other centers");
        var zoneId = insertZone(connection, companyId, "CENTER");
        var storeOne = insertStore(connection, zoneId, "Store one");
        var storeTwo = insertStore(connection, zoneId, "Store two");

        insertStoreCenter(connection, companyId, storeOne, departmentId, "S1-CC");

        assertThat(refusal(connection, """
                                INSERT INTO cost_center (id, company_id, code, name, company_store_id, department_id)
                                VALUES (?::uuid, ?::uuid, 'S1-CC-2', 'Second center', ?::uuid, ?::uuid)
                                """, UUID.randomUUID(), companyId, storeOne, departmentId)
                        .getMessage())
                .as("A10: one store and one department make one center, and a second one splits a report "
                        + "with nothing raising")
                .contains("uq_cost_center_store_department");

        insertCostCenter(connection, companyId, otherDepartmentId, "BARE-CC");

        assertThat(refusal(connection, """
                                INSERT INTO cost_center (id, company_id, code, name, department_id)
                                VALUES (?::uuid, ?::uuid, 'BARE-CC-2', 'Second storeless center', ?::uuid)
                                """, UUID.randomUUID(), companyId, otherDepartmentId)
                        .getMessage())
                .as("A10: this is the case a single composite key would let through — PostgreSQL treats "
                        + "NULLs as distinct, so UNIQUE (company_id, company_store_id, department_id) "
                        + "admits a second storeless center for the same department, which is the "
                        + "defect V95 closed for a tax rate whose region is null")
                .contains("uq_cost_center_company_department");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM cost_center"))
                .as("the two refused rows stored nothing")
                .isEqualTo(2);

        insertStoreCenter(connection, companyId, storeTwo, departmentId, "S2-CC");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM cost_center"))
                .as("the same department in another store is a different center, which is why the pair and "
                        + "not the store is the key")
                .isEqualTo(3);
    }

    // ──────────────────────────────────────
    // helpers: the catalog, read as the application role
    // ──────────────────────────────────────

    private record Policy(String name, String command, String predicate, String withCheck) {}

    /** The two {@code pg_class} flags, in the order ENABLE then FORCE. */
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

    /** Every row of {@code tenant_table}, as {@code table -> kind:key}. */
    private Map<String, String> listedTables() {
        return jdbcTemplate.query("SELECT table_name::text, policy_kind, key_column FROM tenant_table", result -> {
            var listed = new HashMap<String, String>();
            while (result.next()) {
                listed.put(result.getString(1), result.getString(2) + ":" + result.getString(3));
            }
            return listed;
        });
    }

    // ──────────────────────────────────────
    // helpers: fixtures, written on a connection no policy filters
    // ──────────────────────────────────────

    /**
     * One connection per case, opened here and closed above — as the container's <b>own</b> login.
     *
     * <p>A superuser bypasses every policy whatever it says, which is the premise these cases need: the
     * fixtures have to exist before a policy can hide them, and the "the row is there" control has to be
     * unfiltered for the refusal below to be the constraint and not a zero.</p>
     */
    private Connection beginTransaction() throws SQLException {
        transaction =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        transaction.setAutoCommit(false);
        return transaction;
    }

    private static String lineSql() {
        return """
                INSERT INTO account_entry_line
                    (id, company_id, entry_id, account_id, cost_center_id, debit, credit, currency)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 100.0000, 0, ?)
                """;
    }

    private static UUID insertCompany(Connection connection, String tag) throws SQLException {
        var id = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES (?::uuid, ?, ?, ?, ?)",
                id,
                "KEY-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8),
                "Company " + tag,
                "RFC"
                        + UUID.randomUUID()
                                .toString()
                                .replace("-", "")
                                .substring(0, 10)
                                .toUpperCase(),
                TenantFixtures.SEED_CURRENCY);
        return id;
    }

    private static UUID insertDepartment(Connection connection, UUID companyId, String code, String name)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO departments (id, company_id, department_code, department_name) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?)",
                id,
                companyId,
                code,
                name);
        return id;
    }

    private static UUID insertAccount(Connection connection, UUID companyId, String code) throws SQLException {
        var id = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO account (id, company_id, code, name, kind) VALUES (?::uuid, ?::uuid, ?, ?, 'REVENUE')",
                id,
                companyId,
                code,
                "Account " + code);
        return id;
    }

    private static UUID insertEntry(Connection connection, UUID companyId) throws SQLException {
        var id = UUID.randomUUID();
        exec(connection, """
                INSERT INTO account_entry
                    (id, company_id, entry_date, source_type, source_id, posting, actor)
                VALUES (?::uuid, ?::uuid, CURRENT_DATE, 'SALES_ORDER', ?::uuid, 'REVENUE', 'suite')
                """, id, companyId, UUID.randomUUID());
        return id;
    }

    private static UUID insertLine(
            Connection connection, UUID companyId, UUID entryId, UUID accountId, UUID costCenterId)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(connection, lineSql(), id, companyId, entryId, accountId, costCenterId, TenantFixtures.SEED_CURRENCY);
        return id;
    }

    private static UUID insertCostCenter(Connection connection, UUID companyId, UUID departmentId, String code)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO cost_center (id, company_id, code, name, department_id) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid)",
                id,
                companyId,
                code,
                "Center " + code,
                departmentId);
        return id;
    }

    private static UUID insertStoreCenter(
            Connection connection, UUID companyId, UUID storeId, UUID departmentId, String code) throws SQLException {
        var id = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO cost_center (id, company_id, code, name, company_store_id, department_id) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid)",
                id,
                companyId,
                code,
                "Center " + code,
                storeId,
                departmentId);
        return id;
    }

    /**
     * A store of {@code companyId}, inside the zone {@code zoneId}, which must belong to that company.
     *
     * <p>A store has no {@code company_id} of its own to state: `V50` derives it from its zone, so the
     * country, region and zone chain above it is what gives the store its owner.</p>
     */
    private static UUID insertStore(Connection connection, UUID zoneId, String storeName) throws SQLException {
        var storeId = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO company_stores (id, company_zone_id, store_name) VALUES (?::uuid, ?::uuid, ?)",
                storeId,
                zoneId,
                storeName);
        return storeId;
    }

    /**
     * The country → region → zone chain a store hangs from, once per company: `company_countries` is unique by
     * {@code (company_id, country_id)}, so two stores of one company share it rather than each inserting one.
     *
     * @return the zone two stores of that company can share
     */
    private static UUID insertZone(Connection connection, UUID companyId, String tag) throws SQLException {
        var countryId = countryId(connection, "MX");
        var companyCountryId = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO company_countries (id, company_id, country_id) VALUES (?::uuid, ?::uuid, ?::uuid)",
                companyCountryId,
                companyId,
                countryId);

        var regionId = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO company_regions (id, company_country_id, region_code, region_name) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?)",
                regionId,
                companyCountryId,
                "R" + UUID.randomUUID().toString().substring(0, 6),
                "Region of " + tag);

        var zoneId = UUID.randomUUID();
        exec(
                connection,
                "INSERT INTO company_zones (id, company_region_id, zone_code, zone_name) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?)",
                zoneId,
                regionId,
                "Z" + UUID.randomUUID().toString().substring(0, 6),
                "Zone of " + tag);
        return zoneId;
    }

    private static UUID countryId(Connection connection, String countryCode) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM countries WHERE country_code = ?")) {
            statement.setString(1, countryCode);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).as("V2 seeds %s", countryCode).isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    // ──────────────────────────────────────
    // helpers: statements and the refusal they produce
    // ──────────────────────────────────────

    private static void exec(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.execute();
        }
    }

    private static int rowCount(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    /**
     * Runs {@code sql} under a savepoint and returns the refusal it produced, rolling back to the savepoint
     * so the caller can keep reading the same transaction. PostgreSQL aborts the whole transaction on a
     * refused statement, so without the savepoint there would be nothing left to check.
     */
    private static SQLException refusal(Connection connection, String sql, Object... parameters) throws SQLException {
        var savepoint = connection.setSavepoint();
        try {
            exec(connection, sql, parameters);
            connection.rollback(savepoint);
            throw new AssertionError("the write was accepted and should have been refused: " + sql);
        } catch (SQLException refused) {
            connection.rollback(savepoint);
            return refused;
        }
    }
}
