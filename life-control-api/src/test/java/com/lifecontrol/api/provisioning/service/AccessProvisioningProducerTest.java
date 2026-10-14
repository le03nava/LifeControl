package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.DEACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.RECONCILE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.provisioning.exception.AmbiguousCurrentContractException;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
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
 * an employee who already has an open task is <b>not</b> enqueued twice — the producer asks before it
 * calls the gate, so the surrounding transaction is never marked rollback-only by a refusal it means
 * to swallow. The transaction itself is proven against real PostgreSQL by
 * {@code AccessProvisioningProducerTransactionIntegrationTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccessProvisioningProducer Tests")
class AccessProvisioningProducerTest {

    private static final String ACTOR_SUB = "d4a1f0c2-0000-0000-0000-000000000001";

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private AccessProvisioningGateService gateService;

    @Mock
    private AccessProvisioningTaskRepository taskRepository;

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
        @DisplayName("is not enqueued twice: the queued task already represents the fact")
        void thePromiseIsNotDuplicated() {
            when(taskRepository.existsByEmployeeIdAndStatusIn(employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(true);

            producer.onEmploymentTerminated(companyId, unlinked);

            verifyNoInteractions(gateService);
        }

        @Test
        @DisplayName("is asked about through the landed open set, so 'open' has one definition")
        void theOpenSetIsTheLandedOne() {
            when(taskRepository.existsByEmployeeIdAndStatusIn(employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(true);

            producer.onContractActivated(companyId, unlinked);

            verify(taskRepository)
                    .existsByEmployeeIdAndStatusIn(employeeId, AccessProvisioningTaskService.OPEN_STATUSES);
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
