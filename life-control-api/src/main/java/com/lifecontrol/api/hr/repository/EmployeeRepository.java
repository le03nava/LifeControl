package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.Employee;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence access for the company-scoped employee registry.
 *
 * <p>Every finder is company-scoped: an employee is reached through its company, so a foreign
 * employee is already a 404 before any method here runs.</p>
 *
 * <p>The two existence checks are the collision probes of the email generator's loop (record T7).
 * They are deliberately separate reads and not a lock: the write path pre-checks each candidate and
 * picks the first free one without ever retrying a write, and the database's
 * {@code uq_employees_company_email} is the race-safe guarantee (T8).</p>
 *
 * <p>There is deliberately no {@code deleteBy}-style method: an employee is soft-deleted by flipping
 * {@code enabled} to {@code false}.</p>
 */
@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The company-scoped employee row under a <b>pessimistic write lock</b>
     * ({@code SELECT ... FOR UPDATE}), mirroring
     * {@code AccessProvisioningTaskRepository#findByIdAndEmployeeIdForUpdate} and
     * {@code PurchaseOrderRepository#findByIdForUpdate}.
     *
     * <p>Its consumer is {@code AccessProvisioningProducer.enqueue}, which takes this lock
     * <b>before</b> it reads the employee's open access-provisioning task: the access-provisioning
     * per-employee partial unique index {@code uq_access_provisioning_tasks_open} admits one open
     * task, so two concurrent facts for one employee would otherwise both pass the pre-check and one
     * would be refused at commit — losing a legitimate HR write. The lock serializes the whole
     * decision (open-set read, suppress-or-supersede, create) on the employee row, inside the
     * caller's transaction, so the second transaction re-reads the committed open set instead of a
     * stale one.</p>
     *
     * <p>The lock order is <b>employee &rarr; task</b> for every writer: the HR facts
     * ({@code ContractService.createContract}, {@code EmployeeScopeAssignmentService}) and
     * {@code EmployeeService.updateEmployee} all reach the task only through the producer, after
     * their own row write. A path that held a task lock and then took this one would invert the
     * order and deadlock against them.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.id = :id AND e.company.id = :companyId")
    Optional<Employee> findByIdAndCompanyIdForUpdate(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /**
     * The employee row of the account that owns {@code keycloakUserId}, if any.
     *
     * <p>This is the <b>self-scoped</b> resolution the profile path needs: the caller can only ever
     * reach their own employee row, because the value comes from the authenticated principal's own
     * {@code sub} claim. That is why this finder carries <b>no</b> company scope, unlike every other
     * method here: the company is not part of the question, and requiring it would make the profile
     * read fail for a caller whose token carries no company claim. The column is {@code UNIQUE}, so
     * the result is at most one row.</p>
     */
    Optional<Employee> findByKeycloakUserId(String keycloakUserId);

    /**
     * The employee row with the two associations the access-provisioning pass reads — {@code company}
     * and {@code status} — <b>initialized in the same query</b>.
     *
     * <p>Its consumer is {@code AccessProvisioningWorker.process}, which owns <b>no</b> transaction
     * (records T44/T57), so an employee loaded with the inherited {@code findById} is detached by the
     * time the capabilities read it and every {@code employee.getCompany()} or
     * {@code employee.getStatus()} throws {@code LazyInitializationException} (gap G45). A
     * {@code @Transactional} capability does not reattach a detached entity, so the initialization has
     * to happen at the read, which is exactly what this finder does: both associations arrive fetched
     * and the entity survives leaving the session.
     *
     * <p>The two paths are inner joins on purpose: both columns are {@code NOT NULL} in
     * {@code employees}, so an inner join cannot drop a row that the inherited {@code findById} would
     * have returned — the two finders agree on existence, and the caller's "vanished row" branch keeps
     * meaning what it says.</p>
     *
     * <p><b>Why this finder owns a read-only transaction, and why it has to.</b> A declared {@code @Query}
     * method carries no transaction of its own, and ADR-0005 installs the tenant in
     * {@code TenantScopeJpaTransactionManager#doBegin}: a read that owns no boundary therefore has nowhere
     * to install the tenant its caller stated, so {@code set_config('app.company_id', …, true)} is never
     * issued for it and the policy filters every row. Measured on T6b-3 with the pool connected as
     * {@code lifecontrol_app}: inside the worker's stated scope this finder answered {@code present=false}
     * while the inherited {@code findById} — which {@code SimpleJpaRepository} already makes transactional —
     * answered {@code present=true}, and the worker turned that empty read into "Employee not found" for
     * every claimed task. The boundary belongs here rather than in a service that would exist only to carry
     * a transaction: this is the one production caller's read ({@code AccessProvisioningWorker#process}),
     * and stating it where the query lives keeps the fail-closed direction — an employee of another company
     * is still a read that returns empty, so the caller's own not-found branch fails the task instead of
     * applying it to the wrong tenant.</p>
     *
     * <p>{@code readOnly = true} is the honest value: the statement is a {@code SELECT} with two fetch
     * joins, it modifies nothing, and this interface declares no {@code @Modifying} query at all.</p>
     */
    @Transactional(readOnly = true)
    @Query("SELECT e FROM Employee e JOIN FETCH e.company JOIN FETCH e.status WHERE e.id = :id")
    Optional<Employee> findByIdWithCompanyAndStatus(@Param("id") UUID id);

    /**
     * Company-scoped list with optional {@code search}, {@code statusId} and {@code includeDisabled}
     * predicates, ordered deterministically by {@code employeeNumber}.
     *
     * <p>The repository's first company-scoped list that filters, so it is a single JPQL query with
     * optional predicates instead of derived methods. The {@code search} term is normalized by the
     * service to a lowercased {@code %…%} pattern, and the {@code cast(:search as string)} is
     * required: without it this stack cannot infer the type of a null {@code String} parameter in
     * {@code :search IS NULL}. The {@code search} does not escape {@code %} or {@code _}, matching
     * {@code CompanyRepository.findBySearchTerm}'s precedent.</p>
     */
    @Query("""
            SELECT e FROM Employee e
            JOIN FETCH e.status
            WHERE e.company.id = :companyId
              AND (:includeDisabled = true OR e.enabled = true)
              AND (:statusId IS NULL OR e.status.id = :statusId)
              AND (cast(:search as string) IS NULL
                   OR LOWER(e.employeeNumber) LIKE :search
                   OR LOWER(e.firstName) LIKE :search
                   OR LOWER(e.paternalLastName) LIKE :search
                   OR LOWER(e.maternalLastName) LIKE :search
                   OR LOWER(e.email) LIKE :search)
            ORDER BY e.employeeNumber ASC
            """)
    List<Employee> findCompanyEmployees(
            @Param("companyId") UUID companyId,
            @Param("search") String search,
            @Param("statusId") UUID statusId,
            @Param("includeDisabled") boolean includeDisabled);

    boolean existsByCompanyIdAndEmail(UUID companyId, String email);

    boolean existsByCompanyIdAndEmailAndIdNot(UUID companyId, String email, UUID excludeId);

    boolean existsByCompanyIdAndEmployeeNumber(UUID companyId, String employeeNumber);

    boolean existsByCompanyIdAndEmployeeNumberAndIdNot(UUID companyId, String employeeNumber, UUID excludeId);
}
