package com.lifecontrol.api.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.provisioning.service.AccessProvisioningGateService;
import com.lifecontrol.api.provisioning.service.AccessProvisioningProducer;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the producer's atomicity claim <b>against real PostgreSQL and a real transaction manager</b>,
 * which is the only way the claim can be observed at all.
 *
 * <p><b>What is being proven.</b> The producer's own class comment states two things a Mockito test
 * cannot touch: (a) the intent is written in the <b>same transaction</b> as the organisational fact,
 * so a fact asserted while the employee already has an open task still commits — the queued task
 * already represents it; and (b) the producer therefore <b>asks before it enqueues</b> instead of
 * calling the gate and catching the refusal, because a {@link RuntimeException} crossing a
 * participating {@code @Transactional} boundary marks the surrounding transaction
 * <b>rollback-only</b>, so the catch would leave the fact to die at commit with an
 * {@link UnexpectedRollbackException}.</p>
 *
 * <p><b>Why (b) needs a test of its own.</b> It is the measured reason a design was rejected, and the
 * alternative shape is one line shorter than the chosen one — a reviewer is entitled to ask why not.
 * {@link NaiveFactWriter} <b>is</b> that rejected shape: it writes the fact, calls the gate, and
 * catches the refusal. The test asserts the failure mode rather than describing it: the surrounding
 * transaction cannot commit and the fact is rolled back. Without this the correction would be an
 * assertion of Spring's semantics in a comment.</p>
 *
 * <p><b>What it deliberately does not do.</b> It does not drive {@code ContractService} or
 * {@code EmployeeService}: those services need a company, a position, a seniority level and a
 * security context to reach one line of the hook, and the hook's <b>ordering</b> is already pinned by
 * their own unit tests. What belongs to this suite is the transaction, so the fact is written
 * directly and the producer is called the way the hooks call it. It also leaves the security context
 * out of the first test on purpose: with an open task the producer returns before it reaches any
 * company check, which is itself evidence that the tolerated path touches nothing.</p>
 */
