package com.lifecontrol.api.provisioning.repository;

import java.util.Optional;
import java.util.UUID;

/**
 * The company that owns a provisioning task, resolved for the thread that has no request to take a
 * tenant from.
 *
 * <p><b>Its consumer is the worker, and it exists because the worker has no request.</b>
 * {@code AccessProvisioningWorker.process} reads the employee row — a table ADR-0005 arms with a tenant
 * policy — from a thread no filter ever sees, so the pass has to state the tenant itself.
 * {@code access_provisioning_tasks} carries no {@code company_id} (V22, and S2 of
 * {@code odd/tasks/tenant-isolation-rls.md} leaves the table outside the policy set), so the tenant
 * enters through {@code employee_id}, which is the row's only organisational link; the projection
 * answers the id alone because that is all the scope needs and a fetched entity here would be a second
 * snapshot nobody acts on.</p>
 *
 * <p><b>It resolves through a {@code SECURITY DEFINER} function, not through a read of
 * {@code employees}.</b> The first shape of this resolution was a JPQL join over {@code employees}, and
 * it worked only while nobody could hide the row: the moment T5 arms {@code employees} the worker's own
 * resolution reads zero rows with no scope, every claimed task fails with "Employee not found", and
 * provisioning stops in a retry loop — the ADR's named failure mode wearing a different mask (record
 * L14). The fix the maintainer chose is the narrow one (record L16): {@code task_employee_company(uuid)},
 * one fixed query owned by the maintenance superuser, so the bypass is a single uuid and not a
 * connection that sees every row. The function is created by
 * {@code docker/postgres-init/lifecontrol-roles.sql}, because ownership is what grants the bypass and
 * Flyway's owner role is {@code NOSUPERUSER} on purpose.</p>
 *
 * <p><b>Why an interface of its own</b> instead of a {@code @Query} on
 * {@link AccessProvisioningTaskRepository}: the query depends on the database. PostgreSQL runs the
 * resolver; a database that has no ADR-0005 machinery at all (the default H2 test profile, and a
 * PostgreSQL container that never ran the roles script) can only run the join, and the choice is a fact
 * of the database rather than of the caller. {@link AccessProvisioningTaskTenantResolutionImpl} owns
 * that choice and sits behind the same repository the worker already injects, so the worker's read
 * stays one line and one dependency.</p>
 */
public interface AccessProvisioningTaskTenantResolution {

    /**
     * The company of {@code taskId}'s employee, or empty when no task carries that id.
     *
     * @param taskId the task whose tenant is being resolved
     * @return the company the task belongs to
     */
    Optional<UUID> findEmployeeCompanyIdByTaskId(UUID taskId);
}
