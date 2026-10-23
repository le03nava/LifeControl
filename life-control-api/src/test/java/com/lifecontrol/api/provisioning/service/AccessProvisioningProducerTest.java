package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.DEACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.RECONCILE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.AmbiguousCurrentContractException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.status.model.Status;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Focused unit tests for {@link AccessProvisioningProducer}: which kind a fact implies, which actor is
 * recorded, and the one case the producer tolerates instead of propagating.
 *
 * <p>What is pinned here is the <b>rule</b>, not the transaction: that a contract start chooses
 * {@code RECONCILE} for a linked employee and {@code ACTIVATE} for an unlinked one (record T88),
 * because {@code ACTIVATE} would re-send an invitation nobody asked for and {@code RECONCILE} refuses
 * outright on an unlinked account; that the actor is the JWT {@code sub} claim (record T83); and that
 * an employee who already has an open task of the <b>same</b> kind is not enqueued twice — the
 * producer asks before it calls the gate, so the surrounding transaction is never marked
 * rollback-only by a refusal it means to swallow — while an open task of a <b>different</b> kind is
 * superseded and the new fact is enqueued, because that task does not represent it (records R3-001
 * and R4-001 of this unit's own review). The transaction itself is proven against real PostgreSQL by
 * {@code AccessProvisioningProducerTransactionIntegrationTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccessProvisioningProducer Tests")
class AccessProvisioningProducerTest {

    private static final String ACTOR_SUB = "d4a1f0c2-0000-0000-0000-000000000001";
    private static final UUID OPEN_TASK_ID = UUID.fromString("d4a1f0c2-0000-0000-0000-0000000000ff");

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private AccessProvisioningGateService gateService;

    @Mock
    private AccessProvisioningTaskRepository taskRepository;

    @Mock
    private AccessProvisioningTaskService taskService;

    @Mock
    private EmployeeRepository employeeRepository;

    @InjectMocks
    private AccessProvisioningProducer producer;

    private UUID companyId;
    private UUID employeeId;
    private Employee unlinked;
    private Employee linked;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();

        var company = Company.builder()
                .id(companyId)
                .companyKey("acme")
                .companyName("Acme")
                .rfc("ACM010101ABC")
                .emailDomain("acme.com")
                .build();

        unlinked = Employee.builder()
                .id(employeeId)
                .company(company)
                .email("juan.perez@acme.com")
                .build();
        linked = Employee.builder()
                .id(employeeId)
                .company(company)
                .email("juan.perez@acme.com")
                .keycloakUserId("kc-user-1")
                .build();

        when(currentUserContext.getUserId()).thenReturn(ACTOR_SUB);
    }

    /** The same linked employee, soft-deleted: {@code enabled = false} with every other fact standing. */
    private Employee disabledCopy() {
        var disabled = Employee.builder()
                .id(employeeId)
                .company(linked.getCompany())
                .email("juan.perez@acme.com")
                .keycloakUserId("kc-user-1")
                .enabled(false)
                .build();
        return disabled;
    }

    private AccessProvisioningTask openRevocationOf(Employee ofEmployee) {
        return AccessProvisioningTask.builder()
                .id(OPEN_TASK_ID)
                .employee(ofEmployee)
                .kind(DEACTIVATE)
                .status(PENDING)
                .attempts(0)
                .build();
    }

    @Nested
    @DisplayName("onContractActivated")
    class ContractActivated {

        @Test
        @DisplayName("requests an ACTIVATE for an employee with no linked account")
        void unlinkedEmployeeIsActivated() {
            producer.onContractActivated(companyId, unlinked);

            verify(gateService).request(companyId, employeeId, ACTIVATE, ACTOR_SUB);
        }

        @Test
        @DisplayName("requests a RECONCILE for an employee who is already linked")
        void linkedEmployeeIsReconciled() {
            producer.onContractActivated(companyId, linked);

            verify(gateService).request(companyId, employeeId, RECONCILE, ACTOR_SUB);
        }

        @Test
        @DisplayName("a blank keycloak user id is not a linked account")
        void blankUserIdIsNotLinked() {
            unlinked.linkKeycloakUser("   ");

            producer.onContractActivated(companyId, unlinked);

            verify(gateService).request(companyId, employeeId, ACTIVATE, ACTOR_SUB);
        }

        @Test
        @DisplayName("records the JWT sub claim, never the username")
        void theActorIsTheSubjectClaim() {
            producer.onContractActivated(companyId, linked);

            verify(currentUserContext, never()).getUsername();
        }

        @Test
        @DisplayName("a soft-deleted employee enqueues nothing: the revocation owns the fact (G41)")
        void disabledEmployeeEnqueuesNothing() {
            var disabled = disabledCopy();

            producer.onContractActivated(companyId, disabled);

            verifyNoInteractions(gateService);
        }

        @Test
        @DisplayName("a soft-deleted employee never supersedes the open revocation (G41)")
        void disabledEmployeeNeverSupersedesTheOpenRevocation() {
            var disabled = disabledCopy();
            when(taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(Optional.of(openRevocationOf(disabled)));

            producer.onContractActivated(companyId, disabled);

            verifyNoInteractions(gateService);
            verify(taskService, never()).supersede(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("the employee row is locked before the open set is read, so the decision is serialized")
        void theEmployeeRowIsLockedBeforeTheOpenSetRead() {
            // The ORDER is the guard's whole content: taking the lock after the read would still lose
            // the race (both transactions would have passed the pre-check already), and taking it after
            // a task lock would deadlock against the facts this class serves. It is pinned here, at
            // unit level and in milliseconds, because the suite that proves the behaviour needs real
            // PostgreSQL and a latched two-transaction interleaving — the independent verification of
            // this unit proposed exactly this probe and confirmed it fails when the call is removed.
            var inOrder = inOrder(employeeRepository, taskRepository);

            producer.onContractActivated(companyId, unlinked);

            inOrder.verify(employeeRepository).findByIdAndCompanyIdForUpdate(employeeId, companyId);
            inOrder.verify(taskRepository)
                    .findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES);
        }
    }

    @Nested
    @DisplayName("onMembershipChanged")
    class MembershipChanged {

        @Test
        @DisplayName("requests exactly one RECONCILE for an employee who is already linked")
        void linkedEmployeeIsReconciled() {
            producer.onMembershipChanged(companyId, linked);

            verify(gateService).request(companyId, employeeId, RECONCILE, ACTOR_SUB);
            // "Exactly one": a second intent, and in particular an ACTIVATE that would create an
            // account and send an invitation nobody asked for, must not be reachable from this fact.
            verifyNoMoreInteractions(gateService);
        }

        @Test
        @DisplayName("an unlinked employee has no account to converge, so nothing is enqueued")
        void unlinkedEmployeeEnqueuesNothing() {
            producer.onMembershipChanged(companyId, unlinked);

            verifyNoInteractions(gateService, taskRepository, taskService);
        }

        @Test
        @DisplayName("a blank keycloak user id is not a linked account either")
        void blankUserIdEnqueuesNothing() {
            unlinked.linkKeycloakUser("   ");

            producer.onMembershipChanged(companyId, unlinked);

            verifyNoInteractions(gateService, taskRepository, taskService);
        }

        @Test
        @DisplayName("a Terminated employee enqueues nothing and never supersedes the open revocation")
        void terminatedEmployeeEnqueuesNothing() {
            var terminated = Employee.builder()
                    .id(employeeId)
                    .company(linked.getCompany())
                    .email("juan.perez@acme.com")
                    .keycloakUserId("kc-user-1")
                    .status(Status.builder()
                            .statusName("Terminated")
                            .enabled(true)
                            .build())
                    .build();
            var openRevocation = AccessProvisioningTask.builder()
                    .id(OPEN_TASK_ID)
                    .employee(terminated)
                    .kind(DEACTIVATE)
                    .status(PENDING)
                    .attempts(0)
                    .build();
            when(taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(Optional.of(openRevocation));

            producer.onMembershipChanged(companyId, terminated);

            verifyNoInteractions(gateService);
            verify(taskService, never()).supersede(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("a soft-deleted employee enqueues nothing and never supersedes the open revocation (G41)")
        void disabledEmployeeEnqueuesNothing() {
            var disabled = disabledCopy();
            when(taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(Optional.of(openRevocationOf(disabled)));

            producer.onMembershipChanged(companyId, disabled);

            verifyNoInteractions(gateService);
            verify(taskService, never()).supersede(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("records the JWT sub claim, never the username")
        void theActorIsTheSubjectClaim() {
            producer.onMembershipChanged(companyId, linked);

            verify(currentUserContext, never()).getUsername();
        }
    }

    @Nested
    @DisplayName("onSoftDelete")
    class SoftDelete {

        @Test
        @DisplayName("requests a DEACTIVATE for a linked employee (G41)")
        void linkedEmployeeIsRevoked() {
            producer.onSoftDelete(companyId, linked);

            verify(gateService).request(companyId, employeeId, DEACTIVATE, ACTOR_SUB);
        }

        @Test
        @DisplayName("an unlinked employee has no access to revoke, so nothing is enqueued (G41)")
        void unlinkedEmployeeEnqueuesNothing() {
            producer.onSoftDelete(companyId, unlinked);

            verifyNoInteractions(gateService);
        }

        @Test
        @DisplayName("records the JWT sub claim, never the username")
        void theActorIsTheSubjectClaim() {
            producer.onSoftDelete(companyId, linked);

            verify(currentUserContext, never()).getUsername();
        }
    }

    @Nested
    @DisplayName("onAccessRestored")
    class AccessRestored {

        @Test
        @DisplayName("requests exactly one RECONCILE for a linked employee (G41)")
        void linkedEmployeeIsReconciled() {
            producer.onAccessRestored(companyId, linked);

            verify(gateService).request(companyId, employeeId, RECONCILE, ACTOR_SUB);
        }

        @Test
        @DisplayName("an unlinked employee enqueues nothing: a re-enable is not the moment to create an account")
        void unlinkedEmployeeEnqueuesNothing() {
            producer.onAccessRestored(companyId, unlinked);

            verifyNoInteractions(gateService);
        }

        @Test
        @DisplayName("a Terminated employee enqueues nothing: the revocation owns the fact")
        void terminatedEmployeeEnqueuesNothing() {
            var terminated = Employee.builder()
                    .id(employeeId)
                    .company(linked.getCompany())
                    .email("juan.perez@acme.com")
                    .keycloakUserId("kc-user-1")
                    .status(Status.builder()
                            .statusName("Terminated")
                            .enabled(true)
                            .build())
                    .build();

            producer.onAccessRestored(companyId, terminated);

            verifyNoInteractions(gateService);
        }

        @Test
        @DisplayName("records the JWT sub claim, never the username")
        void theActorIsTheSubjectClaim() {
            producer.onAccessRestored(companyId, linked);

            verify(currentUserContext, never()).getUsername();
        }
    }

    @Nested
    @DisplayName("onEmploymentTerminated")
    class EmploymentTerminated {

        @Test
        @DisplayName("requests a DEACTIVATE for a linked employee")
        void linkedEmployeeIsDeactivated() {
            producer.onEmploymentTerminated(companyId, linked);

            verify(gateService).request(companyId, employeeId, DEACTIVATE, ACTOR_SUB);
        }

        @Test
        @DisplayName("requests a DEACTIVATE for an unlinked employee too: a revocation needs no account check")
        void unlinkedEmployeeIsDeactivatedToo() {
            producer.onEmploymentTerminated(companyId, unlinked);

            verify(gateService).request(companyId, employeeId, DEACTIVATE, ACTOR_SUB);
        }

        @Test
        @DisplayName("records the JWT sub claim, never the username")
        void theActorIsTheSubjectClaim() {
            producer.onEmploymentTerminated(companyId, unlinked);

            verify(currentUserContext, never()).getUsername();
        }
    }

    @Nested
    @DisplayName("an employee who already has an open task")
    class AlreadyOpen {

        @Test
        @DisplayName("the same kind is not enqueued twice: the queued task already represents the fact")
        void thePromiseIsNotDuplicated() {
            when(taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(Optional.of(openTask(DEACTIVATE)));

            producer.onEmploymentTerminated(companyId, unlinked);

            verifyNoInteractions(gateService);
        }

        @Test
        @DisplayName("is read through the landed open set, so 'open' has one definition")
        void theOpenSetIsTheLandedOne() {
            when(taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(Optional.of(openTask(DEACTIVATE)));

            producer.onContractActivated(companyId, unlinked);

            verify(taskRepository)
                    .findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES);
        }

        @Test
        @DisplayName("a different kind supersedes it instead of dropping the new fact")
        void aDifferentKindIsNotSwallowed() {
            when(taskRepository.existsByEmployeeIdAndStatusIn(employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(true);
            when(taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(Optional.of(openTask(ACTIVATE)));

            producer.onEmploymentTerminated(companyId, unlinked);

            verify(taskService).supersede(eq(employeeId), eq(OPEN_TASK_ID), eq(ACTOR_SUB), anyString());
            verify(gateService).request(companyId, employeeId, DEACTIVATE, ACTOR_SUB);
        }

        private AccessProvisioningTask openTask(AccessProvisioningTaskKind kind) {
            return AccessProvisioningTask.builder()
                    .id(OPEN_TASK_ID)
                    .employee(unlinked)
                    .kind(kind)
                    .status(PENDING)
                    .attempts(0)
                    .build();
        }
    }

    @Test
    @DisplayName("no other refusal is swallowed: the fact rolls back with it")
    void otherFailuresPropagate() {
        when(gateService.request(companyId, employeeId, DEACTIVATE, ACTOR_SUB))
                .thenThrow(
                        new AmbiguousCurrentContractException("Ambiguous current contract for employee " + employeeId));

        assertThatThrownBy(() -> producer.onEmploymentTerminated(companyId, unlinked))
                .isInstanceOf(AmbiguousCurrentContractException.class);
    }
}
