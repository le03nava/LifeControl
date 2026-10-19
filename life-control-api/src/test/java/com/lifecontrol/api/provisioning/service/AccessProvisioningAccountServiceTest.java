package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccountProvisioningOutcome.ALREADY_LINKED;
import static com.lifecontrol.api.provisioning.model.AccountProvisioningOutcome.CREATED;
import static com.lifecontrol.api.provisioning.model.AccountProvisioningOutcome.LINKED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.config.invitation.InvitationProperties;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.AccountLinkRefusedException;
import com.lifecontrol.api.provisioning.exception.AccountNotLinkedException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException;
import com.lifecontrol.api.usersadmin.identity.UserSearchDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Focused unit tests for {@link AccessProvisioningAccountService}: the create-or-link account
 * lifecycle (records T5/T27/T31), the credential-free account, the invitation (T14/T30/T21) and the
 * named refusal of T33.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessProvisioningAccountService Tests")
class AccessProvisioningAccountServiceTest {

    private static final String EMAIL = "jane.doe@acme.com";
    private static final String CLIENT_ID = "life-control-client";
    private static final String REDIRECT_URI = "https://app.lifecontrol.example/activate";
    private static final int LIFESPAN_SECONDS = 43200;
    private static final List<String> REQUIRED_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD");

    @Mock
    private IdentityProvider identityProvider;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private ApplicationClientProperties applicationClientProperties;

    @Mock
    private InvitationProperties invitationProperties;

    @InjectMocks
    private AccessProvisioningAccountService service;

    private UUID employeeId;
    private Employee employee;

    @BeforeEach
    void setUp() {
        employeeId = UUID.randomUUID();
        employee = employeeWithDomain("acme.com");
        lenient().when(applicationClientProperties.clientId()).thenReturn(CLIENT_ID);
        lenient().when(invitationProperties.redirectUri()).thenReturn(REDIRECT_URI);
        lenient().when(invitationProperties.lifespanSeconds()).thenReturn(LIFESPAN_SECONDS);
    }

    private Employee employeeWithDomain(String emailDomain) {
        var company = Company.builder()
                .id(UUID.randomUUID())
                .companyKey("acme")
                .companyName("Acme")
                .rfc("ACM010101ABC")
                .emailDomain(emailDomain)
                .build();
        return Employee.builder().id(employeeId).company(company).email(EMAIL).build();
    }

