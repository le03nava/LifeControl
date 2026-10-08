package com.lifecontrol.api.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.provisioning.model.AccessProvisioningAppliedRole;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningAppliedRoleRepository;
import com.lifecontrol.api.provisioning.service.AccessProvisioningTaskService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves, against real PostgreSQL, the two facts a mock cannot: the applied snapshot really lands in
 * {@code access_provisioning_applied_roles}, and it lands in the <b>same transaction</b> as the
 * {@code RUNNING → APPLIED} edge — so a database refusal of one row takes the whole edge with it.
 *
 * <p>This suite is the first production consumer of {@link AccessProvisioningAppliedRoleRepository}'s
 * write side. Until now the table was only ever written by the schema suite's raw inserts, so the
 * repository's inherited {@code saveAll} had no test that exercised it end to end. The snapshot's
 * meaning is record T35's: one row per role the run <b>touched</b> ({@code granted ∪ removed}), never
 * a final state, and record T60's: written by {@code markApplied}, replacing the one-argument form,
 * because a process dying between two transactions would leave {@code APPLIED} with no record of what
 * it applied.</p>
 *
 * <p><b>Why the failure path is forced through the database and not asserted from the service.</b>
 * The service deliberately does not duplicate the column's {@code VARCHAR(100)} width in Java (the
 * column is the authority, and a second literal would drift). A role name longer than 100 characters
 * therefore reaches PostgreSQL, which raises {@code value too long for type character varying(100)}.
 * That refusal is the natural trigger for falsifying atomicity: the call persists the edge and the
 * snapshot rows in one {@code @Transactional} method, so the database error must roll back
 * <b>everything</b> — the task stays {@code RUNNING}, {@code applied_at} stays null, and even the
 * well-formed sibling row in the same batch is gone. If the edge survived the child's failure, that
 * would be the defect T60 exists to prevent, and this suite would report it rather than work around
 * it.</p>
 *
 * <p><b>Why the class is not {@code @Transactional}.</b> The base class starts no transaction. If
 * this class opened one, every {@code markApplied} call would join the test-wide transaction, nothing
 * would commit at the method boundary, and the rollback this suite is built to observe would be the
 * test's own teardown rather than the service's transaction — the suite would pass vacuously. The
 * service's {@code @Transactional} boundary is the thing under test, so it must be the outermost
 * one.</p>
 *
 * <p>Seeding mirrors {@code AccessProvisioningTaskConcurrencyIntegrationTest}: the company comes
 * through the repository, but the employee and the task are raw {@link JdbcTemplate} inserts, because
 * the task must be born {@code RUNNING} and the raw path is the only one that skips the guarded
 * creation flow (which would need a security context). Cleanup is leaf-first and scoped to this
 * suite's own company key, so it never removes another suite's fixtures from the shared container.</p>
 */
