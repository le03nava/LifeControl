package com.lifecontrol.api.usersadmin.identity.keycloak;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KeycloakAdminConfig")
class KeycloakAdminConfigTest {

    @Nested
    @DisplayName("KeycloakAdminProperties record")
    class PropertiesTests {

        @Test
        @DisplayName("should bind all keycloak.admin properties")
        void shouldBindAllProperties() {
            var properties = new KeycloakAdminProperties(
                    "http://keycloak:8080",
                    "life-control-realm",
                    "life-control-admin-client",
                    "secret-value"
            );

            assertThat(properties.serverUrl()).isEqualTo("http://keycloak:8080");
            assertThat(properties.realm()).isEqualTo("life-control-realm");
            assertThat(properties.clientId()).isEqualTo("life-control-admin-client");
            assertThat(properties.clientSecret()).isEqualTo("secret-value");
        }

        @Test
        @DisplayName("should support construction with null optional values")
        void shouldSupportNullValues() {
            var properties = new KeycloakAdminProperties(
                    "http://localhost:8080",
                    "my-realm",
                    "my-client",
                    null
            );

            assertThat(properties.serverUrl()).isEqualTo("http://localhost:8080");
            assertThat(properties.clientSecret()).isNull();
        }

        @Test
        @DisplayName("should be equal when all fields match")
        void shouldBeEqualWhenFieldsMatch() {
            var p1 = new KeycloakAdminProperties("url", "realm", "client", "secret");
            var p2 = new KeycloakAdminProperties("url", "realm", "client", "secret");

            assertThat(p1).isEqualTo(p2);
            assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
        }
    }

    @Nested
    @DisplayName("KeycloakAdminConfig bean method")
    class ConfigTests {

        @Test
        @DisplayName("should accept KeycloakAdminProperties via constructor")
        void shouldAcceptPropertiesViaConstructor() {
            var properties = new KeycloakAdminProperties("url", "realm", "client", "secret");
            var config = new KeycloakAdminConfig(properties);

            assertThat(config).isNotNull();
        }

        @Test
        @DisplayName("should expose keycloakAdminClient bean method")
        void shouldExposeKeycloakAdminClientMethod() throws Exception {
            var method = KeycloakAdminConfig.class.getMethod("keycloakAdminClient");

            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(org.keycloak.admin.client.Keycloak.class);
        }
    }
}