    private void stubSave() {
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserRepresentation captureCreatedUser() {
        var captor = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(identityProvider).createUser(captor.capture());
        return captor.getValue();
    }

    private UserSearchDto account(String id, String email) {
        return new UserSearchDto(id, email, email, Boolean.TRUE);
    }

    private void stubConflict() {
        when(identityProvider.createUser(any(UserRepresentation.class)))
                .thenThrow(new IdentityProviderConflictException("User already exists: " + EMAIL));
    }

    @Nested
    @DisplayName("ensureAccount")
    class EnsureAccountTests {

        @Test
        @DisplayName("should create a credential-free account, persist the returned id and report CREATED")
        void createsCredentialFreeAccount() {
            stubSave();
            when(identityProvider.createUser(any(UserRepresentation.class))).thenReturn("kc-new");

            var result = service.ensureAccount(employee);

            assertThat(result.keycloakUserId()).isEqualTo("kc-new");
            assertThat(result.outcome()).isEqualTo(CREATED);
            assertThat(employee.getKeycloakUserId()).isEqualTo("kc-new");
            verify(employeeRepository).save(employee);
            verify(identityProvider, never()).findUsersByEmail(any());

            var created = captureCreatedUser();
            assertThat(created.getUsername()).isEqualTo(EMAIL);
            assertThat(created.getEmail()).isEqualTo(EMAIL);
            assertThat(created.isEnabled()).isTrue();
            assertThat(created.isEmailVerified()).isFalse();
            assertThat(created.getRequiredActions()).containsExactlyElementsOf(REQUIRED_ACTIONS);
            assertThat(created.getCredentials()).isNullOrEmpty();
        }

        @Test
        @DisplayName("should return ALREADY_LINKED without touching the identity provider or saving")
        void alreadyLinkedIsANoOp() {
            employee.linkKeycloakUser("kc-existing");

            var result = service.ensureAccount(employee);

            assertThat(result.keycloakUserId()).isEqualTo("kc-existing");
            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED);
            verifyNoInteractions(identityProvider);
            verify(employeeRepository, never()).save(any(Employee.class));
        }

        @Test
        @DisplayName("should link the only exact match on a 409, without a second create attempt")
        void conflictWithExactlyOneExactMatchLinks() {
            stubSave();
            stubConflict();
            when(identityProvider.findUsersByEmail(EMAIL)).thenReturn(List.of(account("kc-existing", EMAIL)));

            var result = service.ensureAccount(employee);

            assertThat(result.keycloakUserId()).isEqualTo("kc-existing");
            assertThat(result.outcome()).isEqualTo(LINKED);
            assertThat(employee.getKeycloakUserId()).isEqualTo("kc-existing");
            verify(employeeRepository).save(employee);
            verify(identityProvider, times(1)).createUser(any(UserRepresentation.class));
        }

        @Test
        @DisplayName("should link when the only match differs only in email case")
        void conflictWithCaseDifferingExactMatchLinks() {
            stubSave();
            stubConflict();
            when(identityProvider.findUsersByEmail(EMAIL))
                    .thenReturn(List.of(account("kc-existing", "Jane.Doe@ACME.com")));

            var result = service.ensureAccount(employee);

            assertThat(result.keycloakUserId()).isEqualTo("kc-existing");
            assertThat(result.outcome()).isEqualTo(LINKED);
            assertThat(employee.getKeycloakUserId()).isEqualTo("kc-existing");
        }

        @Test
        @DisplayName("should link the single exact match when another returned account has a different email")
        void conflictIgnoresNonMatchingAccounts() {
            stubSave();
            stubConflict();
            when(identityProvider.findUsersByEmail(EMAIL))
                    .thenReturn(List.of(account("kc-other", "someone@else.com"), account("kc-existing", EMAIL)));

            var result = service.ensureAccount(employee);

            assertThat(result.keycloakUserId()).isEqualTo("kc-existing");
            assertThat(result.outcome()).isEqualTo(LINKED);
        }

        @Test
        @DisplayName("should refuse on a 409 with no exact match, without persisting anything")
        void conflictWithNoMatchRefuses() {
            stubConflict();
            when(identityProvider.findUsersByEmail(EMAIL)).thenReturn(List.of(account("kc-other", "someone@else.com")));

            assertThatThrownBy(() -> service.ensureAccount(employee))
                    .isInstanceOf(AccountLinkRefusedException.class)
                    .hasMessageContaining(EMAIL);

            assertThat(employee.getKeycloakUserId()).isNull();
            verify(employeeRepository, never()).save(any(Employee.class));
            verify(identityProvider, times(1)).createUser(any(UserRepresentation.class));
        }

        @Test
        @DisplayName("should refuse on a 409 with two exact matches and use neither id")
        void conflictWithTwoExactMatchesRefuses() {
            stubConflict();
            when(identityProvider.findUsersByEmail(EMAIL))
                    .thenReturn(List.of(account("kc-a", EMAIL), account("kc-b", EMAIL)));

            assertThatThrownBy(() -> service.ensureAccount(employee))
                    .isInstanceOf(AccountLinkRefusedException.class)
                    .hasMessageContaining(EMAIL);

            assertThat(employee.getKeycloakUserId()).isNull();
            verify(employeeRepository, never()).save(any(Employee.class));
            verify(identityProvider, times(1)).createUser(any(UserRepresentation.class));
        }
    }

    @Nested
    @DisplayName("activate")
    class ActivateTests {

        @Test
        @DisplayName("should create the account and send the invitation with the bound values")
        void createsAndSendsInvitation() {
            stubSave();
            when(identityProvider.createUser(any(UserRepresentation.class))).thenReturn("kc-new");

            var result = service.activate(employee);

            assertThat(result.keycloakUserId()).isEqualTo("kc-new");
            assertThat(result.outcome()).isEqualTo(CREATED);
            verify(identityProvider)
                    .sendActionsEmail("kc-new", CLIENT_ID, REDIRECT_URI, LIFESPAN_SECONDS, REQUIRED_ACTIONS);
            var created = captureCreatedUser();
            assertThat(created.getCredentials()).isNullOrEmpty();
        }

