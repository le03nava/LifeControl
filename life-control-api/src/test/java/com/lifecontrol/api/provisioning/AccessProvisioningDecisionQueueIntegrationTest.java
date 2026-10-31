package com.lifecontrol.api.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningQueueEntry;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves, against real PostgreSQL, the <b>scope of the company decision queue</b>: which rows a queue
 * read may see at all (unit W5d, slice B, record T94).
 *
 * <p>A mock cannot prove this. The two filters the read is built on are one query each — the company
 * enters through {@code employees.company_id}, because {@code access_provisioning_tasks} carries no
 * {@code company_id} (V22), and the status is {@code APPROVAL_PENDING} — and a unit test that stubs the
 * repository asserts that the service asked correctly, never that the query answers correctly. The
 * distinction matters most for the company filter: a queue that leaked another company's waiting tasks
 * would be the cross-company read the sibling employee-scoped read was corrected for in W5b, and only a
 * real database can say whether the join in {@code findByEmployeeCompanyIdAndStatus} is the one that
 * scopes it.</p>
 *
 * <p>What it also proves, in the same run: the row carries the employee it belongs to (which is what the
 * {@code JOIN FETCH} exists for), the order is the queue's own — oldest request first, which is the
 * <b>opposite</b> of the per-employee history — and the requester's name is resolved once for the
 * distinct actor the rows share rather than once per row (record T93).</p>
 *
 * <p><b>Why the identity provider is mocked and the database is not.</b> The port is the one dependency
 * that would leave this process; the point of the suite is the SQL, so the port answers a fixed name and
 * its single invocation is asserted instead. The security context is mocked for the opposite reason:
 * {@code @WithMockUser(roles = "lc-admin")} makes the read's own company check a pass-through, which is
 * what keeps this suite about the query and not about authorization — the gate itself is pinned by the
 * controller's own security suite.</p>
 *
 * <p>Cleanup is leaf-first and scoped to this suite's two company keys, so a fixture another suite left
 * in the shared container is never removed.</p>
 */
