package com.lifecontrol.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two list components of {@link GatewayProperties} are copied and made unmodifiable at construction, so no
 * consumer — the JWT issuer validator, the CORS configuration — can rewrite the allowlist the gateway validates
 * against, and the binder cannot change it after the fact by mutating the list it kept.
 */
class GatewayPropertiesTests {

    private static final String API_URI = "http://lifecontrol-api:8080";
    private static final String KEYCLOAK_URI = "http://keycloak:8080";

    @Test
    void allowedIssuersCannotBeModifiedThroughTheAccessor() {
        GatewayProperties props = new GatewayProperties(
                API_URI,
                KEYCLOAK_URI,
                new ArrayList<>(List.of("http://issuer-a")),
                new ArrayList<>(List.of("life-control-api")),
                new ArrayList<>(List.of("http://origin-a")));

        assertThat(props.allowedIssuers()).containsExactly("http://issuer-a");
        assertThatThrownBy(() -> props.allowedIssuers().add("http://issuer-b"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(props.allowedIssuers()).containsExactly("http://issuer-a");
    }

    @Test
    void allowedOriginsCannotBeModifiedThroughTheAccessor() {
        GatewayProperties props = new GatewayProperties(
                API_URI,
                KEYCLOAK_URI,
                new ArrayList<>(List.of("http://issuer-a")),
                new ArrayList<>(List.of("life-control-api")),
                new ArrayList<>(List.of("http://origin-a")));

        assertThat(props.allowedOrigins()).containsExactly("http://origin-a");
        assertThatThrownBy(() -> props.allowedOrigins().add("http://origin-b"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(props.allowedOrigins()).containsExactly("http://origin-a");
    }

    @Test
    void mutatingTheListHandedToTheConstructorDoesNotChangeTheProperties() {
        List<String> issuers = new ArrayList<>(List.of("http://issuer-a"));
        List<String> origins = new ArrayList<>(List.of("http://origin-a"));
        GatewayProperties props = new GatewayProperties(
                API_URI, KEYCLOAK_URI, issuers, new ArrayList<>(List.of("life-control-api")), origins);

        issuers.add("http://issuer-b");
        origins.add("http://origin-b");

        assertThat(props.allowedIssuers()).containsExactly("http://issuer-a");
        assertThat(props.allowedOrigins()).containsExactly("http://origin-a");
    }

    /**
     * An absent list stays null instead of failing in this constructor, so the property is reported by name by the
     * {@code @NotEmpty} constraint the record declares rather than by a bare NullPointerException.
     */
    @Test
    void anAbsentListStaysNullSoThatNotEmptyCanReportIt() {
        GatewayProperties props = new GatewayProperties(API_URI, KEYCLOAK_URI, null, null, null);

        assertThat(props.allowedIssuers()).isNull();
        assertThat(props.allowedAudiences()).isNull();
        assertThat(props.allowedOrigins()).isNull();
    }
}