        @Test
        @DisplayName("should send exactly one invitation across a retried activation")
        void sendsOneInvitationAcrossARetry() {
            stubSave();
            when(identityProvider.createUser(any(UserRepresentation.class))).thenReturn("kc-new");

            // The first attempt is the one that linked the account and sent the mail; the second is
            // the retry a FAILED task gets, and the account half answers ALREADY_LINKED.
            service.activate(employee);
            service.activate(employee);

            verify(identityProvider, times(1))
                    .sendActionsEmail("kc-new", CLIENT_ID, REDIRECT_URI, LIFESPAN_SECONDS, REQUIRED_ACTIONS);
        }

        @Test
        @DisplayName("should not send an invitation when the account was already linked before the run")
        void alreadyLinkedDoesNotInvite() {
            employee.linkKeycloakUser("kc-existing");

            var result = service.activate(employee);

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED);
            verify(identityProvider, never()).sendActionsEmail(any(), any(), any(), any(Integer.class), any());
        }

        @Test
        @DisplayName("should invite the account a run just linked from an existing address")
        void linkedAccountIsInvited() {
            stubSave();
            stubConflict();
            when(identityProvider.findUsersByEmail(EMAIL)).thenReturn(List.of(account("kc-existing", EMAIL)));

            var result = service.activate(employee);

            assertThat(result.outcome()).isEqualTo(LINKED);
            verify(identityProvider)
                    .sendActionsEmail("kc-existing", CLIENT_ID, REDIRECT_URI, LIFESPAN_SECONDS, REQUIRED_ACTIONS);
        }

        @Test
        @DisplayName("should still invite a company with no email domain, to the address the account already has")
        void invitesWithoutADeclaredCompanyDomain() {
            stubSave();
            when(identityProvider.createUser(any(UserRepresentation.class))).thenReturn("kc-new");

            var result = service.activate(employeeWithDomain(null));

            assertThat(result.outcome()).isEqualTo(CREATED);
            verify(identityProvider)
                    .sendActionsEmail("kc-new", CLIENT_ID, REDIRECT_URI, LIFESPAN_SECONDS, REQUIRED_ACTIONS);
            var created = captureCreatedUser();
            assertThat(created.getEmail()).isEqualTo(EMAIL);
            assertThat(created.getCredentials()).isNullOrEmpty();
        }

