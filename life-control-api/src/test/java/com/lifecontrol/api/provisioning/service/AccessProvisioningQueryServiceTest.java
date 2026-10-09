package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.config.provisioning.ProvisioningWorkerProperties;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeStoreScope;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningClaims;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningTaskView;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Focused unit tests for {@link AccessProvisioningQueryService}: the read model the Access section
 * shows.
 *
 * <p>Covered are the decisions the unit is built on — the company access check as the first
 * statement (a cross-company employee is refused before any load), the required set coming from the
 * write path's own derivation, the current roles read <b>live</b> from the identity provider (T7),
 * the no-linked-account path that never calls the port with a {@code null} user id, the diff with
 * both an addition and a removal while a {@code lc-admin} the account holds is never proposed for
 * removal (T12/T34), and a {@code FAILED} task carrying its reason with {@code attempts} rendered
 * against the configured maximum (T10/T43).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccessProvisioningQueryService Tests")
class AccessProvisioningQueryServiceTest {

    private static final String CLIENT_ID = "life-control-client";
    private static final String KC_USER_ID = "kc-user-1";
    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private AccessProvisioningRoleService roleService;

    @Mock
    private AccessProvisioningMembershipService membershipService;

    @Mock
    private IdentityProvider identityProvider;

    @Mock
    private ApplicationClientProperties applicationClientProperties;

    @Mock
    private AccessProvisioningTaskRepository taskRepository;

    @Mock
    private ProvisioningWorkerProperties provisioningWorkerProperties;

    @InjectMocks
    private AccessProvisioningQueryService service;

