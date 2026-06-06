package com.lifecontrol.api.company.listener;

import com.lifecontrol.api.company.event.CompanyCountryCreatedEvent;
import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.company.event.CompanyRegionCreatedEvent;
import com.lifecontrol.api.company.event.CompanyZoneCreatedEvent;
import com.lifecontrol.api.store.event.CompanyStoreCreatedEvent;
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
                    "lc-company-acme_corp",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "lc-company",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should sanitize special characters in company name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, "Acme-Corp S.A. de C.V.");

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-acme-corp_s_a__de_c_v_",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "lc-company",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should lowercase company name")
        void shouldLowercaseCompanyName() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, "ACME CORPORATION");

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-acme_corporation",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "lc-company",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, "my-company_test");

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-my-company_test",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "lc-company",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, companyName);
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroupWithRole(
                            "lc-company-acme_corp",
                            Map.of("company_id", List.of(companyUuid.toString())),
                            "lc-company",
                            "life-control-client");

            // Should not throw
            listener.onCompanyCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-acme_corp",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    "lc-company",
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

    @Nested
    @DisplayName("onCompanyRegionCreated")
    class OnCompanyRegionCreatedTests {

        private final UUID companyRegionUuid = UUID.randomUUID();
        private final UUID companyUuid = UUID.randomUUID();

        @Test
        @DisplayName("should create company-region group with sanitized name and attributes")
        void shouldCreateCompanyRegionGroup() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid, "Region Norte");

            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-region-region_norte",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    "lc-company-region",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should sanitize special characters in region name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid, "Región Sur Este");

            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-region-regi_n_sur_este",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    "lc-company-region",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in region name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid, "Norte-Sur_test");

            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-region-norte-sur_test",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    "lc-company-region",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should lowercase region name")
        void shouldLowercaseRegionName() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid, "ZONA NORTE");

            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-region-zona_norte",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    "lc-company-region",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid, "Region Norte");
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroupWithRole(
                            "lc-company-region-region_norte",
                            Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                            "lc-company-region",
                            "life-control-client");

            // Should not throw
            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-region-region_norte",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    "lc-company-region",
                    "life-control-client"
            );
        }
    }

    @Nested
    @DisplayName("onCompanyZoneCreated")
    class OnCompanyZoneCreatedTests {

        private final UUID companyZoneUuid = UUID.randomUUID();
        private final UUID companyUuid = UUID.randomUUID();

        @Test
        @DisplayName("should create company-zone group with sanitized name and attributes")
        void shouldCreateCompanyZoneGroup() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid, "Zona A");

            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-zone-zona_a",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    "lc-company-zone",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should sanitize special characters in zone name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid, "Zona #1 (Sucursal)");

            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-zone-zona__1__sucursal_",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    "lc-company-zone",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in zone name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid, "Zona-Norte_test");

            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-zone-zona-norte_test",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    "lc-company-zone",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should lowercase zone name")
        void shouldLowercaseZoneName() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid, "ZONA CENTRAL");

            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-zone-zona_central",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    "lc-company-zone",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid, "Zona A");
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroupWithRole(
                            "lc-company-zone-zona_a",
                            Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                            "lc-company-zone",
                            "life-control-client");

            // Should not throw
            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-zone-zona_a",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    "lc-company-zone",
                    "life-control-client"
            );
        }
    }

    @Nested
    @DisplayName("onCompanyStoreCreated")
    class OnCompanyStoreCreatedTests {

        private final UUID companyStoreUuid = UUID.randomUUID();
        private final UUID companyUuid = UUID.randomUUID();

        @Test
        @DisplayName("should create company-store group with sanitized name and attributes")
        void shouldCreateCompanyStoreGroup() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid, "Main Store");

            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-store-main_store",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    "lc-company-store",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should sanitize special characters in store name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid, "Mi Tienda #1!");

            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-store-mi_tienda__1_",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    "lc-company-store",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in store name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid, "Store-North_test");

            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-store-store-north_test",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    "lc-company-store",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should lowercase store name")
        void shouldLowercaseStoreName() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid, "MAIN STORE");

            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-store-main_store",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    "lc-company-store",
                    "life-control-client"
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid, "Main Store");
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroupWithRole(
                            "lc-company-store-main_store",
                            Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                            "lc-company-store",
                            "life-control-client");

            // Should not throw
            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroupWithRole(
                    "lc-company-store-main_store",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    "lc-company-store",
                    "life-control-client"
            );
        }
    }
}
