package com.lifecontrol.api.provisioning.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * Resolves a provisioning task's tenant through {@code task_employee_company(uuid)} on PostgreSQL, and
 * through the employee join only where that machinery does not exist at all.
 *
 * <p><b>Why the resolver is the answer on PostgreSQL.</b> See
 * {@link AccessProvisioningTaskTenantResolution}: once {@code employees} carries its policy, the join
 * returns nothing without a scope, and the worker's resolution <i>is</i> what establishes the scope — a
 * circular read. The function answers from its owner's rights, so it is one query returning one uuid
 * instead of a connection that may see every row.</p>
 *
 * <p><b>The join is a property of the engine, not of a fixture.</b> It exists for one case only: a
 * database that is not PostgreSQL (the default test profile runs H2 with Flyway disabled) has no
 * {@code set_tenant_scope}, no {@code apply_tenant_isolation}, no policy to satisfy and no
 * {@code to_regprocedure} to ask, so the only derivation that can exist there is the plain join. That is
 * a statement about H2, and it is decided by asking the engine its name — never by which objects a
 * particular database happens to carry.</p>
 *
 * <p><b>A PostgreSQL without the function fails loudly, and that is the point.</b> The resolver is
 * created by {@code docker/postgres-init/lifecontrol-roles.sql}, which {@code ./docker/scripts/db-roles.sh
 * <env>} applies on every deploy. A database in that state — a restored cluster whose Flyway history
 * already contains V52, so nothing re-checks the roles; a deploy that started the application without the
 * role step — is <b>broken</b>, and the one thing it must not do is read zero rows: that is ADR-0005's
 * named silent-empty failure mode, and it reads as "the employee disappeared" instead of "the database is
 * missing a function". This class therefore throws {@link IllegalStateException} naming the script, and
 * the worker's own per-task failure path turns it into {@code access_provisioning_tasks.last_error}, where
 * an operator acts on it, leaving the rest of the pass running.</p>
 *
 * <p><b>The test container carries the same function</b> ({@code src/test/resources/test-container-roles.sql},
 * mirroring the roles script), so the loud path is not a hazard for the suites that run the worker — and
 * because that file creates an object before Flyway, the test profile baselines at 0
 * ({@code application-test.properties}), which is the only true statement about a database the suite
 * owns and just created.</p>
 *
 * <p><b>Why the probe is not cached.</b> The answer is a fact of the session's database, and a suite that
 * drops the resolver inside its own transaction must be able to observe that state; a process-wide cache
 * would answer for a database the session is not looking at. It is one catalog lookup through the
 * transaction's own connection ({@code doReturningWork} hands this class that connection, not a new one),
 * which is cheaper than the read it precedes and cannot go stale.</p>
 *
 * <p><b>The read stays inside the scope.</b> Nothing here installs a tenant: the caller
 * ({@code AccessProvisioningWorker}) states the task's company with {@link
 * com.lifecontrol.api.common.tenant.TenantScope#with} <i>after</i> this resolution, so the employee read it
 * unlocks runs inside that company's scope. Both statements run on the transaction's connection, so a
 * {@code SET LOCAL} the boundary hook issued is visible to them and to nothing else.</p>
 */
@Component
public class AccessProvisioningTaskTenantResolutionImpl implements AccessProvisioningTaskTenantResolution {

    /** The resolver the roles script owns: one fixed join, one uuid back. */
    static final String RESOLVER = "select public.task_employee_company(cast(:taskId as uuid)) as company_id";

    /**
     * The derivation for an engine with no ADR-0005 machinery (H2): the same join the resolver performs,
     * executed as the caller. It is dead code on PostgreSQL by construction — a PostgreSQL without the
     * function throws instead — and that asymmetry is the decision, not an oversight.
     */
    static final String THROUGH_EMPLOYEE = """
            select e.company_id
              from access_provisioning_tasks t
              join employees e on e.id = t.employee_id
             where t.id = cast(:taskId as uuid)
            """;

    /** The catalog question that separates "PostgreSQL with the roles script" from "PostgreSQL without it". */
    private static final String RESOLVER_DEFINED =
            "select to_regprocedure('public.task_employee_company(uuid)') is not null";

    /** Which statement this database can run. Decided per call, never cached. */
    private enum Resolution {
        /** Not PostgreSQL: no scope functions, no policies, no catalog to ask — the join, and only here. */
        EMPLOYEE_JOIN,
        /** PostgreSQL that ran the role step: the {@code SECURITY DEFINER} resolver. */
        TENANT_RESOLVER,
        /** PostgreSQL without it: a broken deployment, which must say so instead of reading nothing. */
        RESOLVER_MISSING
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<UUID> findEmployeeCompanyIdByTaskId(UUID taskId) {
        List<?> rows = entityManager
                .createNativeQuery(statementForThisDatabase())
                .setParameter("taskId", taskId)
                .getResultList();
        return rows.stream()
                .filter(Objects::nonNull)
                .findFirst()
                .map(AccessProvisioningTaskTenantResolutionImpl::asCompanyId);
    }

    /**
     * The statement this database can run, or a refusal that names the file to run.
     *
     * <p>The refusal is raised <b>outside</b> the JDBC work on purpose: a runtime exception thrown inside
     * {@code doReturningWork} is the driver's business, and this message is not about the driver.</p>
     */
    private String statementForThisDatabase() {
        return switch (resolutionOfThisDatabase()) {
            case EMPLOYEE_JOIN -> THROUGH_EMPLOYEE;
            case TENANT_RESOLVER -> RESOLVER;
            case RESOLVER_MISSING -> throw missingResolver();
        };
    }

    private Resolution resolutionOfThisDatabase() {
        return entityManager.unwrap(Session.class).doReturningWork(AccessProvisioningTaskTenantResolutionImpl::resolve);
    }

    private static Resolution resolve(Connection connection) throws SQLException {
        if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            return Resolution.EMPLOYEE_JOIN;
        }
        try (var statement = connection.prepareStatement(RESOLVER_DEFINED);
                var result = statement.executeQuery()) {
            result.next();
            return result.getBoolean(1) ? Resolution.TENANT_RESOLVER : Resolution.RESOLVER_MISSING;
        }
    }

    /**
     * The refusal, worded for the person who has to act on it.
     *
     * <p>It names the script and the environment placeholder because that is where an operator reads it —
     * in {@code access_provisioning_tasks.last_error}, once per claimed task — and it says what the
     * alternative would have been, so the next reader does not restore a silent fallback to make the error
     * go away.</p>
     */
    private static IllegalStateException missingResolver() {
        return new IllegalStateException(
                "PostgreSQL has no task_employee_company(uuid): this database was never given ADR-0005's "
                        + "tenant resolver. Run ./docker/scripts/db-roles.sh <env> for this environment "
                        + "(docker/postgres-init/lifecontrol-roles.sql creates it). The access-provisioning "
                        + "worker resolves each task's tenant through it, and without it the resolution "
                        + "would read zero rows and every claimed task would fail as 'Employee not found'.");
    }

    private static UUID asCompanyId(Object column) {
        return column instanceof UUID companyId ? companyId : UUID.fromString(column.toString());
    }
}
