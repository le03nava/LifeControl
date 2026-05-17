package com.lifecontrol.api.company.listener;

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

import java.util.UUID;

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
        private final int companyId = 42;
        private final String companyName = "Acme Corp";

        @Test
        @DisplayName("should create company group with sanitized name and UUID")
        void shouldCreateCompanyGroup() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyId, companyName);

            listener.onCompanyCreated(event);

            verify(identityProvider).createCompanyGroup("company-acme_corp", companyUuid.toString());
        }

        @Test
        @DisplayName("should sanitize special characters in company name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyId, "Acme-Corp S.A. de C.V.");

            listener.onCompanyCreated(event);

            verify(identityProvider).createCompanyGroup("company-acme-corp_s_a__de_c_v_", companyUuid.toString());
        }

        @Test
        @DisplayName("should lowercase company name")
        void shouldLowercaseCompanyName() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyId, "ACME CORPORATION");

            listener.onCompanyCreated(event);

            verify(identityProvider).createCompanyGroup("company-acme_corporation", companyUuid.toString());
        }

        @Test
        @DisplayName("should handle underscores and hyphens in name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyId, "my-company_test");

            listener.onCompanyCreated(event);

            verify(identityProvider).createCompanyGroup("company-my-company_test", companyUuid.toString());
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyId, companyName);
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createCompanyGroup("company-acme_corp", companyUuid.toString());

            // Should not throw
            listener.onCompanyCreated(event);

            verify(identityProvider).createCompanyGroup("company-acme_corp", companyUuid.toString());
        }
    }
}
