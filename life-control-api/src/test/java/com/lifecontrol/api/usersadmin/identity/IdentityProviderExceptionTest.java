package com.lifecontrol.api.usersadmin.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IdentityProviderException sealed hierarchy")
class IdentityProviderExceptionTest {

    @Nested
    @DisplayName("IdentityProviderNotFoundException")
    class NotFoundExceptionTests {

        @Test
        @DisplayName("should be constructable with message")
        void shouldBeConstructableWithMessage() {
            var ex = new IdentityProviderNotFoundException("Role 'admin' not found");

            assertThat(ex).isInstanceOf(IdentityProviderException.class);
            assertThat(ex.getMessage()).isEqualTo("Role 'admin' not found");
        }

        @Test
        @DisplayName("should be constructable with message and cause")
        void shouldBeConstructableWithMessageAndCause() {
            var cause = new RuntimeException("underlying error");
            var ex = new IdentityProviderNotFoundException("User 'abc' not found", cause);

            assertThat(ex.getMessage()).isEqualTo("User 'abc' not found");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("IdentityProviderConflictException")
    class ConflictExceptionTests {

        @Test
        @DisplayName("should be constructable with message")
        void shouldBeConstructableWithMessage() {
            var ex = new IdentityProviderConflictException("Role 'admin' already exists");

            assertThat(ex).isInstanceOf(IdentityProviderException.class);
            assertThat(ex.getMessage()).isEqualTo("Role 'admin' already exists");
        }

        @Test
        @DisplayName("should be constructable with message and cause")
        void shouldBeConstructableWithMessageAndCause() {
            var cause = new RuntimeException("HTTP 409");
            var ex = new IdentityProviderConflictException("Role conflict", cause);

            assertThat(ex.getMessage()).isEqualTo("Role conflict");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("IdentityProviderConnectionException")
    class ConnectionExceptionTests {

        @Test
        @DisplayName("should be constructable with message")
        void shouldBeConstructableWithMessage() {
            var ex = new IdentityProviderConnectionException("Connection refused");

            assertThat(ex).isInstanceOf(IdentityProviderException.class);
            assertThat(ex.getMessage()).isEqualTo("Connection refused");
        }

        @Test
        @DisplayName("should be constructable with message and cause")
        void shouldBeConstructableWithMessageAndCause() {
            var cause = new RuntimeException("Socket timeout");
            var ex = new IdentityProviderConnectionException("Connection failed", cause);

            assertThat(ex.getMessage()).isEqualTo("Connection failed");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("sealed hierarchy constraints")
    class SealedHierarchyTests {

        @Test
        @DisplayName("IdentityProviderException should be abstract or sealed")
        void shouldBeAbstractOrSealed() {
            assertThat(IdentityProviderException.class.isSealed()).isTrue();
        }

        @Test
        @DisplayName("should permit only the three subclasses")
        void shouldPermitCorrectSubclasses() {
            var permitted = IdentityProviderException.class.getPermittedSubclasses();

            assertThat(permitted).isNotNull();
            assertThat(permitted).extracting(Class::getSimpleName)
                    .containsExactlyInAnyOrder(
                            "IdentityProviderNotFoundException",
                            "IdentityProviderConflictException",
                            "IdentityProviderConnectionException");
        }

        @Test
        @DisplayName("subclasses should be final")
        void subclassesShouldBeFinal() {
            assertThat(java.lang.reflect.Modifier.isFinal(
                    IdentityProviderNotFoundException.class.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isFinal(
                    IdentityProviderConflictException.class.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isFinal(
                    IdentityProviderConnectionException.class.getModifiers())).isTrue();
        }
    }
}