    private UUID companyId;
    private UUID employeeId;
    private UUID taskId;
    private Company company;
    private Employee linked;
    private Employee unlinked;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        company = Company.builder()
                .id(companyId)
                .companyKey("acme")
                .companyName("Acme")
                .rfc("ACM010101ABC")
                .emailDomain("acme.com")
                .build();
        linked = Employee.builder()
                .id(employeeId)
                .company(company)
                .email("jane.doe@acme.com")
                .keycloakUserId(KC_USER_ID)
                .status(activeStatus())
                .build();
        unlinked = Employee.builder()
                .id(employeeId)
                .company(company)
                .email("jane.doe@acme.com")
                .status(activeStatus())
                .build();
    }

    // --- fixtures ---

    private Status activeStatus() {
        return Status.builder().statusName("Active").enabled(true).build();
    }

    private EmployeeStoreScope scope(UUID... companyIds) {
        return new EmployeeStoreScope(Set.of(companyIds), Set.of(), Set.of(), Set.of(), Set.of());
    }

    private RoleDto dto(String name) {
        return new RoleDto(name, null, Boolean.FALSE, RoleScope.CLIENT, CLIENT_ID);
    }

    private AccessProvisioningTask task(
            AccessProvisioningTaskStatus status, int attempts, String lastError, LocalDateTime nextAttemptAt) {
        return task(taskId, status, attempts, lastError, nextAttemptAt);
    }

    private AccessProvisioningTask task(UUID id, AccessProvisioningTaskStatus status, int attempts, String lastError) {
        return task(id, status, attempts, lastError, null);
    }

    private AccessProvisioningTask task(
            UUID id, AccessProvisioningTaskStatus status, int attempts, String lastError, LocalDateTime nextAttemptAt) {
        return AccessProvisioningTask.builder()
                .id(id)
                .employee(linked)
                .kind(ACTIVATE)
                .status(status)
                .attempts(attempts)
                .lastError(lastError)
                .nextAttemptAt(nextAttemptAt)
                .requestedBy("requester-1")
                .requestedAt(LocalDateTime.of(2026, 10, 8, 9, 0))
                .enabled(true)
                .build();
    }

    // --- stubbing ---

    private void stubEmployee(Employee employee) {
        when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(employee));
    }

    private void stubRequired(String... roles) {
        when(roleService.requiredRoles(linked)).thenReturn(Set.copyOf(Arrays.asList(roles)));
    }

    private void stubRequiredFor(Employee employee, String... roles) {
        when(roleService.requiredRoles(employee)).thenReturn(Set.copyOf(Arrays.asList(roles)));
    }

    private void stubCurrentRoles(String... names) {
        when(applicationClientProperties.clientId()).thenReturn(CLIENT_ID);
        when(identityProvider.getUserRoles(KC_USER_ID, CLIENT_ID))
                .thenReturn(Arrays.stream(names).map(this::dto).toList());
    }

    private void stubNoCurrentRoles() {
        stubCurrentRoles();
    }

    private void stubMembership(EmployeeStoreScope derived) {
        when(membershipService.deriveMembership(linked)).thenReturn(derived);
    }

    private void stubTasks(AccessProvisioningTask... tasks) {
        when(taskRepository.findByEmployeeIdOrderByRequestedAtDesc(employeeId)).thenReturn(List.of(tasks));
    }

    private void stubMaxAttempts() {
        when(provisioningWorkerProperties.maxAttempts()).thenReturn(MAX_ATTEMPTS);
    }

    // --- tests ---

    @Test
    @DisplayName("an employee with no linked account says so, reads no current roles and no claims")
    void noLinkedAccount() {
        stubEmployee(unlinked);
        stubRequiredFor(unlinked, "lc-sales");
        stubTasks();

        var overview = service.getAccessOverview(companyId, employeeId);

        assertThat(overview.accountLinked()).isFalse();
        assertThat(overview.keycloakUserId()).isNull();
        assertThat(overview.requiredRoles()).containsExactly("lc-sales");
        assertThat(overview.currentRoles()).isEmpty();
        assertThat(overview.roleDiff().added()).containsExactly("lc-sales");
        assertThat(overview.roleDiff().removed()).isEmpty();
        assertThat(overview.openTask()).isNull();
        assertThat(overview.history()).isEmpty();
        assertThat(overview.claims()).isEqualTo(AccessProvisioningClaims.empty());

        verify(currentUserContext).verifyCompanyAccess(companyId);
        verifyNoInteractions(identityProvider);
        verifyNoInteractions(membershipService);
    }

    @Test
    @DisplayName("a linked account whose required set equals its current set has an empty diff")
    void linkedWithEmptyDiff() {
        stubEmployee(linked);
        stubRequired("lc-sales");
        stubCurrentRoles("lc-sales");
        stubMembership(scope(companyId));
        stubTasks();

        var overview = service.getAccessOverview(companyId, employeeId);

        assertThat(overview.accountLinked()).isTrue();
        assertThat(overview.keycloakUserId()).isEqualTo(KC_USER_ID);
        assertThat(overview.requiredRoles()).containsExactly("lc-sales");
        assertThat(overview.currentRoles()).containsExactly("lc-sales");
        assertThat(overview.roleDiff().added()).isEmpty();
        assertThat(overview.roleDiff().removed()).isEmpty();
        verify(identityProvider).getUserRoles(KC_USER_ID, CLIENT_ID);
    }

    @Test
    @DisplayName("the diff separates the addition from the removal and never proposes lc-admin")
    void diffHasAdditionAndRemoval() {
        stubEmployee(linked);
        stubRequired("lc-company", "lc-receiving");
        stubCurrentRoles("lc-company", "lc-sales", "lc-admin");
        stubMembership(scope(companyId));
        stubTasks();

        var overview = service.getAccessOverview(companyId, employeeId);

        assertThat(overview.requiredRoles()).containsExactly("lc-company", "lc-receiving");
        assertThat(overview.currentRoles()).containsExactly("lc-admin", "lc-company", "lc-sales");
        assertThat(overview.roleDiff().added()).containsExactly("lc-receiving");
        assertThat(overview.roleDiff().removed())
                .as("a held role outside the grantable universe is never proposed for removal")
                .containsExactly("lc-sales");
    }

    @Test
    @DisplayName("a FAILED task carries its reason and its attempts rendered against the maximum")
    void failedTaskCarriesReasonAndAttemptsAgainstMaximum() {
        var deadline = LocalDateTime.of(2026, 10, 8, 9, 30);
        stubEmployee(linked);
        stubRequired();
        stubNoCurrentRoles();
        stubMembership(scope(companyId));
        stubTasks(task(FAILED, MAX_ATTEMPTS, "keycloak is unreachable", deadline));
        stubMaxAttempts();

        var overview = service.getAccessOverview(companyId, employeeId);

        assertThat(overview.openTask()).isNotNull();
        assertThat(overview.openTask().id()).isEqualTo(taskId);
        assertThat(overview.openTask().kind()).isEqualTo(ACTIVATE);
        assertThat(overview.openTask().status()).isEqualTo(FAILED);
        assertThat(overview.openTask().attempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(overview.openTask().maxAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(overview.openTask().lastError()).isEqualTo("keycloak is unreachable");
        assertThat(overview.openTask().nextAttemptAt()).isEqualTo(deadline);
        assertThat(overview.openTask().requestedBy()).isEqualTo("requester-1");
        assertThat(overview.history()).containsExactly(overview.openTask());
    }

    @Test
    @DisplayName("a terminal-only history keeps no open task but still renders both terminal rows")
    void terminalOnlyHistoryKeepsNoOpenTaskAndRendersBothRows() {
        var newestId = UUID.randomUUID();
        var olderId = UUID.randomUUID();
        stubEmployee(linked);
        stubRequired();
        stubNoCurrentRoles();
        stubMembership(scope(companyId));
        stubTasks(
                task(newestId, AccessProvisioningTaskStatus.APPLIED, 2, null),
                task(olderId, AccessProvisioningTaskStatus.REJECTED, 4, "rejected by reviewer"));
        stubMaxAttempts();

        var overview = service.getAccessOverview(companyId, employeeId);

        assertThat(overview.openTask()).isNull();
        assertThat(overview.history())
                .extracting(AccessProvisioningTaskView::id)
                .containsExactly(newestId, olderId);
        assertThat(overview.history())
                .extracting(AccessProvisioningTaskView::status)
                .containsExactly(AccessProvisioningTaskStatus.APPLIED, AccessProvisioningTaskStatus.REJECTED);
        assertThat(overview.history())
                .extracting(AccessProvisioningTaskView::attempts)
                .containsExactly(2, 4);
        assertThat(overview.history())
                .extracting(AccessProvisioningTaskView::maxAttempts)
                .containsExactly(MAX_ATTEMPTS, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("a cross-company employee is refused by verifyCompanyAccess before any load")
    void crossCompanyEmployeeIsRefusedBeforeAnyLoad() {
        doThrow(new AccessDeniedException("denied")).when(currentUserContext).verifyCompanyAccess(companyId);

        assertThatThrownBy(() -> service.getAccessOverview(companyId, employeeId))
                .isInstanceOf(AccessDeniedException.class);

        verify(currentUserContext).verifyCompanyAccess(companyId);
        verifyNoInteractions(
                employeeRepository,
                roleService,
                membershipService,
                identityProvider,
                applicationClientProperties,
                taskRepository,
                provisioningWorkerProperties);
    }

    @Test
    @DisplayName("a linked account carries the five claim values its membership derives")
    void linkedAccountCarriesDerivedClaims() {
        var countryId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        stubEmployee(linked);
        stubRequired();
        stubNoCurrentRoles();
        stubMembership(
                new EmployeeStoreScope(Set.of(companyId), Set.of(countryId), Set.of(), Set.of(), Set.of(storeId)));
        stubTasks();

        var overview = service.getAccessOverview(companyId, employeeId);

        assertThat(overview.claims().companyId()).containsExactly(companyId.toString());
        assertThat(overview.claims().companyCountryId()).containsExactly(countryId.toString());
        assertThat(overview.claims().companyRegionId()).isEmpty();
        assertThat(overview.claims().companyZoneId()).isEmpty();
        assertThat(overview.claims().companyStoreId()).containsExactly(storeId.toString());
        verify(membershipService).deriveMembership(linked);
    }

    // --- accessState: the narrow read behind the employee GET (record G3) ---

    @Test
    @DisplayName("accessState is null when the employee has no provisioning task")
    void accessState_NoTask_IsNull() {
        stubTasks();

        assertThat(service.accessState(employeeId)).isNull();
    }

    @Test
    @DisplayName("accessState is PENDING when the newest task is open and running")
    void accessState_Running_IsPending() {
        stubTasks(task(AccessProvisioningTaskStatus.RUNNING, 1, null, null));

        assertThat(service.accessState(employeeId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("accessState is FAILED when the newest task failed")
    void accessState_Failed_IsFailed() {
        stubTasks(task(FAILED, MAX_ATTEMPTS, "keycloak is unreachable", null));

        assertThat(service.accessState(employeeId)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("accessState is null when the history is terminal (APPLIED only)")
    void accessState_AppliedOnly_IsNull() {
        stubTasks(task(AccessProvisioningTaskStatus.APPLIED, 1, null, null));

        assertThat(service.accessState(employeeId)).isNull();
    }

    @Test
    @DisplayName("accessState decides on the newest task, not on any open one")
    void accessState_DecidesOnNewestTask() {
        // The repository returns newest first: a newer RUNNING shadows an older FAILED.
        stubTasks(
                task(AccessProvisioningTaskStatus.RUNNING, 1, null, null),
                task(FAILED, MAX_ATTEMPTS, "older failure", null));

        assertThat(service.accessState(employeeId)).isEqualTo("PENDING");
    }
}
