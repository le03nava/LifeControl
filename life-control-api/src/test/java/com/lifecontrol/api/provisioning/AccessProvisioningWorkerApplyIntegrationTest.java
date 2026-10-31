package com.lifecontrol.api.provisioning;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.model.Contract;
import com.lifecontrol.api.hr.model.ContractType;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.PositionRole;
import com.lifecontrol.api.hr.model.SeniorityLevel;
import com.lifecontrol.api.hr.repository.ContractRepository;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.PositionRoleRepository;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import com.lifecontrol.api.provisioning.service.AccessProvisioningMembershipService;
import com.lifecontrol.api.provisioning.service.AccessProvisioningWorker;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.InMemoryIdentityProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the one fact the unit suites cannot: the worker's <b>pass</b> reaches {@code APPLIED} against
 * real PostgreSQL for an employee whose {@code company} and {@code status} are lazy associations.
 *
 * <p><b>The defect this suite was written for (gap G45).</b> The pass owns no transaction (record T44
 * splits the mechanics from the domain, record T57 keeps every write inside the service method that
 * guards it), so the {@link Employee} it loads with the inherited unlocked {@code findById} is
 * <b>detached</b> by the time the capabilities read it. Every capability assumes initialized
 * associations: {@code AccessProvisioningRoleService.requiredRoles} asks
 * {@code EmployeeStatuses.isTerminated} for {@code employee.getStatus()} and
 * {@code requireOwnedPosition} for {@code employee.getCompany()}, and
 * {@code AccessProvisioningMembershipService.derivedScope} reads the same two. A
 * {@code @Transactional} capability does <b>not</b> reattach a detached instance, which is why the
 * annotations on those methods did not save the pass. The live run measured the consequence: all
 * three real {@code ACTIVATE} tasks exhausted their attempts at
 * {@code Could not initialize proxy [Status#…] - no session}, the account half committed anyway and
 * {@code access_provisioning_applied_roles} stayed empty.
 *
 * <p><b>Why PostgreSQL and not a mock.</b> Three things under test are JPA and SQL facts: that the
 * pass's employee read actually initializes the two associations outside a transaction, that the
 * applied snapshot row really lands, and that the role set the run touched is the one the template
 * declares. Mockito can pin calls but cannot show that a detached entity survives the read.
 *
 * <p><b>What is substituted, exactly.</b> Only the <b>identity provider</b>: the test-scoped
 * {@link InMemoryIdentityProvider} replaces the Keycloak adapter as the primary
 * {@link IdentityProvider} bean, so the run's writes land in real state (per-client role sets, the
 * attribute map, the sent-email log) instead of a mock's argument capture. Everything else is the
 * production wiring: the worker, the task state machine, the three capabilities, Flyway's schema and
 * PostgreSQL.
 *
 * <p><b>The class is deliberately not {@code @Transactional}.</b> A test-wide transaction would make
 * every service call join it, so nothing would commit at the method boundary and the committed-state
 * assertions would read the test's own uncommitted rows; the worker's own boundaries must be the
 * outermost ones. Cleanup is leaf-first and scoped to this suite's two company keys, so it never
 * removes another suite's fixtures from the shared container.
 */
@SpringBootTest
@DisplayName("Access Provisioning Worker Apply Integration Tests")
class AccessProvisioningWorkerApplyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PROV-APPLY-KEY";
    private static final String COMPANY_RFC = "PAPP010101AB";
    private static final String OTHER_COMPANY_KEY = "PROV-APPLY-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "PAPO010101AB";
    private static final String LEVEL_CODE = "PAPP999";
    private static final String CLIENT_ID = "life-control-client";

    /** The two grantable templates the run must grant, plus the two it must ignore. */
    private static final Set<String> EXPECTED_ROLES = Set.of("lc-sales", "lc-scheduling");

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
    private DepartmentRepository departmentRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private PositionRoleRepository positionRoleRepository;

    @Autowired
    private SeniorityLevelRepository seniorityLevelRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private AccessProvisioningWorker worker;

    @Autowired
    private AccessProvisioningMembershipService membershipService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IdentityProvider identityProvider;

    private final List<UUID> seededCompanyIds = new ArrayList<>();

    private UUID companyId;
    private UUID positionId;
    private UUID otherPositionId;
    private UUID seniorityLevelId;
    private UUID employeeId;
    private String keycloakUserId;

    private InMemoryIdentityProvider provider() {
        return (InMemoryIdentityProvider) identityProvider;
    }

    @BeforeEach
    void seedOneProvisionableEmployee() {
        deleteOwnFixtures();

        var company = TenantFixtures.persist(
                companyRepository,
                Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Apply Test Company")
                        .rfc(COMPANY_RFC)
                        .emailDomain("apply.example")
                        .enabled(true)
                        .build());
        companyId = company.getId();
        seededCompanyIds.add(companyId);

        positionId = seedPositionTree(company, "APPLY", "Apply Operations", "APPLY-1", "Apply Operator");

        // A position of a *different* company, used by the retry scenario to fail after the account
        // half: the role convergence refuses a position outside the employee's company.
        var otherCompany = TenantFixtures.persist(
                companyRepository,
                Company.builder()
                        .companyKey(OTHER_COMPANY_KEY)
                        .companyName("Apply Test Other Company")
                        .rfc(OTHER_COMPANY_RFC)
                        .emailDomain("apply-other.example")
                        .enabled(true)
                        .build());
        seededCompanyIds.add(otherCompany.getId());
        otherPositionId = seedPositionTree(otherCompany, "OTHER", "Other Operations", "OTHER-1", "Other Operator");

        seniorityLevelId = seniorityLevelRepository
                .save(SeniorityLevel.builder()
                        .levelCode(LEVEL_CODE)
                        .levelName("Apply Test Seniority")
                        .rank(7_777_777)
                        .enabled(true)
                        .build())
                .getId();

        keycloakUserId = provider().createAccount("linked.account", "ada.lovelace@apply.example");

        employeeId = employeeRepository
                .save(Employee.builder()
                        .company(company)
                        .employeeNumber("PAPP-1")
                        .firstName("Ada")
                        .paternalLastName("Lovelace")
                        .email("ada.lovelace@apply.example")
                        .birthDate(LocalDate.of(1990, 1, 1))
                        .hireDate(LocalDate.of(2020, 1, 1))
                        .status(activeStatus())
                        .keycloakUserId(keycloakUserId)
                        .enabled(true)
                        .build())
                .getId();
    }

    @AfterEach
    void leaveNoFixtureBehind() {
        deleteOwnFixtures();
    }

    @Test
    @DisplayName(
            "one ACTIVATE for an employee with a current contract reaches APPLIED and records the roles it touched")
    void activateReachesAppliedAndRecordsTheTouchedRoles() {
        seedContract(positionId);
        var taskId = seedTask(employeeId);

        worker.runPass();

        assertThat(committedStatus(taskId))
                .describedAs("the pass must apply, not fail on a detached association: %s", committedLastError(taskId))
                .isEqualTo("APPLIED");
        assertThat(committedAppliedRoles(taskId)).containsExactlyInAnyOrderElementsOf(EXPECTED_ROLES);
    }

    @Test
    @DisplayName("the account ends with the template's grantable roles and the five derived company claims")
    void accountEndsWithTheTemplateRolesAndTheDerivedClaims() {
        seedContract(positionId);
        seedTask(employeeId);

        worker.runPass();

        assertThat(provider().roleNames(keycloakUserId, CLIENT_ID))
                .describedAs("the linked account holds exactly the enabled grantable templates")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_ROLES);

        var expected = expectedAttributes();
        var written = provider().attributes(keycloakUserId);
        assertThat(written.keySet())
                .describedAs("the account carries exactly the five claim keys")
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        assertThat(written).isEqualTo(expected);
    }

    @Test
    @DisplayName("a retried ACTIVATE that fails after the account half sends exactly one invitation")
    void retriedActivateSendsExactlyOneInvitation() {
        // Unlinked on purpose: this scenario is about the account half happening in the first place.
        jdbcTemplate.update("UPDATE employees SET keycloak_user_id = NULL WHERE id = ?", employeeId);
        // A position of another company makes the role convergence refuse *after* the account half.
        seedContract(otherPositionId);
        var taskId = seedTask(employeeId);

        worker.runPass();
        assertThat(committedLastError(taskId))
                .describedAs("the run must reach the position rule, not a detached association")
                .contains("outside the company");
        var linkedId = committedKeycloakUserId();
        assertThat(linkedId).isNotNull();

        // The row is back in the queue with a backoff (the guarded FAILED -> PENDING edge); making it
        // due again is exactly what the queue would do once that backoff elapsed (record T53).
        jdbcTemplate.update("UPDATE access_provisioning_tasks SET next_attempt_at = NULL WHERE id = ?", taskId);

        worker.runPass();

        assertThat(provider().actionsEmailCount(linkedId))
                .describedAs("one activation, one invitation — a retry must not re-invite")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private UUID seedPositionTree(
            Company company, String codePrefix, String departmentName, String positionCode, String positionName) {
        var department = departmentRepository.save(Department.builder()
                .company(company)
                .departmentCode(codePrefix)
                .departmentName(departmentName)
                .enabled(true)
                .build());
        var position = positionRepository.save(Position.builder()
                .department(department)
                .positionCode(positionCode)
                .positionName(positionName)
                .enabled(true)
                .build());

        // Two grantable templates, one grantable but disabled and one outside the allowlist: the run
        // must grant the first two and ignore the other two (grantable intersection, T28/T29).
        seedTemplate(position, "lc-sales", true);
        seedTemplate(position, "lc-scheduling", true);
        seedTemplate(position, "lc-receiving", false);
        seedTemplate(position, "lc-admin", true);
        return position.getId();
    }

    private void seedTemplate(Position position, String roleName, boolean enabled) {
        positionRoleRepository.save(PositionRole.builder()
                .position(position)
                .roleName(roleName)
                .enabled(enabled)
                .build());
    }

    private void seedContract(UUID position) {
        contractRepository.save(Contract.builder()
                .employee(employeeRepository.findById(employeeId).orElseThrow())
                .position(positionRepository.findById(position).orElseThrow())
                .seniorityLevel(
                        seniorityLevelRepository.findById(seniorityLevelId).orElseThrow())
                .contractType(ContractType.PERMANENT)
                .monthlySalary(new BigDecimal("1500.00"))
                .startDate(LocalDate.now().minusDays(30))
                .endDate(LocalDate.now().plusDays(30))
                .enabled(true)
                .build());
    }

    /** Seeds the intent the way the schema suite does: raw, because the guarded creation needs a user. */
    private UUID seedTask(UUID employee) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_provisioning_tasks
                    (id, employee_id, kind, status, attempts, requested_by, requested_at)
                VALUES (?, ?, ?, 'PENDING', 0, 'kc-sub-requester', ?)
                """, id, employee, ACTIVATE.name(), LocalDateTime.now());
        return id;
    }

    private Status activeStatus() {
        return statusRepository
                .findByTypeNameAndStatusName("EMPLOYEE_STATUS", "Active")
                .orElseThrow(() -> new IllegalStateException("seed status is missing: Active"));
    }

    /**
     * The five attributes the run must write, derived by the <b>production</b> projection so the
     * expectation cannot drift from the rule under test: the derivation is read through its own
     * {@code readOnly} transaction (the entity has to be attached for it), and the empty levels are
     * dropped exactly as {@code converge} drops them.
     */
    private Map<String, List<String>> expectedAttributes() {
        var template = new TransactionTemplate(transactionManager);
        var scope = template.execute(status -> membershipService.deriveMembership(
                employeeRepository.findById(employeeId).orElseThrow()));
        var expected = new LinkedHashMap<String, List<String>>();
        expected.put(
                ScopeLevel.COMPANY.claim(),
                scope.companyIds().stream().map(UUID::toString).toList());
        putIfHeld(expected, ScopeLevel.COUNTRY.claim(), scope.companyCountryIds());
        putIfHeld(expected, ScopeLevel.REGION.claim(), scope.companyRegionIds());
        putIfHeld(expected, ScopeLevel.ZONE.claim(), scope.companyZoneIds());
        putIfHeld(expected, ScopeLevel.STORE.claim(), scope.companyStoreIds());
        return expected;
    }

    private void putIfHeld(Map<String, List<String>> attributes, String claim, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        attributes.put(claim, ids.stream().map(UUID::toString).toList());
    }

    // ---------------------------------------------------------------
    // Committed-state reads (fresh connection, never the entity)
    // ---------------------------------------------------------------

    private String committedStatus(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM access_provisioning_tasks WHERE id = ?", String.class, taskId);
    }

    private String committedLastError(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM access_provisioning_tasks WHERE id = ?", String.class, taskId);
    }

    private String committedKeycloakUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT keycloak_user_id FROM employees WHERE id = ?", String.class, employeeId);
    }

    private List<String> committedAppliedRoles(UUID taskId) {
        return jdbcTemplate.queryForList(
                "SELECT role_name FROM access_provisioning_applied_roles WHERE task_id = ? ORDER BY role_name",
                String.class,
                taskId);
    }

    /**
     * Deletes this suite's rows leaf-first, scoped to its own two company keys plus the globally
     * unique seniority level it created. The base class' unscoped
     * {@code deleteAllAccessProvisioningRows} is deliberately not used: this suite does not delete
     * every employee, so it is not entitled to delete every intent.
     */
    private void deleteOwnFixtures() {
        for (var seededCompanyId : seededCompanyIds) {
            var employeeIds = "SELECT id FROM employees WHERE company_id = ?";
            jdbcTemplate.update(
                    "DELETE FROM access_provisioning_applied_roles WHERE task_id IN ("
                            + "SELECT id FROM access_provisioning_tasks WHERE employee_id IN ("
                            + employeeIds
                            + "))",
                    seededCompanyId);
            jdbcTemplate.update(
                    "DELETE FROM access_provisioning_reviewed_roles WHERE task_id IN ("
                            + "SELECT id FROM access_provisioning_tasks WHERE employee_id IN ("
                            + employeeIds
                            + "))",
                    seededCompanyId);
            jdbcTemplate.update(
                    "DELETE FROM access_provisioning_tasks WHERE employee_id IN (" + employeeIds + ")",
                    seededCompanyId);
            jdbcTemplate.update(
                    "DELETE FROM employee_contracts WHERE employee_id IN (" + employeeIds + ")", seededCompanyId);
            jdbcTemplate.update("DELETE FROM employees WHERE company_id = ?", seededCompanyId);
            jdbcTemplate.update(
                    "DELETE FROM position_roles WHERE position_id IN ("
                            + "SELECT id FROM positions WHERE department_id IN ("
                            + "SELECT id FROM departments WHERE company_id = ?))",
                    seededCompanyId);
            jdbcTemplate.update(
                    "DELETE FROM positions WHERE department_id IN ("
                            + "SELECT id FROM departments WHERE company_id = ?)",
                    seededCompanyId);
            jdbcTemplate.update("DELETE FROM departments WHERE company_id = ?", seededCompanyId);
            jdbcTemplate.update("DELETE FROM companies WHERE id = ?", seededCompanyId);
        }
        seededCompanyIds.clear();
        jdbcTemplate.update("DELETE FROM seniority_levels WHERE level_code = ?", LEVEL_CODE);
    }
}
