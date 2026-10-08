package com.lifecontrol.api.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.config.provisioning.ProvisioningWorkerProperties;
import com.lifecontrol.api.provisioning.service.AccessProvisioningWorker;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the worker's stale-{@code RUNNING} rescue <b>against real PostgreSQL</b>.
 *
 * <p>Why a database is required: the rescue's defining input is a time predicate,
 * {@code updated_at < now - stalenessThresholdSeconds}, and its defining guard is a pessimistic write
 * lock over the state machine's transition map. A Mockito suite can pin that the finder was called and
 * with which bound, but it cannot show that the SQL predicate selects exactly the stranded rows; this
 * suite inserts rows at chosen ages and reads them back on a fresh connection.</p>
 *
 * <p>How the rows are seeded: raw {@link JdbcTemplate} inserts, mirroring
 * {@code AccessProvisioningTaskConcurrencyIntegrationTest}. Going through
 * {@code AccessProvisioningTaskService#create} would need a security context ({@code create} runs
 * {@code verifyCompanyAccess} first), which is not what is under test here — the rescue reads whatever
 * an interrupted worker left behind, so it is seeded the way a crash leaves it.</p>
 *
 * <p>The class is deliberately <b>not</b> {@code @Transactional}: the base class opens no transaction,
 * and a class-level transaction would make every service call join one, so the rescue's own
 * {@code markFailed} transaction would never commit and the committed-state assertions would read
 * nothing. Cleanup is narrow and leaf-first, scoped to this suite's own company key, so it never
 * removes another suite's fixtures from the shared container.</p>
 *
 * <p>The rescue query is deliberately global (record T47), so it can also see stale rows another suite
 * left behind. That is why every assertion below is scoped to a <b>specific</b> row this suite seeded
 * rather than to a table-wide count.</p>
 */
