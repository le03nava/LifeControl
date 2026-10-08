package com.lifecontrol.api.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.worker.WorkerRetryPolicy;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.config.provisioning.ProvisioningWorkerProperties;
import com.lifecontrol.api.provisioning.exception.InvalidTaskStatusTransitionException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.provisioning.service.AccessProvisioningTaskService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the atomicity of the access-provisioning state machine <b>against real PostgreSQL</b>, under
 * real contention.
 *
 * <p>What is being proven: the class comment of
 * {@link AccessProvisioningTaskService} claims that a pessimistic write lock is the only
 * serialization this design has — the table carries no {@code version} column and the entity no
 * {@code @Version} — and that a loser of a race re-reads the committed status after the lock is
 * released and is refused with {@link InvalidTaskStatusTransitionException} rather than overwriting
 * it. Two concurrent callers therefore cannot both move one row, and the loser cannot increment
 * {@code attempts} or write its own status. This suite exercises that claim through the worker's real
 * entry point ({@code findDueTaskIds}) and through the real service methods
 * ({@code claim}, {@code markFailed}, {@code retry}), with no mocks and no security context.</p>
 *
 * <p>Why the existing suites cannot prove it: every existing test of the lock is a Mockito test. They
 * pin that {@code findByIdAndEmployeeIdForUpdate} was <b>called</b>; none of them can observe whether
 * the lock does anything — whether a second caller actually blocks, or whether it re-reads the
 * committed row instead of a stale one. A Mockito test of a lock is a test of a method call, not a
 * test of a database guarantee.</p>
 *
 * <p>How contention is forced, and what the discriminating observation is: the winner's transaction
 * is held <b>open</b> on purpose. The winner thread claims the row inside a
 * {@link TransactionTemplate}, signals that the row is locked, and then parks on a release latch the
 * test controls. While the winner's transaction is still open, the test thread starts a loser and
 * waits up to two seconds for it to finish; the loser is expected <b>not</b> to finish, because its
 * {@code SELECT … FOR UPDATE} cannot acquire the row lock. That blocking window is the discriminating
 * observation: without {@code PESSIMISTIC_WRITE} the loser's read would complete immediately against
 * the winner's pre-commit state. The commit moment is then released, and the outcome assertions — the
 * loser threw, the loser returned nothing, and the committed row carries only the winner's values —
 * carry the "no lost update" half.</p>
 *
 * <p>What this harness still cannot prove, stated plainly: it cannot prove that the loser had already
 * issued its {@code SELECT … FOR UPDATE} at the instant the blocking window was measured, so the
 * two-second window is not presented as a proof of the loser's internal state — it is evidence of
 * blocking, and only the outcome assertions are load-bearing for atomicity. It also assumes
 * PostgreSQL's default {@code lock_timeout} is unset, so the loser <b>blocks until the winner
 * commits</b> rather than erroring out; if a lock timeout were configured, the loser would raise a
 * lock-timeout error instead and the exception assertion would need a different expected type.</p>
 *
 * <p>The class is deliberately <b>not</b> {@code @Transactional}: the base class starts no
 * transaction, and if this class opened one, every service call would join a single test-wide
 * transaction, nothing could contend, and the suite would pass vacuously. Cleanup is narrow and
 * leaf-first, scoped to this suite's own company key, so it never removes another suite's fixtures
 * from the shared container.</p>
 */
