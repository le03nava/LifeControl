package com.lifecontrol.api.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
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
 * Verification of {@code V97__accounting_book.sql} — S1–S8 of {@code odd/tasks/derived-accounting.md}
 * (T1–T3) — against real PostgreSQL with Flyway enabled, which is the only stack where Row-Level Security,
 * composite foreign keys, partial unique indexes and deferred constraint triggers exist at all.
 *
 * <p><b>What each case proves, and why the database is where it has to be proved.</b> Every rule this slice
 * states is enforced by the schema and by nothing else: a table is armed by {@code apply_tenant_isolation},
 * a pointer carries its tenant through a composite foreign key, a cost center is unique through two partial
 * indexes, an entry balances through a deferred constraint trigger, and a line carries one side through a
 * row {@code CHECK}. A service cannot demonstrate any of them — the application role the pool logs in as is
 * filtered by the policy it is trying to observe, a referential check bypasses row security precisely where
 * S7 says it must not, and <i>when</i> a check runs (at the statement or at commit) is a property of the
 * constraint's deferral, not of the code that wrote the row.</p>
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
 * <p><b>How a deferred rule is observed without committing.</b> S3 and S4 land in a {@code DEFERRABLE
 * INITIALLY DEFERRED} constraint trigger, so they judge the state an entry commits rather than each
 * statement that built it. The cases below prove that timing one way only: they write the whole entry and
 * then call {@link #commitRefusal(Connection)}, because a test that asserts “some exception” cannot tell a
 * statement check from a commit check. The transactions that are expected to be accepted are judged by
 * {@code SET CONSTRAINTS ALL IMMEDIATE} instead of by {@code COMMIT} — the same pass, fired by hand —
 * because this suite runs against a shared container and an accepted book row cannot be removed afterwards:
 * both entry tables are append-only, so a committed fixture would outlive the class and be read by sixty
 * sibling suites that never asked for it.</p>
 *
 * <p><b>What this class deliberately does not cover.</b> The seed, the posting rules, the period close and
 * the read models (S9–S18, T4–T12) are later slices of the same unit, and their tests belong with them.</p>
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

    @Test
    @DisplayName("S2: a stored entry and its lines refuse UPDATE and DELETE, and both rows survive the attempt")
    void aStoredEntryAndItsLinesRefuseUpdateAndDelete() throws SQLException {
        var connection = beginTransaction();
        var companyId = insertCompany(connection, "APPEND");
        var accountId = insertAccount(connection, companyId, "4100");
        var entryId = insertEntry(connection, companyId);
        var lineId = insertLine(connection, companyId, entryId, accountId, null);

        assertThat(refusal(connection, "UPDATE account_entry SET memo = 'amended' WHERE id = ?::uuid", entryId)
                        .getMessage())
                .as("ADR-0008 decision 2: an amended entry is a book with two histories, so a correction is a "
                        + "reversing entry and never an edit")
                .contains("append-only")
                .contains("UPDATE");

        assertThat(refusal(connection, "DELETE FROM account_entry WHERE id = ?::uuid", entryId)
                        .getMessage())
                .as("the same section refuses the delete, so the correction cannot be a removal either")
                .contains("append-only")
                .contains("DELETE");

        assertThat(refusal(connection, "UPDATE account_entry_line SET debit = 99 WHERE id = ?::uuid", lineId)
                        .getMessage())
                .as("the line is the half that actually carries the money, so it is refused by its own "
                        + "trigger and not only through its entry")
                .contains("append-only");

        assertThat(refusal(connection, "DELETE FROM account_entry_line WHERE id = ?::uuid", lineId)
                        .getMessage())
                .contains("append-only");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry")
                        + rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("every refusal happened before the write, so the entry and its one line are both still "
                        + "there and unchanged")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("S2: the pool's role keeps SELECT and INSERT on both entry tables and loses UPDATE and DELETE")
    void theApplicationRoleKeepsOnlySelectAndInsertOnTheEntryTables() {
        for (var table : List.of("account_entry", "account_entry_line")) {
            for (var privilege : List.of("SELECT", "INSERT")) {
                assertThat(privilegeHeld(table, privilege))
                        .as("S2 keeps %s on %s: a posting is an insert and every report is a read", privilege, table)
                        .isTrue();
            }
            for (var privilege : List.of("UPDATE", "DELETE")) {
                assertThat(privilegeHeld(table, privilege))
                        .as(
                                "%s must not survive on %s. `V52:196-199` hands the pool's role all four "
                                        + "privileges on every future table, so the restrictive half has to be "
                                        + "written or it does not exist — and the trigger alone would refuse the "
                                        + "owner and the superuser while the pool kept the privilege",
                                privilege, table)
                        .isFalse();
            }
        }
    }

    // ──────────────────────────────────────
    // S3 — every entry balances, at commit and not at the statement
    // ──────────────────────────────────────

    @Test
    @DisplayName("S3: an entry that debits 100 and credits 90 is refused at commit, after both of its lines "
            + "were written")
    void anUnbalancedEntryIsRefusedAtCommitAndNotAtItsStatements() throws SQLException {
        var connection = beginTransaction();
        var companyId = insertCompany(connection, "BALANCE");
        var receivable = insertAccount(connection, companyId, "1200");
        var revenue = insertAccount(connection, companyId, "4100");
        var entryId = insertEntry(connection, companyId);

        insertLine(connection, companyId, entryId, receivable, null, new BigDecimal("100.0000"), BigDecimal.ZERO);
        insertLine(connection, companyId, entryId, revenue, null, BigDecimal.ZERO, new BigDecimal("90.0000"));

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("both statements were accepted: that is what DEFERRABLE INITIALLY DEFERRED buys, because "
                        + "a posting writes an entry across several statements and a per-statement check "
                        + "would judge an intermediate state that never commits (ADR-0008 decision 3, A1)")
                .isEqualTo(2);

        var refused = commitRefusal(connection);

        assertThat(refused.getMessage())
                .as("A1 names the entry, the invariant and the rule, because an operator reads this at commit "
                        + "time with no other context: it says which entry of which company does not balance "
                        + "and by how much")
                .contains("A1")
                .contains(entryId.toString())
                .contains("balances");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("the refusal at commit took the whole batch with it: a deferred check that let the "
                        + "lines through and then refused the entry would leave half a batch behind")
                .isZero();
        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry"))
                .as("and the entry it was reading is not there either")
                .isZero();
    }

    @Test
    @DisplayName("S3: the same entry with its credit corrected to 100 passes the deferred pass that refused it")
    void aBalancedEntryPassesTheDeferredPass() throws SQLException {
        var connection = beginTransaction();

        // The control first, and in its own transaction: it is what proves the pass below is not a no-op.
        // `SET CONSTRAINTS ALL IMMEDIATE` fires a DEFERRABLE INITIALLY DEFERRED trigger now instead of at
        // commit, so a refused control here is the proof that the acceptance further down was judged.
        var unbalancedCompany = insertCompany(connection, "PASS-NO");
        var unbalancedAccounts = insertAccount(connection, unbalancedCompany, "4100");
        var unbalanced = insertEntry(connection, unbalancedCompany);
        insertLine(
                connection,
                unbalancedCompany,
                unbalanced,
                unbalancedAccounts,
                null,
                new BigDecimal("100.0000"),
                BigDecimal.ZERO);
        insertLine(
                connection,
                unbalancedCompany,
                unbalanced,
                unbalancedAccounts,
                null,
                BigDecimal.ZERO,
                new BigDecimal("90.0000"));

        assertThat(deferredPassRefusal(connection).getMessage())
                .as("the control: the deferred pass is the same pass COMMIT runs, and it refuses the entry "
                        + "the correction below balances — without this, the acceptance would pass whether "
                        + "or not the trigger exists")
                .contains("A1")
                .contains(unbalanced.toString());

        connection.rollback();

        // The same entry, one side corrected. `SET CONSTRAINTS ALL IMMEDIATE` is what judges it, and the
        // rows stay in the transaction so the suite can roll them back: an accepted book row is append-only
        // and this container is shared.
        var companyId = insertCompany(connection, "PASS-YES");
        var receivable = insertAccount(connection, companyId, "1200");
        var revenue = insertAccount(connection, companyId, "4100");
        var entryId = insertEntry(connection, companyId);
        insertLine(connection, companyId, entryId, receivable, null, new BigDecimal("100.0000"), BigDecimal.ZERO);
        insertLine(connection, companyId, entryId, revenue, null, BigDecimal.ZERO, new BigDecimal("100.0000"));

        deferredPass(connection);

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("A1: the only difference from the refused entry is the credit, so the check that refused "
                        + "90 accepts 100 and a balanced entry is what the book takes")
                .isEqualTo(2);
    }

    // ──────────────────────────────────────
    // S4 — every entry has at least two lines, including the entry that has none
    // ──────────────────────────────────────

    @Test
    @DisplayName("S4: an entry committed with one line is refused, and so is an entry committed with none")
    void anEntryWithFewerThanTwoLinesIsRefusedAtCommit() throws SQLException {
        var connection = beginTransaction();

        var singleCompany = insertCompany(connection, "ONE-LINE");
        var singleAccount = insertAccount(connection, singleCompany, "1200");
        var singleEntry = insertEntry(connection, singleCompany);
        insertLine(
                connection,
                singleCompany,
                singleEntry,
                singleAccount,
                null,
                new BigDecimal("100.0000"),
                BigDecimal.ZERO);

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("the line was written, so the refusal below is the entry's shape and not its write")
                .isEqualTo(1);

        assertThat(commitRefusal(connection).getMessage())
                .as("A2: one side of a fact is not the fact — nothing downstream can tell which account is "
                        + "missing, so an entry with a single line is half a double-entry posting")
                .contains("A2")
                .contains(singleEntry.toString())
                .contains("1 line(s)");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry"))
                .as("the refused entry and its line were both rolled back by the commit")
                .isZero();
        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .isZero();

        // The case the trigger on account_entry exists for. A constraint trigger on the line only fires when
        // a line is written, so an entry inserted and committed with no line at all is invisible to it.
        var emptyCompany = insertCompany(connection, "NO-LINE");
        var emptyEntry = insertEntry(connection, emptyCompany);

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("nothing was written for this entry, which is exactly why a line-only trigger would let "
                        + "it commit")
                .isZero();

        assertThat(commitRefusal(connection).getMessage())
                .as("A2 again, from the entry's own trigger: an entry that never had a line written is an "
                        + "entry the line trigger can never judge")
                .contains("A2")
                .contains(emptyEntry.toString())
                .contains("0 line(s)");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry"))
                .as("the empty entry is not in the book either")
                .isZero();
    }

    // ──────────────────────────────────────
    // S5 — one side per line, and it is money
    // ──────────────────────────────────────

    @Test
    @DisplayName("S5: a line with both sides, a line of zeros and a line of minus five are all refused")
    void aLineCarriesExactlyOneNonNegativeSide() throws SQLException {
        var connection = beginTransaction();
        var companyId = insertCompany(connection, "SIDES");
        var accountId = insertAccount(connection, companyId, "1200");
        var entryId = insertEntry(connection, companyId);

        assertThat(refusal(
                                connection,
                                lineWithAmountsSql(),
                                UUID.randomUUID(),
                                companyId,
                                entryId,
                                accountId,
                                null,
                                new BigDecimal("100.0000"),
                                new BigDecimal("100.0000"),
                                TenantFixtures.SEED_CURRENCY)
                        .getMessage())
                .as("A3: a line with both sides is one fact counted twice, and no report can tell which half "
                        + "was meant")
                .contains("ck_account_entry_line_single_side");

        assertThat(refusal(
                                connection,
                                lineWithAmountsSql(),
                                UUID.randomUUID(),
                                companyId,
                                entryId,
                                accountId,
                                null,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                TenantFixtures.SEED_CURRENCY)
                        .getMessage())
                .as("A3: a line with neither side is not a posting. It breaks both the exclusivity rule and "
                        + "the positivity rule, and PostgreSQL reports the first of the two by name")
                .contains("ck_account_entry_line_positive");

        assertThat(refusal(
                                connection,
                                lineWithAmountsSql(),
                                UUID.randomUUID(),
                                companyId,
                                entryId,
                                accountId,
                                null,
                                new BigDecimal("-5.0000"),
                                new BigDecimal("5.0000"),
                                TenantFixtures.SEED_CURRENCY)
                        .getMessage())
                .as("A3: a negative amount is not money. The ADR states this as its own CHECK instead of "
                        + "leaving it to be inferred, and a line of -5 against 5 is why: every rule that reads "
                        + "the row as money cannot be asked a sensible question about it, so the refusal names "
                        + "the amount and not the shape of the row")
                .contains("ck_account_entry_line_non_negative");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("three refused writes stored nothing")
                .isZero();

        insertLine(connection, companyId, entryId, accountId, null, new BigDecimal("100.0000"), BigDecimal.ZERO);

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("one positive side is what the schema accepts")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // S6 — one posting per source, so a replayed job is a no-op
    // ──────────────────────────────────────

    @Test
    @DisplayName("S6: the same source cannot post twice as the same posting, and a different posting or "
            + "another company is a different posting")
    void aSourcePostsOnceAsEachPosting() throws SQLException {
        var connection = beginTransaction();
        var companyA = insertCompany(connection, "SOURCE-A");
        var companyB = insertCompany(connection, "SOURCE-B");
        var sourceId = UUID.randomUUID();

        insertEntry(connection, companyA, "SALE_PAYMENT", sourceId, "COLLECTION");

        assertThat(refusal(connection, """
                                INSERT INTO account_entry
                                    (id, company_id, entry_date, source_type, source_id, posting, actor)
                                VALUES (?::uuid, ?::uuid, CURRENT_DATE, 'SALE_PAYMENT', ?::uuid, 'COLLECTION', 'suite')
                                """, UUID.randomUUID(), companyA, sourceId)
                        .getMessage())
                .as("A5: a replayed job finds the key occupied instead of writing a second entry, and the key "
                        + "is the database's rather than a service guard's — a guard is a statement a second "
                        + "writer can skip, and the second entry is then invisible money in the book")
                .contains("uq_account_entry_company_source_posting");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry"))
                .as("the refused replay stored nothing")
                .isEqualTo(1);

        insertEntry(connection, companyA, "SALE_PAYMENT", sourceId, "REVERSAL");
        insertEntry(connection, companyB, "SALE_PAYMENT", sourceId, "COLLECTION");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry"))
                .as("the key stops at the posting and at the company: one payment posts a collection and "
                        + "later a reversal, and two tenants number their sources independently, so both of "
                        + "those rows are accepted beside the first")
                .isEqualTo(3);
    }

    // ──────────────────────────────────────
    // helpers: the catalog, read as the application role
    // ──────────────────────────────────────

    private record Policy(String name, String command, String predicate, String withCheck) {}

    @Test
    @DisplayName("S5: NaN is not money — the row is refused, and with it the only way to balance a NaN entry")
    void nanIsNotMoney() throws SQLException {
        var connection = beginTransaction();
        var companyId = insertCompany(connection, "NAN");
        var accountId = insertAccount(connection, companyId, "4200");
        var entryId = insertEntry(connection, companyId);

        assertThat(refusal(
                                connection,
                                """
                                INSERT INTO account_entry_line
                                    (id, company_id, entry_id, account_id, debit, credit, currency)
                                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'NaN'::numeric, 0, ?)
                                """,
                                UUID.randomUUID(),
                                companyId,
                                entryId,
                                accountId,
                                TenantFixtures.SEED_CURRENCY)
                        .getMessage())
                .as("PostgreSQL sorts NaN above every number and answers NaN = NaN with true, so a NaN debit "
                        + "passes `debit >= 0`, passes exclusivity against a zero credit and passes "
                        + "`debit + credit > 0` — and a pair of NaNs satisfies A1's equality of the two sums, "
                        + "which is the one way this book could be written unbalanced and read as balanced")
                .contains("ck_account_entry_line_finite");

        assertThat(rowCount(connection, "SELECT COUNT(*) FROM account_entry_line"))
                .as("the refused row stored nothing: an amount that is not a number is not half a line")
                .isZero();

        assertThat(refusal(
                                connection,
                                """
                                INSERT INTO account_entry_line
                                    (id, company_id, entry_id, account_id, debit, credit, currency)
                                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 100, 'NaN'::numeric, ?)
                                """,
                                UUID.randomUUID(),
                                companyId,
                                entryId,
                                accountId,
                                TenantFixtures.SEED_CURRENCY)
                        .getMessage())
                .as("the credit side is the same column family, so it is refused by the same rule")
                .contains("ck_account_entry_line_finite");
    }

    /** Whether the role the pool logs in as holds {@code privilege} directly on {@code table}. */
    private boolean privilegeHeld(String table, String privilege) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('lifecontrol_app', ?::regclass, ?)", Boolean.class, table, privilege));
    }

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

    /** The same statement with the two amounts as parameters, for the lines S3 and S5 judge on value. */
    private static String lineWithAmountsSql() {
        return """
                INSERT INTO account_entry_line
                    (id, company_id, entry_id, account_id, cost_center_id, debit, credit, currency)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?)
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
        return insertEntry(connection, companyId, "SALES_ORDER", UUID.randomUUID(), "REVENUE");
    }

    private static UUID insertEntry(
            Connection connection, UUID companyId, String sourceType, UUID sourceId, String posting)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(connection, """
                INSERT INTO account_entry
                    (id, company_id, entry_date, source_type, source_id, posting, actor)
                VALUES (?::uuid, ?::uuid, CURRENT_DATE, ?, ?::uuid, ?, 'suite')
                """, id, companyId, sourceType, sourceId, posting);
        return id;
    }

    private static UUID insertLine(
            Connection connection, UUID companyId, UUID entryId, UUID accountId, UUID costCenterId)
            throws SQLException {
        return insertLine(
                connection, companyId, entryId, accountId, costCenterId, new BigDecimal("100.0000"), BigDecimal.ZERO);
    }

    private static UUID insertLine(
            Connection connection,
            UUID companyId,
            UUID entryId,
            UUID accountId,
            UUID costCenterId,
            BigDecimal debit,
            BigDecimal credit)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(
                connection,
                lineWithAmountsSql(),
                id,
                companyId,
                entryId,
                accountId,
                costCenterId,
                debit,
                credit,
                TenantFixtures.SEED_CURRENCY);
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

    /**
     * Commits the transaction and returns the refusal the commit produced.
     *
     * <p>This is the only way to tell a deferred check from a statement check: S3 and S4 land in a
     * {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger, so the statements that build the entry have
     * to be accepted and the refusal has to arrive here, at {@code COMMIT}, or nowhere.</p>
     *
     * <p>A refusal leaves the transaction rolled back by the server, which is the other half of what the
     * tests assert: the batch is not half-written.</p>
     */
    private static SQLException commitRefusal(Connection connection) throws SQLException {
        try {
            connection.commit();
        } catch (SQLException refused) {
            connection.rollback();
            return refused;
        }
        connection.rollback();
        throw new AssertionError(
                "the transaction committed: the deferred constraint checks neither fired nor refused it");
    }

    /**
     * Fires every deferred constraint check the transaction has queued, the way {@code COMMIT} would, and
     * leaves the transaction open so the caller rolls it back.
     *
     * <p>{@code SET CONSTRAINTS ALL IMMEDIATE} runs a {@code DEFERRABLE INITIALLY DEFERRED} trigger now
     * instead of at the end of the transaction — the same pass, with the same function and the same
     * message, one statement early. The accepted transactions of this class are judged this way and not by
     * {@code COMMIT} for one reason: an accepted book row is append-only, so it cannot be deleted from a
     * shared container afterwards.</p>
     */
    private static void deferredPass(Connection connection) throws SQLException {
        exec(connection, "SET CONSTRAINTS ALL IMMEDIATE");
    }

    /**
     * The same pass, expected to refuse: the control that proves {@link #deferredPass(Connection)} is not a
     * no-op. A refused check aborts the transaction, so this rolls it back and leaves the connection usable.
     */
    private static SQLException deferredPassRefusal(Connection connection) throws SQLException {
        try {
            deferredPass(connection);
        } catch (SQLException refused) {
            connection.rollback();
            return refused;
        }
        connection.rollback();
        throw new AssertionError("the deferred pass accepted the transaction and should have refused it");
    }
}