        @Test
        @DisplayName("should still invite a company whose email domain is blank")
        void invitesWhenTheCompanyDomainIsBlank() {
            stubSave();
            when(identityProvider.createUser(any(UserRepresentation.class))).thenReturn("kc-new");

            var result = service.activate(employeeWithDomain("   "));

            assertThat(result.outcome()).isEqualTo(CREATED);
            verify(identityProvider)
                    .sendActionsEmail("kc-new", CLIENT_ID, REDIRECT_URI, LIFESPAN_SECONDS, REQUIRED_ACTIONS);
            assertThat(captureCreatedUser().getEmail()).isEqualTo(EMAIL);
        }
    }

    @Nested
    @DisplayName("deactivate")
    class DeactivateTests {

        @Test
        @DisplayName("should disable the linked account with the port's narrow capability and call nothing else")
        void disablesTheLinkedAccount() {
            employee.linkKeycloakUser("kc-existing");

            service.deactivate(employee);

            verify(identityProvider).disableUser("kc-existing");
            // T77/T6: not the whole-representation update and not deleteUser (the sub stays stable).
            verify(identityProvider, never()).updateUser(any(), any());
            verify(identityProvider, never()).deleteUser(any());
            verifyNoMoreInteractions(identityProvider);
        }

        @Test
        @DisplayName("should refuse by name before any identity-provider call when no account is linked")
        void refusesWhenNoAccountIsLinked() {
            assertThatThrownBy(() -> service.deactivate(employee))
                    .isInstanceOf(AccountNotLinkedException.class)
                    .hasMessageContaining("no keycloakUserId");

            verifyNoInteractions(identityProvider);
        }
    }

    @Nested
    @DisplayName("ensureAccountEnabled")
    class EnsureAccountEnabledTests {

        @Test
        @DisplayName("should enable the linked account with the port's narrow capability and call nothing else")
        void enablesTheLinkedAccount() {
            employee.linkKeycloakUser("kc-existing");

            service.ensureAccountEnabled(employee);

            verify(identityProvider).enableUser("kc-existing");
            // T99/T78: the disable direction belongs to the DEACTIVATE arm, and this is never the
            // whole-representation write nor a delete.
            verify(identityProvider, never()).disableUser(any());
            verify(identityProvider, never()).updateUser(any(), any());
            verify(identityProvider, never()).deleteUser(any());
            verifyNoMoreInteractions(identityProvider);
        }

        @Test
        @DisplayName("should do nothing for a revoked employee, whose account state the DEACTIVATE arm owns")
        void doesNothingForATerminatedEmployee() {
            var revoked = employeeWithDomain("acme.com");
            revoked.linkKeycloakUser("kc-existing");
            revoked.setStatus(
                    Status.builder().statusName("Terminated").enabled(true).build());

            service.ensureAccountEnabled(revoked);

            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("should refuse by name before any identity-provider call when no account is linked")
        void refusesWhenNoAccountIsLinked() {
            assertThatThrownBy(() -> service.ensureAccountEnabled(employee))
                    .isInstanceOf(AccountNotLinkedException.class)
                    .hasMessageContaining("no keycloakUserId");

            verifyNoInteractions(identityProvider);
        }
    }

    @Nested
    @DisplayName("resendInvitation")
    class ResendInvitationTests {

        @Test
        @DisplayName("sends the invitation again with the frozen actions, client, redirect and lifespan")
        void resendsWithTheFrozenParameters() {
            employee.linkKeycloakUser("kc-existing");

            service.resendInvitation(employee);

            verify(identityProvider)
                    .sendActionsEmail("kc-existing", CLIENT_ID, REDIRECT_URI, LIFESPAN_SECONDS, REQUIRED_ACTIONS);
        }

        @Test
        @DisplayName("refuses by name before any identity-provider call when no account is linked")
        void refusesWhenNoAccountIsLinked() {
            assertThatThrownBy(() -> service.resendInvitation(employee))
                    .isInstanceOf(AccountNotLinkedException.class)
                    .hasMessageContaining("no keycloakUserId");

            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("sends the invitation for a company with no usable email domain too")
        void sendsWhenTheDomainIsMissing() {
            employee = employeeWithDomain(null);
            employee.linkKeycloakUser("kc-existing");

            service.resendInvitation(employee);

            verify(identityProvider)
                    .sendActionsEmail("kc-existing", CLIENT_ID, REDIRECT_URI, LIFESPAN_SECONDS, REQUIRED_ACTIONS);
        }

        @Test
        @DisplayName("sends the invitation for a blank company domain too")
        void sendsWhenTheCompanyDomainIsBlank() {
            employee = employeeWithDomain("   ");
            employee.linkKeycloakUser("kc-existing");

            service.resendInvitation(employee);

            verify(identityProvider)
                    .sendActionsEmail("kc-existing", CLIENT_ID, REDIRECT_URI, LIFESPAN_SECONDS, REQUIRED_ACTIONS);
        }

        @Test
        @DisplayName("refuses by name when no account is linked, whether or not the company declares a domain")
        void theLinkedRefusalIsTheOnlyOneLeft() {
            employee = employeeWithDomain(null);

            assertThatThrownBy(() -> service.resendInvitation(employee))
                    .isInstanceOf(AccountNotLinkedException.class)
                    .hasMessageContaining("no keycloakUserId");

            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("writes no row and creates nothing: it only re-sends")
        void resendingTouchesNoRow() {
            employee.linkKeycloakUser("kc-existing");

            service.resendInvitation(employee);

            verify(employeeRepository, never()).save(any(Employee.class));
            verify(identityProvider, never()).createUser(any(UserRepresentation.class));
            verify(identityProvider, never()).updateUser(any(), any());
        }
    }
}
