package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.model.Contract;
import com.lifecontrol.api.hr.model.ContractType;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.SeniorityLevel;
import com.lifecontrol.api.hr.repository.ContractRepository;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Proves the enqueue decision's <b>serialization against real PostgreSQL and real concurrency</b>:
 * two facts asserted at the same instant for one employee, from two transactions that do not write
 * the employee row, both commit and leave exactly one open intent (record G39; spec S3).
 *
 * <p><b>Why a Mockito test cannot prove it.</b> The race is not a method-call shape: two transactions
 * each pass the producer's open-set pre-check and the task service's own pre-check, and the partial
 * unique index {@code uq_access_provisioning_tasks_open} then refuses one of them <b>at commit</b>
 * ({@code DataIntegrityViolationException} → HTTP 409), so the whole HR write — including the
 * legitimate contract row — rolls back. Only a database with a real row lock can observe whether the
 * guard serializes the two. The fix this suite pins is a pessimistic-write lock on the employee row
 * taken before the first read of the open set — at the top of {@code AccessProvisioningProducer.enqueue}
 * and at the top of {@code AccessProvisioningGateService.request}, the single door the producer and the
 * operator request path share — so the second transaction blocks until the first commits and then sees
 * the open task instead of racing it. The second scenario below is that second door: two direct
 * {@code request} calls for one employee, where the loser must get the <b>named</b>
 * {@code AccessProvisioningTaskAlreadyOpenException} and never a constraint violation.
 *
 * <p><b>Coordination is latched, not slept into place.</b> Both threads start on a
 * {@link CyclicBarrier}; each inserts its own {@code employee_contracts} row (a fact that does not
 * touch the employee row) and calls the producer inside its own
 * {@link TransactionTemplate}. Thread&nbsp;B signals right before it commits; thread&nbsp;A, after its
 * own producer call, waits for that signal and then for a short bounded grace before committing, so
 * B's task row lands while A's is still uncommitted. Every latch wait and every thread join is
 * bounded, and each thread's throwable is captured so a failure is a readable assertion rather than
 * an opaque crash. The class is deliberately <b>not</b> {@code @Transactional}: a test-wide
 * transaction would make every producer call join it, nothing could contend and the suite would pass
 * vacuously.
 *
 * <p><b>The employee is unlinked on purpose.</b> An unlinked employee makes the contract fact derive
 * {@code ACTIVATE} and keeps {@code AccessProvisioningGateService.deriveReviewedDiff} away from the
 * live Keycloak role read ({@code currentRoles}), which has no server in this context. Both threads
 * share one position and one seniority level; the two contract ranges touch without overlapping, so
 * the V21 exclusion constraint {@code ex_employee_contracts_no_overlap} (half-open) is satisfied.
 *
 * <p><b>Cleanup is leaf-first and scoped to this suite's company key</b>, and the globally unique
 * seniority-level row this suite creates is deleted too: the PostgreSQL container is shared per JVM,
 * so a leak fails a later suite rather than this one.
 */
