package com.lifecontrol.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one read of a tenant table the asynchronous path cannot derive from a tenant table: the
 * provisioning worker's tenant, resolved through {@code task_employee_company(uuid)} (ADR-0005's
 * 2026-10-30 amendment; record L16 of {@code odd/tasks/tenant-isolation-rls.md}).
 *
 * <p><b>The pair is the point, and it is one case.</b> {@code access_provisioning_tasks} carries no
 * {@code company_id} (V22), and its due-task scan is cross-tenant on purpose, so the worker's tenant
 * lives in {@code employees} — a table T5 arms with a policy. Once the policy is armed, the read that
 * would resolve the tenant is the very read that needs the tenant: without a scope it returns zero rows,
 * and the pass fails every claimed task with "Employee not found" in a retry loop. The first case below
 * arms the real policy on the real {@code employees} table and shows both halves in one transaction:
 * <b>the plain read returns zero rows while the resolver still returns the task's company</b>. Two
 * separate cases would let one of them pass with the other's mechanism.</p>
 *
 * <p><b>What the resolver's owner has to be, and why the test does not name the production role.</b>
 * The bypass comes from {@code SECURITY DEFINER} running the body with the rights of the function's
 * owner, so the invariant is "the owner is a role no policy applies to" — in delivery
 * {@code lifecontrol_bootstrap}, created by {@code docker/postgres-init/lifecontrol-roles.sql}. The test
 * container creates only the two roles of {@code test-container-roles.sql} and never runs that script
 * (record L9), so the function here is owned by the container's superuser, which is the same attribute.
 * The case asserts the attribute ({@code pg_roles.rolsuper} of the owner) instead of the name, and
 * {@code docker/scripts/tests/db-roles.test.sh} pins the name, the ownership {@code ALTER}, the
 * {@code REVOKE FROM PUBLIC} and the {@code GRANT EXECUTE} to the application role in the script itself.
 * </p>
 *
 * <p><b>What each case runs as.</b> This suite's pool logs in as the container's <b>own</b> user — a
 * SUPERUSER — declared by {@link SuperuserPoolConfiguration} rather than inherited, because the facts these
 * cases observe are the machinery's: {@code apply_tenant_isolation} arms the real {@code employees} table with
 * {@code ALTER TABLE … FORCE ROW LEVEL SECURITY}, which only the table's owner or a superuser may run, and the
 * controls that prove the row exists before the policy hides it must be unfiltered. Since T6b-4 the shared pool
 * is {@code lifecontrol_app}, so a suite needing the superuser states it; each case that wants the policy's
 * answer switches down with {@code SET LOCAL ROLE lifecontrol_app} on the same connection.</p>
 *
 * <p><b>Why the second case creates the resolver inside its own transaction.</b> It has to observe the
 * statement the worker's repository really runs, and the container has no function until the roles script
 * runs. Creating it on the same connection — through Hibernate's {@code doWork}, which hands the
 * transaction's connection and not a second one — is what makes the repository's catalog probe see it,
 * so the assertion is about the resolver branch and not about the fallback. Everything is rolled back:
 * the fixtures, the policy, the function and the grant, so the 60-odd suites sharing this container never
 * see them.</p>
 */
