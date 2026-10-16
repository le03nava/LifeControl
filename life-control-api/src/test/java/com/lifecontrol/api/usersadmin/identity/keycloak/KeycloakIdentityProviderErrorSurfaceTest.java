package com.lifecontrol.api.usersadmin.identity.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderNotFoundException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.RedirectionException;
import jakarta.ws.rs.ServerErrorException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.GroupsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The translation's coverage <b>across the surface</b>, as opposed to the five outcome tests
 * {@code KeycloakIdentityProviderTest} carries next to the method they exercise. These pin the three
 * shapes those outcome tests never reach — a provider 3xx, a raw {@link ClientErrorException} whose
 * status is 404 without being a {@link NotFoundException}, and the one method that deliberately does
 * <b>not</b> take the 404 outcome — which is what lets
 * {@code odd/tasks/keycloak-adapter-error-contract.md} claim the mapping is <b>uniform</b> rather than
 * merely present wherever a test happened to look.
 *
 * <p>The discriminator pinned here is the <b>type</b>, not the status: a plain
 * {@code ClientErrorException} carrying 404 is retryable, because only the JAX-RS
 * {@code NotFoundException} is a missing resource. That correspondence between status and type belongs
 * to the dependency and is the record's declared limit <b>X1</b>, not something this suite proves.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeycloakIdentityProvider error surface")
class KeycloakIdentityProviderErrorSurfaceTest {

    private static final String REALM = "life-control-realm";
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private Keycloak keycloak;

    @Mock
    private RealmResource realm;

    @Mock
    private RolesResource roles;

    @Mock
    private RoleResource role;

    @Mock
    private GroupsResource groups;

    @Mock
    private UsersResource users;

    @Mock
    private UserResource user;

    private KeycloakIdentityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new KeycloakIdentityProvider(
                keycloak, new KeycloakAdminProperties("http://localhost:8080", REALM, "client", "secret"));
        lenient().when(keycloak.realm(REALM)).thenReturn(realm);
    }

    @Nested
    @DisplayName("the 4xx family")
    class ClientErrorTests {

        @Test
        @DisplayName("a 403 is retryable and carries the operation's message")
        void forbidden_becomesRetryable() {
            when(realm.roles()).thenReturn(roles);
            when(roles.get("lc-sales")).thenReturn(role);
            when(role.toRepresentation()).thenThrow(new ClientErrorException("Forbidden", 403));

            assertThatThrownBy(() -> provider.getRealmRole("lc-sales"))
                    .isInstanceOf(IdentityProviderConnectionException.class)
                    .hasCauseInstanceOf(ClientErrorException.class)
                    .hasMessage("Failed to get realm role: lc-sales");
        }

        @Test
        @DisplayName("a 404 is the domain not-found, carrying the operation's message")
        void notFound_becomesDomainNotFound() {
            when(realm.roles()).thenReturn(roles);
            when(roles.get("lc-sales")).thenReturn(role);
            when(role.toRepresentation()).thenThrow(new NotFoundException("no such role"));

            assertThatThrownBy(() -> provider.getRealmRole("lc-sales"))
                    .isInstanceOf(IdentityProviderNotFoundException.class)
                    .hasCauseInstanceOf(NotFoundException.class)
                    .hasMessage("Failed to get realm role: lc-sales");
        }

        @Test
        @DisplayName("a 409 keeps the site's own conflict message")
        void conflict_keepsTheConflictMessage() {
            when(realm.roles()).thenReturn(roles);
            doThrow(new ClientErrorException("Conflict", 409)).when(roles).deleteRole("lc-sales");

            assertThatThrownBy(() -> provider.deleteRealmRole("lc-sales"))
                    .isInstanceOf(IdentityProviderConflictException.class)
                    .hasMessage("Cannot delete realm role: lc-sales (has user assignments)");
        }

        @Test
        @DisplayName("a 404 that is not a NotFoundException is retryable, because the type decides")
        void rawClientErrorWith404_isRetryable() {
            when(realm.users()).thenReturn(users);
            when(users.get(USER_ID)).thenReturn(user);
            when(user.toRepresentation()).thenThrow(new ClientErrorException("not found", 404));

            assertThatThrownBy(() -> provider.findUserDisplayName(USER_ID))
                    .isInstanceOf(IdentityProviderConnectionException.class)
                    .hasMessage("Failed to find user by id: " + USER_ID);
        }

        @Test
        @DisplayName("a 404 from a method that never had the outcome still becomes the domain not-found")
        void createGroup_notFound_becomesDomainNotFound() {
            when(realm.groups()).thenReturn(groups);
            when(groups.add(any(GroupRepresentation.class))).thenThrow(new NotFoundException("no such realm"));

            assertThatThrownBy(() -> provider.createGroup("planta-norte", Map.of("k", List.of("v")), Optional.empty()))
                    .isInstanceOf(IdentityProviderNotFoundException.class)
                    .hasMessage("Failed to create group: planta-norte");
        }
    }

    @Nested
    @DisplayName("the 5xx and 3xx family")
    class ServerAndRedirectTests {

        @Test
        @DisplayName("a 500 from a method that caught nothing before is now retryable")
        void serverError_becomesRetryable() {
            when(realm.roles()).thenReturn(roles);
            when(roles.list()).thenThrow(new ServerErrorException("boom", 500));

            assertThatThrownBy(() -> provider.listRealmRoles())
                    .isInstanceOf(IdentityProviderConnectionException.class)
                    .hasCauseInstanceOf(ServerErrorException.class)
                    .hasMessage("Failed to list realm roles");
        }

        @Test
        @DisplayName("a redirect is retryable rather than an escape to the catch-all")
        void redirection_becomesRetryable() {
            when(realm.roles()).thenReturn(roles);
            when(roles.get("lc-sales")).thenReturn(role);
            when(role.toRepresentation())
                    .thenThrow(new RedirectionException(302, URI.create("http://idp.example.com/login")));

            assertThatThrownBy(() -> provider.getRealmRole("lc-sales"))
                    .isInstanceOf(IdentityProviderConnectionException.class)
                    .hasCauseInstanceOf(RedirectionException.class)
                    .hasMessage("Failed to get realm role: lc-sales");
        }
    }

    @Nested
    @DisplayName("findUserDisplayName's 404 carve-out")
    class DisplayNameTests {

        @Test
        @DisplayName("a provider 404 stays an empty answer, which is why the method keeps its own catch")
        void notFound_isAnEmptyAnswer() {
            when(realm.users()).thenReturn(users);
            when(users.get(USER_ID)).thenReturn(user);
            when(user.toRepresentation()).thenThrow(new NotFoundException("no such user"));

            assertThat(provider.findUserDisplayName(USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("every other failure still reaches the translation")
        void otherFailures_stillReachTheTranslation() {
            when(realm.users()).thenReturn(users);
            when(users.get(USER_ID)).thenReturn(user);
            when(user.toRepresentation()).thenThrow(new ClientErrorException("Forbidden", 403));

            assertThatThrownBy(() -> provider.findUserDisplayName(USER_ID))
                    .isInstanceOf(IdentityProviderConnectionException.class)
                    .hasMessage("Failed to find user by id: " + USER_ID);
        }
    }
}
