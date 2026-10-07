package com.lifecontrol.api.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.model.AccessProvisioningAppliedRole;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningAppliedRoleRepository;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence-level verification of {@code V22__employee_access_provisioning.sql} against real
 * PostgreSQL with Flyway enabled and {@code ddl-auto=validate}, so a green run proves the access
 * provisioning schema landed exactly as the migration declares it.
 *
 * <p>The service layer cannot prove these guarantees: they are enforced by the database itself. This
 * suite pins the schema's <b>first partial UNIQUE index</b> ({@code uq_access_provisioning_tasks_open}),
 * and the discriminator is behavioural rather than structural: a second task is refused while the
 * first one occupies an open status, and accepted once it is {@code APPLIED} or {@code REJECTED}. A
 * plain {@code UNIQUE (employee_id)} would pass the first half and fail the second, so the two halves
 * together are what pin the {@code WHERE} predicate.</p>
 *
 * <p>Cleanup is deliberately narrow: {@code access_provisioning_applied_roles} first, then
 * {@code access_provisioning_tasks} (the child references the parent), then the {@code employees}
 * rows — each scoped to the company this suite creates, so no other suite's fixtures are removed even
 * though the tables are shared for the life of the JVM. {@code companies}, {@code statuses} and
 * {@code status_types} are shared fixtures other suites depend on and are never touched. That is what
 * makes the suite order-independent inside the shared PostgreSQL container, and it keeps this suite
 * from deleting the {@code access_provisioning_*} rows a later work unit's fixtures will hold.</p>
 */