@SpringBootTest
@DisplayName("Access Provisioning Producer Transaction Integration Tests")
class AccessProvisioningProducerTransactionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PROV-PRODUCER-KEY";
    private static final String COMPANY_RFC = "PROVP010101AB";
    private static final String ACTOR_SUB = "d4a1f0c2-0000-0000-0000-000000000001";

    /** A phone number neither the seed nor any other suite writes, so a leak is unambiguous. */
    private static final String SWALLOWED_PHONE = "+52-555-0100";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private AccessProvisioningTaskRepository taskRepository;

    @Autowired
    private AccessProvisioningProducer producer;

    @Autowired
    private NaiveFactWriter naiveFactWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID companyId;
    private UUID employeeId;

    /**
     * The shape record T88 rejected, kept as a test fixture so its failure mode is a fact rather than
     * a claim in a comment: write the fact, call the gate, catch the already-open refusal.
     */
    static class NaiveFactWriter {

        private final EmployeeRepository employeeRepository;
        private final AccessProvisioningGateService gateService;

        NaiveFactWriter(EmployeeRepository employeeRepository, AccessProvisioningGateService gateService) {
            this.employeeRepository = employeeRepository;
            this.gateService = gateService;
        }

        @Transactional
        public void writeFactAndSwallow(UUID companyId, UUID employeeId) {
            var employee = employeeRepository
                    .findByIdAndCompanyId(employeeId, companyId)
                    .orElseThrow(() -> new IllegalStateException("fixture employee is missing"));
            employee.setPhoneNumber(SWALLOWED_PHONE);
            employeeRepository.save(employee);

            try {
                gateService.request(companyId, employeeId, AccessProvisioningTaskKind.DEACTIVATE, ACTOR_SUB);
            } catch (AccessProvisioningTaskAlreadyOpenException swallowed) {
                // The catch the plan first proposed. It compiles, it looks tolerant, and the boundary
                // that threw has already marked this transaction rollback-only.
            }
        }
    }

    @TestConfiguration
    static class NaiveProducerConfiguration {

        @Bean
        NaiveFactWriter naiveFactWriter(
                EmployeeRepository employeeRepository, AccessProvisioningGateService gateService) {
            return new NaiveFactWriter(employeeRepository, gateService);
        }
    }

    @BeforeEach
    void resetProvisioningTables() {
        // Leaf-first and scoped to this suite's own company key, so a fixture another suite left in
        // these shared tables is never removed.
        jdbcTemplate.update("""
                DELETE FROM access_provisioning_applied_roles WHERE task_id IN
                    (SELECT t.id FROM access_provisioning_tasks t
                     JOIN employees e ON e.id = t.employee_id
                     JOIN companies c ON c.id = e.company_id
                     WHERE c.company_key = ?)
                """, COMPANY_KEY);
        jdbcTemplate.update("""
                DELETE FROM access_provisioning_tasks WHERE employee_id IN
                    (SELECT e.id FROM employees e
                     JOIN companies c ON c.id = e.company_id
                     WHERE c.company_key = ?)
                """, COMPANY_KEY);
        jdbcTemplate.update("""
                DELETE FROM employees WHERE company_id IN
                    (SELECT id FROM companies WHERE company_key = ?)
                """, COMPANY_KEY);
        jdbcTemplate.update("DELETE FROM companies WHERE company_key = ?", COMPANY_KEY);

        seedActiveEmployee();
    }

    private void seedActiveEmployee() {
        var company = companyRepository.save(Company.builder()
                .companyKey(COMPANY_KEY)
                .companyName("Provisioning Producer Company")
                .rfc(COMPANY_RFC)
                .emailDomain("prov-producer.example")
                .enabled(true)
                .build());
        companyId = company.getId();

        var employee = employeeRepository.save(Employee.builder()
                .company(company)
                .employeeNumber("PROV-1")
                .firstName("Juan")
                .paternalLastName("Pérez")
                .email("juan.perez@prov-producer.example")
                .birthDate(LocalDate.of(1990, 1, 1))
                .hireDate(LocalDate.of(2020, 1, 1))
                .status(status("Active"))
                .enabled(true)
                .build());
        employeeId = employee.getId();
    }

    private Status status(String name) {
        return statusRepository
                .findByTypeNameAndStatusName("EMPLOYEE_STATUS", name)
                .orElseThrow(() -> new IllegalStateException("seed status is missing: " + name));
    }

    private void openTaskExists() {
        var employee = employeeRepository.findById(employeeId).orElseThrow();
        taskRepository.save(AccessProvisioningTask.builder()
                .employee(employee)
                .kind(AccessProvisioningTaskKind.DEACTIVATE)
                .status(AccessProvisioningTaskStatus.PENDING)
                .requestedBy(ACTOR_SUB)
                .requestedAt(LocalDateTime.now())
                .attempts(0)
                .build());
    }

    @Test
    @DisplayName("the fact commits when the employee already has an open task, and no second task is written")
    void theFactCommitsAndThePromiseIsNotDuplicated() {
        openTaskExists();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var employee = employeeRepository.findById(employeeId).orElseThrow();
            employee.setStatus(status("Terminated"));
            employee.setTerminationDate(LocalDate.of(2026, 1, 1));
            employeeRepository.save(employee);

            producer.onEmploymentTerminated(companyId, employee);
        });

        // The status is a lazy association, so it is read inside a session: outside one, a detached
        // employee's status proxy cannot be initialized and the assertion would fail for the wrong
        // reason.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var committed = employeeRepository.findById(employeeId).orElseThrow();
            assertThat(committed.getStatus().getStatusName()).isEqualTo("Terminated");
            assertThat(committed.getTerminationDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        });
        assertThat(taskRepository.findByEmployeeIdOrderByRequestedAtDesc(employeeId))
                .as("the queued task already represents the fact, so the producer must not add another")
                .hasSize(1);
    }

    @Test
    @WithMockUser(roles = "lc-admin")
    @DisplayName("the rejected shape cannot save the fact: the surrounding transaction comes back rollback-only")
    void theNaiveShapeCannotSwallowTheRefusal() {
        openTaskExists();

        assertThatThrownBy(() -> naiveFactWriter.writeFactAndSwallow(companyId, employeeId))
                .as("the boundary that threw marked the joined transaction rollback-only, so the fact dies at commit")
                .isInstanceOf(UnexpectedRollbackException.class);

        assertThat(employeeRepository.findById(employeeId).orElseThrow().getPhoneNumber())
                .as("the fact rolled back with the transaction that could not commit")
                .isNotEqualTo(SWALLOWED_PHONE);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM access_provisioning_tasks WHERE employee_id = ?",
                        Integer.class,
                        employeeId))
                .isEqualTo(1);
    }
}
