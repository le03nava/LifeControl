package com.lifecontrol.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.service.AccessProvisioningWorker;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.InMemoryIdentityProvider;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The asynchronous path's own scope: the one read of {@code runPass} that touches a table ADR-0005 arms
 * runs inside the <b>task's</b> tenant, not inside whatever the thread happened to carry.
 *
 * <p><b>Why the worker is the case that matters.</b> It runs on a scheduled thread no servlet filter ever
 * sees, and it reads {@code employees} (record L3). Without a scope the read is not an error: it is zero
 * rows under a policy, so provisioning would stop in silence — the failure mode the ADR names in its
 * consequences. The pass therefore states its own scope, from the task's own company, once per task.</p>
 *
 * <p><b>What this suite can and cannot observe.</b> It observes the fact that is the worker's
 * responsibility: <b>which company is installed at the moment of the read</b>, captured through a spy on
 * the repository so the assertion is about the call the pass really makes. It does not observe the policy
 * filtering the row, and that is deliberate: the container's user is a superuser, which bypasses every
 * policy (record L9), and arming {@code employees} globally would make the other 63 suites in this shared
 * container depend on this one. The filtering half is proved by
 * {@code TenantScopeBoundaryIntegrationTest}, on a probe table armed with the same
 * {@code apply_tenant_isolation} call T5 will use; the two cases compose into the claim — the worker
 * installs the task's tenant, and a transaction with that tenant installed can only see that company's
 * rows.</p>
 *
 * <p>The identity provider is substituted so the pass runs to its own edge instead of a Keycloak timeout;
 * everything else — the worker, the state machine, the capabilities, Flyway's schema, PostgreSQL — is the
 * production wiring, exactly as in {@code AccessProvisioningWorkerApplyIntegrationTest}.</p>
 */
