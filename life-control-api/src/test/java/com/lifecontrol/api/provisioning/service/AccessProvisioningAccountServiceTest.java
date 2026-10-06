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
import static org.mockito.Mockito.when;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.config.invitation.InvitationProperties;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.AccountLinkRefusedException;
import com.lifecontrol.api.provisioning.exception.InvitationNotDeliverableException;
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
        @DisplayName("should still send the invitation when the account was already linked")
        void alreadyLinkedStillInvites() {
            employee.linkKeycloakUser("kc-existing");

            var result = service.activate(employee);

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED);
            verify(identityProvider)
                    .sendActionsEmail("kc-existing", CLIENT_ID, REDIRECT_URI, LIFESPAN_SECONDS, REQUIRED_ACTIONS);
        }

        @Test
        @DisplayName("should refuse before any Keycloak call when the company has no email domain")
        void refusesWhenNoEmailDomain() {
            var noDomain = employeeWithDomain(null);

            assertThatThrownBy(() -> service.activate(noDomain))
                    .isInstanceOf(InvitationNotDeliverableException.class)
                    .hasMessageContaining("email domain")
                    .hasMessageContaining("no email domain configured");

            verifyNoInteractions(identityProvider);
            verify(employeeRepository, never()).save(any(Employee.class));
        }

        @Test
        @DisplayName("should refuse before any Keycloak call when the email domain is blank")
        void refusesWhenBlankEmailDomain() {
            var blankDomain = employeeWithDomain("   ");

            assertThatThrownBy(() -> service.activate(blankDomain))
                    .isInstanceOf(InvitationNotDeliverableException.class)
                    .hasMessageContaining("no email domain configured");

            verifyNoInteractions(identityProvider);
            verify(employeeRepository, never()).save(any(Employee.class));
        }
    }
}