@SpringBootTest
@DisplayName("Access Provisioning Enqueue Race Integration Tests")
class AccessProvisioningEnqueueRaceIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PROV-ENQUEUE-RACE-KEY";
    private static final String COMPANY_RFC = "PERQ010101AB";
    private static final String LEVEL_CODE = "PERQ999";
    private static final String LEVEL_NAME = "Enqueue Race Seniority";
    private static final int LEVEL_RANK = 8_888_888;
    private static final String ACTOR_SUB = "d4a1f0c2-0000-0000-0000-000000000042";
    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    /** Bound on every latch wait so a miswired harness fails instead of hanging the suite. */
    private static final long COORDINATION_TIMEOUT_SECONDS = 5;

    /** Bound on each thread join for the same reason. */
    private static final long THREAD_JOIN_TIMEOUT_SECONDS = 30;

    /**
     * How long A holds its still-uncommitted task after B signals, so B's commit lands first. Short
     * enough not to slow the suite, long enough for a local commit.
     */
    private static final long COMMIT_GRACE_MILLIS = 300;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private SeniorityLevelRepository seniorityLevelRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private AccessProvisioningTaskRepository taskRepository;

    @Autowired
    private AccessProvisioningProducer producer;

    @Autowired
    private AccessProvisioningGateService gateService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID companyId;
    private UUID employeeId;
    private UUID positionId;
    private UUID seniorityLevelId;

    @BeforeEach
    void seedOneUnlinkedEmployee() {
        deleteOwnFixtures();
        seedCompanyTree();
    }

    @AfterEach
    void leaveNoFixtureBehind() {
        deleteOwnFixtures();
    }

    @Test
    @DisplayName("two facts for one employee asserted at once both commit, leaving one open ACTIVATE")
    void twoConcurrentFactsBothCommit() throws Exception {
        var barrier = new CyclicBarrier(2);
        var bAboutToCommit = new CountDownLatch(1);
        var aError = new AtomicReference<Throwable>();
        var bError = new AtomicReference<Throwable>();

        // B is started first so it wins the employee-row lock in the common case. If A wins instead,
        // A's bounded wait below simply expires and the lock still serializes the two commits.
        var threadB = spawnTransactionThread(
                "fact-b",
                false,
                barrier,
                bAboutToCommit,
                bError,
                () -> insertFactAndEnqueue(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)));
        var threadA = spawnTransactionThread(
                "fact-a",
                true,
                barrier,
                bAboutToCommit,
                aError,
                () -> insertFactAndEnqueue(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1)));

        threadA.join(TimeUnit.SECONDS.toMillis(THREAD_JOIN_TIMEOUT_SECONDS));
        threadB.join(TimeUnit.SECONDS.toMillis(THREAD_JOIN_TIMEOUT_SECONDS));
        assertThat(threadA.isAlive()).as("thread A finished instead of hanging").isFalse();
        assertThat(threadB.isAlive()).as("thread B finished instead of hanging").isFalse();

        assertThat(aError.get())
                .as("the losing fact must not be refused at commit: no exception in thread A")
                .isNull();
        assertThat(bError.get()).as("thread B must commit cleanly").isNull();

        assertThat(contractCount())
                .as("both facts survive: the second commit must not roll its contract row back")
                .isEqualTo(2);

        var tasks = taskRepository.findByEmployeeIdOrderByRequestedAtDesc(employeeId);
        assertThat(tasks)
                .as("exactly one open intent remains for the employee: the second fact was suppressed")
                .hasSize(1);
        var open = tasks.getFirst();
        assertThat(open.getKind()).isEqualTo(ACTIVATE);
        assertThat(open.getStatus())
                .as("the surviving task is open, through the service's own OPEN_STATUSES set")
                .isIn(AccessProvisioningTaskService.OPEN_STATUSES);
    }

    @Test
    @DisplayName("two operator requests for one employee at once: one creates, the loser is refused by name")
    void twoConcurrentRequestsLeaveOneNamedRefusal() throws Exception {
        var barrier = new CyclicBarrier(2);
        var bAboutToCommit = new CountDownLatch(1);
        var requestAError = new AtomicReference<Throwable>();
        var requestBError = new AtomicReference<Throwable>();

        // The operator request path is the second door into task creation, and it writes nothing else:
        // the loser of this race deserves the named duplicate-request refusal, not a raw
        // constraint-violation failure. Both use the same kind because the fact is a duplicate request.
        var threadB = spawnTransactionThread(
                "request-b",
                false,
                barrier,
                bAboutToCommit,
                requestBError,
                () -> gateService.request(companyId, employeeId, ACTIVATE, ACTOR_SUB));
        var threadA = spawnTransactionThread(
                "request-a",
                true,
                barrier,
                bAboutToCommit,
                requestAError,
                () -> gateService.request(companyId, employeeId, ACTIVATE, ACTOR_SUB));

        threadA.join(TimeUnit.SECONDS.toMillis(THREAD_JOIN_TIMEOUT_SECONDS));
        threadB.join(TimeUnit.SECONDS.toMillis(THREAD_JOIN_TIMEOUT_SECONDS));
        assertThat(threadA.isAlive())
                .as("request thread A finished instead of hanging")
                .isFalse();
        assertThat(threadB.isAlive())
                .as("request thread B finished instead of hanging")
                .isFalse();

        var errors = Arrays.asList(requestAError.get(), requestBError.get());
        assertThat(errors.stream().filter(Objects::nonNull))
                .as("exactly one of the two requests must lose")
                .hasSize(1);
        var loserError = errors.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        assertThat(loserError)
                .as("the duplicate request is refused by name, never by uq_access_provisioning_tasks_open")
                .isInstanceOf(AccessProvisioningTaskAlreadyOpenException.class);

        var tasks = taskRepository.findByEmployeeIdOrderByRequestedAtDesc(employeeId);
        assertThat(tasks).as("exactly one open intent exists for the employee").hasSize(1);
        assertThat(tasks.getFirst().getKind()).isEqualTo(ACTIVATE);
        assertThat(tasks.getFirst().getStatus())
                .as("the surviving task is open, through the service's own OPEN_STATUSES set")
                .isIn(AccessProvisioningTaskService.OPEN_STATUSES);
    }

    /**
     * Runs one fact in its own transaction on its own thread. The barrier releases both threads
     * together; {@code waitsForTheOther} is the second committer's half of the ordering.
     */
    private Thread spawnTransactionThread(
            String name,
            boolean waitsForTheOther,
            CyclicBarrier barrier,
            CountDownLatch peerAboutToCommit,
            AtomicReference<Throwable> error,
            Runnable inTransaction) {
        var thread = new Thread(
                () -> {
                    try {
                        bindRequestContext();
                        barrier.await(COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            inTransaction.run();
                            if (waitsForTheOther) {
                                awaitSignalThenGrace(peerAboutToCommit);
                            } else {
                                peerAboutToCommit.countDown();
                            }
                        });
                    } catch (Throwable failure) {
                        error.set(failure);
                    } finally {
                        clearRequestContext();
                    }
                },
                name);
        thread.start();
        return thread;
    }

    /** A fact and the intent it implies, in the caller's own transaction. */
    private void insertFactAndEnqueue(LocalDate start, LocalDate end) {
        var employee = employeeRepository.findById(employeeId).orElseThrow();
        var position = positionRepository.findById(positionId).orElseThrow();
        var seniorityLevel = seniorityLevelRepository.findById(seniorityLevelId).orElseThrow();

        // The fact is a plain contract insert: it references the employee by id and must not write
        // the employee row, which is what makes the race reachable at all.
        contractRepository.save(Contract.builder()
                .employee(employee)
                .position(position)
                .seniorityLevel(seniorityLevel)
                .contractType(ContractType.PERMANENT)
                .monthlySalary(new BigDecimal("1500.00"))
                .startDate(start)
                .endDate(end)
                .enabled(true)
                .build());

        producer.onContractActivated(companyId, employee);
    }

    /**
     * Thread A's half: wait (bounded) for B's pre-commit signal, then hold the still-uncommitted task
     * for the grace window so B's commit lands first. A timeout is not a failure — when A won the
     * employee-row lock, B cannot reach its signal until A commits, so A proceeds and the lock
     * serializes the two just the same.
     */
    private static void awaitSignalThenGrace(CountDownLatch bAboutToCommit) {
        try {
            bAboutToCommit.await(COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            Thread.sleep(COMMIT_GRACE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Binds the request and security context this thread needs: {@code CurrentUserContext} is
     * request-scoped, and the producer reaches {@code verifyCompanyAccess}, so the proxy needs a
     * bound request plus an admin authority to pass through it.
     */
    private void bindRequestContext() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var jwt = Jwt.withTokenValue("enqueue-race-token")
                .header("alg", "none")
                .subject(ACTOR_SUB)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(ROLE_LC_ADMIN)));
    }

    private void clearRequestContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private int contractCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employee_contracts WHERE employee_id = ?", Integer.class, employeeId);
    }

    private void seedCompanyTree() {
        var company = companyRepository.save(Company.builder()
                .companyKey(COMPANY_KEY)
                .companyName("Enqueue Race Test Company")
                .rfc(COMPANY_RFC)
                .emailDomain("enqueue-race.example")
                .enabled(true)
                .build());
        companyId = company.getId();

        var department = departmentRepository.save(Department.builder()
                .company(company)
                .departmentCode("RACE")
                .departmentName("Race Operations")
                .enabled(true)
                .build());
        positionId = positionRepository
                .save(Position.builder()
                        .department(department)
                        .positionCode("RACE01")
                        .positionName("Race Operator")
                        .enabled(true)
                        .build())
                .getId();

        // Globally unique by code/name/rank: the row is deleted in cleanup so it never collides with
        // another shared-container suite.
        seniorityLevelId = seniorityLevelRepository
                .save(SeniorityLevel.builder()
                        .levelCode(LEVEL_CODE)
                        .levelName(LEVEL_NAME)
                        .rank(LEVEL_RANK)
                        .enabled(true)
                        .build())
                .getId();

        employeeId = employeeRepository
                .save(Employee.builder()
                        .company(company)
                        .employeeNumber("PERQ-1")
                        .firstName("Ada")
                        .paternalLastName("Lovelace")
                        .email("ada.lovelace@enqueue-race.example")
                        .birthDate(LocalDate.of(1990, 1, 1))
                        .hireDate(LocalDate.of(2020, 1, 1))
                        .status(activeStatus())
                        .enabled(true)
                        .build())
                .getId();
    }

    private Status activeStatus() {
        return statusRepository
                .findByTypeNameAndStatusName("EMPLOYEE_STATUS", "Active")
                .orElseThrow(() -> new IllegalStateException("seed status is missing: Active"));
    }

    /**
     * Deletes this suite's rows leaf-first and scoped to its own company key, plus the globally
     * unique seniority level it created. The base class' unscoped
     * {@code deleteAllAccessProvisioningRows} is deliberately not used: this suite does not delete
     * every employee, so it is not entitled to delete every intent.
     */
    private void deleteOwnFixtures() {
        jdbcTemplate.update("""
                DELETE FROM access_provisioning_applied_roles WHERE task_id IN
                    (SELECT t.id FROM access_provisioning_tasks t
                     JOIN employees e ON e.id = t.employee_id
                     JOIN companies c ON c.id = e.company_id
                     WHERE c.company_key = ?)
                """, COMPANY_KEY);
        jdbcTemplate.update("""
                DELETE FROM access_provisioning_reviewed_roles WHERE task_id IN
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
                DELETE FROM employee_contracts WHERE employee_id IN
                    (SELECT e.id FROM employees e
                     JOIN companies c ON c.id = e.company_id
                     WHERE c.company_key = ?)
                """, COMPANY_KEY);
        jdbcTemplate.update("""
                DELETE FROM employees WHERE company_id IN
                    (SELECT id FROM companies WHERE company_key = ?)
                """, COMPANY_KEY);
        jdbcTemplate.update("""
                DELETE FROM positions WHERE department_id IN
                    (SELECT id FROM departments WHERE company_id IN
                        (SELECT id FROM companies WHERE company_key = ?))
                """, COMPANY_KEY);
        jdbcTemplate.update("""
                DELETE FROM departments WHERE company_id IN
                    (SELECT id FROM companies WHERE company_key = ?)
                """, COMPANY_KEY);
        jdbcTemplate.update("DELETE FROM seniority_levels WHERE level_code = ?", LEVEL_CODE);
        jdbcTemplate.update("DELETE FROM companies WHERE company_key = ?", COMPANY_KEY);
    }
}
