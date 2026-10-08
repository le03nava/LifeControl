package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.DEACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.RECONCILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.service.EmployeeStoreScope;
import com.lifecontrol.api.provisioning.dto.AccountProvisioningResult;
import com.lifecontrol.api.provisioning.dto.RoleConvergenceResult;
import com.lifecontrol.api.provisioning.exception.AccountDeactivationNotSupportedException;
import com.lifecontrol.api.provisioning.exception.AccountNotLinkedException;
import com.lifecontrol.api.provisioning.exception.CompanyScopeInvariantException;
import com.lifecontrol.api.provisioning.exception.InvitationNotDeliverableException;
import com.lifecontrol.api.provisioning.model.AccountProvisioningOutcome;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Focused unit tests for {@link AccessProvisioningWorkerHandler}: the per-kind dispatch with its call
 * order (records T44/T59), the touched-set contract of the snapshot (record T35), the fail-closed
 * {@code DEACTIVATE} refusal (records G19/T59) and the no-swallow rule (records T10/T13).
 *
 * <p>The three capabilities are mocked — the composition of the real services is record G23's
 * limitation, declared rather than proven here — so every assertion is about <b>what this handler
 * decided</b>: which capability it called, in which order, and what it returned. The call order is
 * pinned with {@code InOrder} and the absences with {@code verifyNoInteractions}, because a dispatch
 * test that only checked return values would pass for a handler that called everything for every
 * kind.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessProvisioningWorkerHandler Tests")
class AccessProvisioningWorkerHandlerTest {

    private static final Set<String> NO_ROLES = Set.of();
    private static final Set<UUID> NO_IDS = Set.of();

    @Mock
    private AccessProvisioningAccountService accountService;

    @Mock
    private AccessProvisioningRoleService roleService;

    @Mock
    private AccessProvisioningMembershipService membershipService;

    @InjectMocks
    private AccessProvisioningWorkerHandler handler;

    private Employee employee;

    @BeforeEach
    void setUp() {
        employee = Employee.builder().id(UUID.randomUUID()).build();
    }

    private void stubActivate() {
        when(accountService.activate(employee))
                .thenReturn(new AccountProvisioningResult("kc-user-1", AccountProvisioningOutcome.CREATED));
    }

    private void stubRoles(Set<String> required, Set<String> granted, Set<String> removed) {
        when(roleService.convergeRoles(employee)).thenReturn(new RoleConvergenceResult(required, granted, removed));
    }

    /** The membership projection's own return value is not asserted here: its consumer is the claim writer. */
    private void stubMembership() {
        when(membershipService.convergeMembership(employee))
                .thenReturn(new EmployeeStoreScope(NO_IDS, NO_IDS, NO_IDS, NO_IDS, NO_IDS));
    }

    @Nested
    @DisplayName("ACTIVATE")
    class Activate {

        @Test
        @DisplayName("runs the account lifecycle first, then roles, then membership, and returns granted ∪ removed")
        void activatesThenConvergesRolesThenMembership() {
            stubActivate();
            stubRoles(Set.of("lc-a", "lc-b", "lc-c"), Set.of("lc-a", "lc-b"), Set.of("lc-d"));
            stubMembership();

            var touched = handler.apply(employee, ACTIVATE);

            // A member without an account has nowhere to receive roles, so the lifecycle leads; the
            // membership projection comes last because it only publishes the scope the diff implies.
            var inOrder = inOrder(accountService, roleService, membershipService);
            inOrder.verify(accountService).activate(employee);
            inOrder.verify(roleService).convergeRoles(employee);
            inOrder.verify(membershipService).convergeMembership(employee);
            // The required role lc-c was already held, so it is neither granted nor removed and must
            // not appear in the touched set.
            assertThat(touched).containsExactlyInAnyOrder("lc-a", "lc-b", "lc-d");
        }
    }

    @Nested
    @DisplayName("RECONCILE")
    class Reconcile {

        @Test
        @DisplayName("converges roles then membership and never calls the account lifecycle")
        void convergesTheTwoProjectionsWithoutTouchingTheAccountLifecycle() {
            stubRoles(Set.of("lc-a"), Set.of("lc-a"), NO_ROLES);
            stubMembership();

            handler.apply(employee, RECONCILE);

            var inOrder = inOrder(roleService, membershipService);
            inOrder.verify(roleService).convergeRoles(employee);
            inOrder.verify(membershipService).convergeMembership(employee);
            // A reconcile re-derives the desired state; re-inviting on it would send an invitation
            // nobody asked for.
            verifyNoInteractions(accountService);
        }
    }

