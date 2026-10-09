package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningRoleDirection.GRANT;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningRoleDirection.REVOKE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.APPROVAL_PENDING;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.model.AccessProvisioningReviewedRole;
import com.lifecontrol.api.provisioning.model.AccessProvisioningRoleDirection;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningReviewedRoleRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Focused unit tests for {@link AccessProvisioningGateService}'s request path: the company check as
 * the first statement, the diff composed as the read path composes it, the status the approval
 * policy selects, and the frozen reviewed rows that are written only when the gate applies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccessProvisioningGateService Tests")
class AccessProvisioningGateServiceTest {

    private static final String CLIENT_ID = "life-control-client";
    private static final String KC_USER_ID = "kc-user-1";
    private static final String REQUESTED_BY = "requester-1";
    private static final String ACTOR_SUB = "d4a1f0c2-0000-0000-0000-000000000001";

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private AccessProvisioningRoleService roleService;

    @Mock
    private IdentityProvider identityProvider;

    @Mock
    private ApplicationClientProperties applicationClientProperties;

    @Mock
    private AccessProvisioningApprovalPolicy approvalPolicy;

    @Mock
    private AccessProvisioningTaskService taskService;

    @Mock
    private AccessProvisioningReviewedRoleRepository reviewedRoleRepository;

    @InjectMocks
    private AccessProvisioningGateService service;

    private UUID companyId;
    private UUID employeeId;
    private UUID taskId;
    private Employee linked;
    private Employee unlinked;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        var company = Company.builder()
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

    private RoleDto dto(String name) {
        return new RoleDto(name, null, Boolean.FALSE, RoleScope.CLIENT, CLIENT_ID);
    }

    // --- stubbing ---