@SpringBootTest
@DisplayName("The provisioning worker's tenant resolver")
class TenantTaskResolverIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID COMPANY_A = UUID.fromString("0a000000-0000-4000-8000-00000000007a");
    private static final UUID EMPLOYEE_A = UUID.fromString("0a000000-0000-4000-8000-0000000000ea");
    private static final UUID TASK_A = UUID.fromString("0a000000-0000-4000-8000-0000000000ca");

    /** The role the pool connects as in delivery: the one policies apply to (ADR decision 3). */
    private static final String APPLICATION_ROLE = "lifecontrol_app";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private AccessProvisioningTaskRepository taskRepository;

    /**
     * This suite's pool: the container's own login, so the DDL that arms {@code employees} and the unfiltered
     * control reads are available to the connection the transaction manager hands the repository.
     *
     * <p>A bean and not a per-statement switch, because the repository's own transaction is the connection the
     * policy and the resolver are observed on — the cases cannot re-open it. The role is therefore stated once,
     * for the whole suite, and every javadoc above says which one it is.</p>
     */
    @TestConfiguration
    static class SuperuserPoolConfiguration {

        @Bean
        DataSource dataSource() {
            return containerSuperuserPool();
        }
    }

    /** One connection per case at most, opened with {@link #beginTransaction()} and closed below. */
    private Connection transaction;

    @AfterEach
    void rollBackEverythingThisTestWrote() throws SQLException {
        TenantScope.clear();
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

    @Test
    @DisplayName("the plain read of employees returns zero rows while the resolver still answers")
    void theResolverAnswersWhereThePolicyHidesTheRow() throws SQLException {
        var connection = beginTransaction();
        insertFixtures(connection);
        armEmployees(connection);

        assertThat(ownerIsASuperuser(connection))
                .as("SECURITY DEFINER runs the body with the owner's rights, so the owner has to be a "
                        + "role no policy applies to: that ownership IS the bypass, and it is why the "
                        + "function lives in the file that runs as the maintenance superuser")
                .isTrue();
        assertThat(rows(connection, "SELECT count(*) FROM employees WHERE id = ?::uuid", EMPLOYEE_A))
                .as("the control: the row exists and this count runs as the superuser, which bypasses "
                        + "every policy — so the zero below is the predicate filtering, not an empty table")
                .isEqualTo(1);

        exec(connection, "SET LOCAL ROLE " + APPLICATION_ROLE);

        assertThat(rows(connection, "SELECT count(*) FROM employees WHERE id = ?::uuid", EMPLOYEE_A))
                .as("the tenant read the worker's resolution used to be, once `employees` carries its "
                        + "policy: zero rows with no scope, never another company's row (fail-closed)")
                .isZero();
        assertThat(companyOf(connection, TASK_A))
                .as("and the resolver answers anyway: one fixed query, one uuid back, no row visibility — "
                        + "the pair is what makes the worker keep working when employees is armed")
                .isEqualTo(COMPANY_A);
    }

    @Test
    @Transactional
    @DisplayName("the task repository resolves the tenant through the resolver, not through employees")
    void theTaskRepositoryResolvesThroughTheResolver() { // Fixtures, policy, function and grant are created on THIS
        // transaction's connection: the
        // repository asks the database whether the resolver exists, and a separate connection would see
        // the container's state instead of this one's.
        var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        entityManager.unwrap(Session.class).doWork(connection -> {
            insertFixtures(connection);
            armEmployees(connection);
            exec(connection, "SET LOCAL ROLE " + APPLICATION_ROLE);
        });

        assertThat(taskRepository.findEmployeeCompanyIdByTaskId(TASK_A))
                .as("the branch the worker takes on a database that ran the roles script: the join would "
                        + "return nothing here, because employees is armed and no scope is stated")
                .contains(COMPANY_A);
        assertThat(taskRepository.findEmployeeCompanyIdByTaskId(UUID.randomUUID()))
                .as("a task nobody wrote resolves to no tenant, which is the worker's own not-found branch ")
                .isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("a PostgreSQL database without the resolver fails loudly instead of reading nothing")
    void aDatabaseWithoutTheResolverFailsLoudly() {
        // The state a deployment is in when the role step never ran: PostgreSQL, Flyway applied, and no
        // `task_employee_company`. The function is dropped on this transaction's own connection — the one
        // the resolver asks — so the probe sees exactly that state, and the rollback puts it back for the
        // suites that share the container.
        var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        entityManager.unwrap(Session.class).doWork(connection -> {
            insertFixtures(connection);
            armEmployees(connection);
            exec(connection, "DROP FUNCTION task_employee_company(uuid)");
        });

        var refusal = catchThrowable(() -> taskRepository.findEmployeeCompanyIdByTaskId(TASK_A));

        // The type is asserted through the root cause because the repository is a Spring Data proxy: the
        // persistence layer re-wraps a bare IllegalStateException as `InvalidDataAccessApiUsageException`
        // (JPA's own convention for a bad EntityManager call), keeping the message. What an operator reads
        // is that message -- `AccessProvisioningWorker` records `failure.getMessage()` as `last_error`.
        assertThat(refusal)
                .as("zero rows is the failure ADR-0005 exists to remove: a deployment in this state is "
                        + "broken, and being told so per task is the point")
                .isNotNull()
                .hasMessageContaining("task_employee_company")
                .hasMessageContaining("./docker/scripts/db-roles.sh")
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    // ──────────────────────────────────────
    // fixtures
    // ──────────────────────────────────────

    /**
     * A company, its employee and a provisioning task for that employee: the minimum the resolver's body
     * joins. Inserted as the container's superuser, before the policy is armed, so the rows exist whatever
     * the policy then says.
     */
    private static void insertFixtures(Connection connection) throws SQLException {
        exec(
                connection,
                "INSERT INTO companies (id, company_key, company_name, rfc) VALUES (?::uuid, ?, ?, ?)",
                COMPANY_A,
                "TASK-RESOLVER-KEY",
                "Task Resolver Company",
                "TRES010101AA");
        exec(connection, """
                INSERT INTO employees (id, company_id, employee_number, first_name, paternal_last_name,
                                       email, birth_date, hire_date, employment_status_id)
                SELECT ?::uuid, ?::uuid, 'EMP-RES-1', 'Tenant', 'Resolver',
                       'tenant.resolver@task-resolver.test', DATE '1990-01-01', DATE '2020-01-01', s.id
                  FROM statuses s
                  JOIN status_types st ON st.id = s.status_type_id
                 WHERE st.status_type_name = 'EMPLOYEE_STATUS' AND s.status_name = 'Active'
                """, EMPLOYEE_A, COMPANY_A);
        exec(connection, """
                INSERT INTO access_provisioning_tasks (id, employee_id, kind, status, requested_by)
                VALUES (?::uuid, ?::uuid, 'ACTIVATE', 'PENDING', 'resolver-suite')
                """, TASK_A, EMPLOYEE_A);
    }

    /** The policy T5 will apply to the real table, applied inside this transaction and rolled back. */
    private static void armEmployees(Connection connection) throws SQLException {
        exec(connection, "SELECT apply_tenant_isolation('employees', 'tenant')");
    }

    private Connection beginTransaction() throws SQLException {
        transaction = dataSource.getConnection();
        transaction.setAutoCommit(false);
        return transaction;
    }

    private static boolean ownerIsASuperuser(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT r.rolsuper
                          FROM pg_proc p
                          JOIN pg_roles r ON r.oid = p.proowner
                         WHERE p.oid = to_regprocedure('public.task_employee_company(uuid)')
                        """)) {
            assertThat(result.next())
                    .as("to_regprocedure proves the function exists and is the one with this signature")
                    .isTrue();
            return result.getBoolean(1);
        }
    }

    private static UUID companyOf(Connection connection, UUID taskId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT task_employee_company(?::uuid)")) {
            statement.setObject(1, taskId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static long rows(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static void exec(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.execute();
        }
    }
}