    @Nested
    @DisplayName("the touched set is granted ∪ removed (record T35)")
    class TouchedSet {

        @Test
        @DisplayName("returns the granted roles only, never the un-granted required ones")
        void grantedOnly() {
            stubRoles(Set.of("lc-a", "lc-b", "lc-c"), Set.of("lc-a", "lc-b"), NO_ROLES);
            stubMembership();

            assertThat(handler.apply(employee, RECONCILE)).containsExactlyInAnyOrder("lc-a", "lc-b");
        }

        @Test
        @DisplayName("returns the removed roles only — a final-state reading would be empty here")
        void removedOnly() {
            // The T35 counter-example: a Terminated employee requires nothing, so the required set is
            // empty and only the removal is left to record.
            stubRoles(NO_ROLES, NO_ROLES, Set.of("lc-a", "lc-b"));
            stubMembership();

            assertThat(handler.apply(employee, RECONCILE)).containsExactlyInAnyOrder("lc-a", "lc-b");
        }

        @Test
        @DisplayName("returns the union when the run both granted and removed")
        void bothGrantedAndRemoved() {
            stubRoles(Set.of("lc-a"), Set.of("lc-a"), Set.of("lc-b"));
            stubMembership();

            assertThat(handler.apply(employee, RECONCILE)).containsExactlyInAnyOrder("lc-a", "lc-b");
        }

        @Test
        @DisplayName("returns the empty set when the run touched nothing")
        void neither() {
            stubRoles(Set.of("lc-a"), NO_ROLES, NO_ROLES);
            stubMembership();

            assertThat(handler.apply(employee, RECONCILE)).isEmpty();
        }

        @Test
        @DisplayName("returns an unmodifiable set, so a caller cannot rewrite the snapshot it records")
        void unmodifiable() {
            stubRoles(Set.of("lc-a"), Set.of("lc-a"), NO_ROLES);
            stubMembership();

            var touched = handler.apply(employee, RECONCILE);

            assertThatThrownBy(() -> touched.add("lc-admin")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("DEACTIVATE fails closed (records G19/T59)")
    class Deactivate {

        @Test
        @DisplayName("refuses by name before calling anything: no write happens on the way to the throw")
        void refusesBeforeAnyWrite() {
            assertThatThrownBy(() -> handler.apply(employee, DEACTIVATE))
                    .isInstanceOf(AccountDeactivationNotSupportedException.class);

            verifyNoInteractions(accountService, roleService, membershipService);
        }

        @Test
        @DisplayName("the refusal names the missing capability within the last_error bound")
        void refusalMessageNamesTheMissingCapabilityAndFitsTheColumn() {
            var thrown = catchThrowable(() -> handler.apply(employee, DEACTIVATE));

            assertThat(thrown).isInstanceOf(AccountDeactivationNotSupportedException.class);
            assertThat(thrown.getMessage())
                    .contains("DEACTIVATE")
                    .contains("disable an account")
                    .contains("G19")
                    .hasSizeLessThanOrEqualTo(500);
        }
    }

    @Nested
    @DisplayName("nothing is swallowed (records T10/T13)")
    class Propagation {

        @Test
        @DisplayName("the ACTIVATE refusal of record T33 propagates unchanged and nothing later runs")
        void activateRefusalPropagates() {
            var refusal = new InvitationNotDeliverableException("Invitation not deliverable: no email domain");
            when(accountService.activate(employee)).thenThrow(refusal);

            assertThatThrownBy(() -> handler.apply(employee, ACTIVATE)).isSameAs(refusal);

            verifyNoInteractions(roleService, membershipService);
        }

        @Test
        @DisplayName("a role failure propagates unchanged and the membership projection is never called")
        void roleFailureStopsTheChain() {
            var failure = new AccountNotLinkedException("Account not linked: employee has no keycloakUserId");
            stubActivate();
            when(roleService.convergeRoles(employee)).thenThrow(failure);

            assertThatThrownBy(() -> handler.apply(employee, ACTIVATE)).isSameAs(failure);

            verifyNoInteractions(membershipService);
        }

        @Test
        @DisplayName("a membership failure propagates unchanged instead of being absorbed")
        void membershipFailurePropagates() {
            var failure = new CompanyScopeInvariantException("membership projection refused: exactly one company id");
            stubRoles(Set.of("lc-a"), Set.of("lc-a"), NO_ROLES);
            when(membershipService.convergeMembership(employee)).thenThrow(failure);

            assertThatThrownBy(() -> handler.apply(employee, RECONCILE)).isSameAs(failure);
        }
    }
}