    private void stubEmployee(Employee employee) {
        when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(employee));
    }

    private void stubRequiredFor(Employee employee, String... roles) {
        when(roleService.requiredRoles(employee)).thenReturn(Set.copyOf(Arrays.asList(roles)));
    }

    private void stubCurrentRoles(String... names) {
        when(applicationClientProperties.clientId()).thenReturn(CLIENT_ID);
        when(identityProvider.getUserRoles(KC_USER_ID, CLIENT_ID))
                .thenReturn(Arrays.stream(names).map(this::dto).toList());
    }

    private void stubPolicy(Set<String> touched, AccessProvisioningTaskStatus status) {
        when(approvalPolicy.initialStatusFor(touched)).thenReturn(status);
    }

    private AccessProvisioningTask stubCreate(AccessProvisioningTaskStatus status) {
        var task = AccessProvisioningTask.builder()
                .id(taskId)
                .employee(linked)
                .kind(ACTIVATE)
                .status(status)
                .attempts(0)
                .requestedBy(REQUESTED_BY)
                .build();
        when(taskService.create(companyId, employeeId, ACTIVATE, REQUESTED_BY, status))
                .thenReturn(task);
        return task;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<AccessProvisioningReviewedRole>> reviewedRowsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private AccessProvisioningReviewedRole frozenRow(String roleName, AccessProvisioningRoleDirection direction) {
        return AccessProvisioningReviewedRole.builder()
                .task(AccessProvisioningTask.builder()
                        .id(taskId)
                        .employee(linked)
                        .kind(ACTIVATE)
                        .status(APPROVAL_PENDING)
                        .build())
                .roleName(roleName)
                .direction(direction)
                .build();
    }

    private void stubFrozenRows(AccessProvisioningReviewedRole... rows) {
        when(reviewedRoleRepository.findByTaskIdOrderByRoleNameAsc(taskId)).thenReturn(List.of(rows));
    }

    private AccessProvisioningTask stubReturnToGate() {
        var task = AccessProvisioningTask.builder()
                .id(taskId)
                .employee(linked)
                .kind(ACTIVATE)
                .status(APPROVAL_PENDING)
                .build();
        when(taskService.returnToGate(employeeId, taskId, AccessProvisioningGateService.REVIEWED_SET_CHANGED_REASON))
                .thenReturn(task);
        return task;
    }

    // --- tests ---

    @Test
    @DisplayName("an all-store diff creates a PENDING task and freezes no reviewed rows")
    void autoApplyDiffIsPendingAndFreezesNothing() {
        stubEmployee(linked);
        stubRequiredFor(linked, "lc-sales");
        stubCurrentRoles();
        stubPolicy(Set.of("lc-sales"), PENDING);
        var task = stubCreate(PENDING);

        var result = service.request(companyId, employeeId, ACTIVATE, REQUESTED_BY);

        assertThat(result).isSameAs(task);
        assertThat(result.getStatus()).isEqualTo(PENDING);

        var inOrder = inOrder(currentUserContext, employeeRepository);
        inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
        inOrder.verify(employeeRepository).findByIdAndCompanyId(employeeId, companyId);

        verify(approvalPolicy).initialStatusFor(Set.of("lc-sales"));
        verify(taskService).create(companyId, employeeId, ACTIVATE, REQUESTED_BY, PENDING);
        verifyNoInteractions(reviewedRoleRepository);
    }

    @Test
    @DisplayName("a diff outside the auto-apply set is APPROVAL_PENDING and freezes the diff with direction")
    void gatedDiffIsApprovalPendingAndFreezesDiffWithDirection() {
        stubEmployee(linked);
        stubRequiredFor(linked, "lc-company", "lc-company-country");
        stubCurrentRoles("lc-sales", "lc-admin");
        var touched = Set.of("lc-company", "lc-company-country", "lc-sales");
        stubPolicy(touched, APPROVAL_PENDING);
        var task = stubCreate(APPROVAL_PENDING);

        var result = service.request(companyId, employeeId, ACTIVATE, REQUESTED_BY);

        assertThat(result).isSameAs(task);
        assertThat(result.getStatus()).isEqualTo(APPROVAL_PENDING);

        verify(approvalPolicy).initialStatusFor(touched);
        verify(taskService).create(companyId, employeeId, ACTIVATE, REQUESTED_BY, APPROVAL_PENDING);

        var captor = reviewedRowsCaptor();
        verify(reviewedRoleRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .as("one row per touched role, each with its direction; lc-admin is outside the grantable universe")
                .extracting(AccessProvisioningReviewedRole::getRoleName, AccessProvisioningReviewedRole::getDirection)
                .containsExactlyInAnyOrder(
                        tuple("lc-company", GRANT), tuple("lc-company-country", GRANT), tuple("lc-sales", REVOKE));
        assertThat(captor.getValue())
                .as("every frozen row points back at the task that was saved")
                .extracting(row -> row.getTask().getId())
                .containsOnly(taskId);
    }

    @Test
    @DisplayName("an unlinked employee reads no current roles and every required role is an addition")
    void unlinkedEmployeeHasEveryRequiredRoleAsAddition() {
        stubEmployee(unlinked);
        stubRequiredFor(unlinked, "lc-sales", "lc-employee");
        var touched = Set.of("lc-sales", "lc-employee");
        stubPolicy(touched, APPROVAL_PENDING);
        stubCreate(APPROVAL_PENDING);

        service.request(companyId, employeeId, ACTIVATE, REQUESTED_BY);

        verifyNoInteractions(identityProvider);
        verify(approvalPolicy).initialStatusFor(touched);

        var captor = reviewedRowsCaptor();
        verify(reviewedRoleRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .as("with no current roles, every required role is an addition")
                .extracting(AccessProvisioningReviewedRole::getRoleName, AccessProvisioningReviewedRole::getDirection)
                .containsExactlyInAnyOrder(tuple("lc-sales", GRANT), tuple("lc-employee", GRANT));
    }

    @Test
    @DisplayName("an empty diff creates a PENDING task and freezes nothing")
    void emptyDiffIsPendingAndFreezesNothing() {
        stubEmployee(linked);
        stubRequiredFor(linked, "lc-sales");
        stubCurrentRoles("lc-sales");
        stubPolicy(Set.of(), PENDING);
        var task = stubCreate(PENDING);

        var result = service.request(companyId, employeeId, ACTIVATE, REQUESTED_BY);

        assertThat(result).isSameAs(task);
        verify(approvalPolicy).initialStatusFor(Set.of());
        verify(taskService).create(companyId, employeeId, ACTIVATE, REQUESTED_BY, PENDING);
        verifyNoInteractions(reviewedRoleRepository);
    }

    @Test
    @DisplayName("a foreign or unknown employee is refused before anything is created or frozen")
    void foreignOrUnknownEmployeeIsRefused() {
        when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(companyId, employeeId, ACTIVATE, REQUESTED_BY))
                .isInstanceOf(EmployeeNotFoundException.class);

        verify(currentUserContext).verifyCompanyAccess(companyId);
        verifyNoInteractions(roleService, identityProvider, approvalPolicy, taskService, reviewedRoleRepository);
    }

    @Test
    @DisplayName("the company check is the first statement: a denied caller touches nothing else")
    void companyCheckIsFirst() {
        doThrow(new AccessDeniedException("denied")).when(currentUserContext).verifyCompanyAccess(companyId);

        assertThatThrownBy(() -> service.request(companyId, employeeId, ACTIVATE, REQUESTED_BY))
                .isInstanceOf(AccessDeniedException.class);

        verify(currentUserContext).verifyCompanyAccess(companyId);
        verifyNoInteractions(
                employeeRepository, roleService, identityProvider, approvalPolicy, taskService, reviewedRoleRepository);
    }

    // --- the re-gate check ---

    @Test
    @DisplayName("a frozen set equal to the live diff leaves the task alone and writes nothing")
    void unchangedReviewedSetLeavesTheTaskAlone() {
        stubFrozenRows(frozenRow("lc-company", GRANT), frozenRow("lc-sales", REVOKE));
        stubRequiredFor(linked, "lc-company");
        stubCurrentRoles("lc-sales");

        var changed = service.reenterGateIfReviewedSetChanged(linked, taskId);

        assertThat(changed).isFalse();
        verify(taskService, never()).returnToGate(any(), any(), any());
        verify(reviewedRoleRepository, never()).deleteAllByTaskId(any());
        verify(reviewedRoleRepository, never()).saveAll(any());
    }

    /**
     * Proves the identity the comparison uses is the <b>diff</b>, not the two raw role sets: both raw
     * inputs moved ({@code lc-department} and {@code lc-position} now sit on both sides) while the
     * additions stayed exactly {@code {lc-company}} and the removals exactly {@code {lc-sales}}, so
     * the untouched task is left alone. A comparison over the raw inputs would re-gate it here.
     */
    @Test
    @DisplayName("a changed raw set that leaves the diff identical does not re-gate: the identity is the diff")
    void changedRawSetsWithUnchangedDiffLeaveTheTaskAlone() {
        stubFrozenRows(frozenRow("lc-company", GRANT), frozenRow("lc-sales", REVOKE));
        stubRequiredFor(linked, "lc-department", "lc-company", "lc-position");
        stubCurrentRoles("lc-department", "lc-sales", "lc-position");

        var changed = service.reenterGateIfReviewedSetChanged(linked, taskId);

        assertThat(changed).isFalse();
        verify(taskService, never()).returnToGate(any(), any(), any());
        verify(reviewedRoleRepository, never()).deleteAllByTaskId(any());
        verify(reviewedRoleRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("no frozen rows short-circuits before any identity-provider read")
    void noFrozenRowsShortCircuitsBeforeTheIdentityProvider() {
        stubFrozenRows();

        var changed = service.reenterGateIfReviewedSetChanged(linked, taskId);

        assertThat(changed).isFalse();
        verifyNoInteractions(identityProvider);
        verifyNoInteractions(roleService);
        verify(taskService, never()).returnToGate(any(), any(), any());
        verify(reviewedRoleRepository, never()).deleteAllByTaskId(any());
        verify(reviewedRoleRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a diff that gained a role returns to the gate and replaces the frozen rows")
    void changedReviewedSetReturnsToGateAndReplacesTheRowsForAGrant() {
        stubFrozenRows(frozenRow("lc-sales", REVOKE));
        stubRequiredFor(linked, "lc-company");
        stubCurrentRoles("lc-sales");
        var replaced = stubReturnToGate();

        var changed = service.reenterGateIfReviewedSetChanged(linked, taskId);

        assertThat(changed).isTrue();
        verify(taskService).returnToGate(employeeId, taskId, AccessProvisioningGateService.REVIEWED_SET_CHANGED_REASON);
        verify(reviewedRoleRepository).deleteAllByTaskId(taskId);
        var captor = reviewedRowsCaptor();
        verify(reviewedRoleRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .as("the frozen set is replaced by the re-derived diff, with direction")
                .extracting(AccessProvisioningReviewedRole::getRoleName, AccessProvisioningReviewedRole::getDirection)
                .containsExactlyInAnyOrder(tuple("lc-company", GRANT), tuple("lc-sales", REVOKE));
        assertThat(captor.getValue())
                .as("the replacement rows hang off the task returnToGate returned")
                .extracting(row -> row.getTask().getId())
                .containsOnly(replaced.getId());
    }

    @Test
    @DisplayName("a diff that lost a role round-trips as a REVOKE-only replacement")
    void changedReviewedSetReturnsToGateAndReplacesTheRowsForARevoke() {
        stubFrozenRows(frozenRow("lc-sales", GRANT));
        stubRequiredFor(linked);
        stubCurrentRoles("lc-sales");
        stubReturnToGate();

        var changed = service.reenterGateIfReviewedSetChanged(linked, taskId);

        assertThat(changed).isTrue();
        verify(taskService).returnToGate(employeeId, taskId, AccessProvisioningGateService.REVIEWED_SET_CHANGED_REASON);
        verify(reviewedRoleRepository).deleteAllByTaskId(taskId);
        var captor = reviewedRowsCaptor();
        verify(reviewedRoleRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .as("a role that moved out of the required set becomes a REVOKE")
                .extracting(AccessProvisioningReviewedRole::getRoleName, AccessProvisioningReviewedRole::getDirection)
                .containsExactly(tuple("lc-sales", REVOKE));
    }

    // --- decision methods ---

    @Test
    @DisplayName("approve checks the company first and delegates with the JWT subject as the actor")
    void approveChecksCompanyFirstAndDelegatesWithTheUserId() {
        when(currentUserContext.getUserId()).thenReturn(ACTOR_SUB);

        service.approve(companyId, employeeId, taskId);

        var inOrder = inOrder(currentUserContext, taskService);
        inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
        inOrder.verify(taskService).approve(employeeId, taskId, ACTOR_SUB);

        var actorCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).approve(eq(employeeId), eq(taskId), actorCaptor.capture());
        assertThat(actorCaptor.getValue())
                .as("the persisted decided_by is the JWT sub, the same basis requested_by uses")
                .isEqualTo(ACTOR_SUB);
        verify(currentUserContext, never()).getUsername();
    }

    @Test
    @DisplayName("approve refuses a denied caller before it reaches the task service")
    void approveRefusesDeniedCallerBeforeTheTaskService() {
        doThrow(new AccessDeniedException("denied")).when(currentUserContext).verifyCompanyAccess(companyId);

        assertThatThrownBy(() -> service.approve(companyId, employeeId, taskId))
                .isInstanceOf(AccessDeniedException.class);

        verify(currentUserContext).verifyCompanyAccess(companyId);
        verifyNoInteractions(taskService);
        verify(currentUserContext, never()).getUserId();
    }

    @Test
    @DisplayName("reject checks the company first and delegates with the JWT subject and the reason")
    void rejectChecksCompanyFirstAndDelegatesWithTheUserIdAndReason() {
        when(currentUserContext.getUserId()).thenReturn(ACTOR_SUB);

        service.reject(companyId, employeeId, taskId, "outside the allowlist");

        var inOrder = inOrder(currentUserContext, taskService);
        inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
        inOrder.verify(taskService).reject(employeeId, taskId, ACTOR_SUB, "outside the allowlist");

        var actorCaptor = ArgumentCaptor.forClass(String.class);
        var reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).reject(eq(employeeId), eq(taskId), actorCaptor.capture(), reasonCaptor.capture());
        assertThat(actorCaptor.getValue())
                .as("the persisted decided_by is the JWT sub, the same basis requested_by uses")
                .isEqualTo(ACTOR_SUB);
        assertThat(reasonCaptor.getValue()).isEqualTo("outside the allowlist");
        verify(currentUserContext, never()).getUsername();
    }

    @Test
    @DisplayName("reject refuses a denied caller before it reaches the task service")
    void rejectRefusesDeniedCallerBeforeTheTaskService() {
        doThrow(new AccessDeniedException("denied")).when(currentUserContext).verifyCompanyAccess(companyId);

        assertThatThrownBy(() -> service.reject(companyId, employeeId, taskId, "no"))
                .isInstanceOf(AccessDeniedException.class);

        verify(currentUserContext).verifyCompanyAccess(companyId);
        verifyNoInteractions(taskService);
        verify(currentUserContext, never()).getUserId();
    }
}