@SpringBootTest
@DisplayName("Employee Access Provisioning Task Concurrency Integration Tests")
class AccessProvisioningTaskConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PROV-CONCURRENCY-KEY";
    private static final String COMPANY_RFC = "PROVC010101AB";

    /** The window in which a correctly locked loser must still be blocked. */
    private static final long BLOCKING_WINDOW_SECONDS = 2;

    /** How long the gated winner or the test itself waits before declaring the harness stuck. */
    private static final long LATCH_TIMEOUT_SECONDS = 5;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccessProvisioningTaskRepository taskRepository;

    @Autowired
    private AccessProvisioningTaskService taskService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WorkerRetryPolicy workerRetryPolicy;

    @Autowired
    private ProvisioningWorkerProperties workerProperties;

    // One test method per test instance (JUnit's default lifecycle), so this counter restarts at 0
    // for each test and the natural keys it builds never collide with the @BeforeEach-cleared tables.
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void resetProvisioningTables() {
        // Leaf-first: applied_roles references access_provisioning_tasks, which references employees.
        // Every delete is scoped to the company this suite creates, so a fixture another suite — or a
        // later work unit — left in these shared tables is never removed.
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
    }

    @Test
    @DisplayName("two simultaneous claims of one due row leave exactly one winner and attempts = 1")
    void twoSimultaneousClaimsLeaveExactlyOneWinner() throws Exception {
        var employeeId = insertEmployee();
        var taskId = insertDuePendingTask(employeeId);

        // First connect the proof to the worker's real entry point: the claimed row must be visible
        // to the queue read before any claim runs.
        assertThat(taskRepository.findDueTaskIds(LocalDateTime.now(), PageRequest.of(0, 10)))
                .as("the worker's due-queue read must surface the due row before any claim")
                .contains(taskId);

        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var winnerError = new AtomicReference<Throwable>();
        var loserError = new AtomicReference<Throwable>();
        var loserResult = new AtomicReference<AccessProvisioningTask>();

        var winner = new Thread(
                () -> {
                    try {
                        // The claim joins this outer transaction, so the row lock is held until this
                        // callback returns — which is when the test releases the latch.
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            var claimed = taskService.claim(employeeId, taskId);
                            assertThat(claimed.getStatus())
                                    .as("the winner's claim transitions the row to RUNNING")
                                    .isEqualTo(AccessProvisioningTaskStatus.RUNNING);
                            assertThat(claimed.getAttempts())
                                    .as("the winner's claim increments attempts to 1")
                                    .isEqualTo(1);
                            locked.countDown();
                            await(release, LATCH_TIMEOUT_SECONDS, "the winner's commit release");
                        });
                    } catch (Throwable t) {
                        winnerError.set(t);
                    }
                },
                "winner-claim");
        winner.start();

        var loser = new Thread(
                () -> {
                    try {
                        await(locked, LATCH_TIMEOUT_SECONDS, "the winner to hold the row lock");
                        started.countDown();
                        loserResult.set(taskService.claim(employeeId, taskId));
                    } catch (Throwable t) {
                        loserError.set(t);
                    } finally {
                        done.countDown();
                    }
                },
                "loser-claim");
        loser.start();

        try {
            await(started, LATCH_TIMEOUT_SECONDS, "the loser to enter its claim");
            assertThat(done.await(BLOCKING_WINDOW_SECONDS, TimeUnit.SECONDS))
                    .as("the loser must stay blocked on the row lock while the winner's transaction " + "is still open")
                    .isFalse();
        } finally {
            release.countDown();
            winner.join(TimeUnit.SECONDS.toMillis(LATCH_TIMEOUT_SECONDS));
            loser.join(TimeUnit.SECONDS.toMillis(LATCH_TIMEOUT_SECONDS));
        }

        assertThat(winner.isAlive()).as("the winner thread finished").isFalse();
        assertThat(loser.isAlive()).as("the loser thread finished").isFalse();
        rethrow(winnerError.get());
        assertThat(loserError.get())
                .as("the loser re-read the committed RUNNING row and was refused")
                .isInstanceOf(InvalidTaskStatusTransitionException.class);
        assertThat(loserResult.get()).as("the loser produced no transition").isNull();

        assertThat(committedStatus(taskId)).as("exactly one claim committed").isEqualTo("RUNNING");
        assertThat(committedAttempts(taskId))
                .as("the loser neither transitioned nor incremented attempts")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a concurrent markFailed and retry cannot act on a stale read")
    void concurrentMarkFailedAndRetryCannotActOnAStaleRead() throws Exception {
        var employeeId = insertEmployee();
        var taskId = insertRunningTask(employeeId);
        // Truncated to microseconds: the column keeps microsecond precision, so the value that comes
        // back from PostgreSQL is exactly what was written.
        var deadline = LocalDateTime.now().plusMinutes(1).truncatedTo(ChronoUnit.MICROS);

        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var winnerError = new AtomicReference<Throwable>();
        var loserError = new AtomicReference<Throwable>();
        var loserResult = new AtomicReference<AccessProvisioningTask>();

        var winner = new Thread(
                () -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            var failed = taskService.markFailed(employeeId, taskId, "boom", deadline);
                            assertThat(failed.getStatus())
                                    .as("a non-null deadline ends the winner at PENDING")
                                    .isEqualTo(AccessProvisioningTaskStatus.PENDING);
                            assertThat(failed.getLastError())
                                    .as("the winner's reason is written")
                                    .isEqualTo("boom");
                            locked.countDown();
                            await(release, LATCH_TIMEOUT_SECONDS, "the winner's commit release");
                        });
                    } catch (Throwable t) {
                        winnerError.set(t);
                    }
                },
                "winner-mark-failed");
        winner.start();

        var loser = new Thread(
                () -> {
                    try {
                        await(locked, LATCH_TIMEOUT_SECONDS, "the winner to hold the row lock");
                        started.countDown();
                        loserResult.set(taskService.retry(employeeId, taskId));
                    } catch (Throwable t) {
                        loserError.set(t);
                    } finally {
                        done.countDown();
                    }
                },
                "loser-retry");
        loser.start();

        try {
            await(started, LATCH_TIMEOUT_SECONDS, "the loser to enter its retry");
            assertThat(done.await(BLOCKING_WINDOW_SECONDS, TimeUnit.SECONDS))
                    .as("the loser must stay blocked on the row lock while the winner's transaction " + "is still open")
                    .isFalse();
        } finally {
            release.countDown();
            winner.join(TimeUnit.SECONDS.toMillis(LATCH_TIMEOUT_SECONDS));
            loser.join(TimeUnit.SECONDS.toMillis(LATCH_TIMEOUT_SECONDS));
        }

        assertThat(winner.isAlive()).as("the winner thread finished").isFalse();
        assertThat(loser.isAlive()).as("the loser thread finished").isFalse();
        rethrow(winnerError.get());
        assertThat(loserError.get())
                .as("retry is refused from the winner's committed PENDING (and from RUNNING)")
                .isInstanceOf(InvalidTaskStatusTransitionException.class);
        assertThat(loserResult.get()).as("the loser produced no transition").isNull();

        assertThat(committedStatus(taskId))
                .as("the winner's PENDING survived; the loser overwrote nothing")
                .isEqualTo("PENDING");
        assertThat(committedAttempts(taskId)).as("attempts unchanged").isEqualTo(1);
        assertThat(committedLastError(taskId))
                .as("the winner's reason survived; the loser's clean-slate did not win")
                .isEqualTo("boom");
        assertThat(committedNextAttemptAt(taskId))
                .as("the winner's deadline is exactly what was written")
                .isEqualTo(deadline);
    }

    @Test
    @DisplayName("the retry policy bean follows the bound worker properties")
    void retryPolicyFollowsBoundWorkerProperties() {
        // This is the only class in the unit that boots the whole application context, so the policy
        // is asserted here against the bound values rather than against the literal defaults.
        assertThat(workerProperties.maxAttempts())
                .as("the bound attempt ceiling is present")
                .isNotNull();
        assertThat(workerProperties.maxAttempts()).isPositive();
        assertThat(workerRetryPolicy.backoffFor(1))
                .as("the first retry delays by the bound base delay")
                .isEqualTo(Duration.ofSeconds(workerProperties.baseDelaySeconds()));
        assertThat(workerRetryPolicy.exhausted(workerProperties.maxAttempts()))
                .as("the ceiling is exhausted")
                .isTrue();
        assertThat(workerRetryPolicy.exhausted(workerProperties.maxAttempts() - 1))
                .as("one below the ceiling is not")
                .isFalse();
    }

    /**
     * Find-or-create the company row the employee fixtures hang off, mirroring the sibling access
     * provisioning schema suite. Reuses the persisted entity path instead of hand-writing an insert
     * into {@code companies}; the rows this suite races over are inserted raw.
     */
    private UUID accessCompanyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Access Provisioning Concurrency Test Company")
                        .rfc(COMPANY_RFC)
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

    /**
     * Inserts one employee owned by this suite's company. The raw insert is deliberate: the employee
     * fixture is only the foreign key's target, and no part of the state machine depends on it.
     */
    private UUID insertEmployee() {
        var companyId = accessCompanyId();
        var statusId = activeEmployeeStatusId();
        var n = sequence.incrementAndGet();
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO employees
                    (id, company_id, employee_number, first_name, paternal_last_name, email,
                     birth_date, hire_date, employment_status_id)
                VALUES (?, ?, ?, 'Ada', 'Lovelace', ?, DATE '1990-01-01', DATE '2020-01-01', ?)
                """, id, companyId, "EMP-PROVC-" + n, "provc.employee" + n + "@example.com", statusId);
        return id;
    }

    /**
     * A genuinely due row: {@code PENDING} with {@code next_attempt_at} NULL, so the due-queue read
     * sees it without any clock manipulation.
     */
    private UUID insertDuePendingTask(UUID employeeId) {
        return insertTask(employeeId, "PENDING", 0, null, null);
    }

    /** A row already claimed: {@code RUNNING} with {@code attempts = 1}. */
    private UUID insertRunningTask(UUID employeeId) {
        return insertTask(employeeId, "RUNNING", 1, null, null);
    }

    private UUID insertTask(
            UUID employeeId, String status, int attempts, String lastError, LocalDateTime nextAttemptAt) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_provisioning_tasks
                    (id, employee_id, kind, status, attempts, last_error, requested_by,
                     requested_at, next_attempt_at)
                VALUES (?, ?, 'ACTIVATE', ?, ?, ?, 'kc-sub-requester', ?, ?)
                """, id, employeeId, status, attempts, lastError, LocalDateTime.now(), nextAttemptAt);
        return id;
    }

    /** The committed status, read on a fresh connection rather than through the entity. */
    private String committedStatus(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM access_provisioning_tasks WHERE id = ?", String.class, taskId);
    }

    private int committedAttempts(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempts FROM access_provisioning_tasks WHERE id = ?", Integer.class, taskId);
    }

    private String committedLastError(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM access_provisioning_tasks WHERE id = ?", String.class, taskId);
    }

    private LocalDateTime committedNextAttemptAt(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM access_provisioning_tasks WHERE id = ?",
                (rs, rowNum) -> rs.getObject("next_attempt_at", LocalDateTime.class),
                taskId);
    }

    /** Awaits a latch with a deadline, failing the suite instead of hanging it. */
    private static void await(CountDownLatch latch, long seconds, String description) {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + description);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for " + description, e);
        }
    }

    /** Surfaces a throwable captured on a worker thread onto the test thread, failing the suite. */
    private static void rethrow(Throwable captured) {
        if (captured instanceof AssertionError assertionError) {
            throw assertionError;
        }
        if (captured != null) {
            throw new AssertionError("worker thread failed: " + captured.getMessage(), captured);
        }
    }
}