@SpringBootTest
@DisplayName("Access Provisioning Worker Rescue Integration Tests")
class AccessProvisioningWorkerRescueIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PROV-RESCUE-KEY";
    private static final String COMPANY_RFC = "PROVR010101AB";

    /** Older than any sane test threshold, so the row is unambiguously stranded. */
    private static final LocalDateTime STALE_INSTANT = LocalDateTime.now().minusHours(1);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccessProvisioningWorker worker;

    @Autowired
    private ProvisioningWorkerProperties properties;

    // One test method per test instance (JUnit's default lifecycle), so this counter restarts at 0 and
    // the natural keys it builds never collide with the @BeforeEach-cleared rows.
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void resetProvisioningTables() {
        // Leaf-first: applied_roles references access_provisioning_tasks, which references employees.
        // Every delete is scoped to this suite's company key, so another suite's fixtures survive.
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
    @DisplayName("a fresh RUNNING row is not a rescue candidate and is left untouched")
    void freshRunningRowIsLeftUntouched() {
        var employeeId = insertEmployee();
        var taskId = insertTask(employeeId, "RUNNING", 1, LocalDateTime.now());

        worker.rescueStaleRunningTasks();

        assertThat(committedStatus(taskId))
                .as("a row whose run is still alive keeps looking alive")
                .isEqualTo("RUNNING");
        assertThat(committedLastError(taskId))
                .as("no rescue means no failure reason")
                .isNull();
        assertThat(committedNextAttemptAt(taskId))
                .as("a RUNNING row carries no retry deadline")
                .isNull();
    }

    @Test
    @DisplayName("a stale RUNNING row is requeued with the rescue reason and a backoff deadline")
    void staleRunningRowIsRequeued() {
        var employeeId = insertEmployee();
        var taskId = insertTask(employeeId, "RUNNING", 1, STALE_INSTANT);

        worker.rescueStaleRunningTasks();

        assertThat(committedStatus(taskId))
                .as("a below-ceiling rescue passes through the guarded RUNNING -> FAILED -> PENDING")
                .isEqualTo("PENDING");
        assertThat(committedLastError(taskId))
                .as("the reason names the staleness rescue")
                .containsIgnoringCase("staleness rescue")
                .contains(String.valueOf(properties.stalenessThresholdSeconds()));
        assertThat(committedNextAttemptAt(taskId))
                .as("a below-ceiling rescue gets the policy's backoff deadline, not the operator's click")
                .isNotNull()
                .isAfter(LocalDateTime.now());
        // The seed and this read both come from the JVM's LocalDateTime clock: the seeded value is the
        // one this suite wrote, and the committed value is what Auditable#onUpdate stamped when the
        // rescue saved the row. The assertion depends on that write happening — not on a fixed zone —
        // so it proves the guard's update ran rather than a wall-clock instant.
        assertThat(committedUpdatedAt(taskId))
                .as("the rescue's guarded write moved updated_at past the seeded stale instant")
                .isAfter(STALE_INSTANT);
    }

    @Test
    @DisplayName("a stale row already at the retry ceiling ends FAILED with no deadline")
    void staleRowAtTheCeilingEndsFailed() {
        var employeeId = insertEmployee();
        var taskId = insertTask(employeeId, "RUNNING", properties.maxAttempts(), STALE_INSTANT);

        worker.rescueStaleRunningTasks();

        assertThat(committedStatus(taskId))
                .as("at the ceiling the rescue leaves the row FAILED for the operator")
                .isEqualTo("FAILED");
        assertThat(committedLastError(taskId))
                .as("the failure stays visible with its reason")
                .containsIgnoringCase("staleness rescue");
        assertThat(committedNextAttemptAt(taskId))
                .as("a null deadline clears any next attempt, so the row is not requeued")
                .isNull();
    }

    @Test
    @DisplayName("a PENDING row is never a rescue candidate")
    void pendingRowIsNeverARescueCandidate() {
        var employeeId = insertEmployee();
        var taskId = insertTask(employeeId, "PENDING", 0, STALE_INSTANT);

        worker.rescueStaleRunningTasks();

        assertThat(committedStatus(taskId))
                .as("only RUNNING rows are stranded; a waiting PENDING row must not be touched")
                .isEqualTo("PENDING");
        assertThat(committedLastError(taskId)).isNull();
        assertThat(committedNextAttemptAt(taskId)).isNull();
    }

    /**
     * Find-or-create the company the employee fixtures hang off, mirroring the sibling concurrency
     * suite. The rows the rescue reads are inserted raw; only the foreign key's target is persisted
     * through the entity path.
     */
    private UUID accessCompanyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Access Provisioning Rescue Test Company")
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

    /** Inserts one employee owned by this suite's company; the rescue only needs the foreign key. */
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
                """, id, companyId, "EMP-PROVR-" + n, "provr.employee" + n + "@example.com", statusId);
        return id;
    }

    /**
     * Inserts one stranded-or-waiting task at a chosen {@code updated_at}. The age of that column is
     * the rescue's only interesting input, so it is written explicitly rather than left to the column
     * default; {@code requested_at} still comes from the default.
     */
    private UUID insertTask(UUID employeeId, String status, int attempts, LocalDateTime updatedAt) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_provisioning_tasks
                    (id, employee_id, kind, status, attempts, requested_by, requested_at, updated_at)
                VALUES (?, ?, 'ACTIVATE', ?, ?, 'kc-sub-requester', ?, ?)
                """, id, employeeId, status, attempts, LocalDateTime.now(), updatedAt);
        return id;
    }

    /** The committed status, read on a fresh connection rather than through the entity. */
    private String committedStatus(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM access_provisioning_tasks WHERE id = ?", String.class, taskId);
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

    private LocalDateTime committedUpdatedAt(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM access_provisioning_tasks WHERE id = ?",
                (rs, rowNum) -> rs.getObject("updated_at", LocalDateTime.class),
                taskId);
    }
}