@SpringBootTest
@DisplayName("Employee Access Provisioning Schema Integration Tests")
class AccessProvisioningSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PROV-ACCESS-KEY";
    private static final String COMPANY_RFC = "PROVA010101AB";

    /**
     * The statuses the partial index treats as OPEN, read from the migration's own predicate. The
     * Java enum is the same set, and this list is what makes the predicate explicit here.
     */
    private static final List<String> OPEN_STATUSES = List.of("PENDING", "APPROVAL_PENDING", "RUNNING", "FAILED");

    /** The closed statuses, which must stop occupying the single open-task slot. */
    private static final List<String> CLOSED_STATUSES = List.of("APPLIED", "REJECTED");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private AccessProvisioningTaskRepository taskRepository;

    @Autowired
    private AccessProvisioningAppliedRoleRepository appliedRoleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    @DisplayName("applies every migration through V24 and leaves no pending migration")
    void flywayHeadIsV24() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("24");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("creates access_provisioning_tasks with its exact column set")
    void accessProvisioningTasksTableHasItsColumns() {
        var columns = jdbcTemplate.query(
                """
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'access_provisioning_tasks'
                """, (rs, rowNum) -> new ColumnType(rs.getString("column_name"), rs.getString("data_type")));

        assertThat(columns)
                .as("access_provisioning_tasks columns and their data types")
                .containsExactlyInAnyOrder(
                        new ColumnType("id", "uuid"),
                        new ColumnType("employee_id", "uuid"),
                        new ColumnType("kind", "character varying"),
                        new ColumnType("status", "character varying"),
                        new ColumnType("attempts", "integer"),
                        new ColumnType("last_error", "character varying"),
                        new ColumnType("requested_by", "character varying"),
                        new ColumnType("requested_at", "timestamp without time zone"),
                        new ColumnType("decided_by", "character varying"),
                        new ColumnType("decided_at", "timestamp without time zone"),
                        new ColumnType("applied_at", "timestamp without time zone"),
                        new ColumnType("next_attempt_at", "timestamp without time zone"),
                        new ColumnType("enabled", "boolean"),
                        new ColumnType("created_at", "timestamp without time zone"),
                        new ColumnType("updated_at", "timestamp without time zone"));
    }

    @Test
    @DisplayName("creates access_provisioning_applied_roles with its exact column set and named UNIQUE")
    void accessProvisioningAppliedRolesTableHasItsColumnsAndNamedUnique() {
        var columns = jdbcTemplate.query(
                """
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'access_provisioning_applied_roles'
                """, (rs, rowNum) -> new ColumnType(rs.getString("column_name"), rs.getString("data_type")));

        assertThat(columns)
                .as("access_provisioning_applied_roles columns and their data types, with no updated_at")
                .containsExactlyInAnyOrder(
                        new ColumnType("id", "uuid"),
                        new ColumnType("task_id", "uuid"),
                        new ColumnType("role_name", "character varying"),
                        new ColumnType("created_at", "timestamp without time zone"));

        var constraintType = jdbcTemplate.queryForObject("""
                SELECT c.contype::text FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public' AND t.relname = 'access_provisioning_applied_roles'
                  AND c.conname = 'uq_access_provisioning_applied_roles'
                """, String.class);
        assertThat(constraintType)
                .as("uq_access_provisioning_applied_roles is a UNIQUE constraint")
                .isEqualTo("u");
    }

    @Test
    @DisplayName("uq_access_provisioning_tasks_open is a partial index with the open-status predicate")
    void openTaskIndexIsPartialOnTheOpenStatusSet() {
        var indexDefinition = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'uq_access_provisioning_tasks_open'
                """, String.class);

        assertThat(indexDefinition)
                .as("the index is UNIQUE, keys on employee_id and carries a WHERE predicate")
                .containsIgnoringCase("UNIQUE")
                .contains("employee_id")
                .containsIgnoringCase("WHERE");

        // The predicate and the Java enum must name the same set, in both directions: a status the
        // enum has and the predicate lacks lets two tasks for one employee race, and a status the
        // predicate has and the enum lacks is unreachable. Matching the quoted form is what keeps
        // "PENDING" from being satisfied by "APPROVAL_PENDING".
        for (var status : AccessProvisioningTaskStatus.values()) {
            var quoted = "'" + status.name() + "'";
            if (OPEN_STATUSES.contains(status.name())) {
                assertThat(indexDefinition)
                        .as("%s is an open status and must appear in the predicate", quoted)
                        .contains(quoted);
            } else {
                assertThat(indexDefinition)
                        .as("%s is a closed status and must not appear in the predicate", quoted)
                        .doesNotContain(quoted);
            }
        }
    }

    @Test
    @DisplayName("idx_access_provisioning_tasks_due is partial on the single PENDING status")
    void dueIndexIsPartialOnPending() {
        var indexDefinition = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'idx_access_provisioning_tasks_due'
                """, String.class);

        assertThat(indexDefinition)
                .as("the due index keys on the deadline and carries a WHERE predicate")
                .contains("next_attempt_at")
                .containsIgnoringCase("WHERE");

        // The predicate and the Java enum must agree in both directions, and the quoted form is what
        // keeps "PENDING" from being satisfied by "APPROVAL_PENDING" (the W1a lesson): the worker's
        // queue is PENDING only, so any other status appearing here would widen the candidate set.
        for (var status : AccessProvisioningTaskStatus.values()) {
            var quoted = "'" + status.name() + "'";
            if (status == AccessProvisioningTaskStatus.PENDING) {
                assertThat(indexDefinition)
                        .as("%s is the only status the due index admits", quoted)
                        .contains(quoted);
            } else {
                assertThat(indexDefinition)
                        .as("%s must not appear in the due index predicate", quoted)
                        .doesNotContain(quoted);
            }
        }
    }

    @Test
    @DisplayName("findDueTaskIds returns only due PENDING tasks, FIFO, bounded by the page size")
    void dueCandidatesArePendingDueOrderedAndBounded() {
        var now = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

        // One employee per task: the partial unique index admits at most one open task each.
        var oldestDue = insertTaskWithDeadline(insertEmployee(), "PENDING", LocalDateTime.of(1900, 1, 1, 0, 0), null);
        var pastDue = insertTaskWithDeadline(insertEmployee(), "PENDING", now.minusMinutes(20), now.minusSeconds(1));
        var dueNow = insertTaskWithDeadline(insertEmployee(), "PENDING", now.minusMinutes(10), now);
        var notYetDue = insertTaskWithDeadline(insertEmployee(), "PENDING", now.minusMinutes(5), now.plusSeconds(1));
        var running = insertTaskWithDeadline(insertEmployee(), "RUNNING", now.minusMinutes(4), null);
        var failed = insertTaskWithDeadline(insertEmployee(), "FAILED", now.minusMinutes(3), now.minusSeconds(1));
        var gated = insertTaskWithDeadline(insertEmployee(), "APPROVAL_PENDING", now.minusMinutes(2), null);

        var mine = List.of(oldestDue, pastDue, dueNow);

        var dueIds = taskRepository.findDueTaskIds(now, PageRequest.of(0, 100));
        assertThat(dueIds)
                .as("a future deadline, a FAILED task and every non-PENDING status are not candidates")
                .doesNotContain(notYetDue, running, failed, gated);
        assertThat(dueIds.stream().filter(mine::contains).toList())
                .as("the due PENDING tasks come back oldest-requested-first")
                .containsExactlyElementsOf(mine);

        // The page size is the bound: the oldest due row (year 1900, globally the earliest) is all a
        // one-row page can return, and it is not the "no bound at all" list the previous call got.
        assertThat(taskRepository.findDueTaskIds(now, PageRequest.of(0, 1)))
                .as("the caller-supplied page size bounds the candidate batch")
                .containsExactly(oldestDue);
    }

    @Test
    @DisplayName("pins the nullability, the VARCHAR lengths and the defaults of both provisioning tables")
    void provisioningColumnsCarryTheirNullabilityLengthsAndDefaults() {
        // ddl-auto=validate proves names and types only: a nullable requested_at or a VARCHAR(500)
        // requested_by would keep the whole suite green, so the detail is asserted here from the
        // catalogue instead of from the entity. The timestamp defaults are pinned as
        // CURRENT_TIMESTAMP because PostgreSQL stores the default's written form, so this text is the
        // migration's own and not a normalisation of it.
        assertThat(columnDetails("access_provisioning_tasks"))
                .as("access_provisioning_tasks nullability, length and default per column")
                .containsExactlyInAnyOrder(
                        new ColumnDetail("id", "NO", null, "gen_random_uuid()"),
                        new ColumnDetail("employee_id", "NO", null, null),
                        new ColumnDetail("kind", "NO", 20, null),
                        new ColumnDetail("status", "NO", 20, null),
                        new ColumnDetail("attempts", "NO", null, "0"),
                        new ColumnDetail("last_error", "YES", 500, null),
                        new ColumnDetail("requested_by", "NO", 36, null),
                        new ColumnDetail("requested_at", "NO", null, "CURRENT_TIMESTAMP"),
                        new ColumnDetail("decided_by", "YES", 36, null),
                        new ColumnDetail("decided_at", "YES", null, null),
                        new ColumnDetail("applied_at", "YES", null, null),
                        new ColumnDetail("next_attempt_at", "YES", null, null),
                        new ColumnDetail("enabled", "NO", null, "true"),
                        new ColumnDetail("created_at", "NO", null, "CURRENT_TIMESTAMP"),
                        new ColumnDetail("updated_at", "NO", null, "CURRENT_TIMESTAMP"));

        assertThat(columnDetails("access_provisioning_applied_roles"))
                .as("access_provisioning_applied_roles nullability, length and default per column")
                .containsExactlyInAnyOrder(
                        new ColumnDetail("id", "NO", null, "gen_random_uuid()"),
                        new ColumnDetail("task_id", "NO", null, null),
                        new ColumnDetail("role_name", "NO", 100, null),
                        new ColumnDetail("created_at", "NO", null, "CURRENT_TIMESTAMP"));
    }

    @Test
    @DisplayName("creates every index the migration declares, and no more than it declares")
    void theMigrationIndexesExist() {
        // The sizes are the half that catches an index nobody intended; contains() is the half that
        // catches a dropped one.
        assertThat(indexNames("access_provisioning_tasks"))
                .as("the four declared indexes plus the primary key")
                .hasSize(5)
                .contains(
                        "idx_access_provisioning_tasks_employee_id",
                        "idx_access_provisioning_tasks_status",
                        "idx_access_provisioning_tasks_due",
                        "uq_access_provisioning_tasks_open");

        assertThat(indexNames("access_provisioning_applied_roles"))
                .as("the declared index, the named UNIQUE's index and the primary key")
                .hasSize(3)
                .contains("idx_access_provisioning_applied_roles_task_id", "uq_access_provisioning_applied_roles");
    }

    @Test
    @DisplayName("uq_access_provisioning_tasks_open refuses a second open task for the same employee")
    void secondOpenTaskIsRefusedByPartialUniqueIndex() {
        var employeeId = insertEmployee();
        insertTask(employeeId, "ACTIVATE", "PENDING");

        assertViolatesConstraint("uq_access_provisioning_tasks_open", """
                INSERT INTO access_provisioning_tasks (id, employee_id, kind, status, requested_by)
                VALUES (?, ?, 'RECONCILE', 'PENDING', 'kc-sub-requester')
                """, UUID.randomUUID(), employeeId);

        assertThat(countTasks(employeeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("every open status blocks a second task while APPLIED and REJECTED stop blocking it")
    void partialIndexPredicateCoversTheWholeOpenStatusSet() {
        // The open half: each member of the predicate's set must refuse a second task, so a narrowed
        // predicate (for example dropping FAILED) would fail one of these iterations.
        for (var status : OPEN_STATUSES) {
            var employeeId = insertEmployee();
            insertTask(employeeId, "ACTIVATE", status);

            assertViolatesConstraint("uq_access_provisioning_tasks_open", """
                    INSERT INTO access_provisioning_tasks (id, employee_id, kind, status, requested_by)
                    VALUES (?, ?, 'RECONCILE', 'PENDING', 'kc-sub-requester')
                    """, UUID.randomUUID(), employeeId);

            assertThat(countTasks(employeeId))
                    .as("the %s task still occupies the open slot", status)
                    .isEqualTo(1);
        }

        // The closed half: the two terminal failures of the flow release the slot, which is what a
        // plain UNIQUE (employee_id) would get wrong.
        for (var status : CLOSED_STATUSES) {
            var employeeId = insertEmployee();
            insertTask(employeeId, "ACTIVATE", status);
            insertTask(employeeId, "RECONCILE", "PENDING");

            assertThat(countTasks(employeeId))
                    .as("a closed %s task no longer blocks its successor", status)
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("uq_access_provisioning_applied_roles refuses a duplicate role for one task")
    void duplicateAppliedRoleForOneTaskIsRefused() {
        var employeeId = insertEmployee();
        var taskId = insertTask(employeeId, "ACTIVATE", "PENDING");
        insertAppliedRole(taskId, "lc-sales");

        assertViolatesConstraint("uq_access_provisioning_applied_roles", """
                INSERT INTO access_provisioning_applied_roles (id, task_id, role_name)
                VALUES (?, ?, 'lc-sales')
                """, UUID.randomUUID(), taskId);

        assertThat(countAppliedRoles(taskId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the employee_id foreign key really points at employees: an unknown uuid is refused")
    void unknownEmployeeIdIsRefusedByTheForeignKey() {
        assertViolatesConstraint(
                "access_provisioning_tasks_employee_id_fkey", """
                INSERT INTO access_provisioning_tasks (id, employee_id, kind, status, requested_by)
                VALUES (?, ?, 'ACTIVATE', 'PENDING', 'kc-sub-requester')
                """, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName("the task_id foreign key really points at access_provisioning_tasks: an unknown uuid is refused")
    void unknownTaskIdIsRefusedByTheForeignKey() {
        assertViolatesConstraint(
                "access_provisioning_applied_roles_task_id_fkey", """
                INSERT INTO access_provisioning_applied_roles (id, task_id, role_name)
                VALUES (?, ?, 'lc-sales')
                """, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName("round-trips a task and its applied role through the repositories, storing both enums as strings")
    void roundTripsThroughTheRepositories() {
        var companyId = accessCompanyId();
        var statusId = activeEmployeeStatusId();

        var employee = employeeRepository.saveAndFlush(Employee.builder()
                .company(companyRepository.findById(companyId).orElseThrow())
                .status(statusRepository.findById(statusId).orElseThrow())
                .employeeNumber("EMP-PROV-RT")
                .firstName("Ada")
                .paternalLastName("Lovelace")
                .email("prov.roundtrip@example.com")
                .birthDate(LocalDate.of(1990, 1, 1))
                .hireDate(LocalDate.of(2020, 1, 1))
                .enabled(true)
                .build());

        var saved = taskRepository.saveAndFlush(AccessProvisioningTask.builder()
                .employee(employee)
                .kind(AccessProvisioningTaskKind.ACTIVATE)
                .status(AccessProvisioningTaskStatus.APPROVAL_PENDING)
                .requestedBy("kc-sub-requester")
                .build());

        appliedRoleRepository.saveAndFlush(AccessProvisioningAppliedRole.builder()
                .task(saved)
                .roleName("lc-sales")
                .build());

        // The repository commits its own transaction, so the lazy employee is read inside one.
        var reloaded = inTransaction(() -> taskRepository
                .findByIdAndEmployeeId(saved.getId(), employee.getId())
                .orElseThrow());

        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getKind()).isEqualTo(AccessProvisioningTaskKind.ACTIVATE);
        assertThat(reloaded.getStatus()).isEqualTo(AccessProvisioningTaskStatus.APPROVAL_PENDING);
        assertThat(reloaded.getRequestedBy()).isEqualTo("kc-sub-requester");
        assertThat(reloaded.getRequestedAt())
                .as("the @PrePersist default filled requested_at")
                .isNotNull();
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getLastError()).isNull();
        assertThat(reloaded.getDecidedBy()).isNull();
        assertThat(reloaded.getDecidedAt()).isNull();
        assertThat(reloaded.getAppliedAt()).isNull();
        assertThat(reloaded.getEnabled()).isTrue();
        assertThat(reloaded.getEmployee().getId()).isEqualTo(employee.getId());
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();

        // The three derived finders the repository declares, exercised so the round-trip is their
        // W1a consumer: the scoped list, the newest-first history and the open-status probe.
        assertThat(taskRepository.findByEmployeeIdOrderByRequestedAtDesc(employee.getId()))
                .extracting(AccessProvisioningTask::getId)
                .containsExactly(saved.getId());
        assertThat(taskRepository.existsByEmployeeIdAndStatusIn(
                        employee.getId(), List.of(AccessProvisioningTaskStatus.APPROVAL_PENDING)))
                .isTrue();
        assertThat(taskRepository.existsByEmployeeIdAndStatusIn(
                        employee.getId(), List.of(AccessProvisioningTaskStatus.APPLIED)))
                .isFalse();
        assertThat(taskRepository.findByIdAndEmployeeId(saved.getId(), UUID.randomUUID()))
                .as("a task of another employee is not reachable")
                .isEmpty();

        var appliedRoles = appliedRoleRepository.findByTaskIdOrderByCreatedAtAsc(saved.getId());
        assertThat(appliedRoles)
                .extracting(AccessProvisioningAppliedRole::getRoleName)
                .containsExactly("lc-sales");
        assertThat(appliedRoles.get(0).getCreatedAt())
                .as("the child's @PrePersist filled created_at")
                .isNotNull();

        // The enum is stored as its name and not as its ordinal: the raw read is what pins
        // @Enumerated(EnumType.STRING) against a column that has no CHECK to fall back on.
        var raw = jdbcTemplate.queryForMap(
                "SELECT kind, status FROM access_provisioning_tasks WHERE id = ?", saved.getId());
        assertThat(raw.get("kind")).isEqualTo("ACTIVATE");
        assertThat(raw.get("status")).isEqualTo("APPROVAL_PENDING");
    }

    /**
     * Reads a lazy association inside a transaction: the repository commits its own transaction, so
     * reading {@code employee} afterwards on the detached entity would throw.
     */
    private <T> T inTransaction(Supplier<T> reader) {
        return new TransactionTemplate(transactionManager).execute(status -> reader.get());
    }

    /**
     * Find-or-create the company row the employee fixtures hang off, mirroring the idempotent fixture
     * style of the sibling HR schema suites. Reuses the persisted entity path instead of hand-writing
     * an insert into {@code companies}.
     */
    private UUID accessCompanyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Access Provisioning Schema Test Company")
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
     * Inserts one employee owned by this suite's company. The raw insert is deliberate: the schema
     * under test is V22, and the employee fixture is only the foreign key's target.
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
                """, id, companyId, "EMP-PROV-" + n, "prov.employee" + n + "@example.com", statusId);
        return id;
    }

    /** Raw insert keeps the status controllable, which is what the partial index discriminates on. */
    private UUID insertTask(UUID employeeId, String kind, String status) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_provisioning_tasks (id, employee_id, kind, status, requested_by)
                VALUES (?, ?, ?, ?, 'kc-sub-requester')
                """, id, employeeId, kind, status);
        return id;
    }

    /**
     * Raw insert with an explicit {@code requested_at} and {@code next_attempt_at}, so the due
     * candidate query is tested against controlled ordering and deadline values rather than against
     * the column defaults. A {@code null} deadline is the never-failed case the query reads as due.
     */
    private UUID insertTaskWithDeadline(
            UUID employeeId, String status, LocalDateTime requestedAt, LocalDateTime nextAttemptAt) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_provisioning_tasks
                    (id, employee_id, kind, status, requested_by, requested_at, next_attempt_at)
                VALUES (?, ?, 'ACTIVATE', ?, 'kc-sub-requester', ?, ?)
                """, id, employeeId, status, requestedAt, nextAttemptAt);
        return id;
    }

    private UUID insertAppliedRole(UUID taskId, String roleName) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_provisioning_applied_roles (id, task_id, role_name)
                VALUES (?, ?, ?)
                """, id, taskId, roleName);
        return id;
    }

    /** Scoped count: the tasks of one employee, never the whole table. */
    private int countTasks(UUID employeeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM access_provisioning_tasks WHERE employee_id = ?", Integer.class, employeeId);
    }

    /** Scoped count: the applied roles of one task, never the whole table. */
    private int countAppliedRoles(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM access_provisioning_applied_roles WHERE task_id = ?", Integer.class, taskId);
    }

    /**
     * Asserts the raw write is rejected by a real PostgreSQL constraint and that the reported error
     * names the exact constraint. The most specific cause carries the {@code PSQLException} message.
     */
    private void assertViolatesConstraint(String constraintName, String sql, Object... args) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, args))
                .as("write rejected by %s", constraintName)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(((DataIntegrityViolationException) thrown)
                                .getMostSpecificCause()
                                .getMessage())
                        .as("PostgreSQL reports the violated constraint")
                        .contains(constraintName));
    }

    /**
     * Nullability, VARCHAR length and default per column — the three facts {@code ddl-auto=validate}
     * does not check, read from the catalogue rather than from the entity's own declarations.
     */
    private List<ColumnDetail> columnDetails(String tableName) {
        return jdbcTemplate.query(
                """
                SELECT column_name, is_nullable, character_maximum_length, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """,
                (rs, rowNum) -> new ColumnDetail(
                        rs.getString("column_name"),
                        rs.getString("is_nullable"),
                        rs.getObject("character_maximum_length", Integer.class),
                        rs.getString("column_default")),
                tableName);
    }

    /** Every index on one table, the primary key's included, so a size assertion means something. */
    private List<String> indexNames(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
                String.class,
                tableName);
    }

    private record ColumnType(String name, String type) {}

    private record ColumnDetail(String name, String nullable, Integer length, String defaultValue) {}
}