@SpringBootTest
@WithMockUser(roles = "lc-admin")
@DisplayName("Employee Access Provisioning Decision Queue Integration Tests")
class AccessProvisioningDecisionQueueIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PROV-QUEUE-KEY";
    private static final String COMPANY_RFC = "PROVQ010101AB";
    private static final String OTHER_COMPANY_KEY = "PROV-QUEUE-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "PROVQ010101CD";
    private static final String REQUESTER = "kc-sub-requester";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccessProvisioningQueryService queryService;

    /**
     * The port the read resolves the requester's name through. Mocked because it is the only dependency
     * that would reach another system from this suite; the store it would answer from is not what this
     * suite is about.
     */
    @MockitoBean
    private IdentityProvider identityProvider;

    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void resetQueueFixtures() {
        deleteScopedRows(COMPANY_KEY);
        deleteScopedRows(OTHER_COMPANY_KEY);
    }

    /**
     * Leaves no row of this suite behind on the way out: {@code access_provisioning_tasks.employee_id}
     * references {@code employees}, so a task of this suite outliving the class breaks every later suite
     * whose own cleanup is an unscoped {@code DELETE FROM employees} on that foreign key (T90).
     */
    @AfterEach
    void leaveNoQueueFixtureBehind() {
        deleteScopedRows(COMPANY_KEY);
        deleteScopedRows(OTHER_COMPANY_KEY);
    }

    @Test
    @DisplayName("the queue is that company's APPROVAL_PENDING tasks, oldest request first, with the employee")
    void queueIsTheCompanyApprovalPendingTasksOldestFirst() {
        // Stubbed here rather than in @BeforeEach: the empty-queue case asserts the port is never
        // touched, and a suite-level stub is the interaction that assertion cannot tell apart.
        when(identityProvider.findUserDisplayName(anyString())).thenReturn(Optional.of("Ada Lovelace"));
        var companyId = companyId(COMPANY_KEY, "Access Provisioning Queue Test Company", COMPANY_RFC);
        var otherCompanyId = companyId(OTHER_COMPANY_KEY, "Other Access Provisioning Test Company", OTHER_COMPANY_RFC);

        var ada = insertEmployee(companyId, "EMP-QUEUE-1", "Ada", "Lovelace", "Byron");
        var grace = insertEmployee(companyId, "EMP-QUEUE-2", "Grace", "Hopper", null);
        var alan = insertEmployee(companyId, "EMP-QUEUE-3", "Alan", "Turing", null);
        var foreign = insertEmployee(otherCompanyId, "EMP-QUEUE-4", "Katherine", "Johnson", null);

        // The employee's terminal task is history, not a decision: same employee, already applied.
        insertTask(ada, "APPLIED", LocalDateTime.of(2026, 10, 9, 9, 0), LocalDateTime.of(2026, 10, 9, 10, 0));
        var olderWaiting = insertTask(ada, "APPROVAL_PENDING", LocalDateTime.of(2026, 10, 10, 9, 0), null);
        var newerWaiting = insertTask(grace, "APPROVAL_PENDING", LocalDateTime.of(2026, 10, 11, 9, 0), null);
        // Open, but not a decision: the queue is the decision set and this unit does not widen it (G42).
        insertTask(alan, "PENDING", LocalDateTime.of(2026, 10, 8, 9, 0), null);
        // Waiting for a human in another company: the join from the employee is the only scope there is.
        insertTask(foreign, "APPROVAL_PENDING", LocalDateTime.of(2026, 10, 7, 9, 0), null);

        // The service is called directly, so no request states the tenant its query's join to
        // `employees` needs; without it the join yields no row for any task.
        var queue = TenantFixtures.inTenant(companyId, () -> queryService.getDecisionQueue(companyId));

        assertThat(queue)
                .as("exactly the two APPROVAL_PENDING tasks of this company, oldest request first")
                .extracting(AccessProvisioningQueueEntry::taskId)
                .containsExactly(olderWaiting, newerWaiting);
        assertThat(queue).allSatisfy(entry -> {
            assertThat(entry.status()).isEqualTo(AccessProvisioningTaskStatus.APPROVAL_PENDING);
            assertThat(entry.kind()).isEqualTo(AccessProvisioningTaskKind.ACTIVATE);
            assertThat(entry.requestedBy()).isEqualTo(REQUESTER);
            assertThat(entry.requestedByName()).isEqualTo("Ada Lovelace");
        });

        var first = queue.getFirst();
        assertThat(first.employeeId())
                .as("the row carries the employee it belongs to")
                .isEqualTo(ada);
        assertThat(first.employeeNumber()).isEqualTo("EMP-QUEUE-1");
        assertThat(first.employeeFirstName()).isEqualTo("Ada");
        assertThat(first.employeePaternalLastName()).isEqualTo("Lovelace");
        assertThat(first.employeeMaternalLastName()).isEqualTo("Byron");

        // One lookup for the single distinct actor the rows share, never one per row.
        verify(identityProvider, times(1)).findUserDisplayName(REQUESTER);
    }

    @Test
    @DisplayName("the queue is empty when nothing waits for a human, and asks the port for no name")
    void queueIsEmptyWhenNothingWaitsForAHuman() {
        var companyId = companyId(COMPANY_KEY, "Access Provisioning Queue Test Company", COMPANY_RFC);
        var alan = insertEmployee(companyId, "EMP-QUEUE-5", "Alan", "Turing", null);
        var grace = insertEmployee(companyId, "EMP-QUEUE-6", "Grace", "Hopper", null);
        // An open task the worker owns, and a rehire's terminal one: neither is a decision.
        insertTask(alan, "PENDING", LocalDateTime.of(2026, 10, 12, 9, 0), null);
        insertTask(grace, "REJECTED", LocalDateTime.of(2026, 10, 12, 10, 0), null);

        assertThat(queryService.getDecisionQueue(companyId)).isEmpty();

        verifyNoInteractions(identityProvider);
    }

    private void deleteScopedRows(String companyKey) {
        // Stated under the key's own tenant, and the id is the one `TenantFixtures.findOrCreate` derives
        // from it: each DELETE joins `employees` and `companies`, both policy-armed, so unscoped it
        // matches zero rows — silently for the DELETE, and by hiding the company row the subquery needs.
        TenantFixtures.inTenant(TenantFixtures.derivedId(companyKey), () -> {
            jdbcTemplate.update("""
                    DELETE FROM access_provisioning_applied_roles WHERE task_id IN
                        (SELECT t.id FROM access_provisioning_tasks t
                         JOIN employees e ON e.id = t.employee_id
                         JOIN companies c ON c.id = e.company_id
                         WHERE c.company_key = ?)
                    """, companyKey);
            jdbcTemplate.update("""
                    DELETE FROM access_provisioning_reviewed_roles WHERE task_id IN
                        (SELECT t.id FROM access_provisioning_tasks t
                         JOIN employees e ON e.id = t.employee_id
                         JOIN companies c ON c.id = e.company_id
                         WHERE c.company_key = ?)
                    """, companyKey);
            jdbcTemplate.update("""
                    DELETE FROM access_provisioning_tasks WHERE employee_id IN
                        (SELECT e.id FROM employees e
                         JOIN companies c ON c.id = e.company_id
                         WHERE c.company_key = ?)
                    """, companyKey);
            jdbcTemplate.update(
                    "DELETE FROM employees WHERE company_id IN (SELECT id FROM companies WHERE company_key = ?)",
                    companyKey);
        });
    }

    /** Find-or-create one company row; reuses the persisted entity path rather than a raw insert. */
    private UUID companyId(String companyKey, String companyName, String rfc) {
        return TenantFixtures.findOrCreate(companyRepository, companyKey, companyName, rfc)
                .getId();
    }

    /** Resolves the seeded {@code Active} row of the {@code EMPLOYEE_STATUS} family. */
    private UUID activeEmployeeStatusId() {
        return jdbcTemplate.queryForObject("""
                SELECT s.id FROM statuses s
                JOIN status_types st ON st.id = s.status_type_id
                WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
                  AND LOWER(s.status_name) = LOWER('Active')
                """, UUID.class);
    }

    /**
     * Inserts one employee owned by the given company. The raw insert is deliberate — the employee is
     * the queue row's foreign key target and the name it renders, and no part of the state machine
     * depends on how the row was written.
     */
    private UUID insertEmployee(
            UUID companyId, String employeeNumber, String firstName, String paternalLastName, String maternalLastName) {
        var statusId = activeEmployeeStatusId();
        var n = sequence.incrementAndGet();
        var id = UUID.randomUUID();
        // `employees` is policy-armed, so the insert states the company that owns the row.
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO employees
                            (id, company_id, employee_number, first_name, paternal_last_name, maternal_last_name,
                             email, birth_date, hire_date, employment_status_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                        """,
                        id,
                        companyId,
                        employeeNumber,
                        firstName,
                        paternalLastName,
                        maternalLastName,
                        "provq.employee" + n + "@example.com",
                        statusId));
        return id;
    }

    /** Inserts one task in the status the case needs; {@code appliedAt} is set only for a terminal one. */
    private UUID insertTask(UUID employeeId, String status, LocalDateTime requestedAt, LocalDateTime appliedAt) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_provisioning_tasks
                    (id, employee_id, kind, status, attempts, requested_by, requested_at, applied_at)
                VALUES (?, ?, 'ACTIVATE', ?, 0, ?, ?, ?)
                """, id, employeeId, status, REQUESTER, requestedAt, appliedAt);
        return id;
    }
}