@SpringBootTest
@DisplayName("Access provisioning worker tenant scope")
class ProvisioningWorkerTenantScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PROV-SCOPE-KEY";
    private static final String COMPANY_RFC = "PSCO010101AB";
    private static final String OTHER_COMPANY_KEY = "PROV-SCOPE-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "PSOT010101AB";

    @TestConfiguration
    static class InMemoryIdentityProviderConfiguration {

        @Bean
        @Primary
        IdentityProvider inMemoryIdentityProvider() {
            return new InMemoryIdentityProvider();
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccessProvisioningWorker worker;

    @MockitoSpyBean
    private EmployeeRepository employeeRepository;

    /** The scope the pass had installed when it read <b>this suite's</b> employee row. */
    private final AtomicReference<TenantScope.Scope> scopeAtTheEmployeeRead = new AtomicReference<>();

    /** How many times the pass read this suite's employee — a pass that never got there proves nothing. */
    private final AtomicInteger employeeReads = new AtomicInteger();

    // One test method per instance (JUnit's default), so the counter restarts and its natural keys never
    // collide with the @BeforeEach-cleared rows.
    private final AtomicInteger sequence = new AtomicInteger();

    private UUID companyId;
    private UUID employeeId;
    private UUID taskId;

    @BeforeEach
    void resetThisSuitesRows() {
        // Leaf-first and scoped to this suite's two company keys, so another suite's fixtures survive.
        jdbcTemplate.update("""
                DELETE FROM access_provisioning_applied_roles WHERE task_id IN
                    (SELECT t.id FROM access_provisioning_tasks t
                     JOIN employees e ON e.id = t.employee_id
                     JOIN companies c ON c.id = e.company_id
                     WHERE c.company_key IN (?, ?))
                """, COMPANY_KEY, OTHER_COMPANY_KEY);
        jdbcTemplate.update("""
                DELETE FROM access_provisioning_tasks WHERE employee_id IN
                    (SELECT e.id FROM employees e
                     JOIN companies c ON c.id = e.company_id
                     WHERE c.company_key IN (?, ?))
                """, COMPANY_KEY, OTHER_COMPANY_KEY);
        jdbcTemplate.update("""
                DELETE FROM employees WHERE company_id IN
                    (SELECT id FROM companies WHERE company_key IN (?, ?))
                """, COMPANY_KEY, OTHER_COMPANY_KEY);

        companyId = companyId(COMPANY_KEY, COMPANY_RFC);
        employeeId = insertEmployee(companyId);
        taskId = insertPendingTask(employeeId);

        scopeAtTheEmployeeRead.set(null);
        employeeReads.set(0);
        var watchedEmployeeId = employeeId;
        doAnswer(invocation -> {
                    if (watchedEmployeeId.equals(invocation.getArgument(0))) {
                        scopeAtTheEmployeeRead.set(TenantScope.current().orElse(null));
                        employeeReads.incrementAndGet();
                    }
                    return invocation.callRealMethod();
                })
                .when(employeeRepository)
                .findByIdWithCompanyAndStatus(any(UUID.class));
    }

    @AfterEach
    void clearTheThread() {
        TenantScope.clear();
    }

    @Test
    @DisplayName("the pass states the task's own company before it reads the employee")
    void thePassStatesTheTasksCompanyBeforeTheRead() {
        worker.runPass();

        assertThat(employeeReads.get())
                .as("a pass that never reached the employee read would prove nothing about its scope")
                .isEqualTo(1);
        assertThat(scopeAtTheEmployeeRead.get())
                .as("the read of a table T5 arms runs inside the task's tenant, and a platform thread "
                        + "states no subject")
                .isEqualTo(new TenantScope.Scope(companyId, null));
        assertThat(lastError())
                .as("the scoped read still finds the employee — whatever fails later in the pass, it is "
                        + "not the tenant that hid the row")
                .doesNotContain("Employee not found");
        assertThat(TenantScope.current())
                .as("and the scope dies with the pass: the scheduler's thread is reused by the next tick")
                .isEmpty();
    }

    @Test
    @DisplayName("the pass states the task's company even when the thread carries another scope")
    void thePassOverridesAScopeTheThreadAlreadyCarried() {
        // The shape of a leaked scope: a thread that served something else and still carries a tenant.
        // The pass must not inherit it — a task cannot be applied under a company that is not its own.
        var otherCompanyId = companyId(OTHER_COMPANY_KEY, OTHER_COMPANY_RFC);
        TenantScope.set(otherCompanyId, "kc-subject-somewhere-else");
        try {
            worker.runPass();
        } finally {
            TenantScope.clear();
        }

        assertThat(scopeAtTheEmployeeRead.get())
                .as("the task's company, not the one the thread was carrying: the read sees the task's "
                        + "employee or nothing, never another tenant's")
                .isEqualTo(new TenantScope.Scope(companyId, null));
    }

    // ──────────────────────────────────────
    // fixtures
    // ──────────────────────────────────────

    private String lastError() {
        var error = jdbcTemplate.queryForObject(
                "SELECT last_error FROM access_provisioning_tasks WHERE id = ?", String.class, taskId);
        return error == null ? "" : error;
    }

    /** Find-or-create the company the employee fixture hangs off, mirroring the sibling worker suites. */
    private UUID companyId(String companyKey, String rfc) {
        return companyRepository
                .findByCompanyKey(companyKey)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(companyKey)
                        .companyName("Access Provisioning Scope " + companyKey)
                        .rfc(rfc)
                        .enabled(true)
                        .build()))
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

    /** Inserts one employee owned by the given company; the pass only needs the row and its company. */
    private UUID insertEmployee(UUID ownerCompanyId) {
        var statusId = activeEmployeeStatusId();
        var n = sequence.incrementAndGet();
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, ?, 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                """, id, ownerCompanyId, "EMP-PROVS-" + n, "provs.employee" + n + "@example.com", statusId);
        return id;
    }

    /**
     * Inserts one due {@code PENDING} task — the only status the claim loop takes — so the pass reaches the
     * employee read this suite measures. {@code next_attempt_at} is left NULL, which is "never failed".
     */
    private UUID insertPendingTask(UUID ownerEmployeeId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_provisioning_tasks
                    (id, employee_id, kind, status, attempts, requested_by, requested_at, updated_at)
                VALUES (?, ?, 'ACTIVATE', 'PENDING', 0, 'kc-sub-requester', ?, ?)
                """, id, ownerEmployeeId, LocalDateTime.now(), LocalDateTime.now());
        return id;
    }
}
