package com.lifecontrol.api.company.listener;

import com.lifecontrol.api.company.event.CompanyCountryCreatedEvent;
import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("KeycloakGroupEventListener Tests")
class KeycloakGroupEventListenerTest {

    @Mock
    private IdentityProvider identityProvider;

    private KeycloakGroupEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new KeycloakGroupEventListener(identityProvider);
    }

    @Nested
    @DisplayName("onCompanyCreated")
    class OnCompanyCreatedTests {

        private final UUID companyUuid = UUID.randomUUID();
        private final String companyKey = "KEY-001";
        private final String companyName = "Acme Corp";

        @Test
        @DisplayName("should create company group with sanitized name and UUID")
        void shouldCreateCompanyGroup() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, companyName);

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "company-acme_corp",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "life-control-company",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should sanitize special characters in company name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, "Acme-Corp S.A. de C.V.");

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "company-acme-corp_s_a__de_c_v_",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "life-control-company",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should lowercase company name")
        void shouldLowercaseCompanyName() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, "ACME CORPORATION");

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "company-acme_corporation",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "life-control-company",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, "my-company_test");

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "company-my-company_test",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "life-control-company",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, companyName);
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroupWithRole(
                            "company-acme_corp",
                            Map.of("company_id", List.of(companyUuid.toString())),
                            "life-control-company",
                            "life-control-client");

            // Should not throw
            listener.onCompanyCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "company-acme_corp",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "life-control-company",
                    "life-control-client"
            );
        }
    }

    @Nested
    @DisplayName("onCompanyCountryCreated")
    class OnCompanyCountryCreatedTests {

        private final UUID companyCountryUuid = UUID.randomUUID();
        private final UUID companyUuid = UUID.randomUUID();

        @Test
        @DisplayName("should create company-country group with sanitized name and attributes")
        void shouldCreateCompanyCountryGroup() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid, "Argentina");

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-country-argentina",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    "lc-company-country",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should sanitize special characters in country name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid, "São Paulo");

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-country-s_o_paulo",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    "lc-company-country",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in country name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid, "Costa-Rica_test");

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-country-costa-rica_test",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    "lc-company-country",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should lowercase country name")
        void shouldLowercaseCountryName() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid, "UNITED KINGDOM");

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-country-united_kingdom",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    "lc-company-country",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid, "Argentina");
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroupWithRole(
                            "lc-company-country-argentina",
                            Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                            "lc-company-country",
                            "life-control-client");

            // Should not throw
            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-country-argentina",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    "lc-company-country",
                    "life-control-client"
            );
        }
    }
}