@SpringBootTest
@DisplayName("Employee Access Provisioning Task Snapshot Integration Tests")
class AccessProvisioningTaskSnapshotIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PROV-SNAPSHOT-KEY";
    private static final String COMPANY_RFC = "PROVS010101AB";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccessProvisioningAppliedRoleRepository appliedRoleRepository;

    @Autowired
    private AccessProvisioningTaskService taskService;

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
    @DisplayName("markApplied commits the RUNNING -> APPLIED edge and one snapshot row per touched role")
    void markAppliedWritesTheEdgeAndTheSnapshot() {
        var employeeId = insertEmployee();
        var taskId = insertRunningTask(employeeId);

        var result = taskService.markApplied(employeeId, taskId, Set.of("lc-sales", "lc-admin-viewer"));

        assertThat(result.getStatus())
                .as("the returned entity carries the edge")
                .isEqualTo(AccessProvisioningTaskStatus.APPLIED);
        assertThat(committedStatus(taskId))
                .as("the committed row is APPLIED, not merely the in-memory entity")
                .isEqualTo("APPLIED");
        assertThat(committedAppliedAt(taskId)).as("applied_at is stamped").isNotNull();

        assertThat(appliedRoleCount(taskId)).as("one row per touched role").isEqualTo(2);
        assertThat(appliedRoleNames(taskId))
                .as("the exact role names, read back from the table")
                .containsExactly("lc-admin-viewer", "lc-sales");
        assertThat(appliedRoleTaskIds(taskId))
                .as("every snapshot row references the task it belongs to (the FK holds)")
                .containsOnly(taskId);

        var appliedAt = committedAppliedAt(taskId);
        assertThat(appliedRoleCreatedAts(taskId))
                .as("the rows share the task's applied_at, proving the clock was read once (record T60)")
                .containsOnly(appliedAt);

        assertThat(appliedRoleRepository.findByTaskIdOrderByCreatedAtAsc(taskId))
                .as("the read-side finder surfaces both rows")
                .extracting(AccessProvisioningAppliedRole::getRoleName)
                .containsExactlyInAnyOrder("lc-admin-viewer", "lc-sales");
    }

    @Test
    @DisplayName("an over-long role name rolls back the edge and the whole snapshot, not only the bad row")
    void anOverlongRoleNameRollsBackTheEdgeAndTheSnapshot() {
        var employeeId = insertEmployee();
        var taskId = insertRunningTask(employeeId);
        var overlong = "r".repeat(101);

        assertThatThrownBy(() -> taskService.markApplied(employeeId, taskId, Set.of("lc-sales", overlong)))
                .as("the 101-character name is refused by the column's width, inside the transaction")
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(deepestMessage(thrown))
                        .as("PostgreSQL reports the column width as the reason")
                        .contains("value too long"));

        assertThat(committedStatus(taskId))
                .as("the RUNNING -> APPLIED edge rolled back entirely")
                .isEqualTo("RUNNING");
        assertThat(committedAppliedAt(taskId)).as("applied_at stayed null").isNull();
        assertThat(appliedRoleCount(taskId))
                .as("the well-formed sibling row rolled back with the bad one: the snapshot is all-or-nothing")
                .isZero();
    }

    /**
     * Find-or-create the company row the employee fixtures hang off, mirroring the sibling access
     * provisioning suites. Reuses the persisted entity path instead of hand-writing an insert into
     * {@code companies}; the rows this suite asserts over are inserted raw.
     */
    private UUID accessCompanyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Access Provisioning Snapshot Test Company")
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
                """, id, companyId, "EMP-PROVS-" + n, "provs.employee" + n + "@example.com", statusId);
        return id;
    }

    /** A row already claimed: {@code RUNNING} with {@code attempts = 1}, the only input markApplied accepts. */
    private UUID insertRunningTask(UUID employeeId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_provisioning_tasks
                    (id, employee_id, kind, status, attempts, requested_by, requested_at)
                VALUES (?, ?, 'ACTIVATE', 'RUNNING', 1, 'kc-sub-requester', ?)
                """, id, employeeId, LocalDateTime.now());
        return id;
    }

    /** The committed status, read on a fresh connection rather than through the entity. */
    private String committedStatus(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM access_provisioning_tasks WHERE id = ?", String.class, taskId);
    }

    private LocalDateTime committedAppliedAt(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT applied_at FROM access_provisioning_tasks WHERE id = ?",
                (rs, rowNum) -> rs.getObject("applied_at", LocalDateTime.class),
                taskId);
    }

    /** Scoped count: the applied roles of one task, never the whole table. */
    private int appliedRoleCount(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM access_provisioning_applied_roles WHERE task_id = ?", Integer.class, taskId);
    }

    /** Scoped read in the database's own order, so the expected list is deterministic. */
    private List<String> appliedRoleNames(UUID taskId) {
        return jdbcTemplate.queryForList(
                "SELECT role_name FROM access_provisioning_applied_roles WHERE task_id = ? ORDER BY role_name",
                String.class,
                taskId);
    }

    private List<UUID> appliedRoleTaskIds(UUID taskId) {
        return jdbcTemplate.query(
                "SELECT task_id FROM access_provisioning_applied_roles WHERE task_id = ?",
                (rs, rowNum) -> rs.getObject("task_id", UUID.class),
                taskId);
    }

    private List<LocalDateTime> appliedRoleCreatedAts(UUID taskId) {
        return jdbcTemplate.query(
                "SELECT created_at FROM access_provisioning_applied_roles WHERE task_id = ? ORDER BY role_name",
                (rs, rowNum) -> rs.getObject("created_at", LocalDateTime.class),
                taskId);
    }

    /**
     * The innermost cause's message: Spring may wrap the JDBC failure in a transaction exception at
     * commit time, so the assertion reads the cause chain rather than one fixed type.
     */
    private static String deepestMessage(Throwable thrown) {
        var current = thrown;
        var message = String.valueOf(thrown.getMessage());
        while (current != null) {
            message = String.valueOf(current.getMessage());
            current = current.getCause();
        }
        return message;
    }
}
