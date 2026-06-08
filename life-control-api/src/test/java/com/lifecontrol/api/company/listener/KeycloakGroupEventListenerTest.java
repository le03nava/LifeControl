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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

            verify(identityProvider).createGroup(
                    "lc-company-acme_corp",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should sanitize special characters in company name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, "Acme-Corp S.A. de C.V.");

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-acme-corp_s_a__de_c_v_",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should lowercase company name")
        void shouldLowercaseCompanyName() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, "ACME CORPORATION");

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-acme_corporation",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, "my-company_test");

            listener.onCompanyCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-my-company_test",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyCreatedEvent(this, companyUuid, companyKey, companyName);
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroup(
                            "lc-company-acme_corp",
                            Map.of("company_id", List.of(companyUuid.toString())),
                            Optional.empty());

            // Should not throw
            listener.onCompanyCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-acme_corp",
                    Map.of("company_id", List.of(companyUuid.toString())),
                    Optional.empty()
            );
        }
    }

    @Nested
    @DisplayName("onCompanyCountryCreated")
    class OnCompanyCountryCreatedTests {

        private final UUID companyCountryUuid = UUID.randomUUID();
        private final UUID companyUuid = UUID.randomUUID();
        private final String companyName = "Acme Corp";

        @Test
        @DisplayName("should create company-country group with sanitized name and attributes")
        void shouldCreateCompanyCountryGroup() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid,
                    "Argentina", companyName);
            when(identityProvider.findGroupIdByName("lc-company-acme_corp"))
                    .thenReturn(Optional.empty());

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-country-argentina",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should sanitize special characters in country name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid,
                    "São Paulo", companyName);
            when(identityProvider.findGroupIdByName("lc-company-acme_corp"))
                    .thenReturn(Optional.empty());

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-country-s_o_paulo",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in country name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid,
                    "Costa-Rica_test", companyName);
            when(identityProvider.findGroupIdByName("lc-company-acme_corp"))
                    .thenReturn(Optional.empty());

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-country-costa-rica_test",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should lowercase country name")
        void shouldLowercaseCountryName() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid,
                    "UNITED KINGDOM", companyName);
            when(identityProvider.findGroupIdByName("lc-company-acme_corp"))
                    .thenReturn(Optional.empty());

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-country-united_kingdom",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyCountryCreatedEvent(this, companyCountryUuid, companyUuid,
                    "Argentina", companyName);
            when(identityProvider.findGroupIdByName("lc-company-acme_corp"))
                    .thenReturn(Optional.empty());
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroup(
                            "lc-company-country-argentina",
                            Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                            Optional.empty());

            // Should not throw
            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-country-argentina",
                    Map.of("company_country_id", List.of(companyCountryUuid.toString())),
                    Optional.empty()
            );
        }
    }

    @Nested
    @DisplayName("onCompanyRegionCreated")
    class OnCompanyRegionCreatedTests {

        private final UUID companyRegionUuid = UUID.randomUUID();
        private final UUID companyUuid = UUID.randomUUID();
        private static final String COUNTRY_NAME = "Mexico";

        @Test
        @DisplayName("should create company-region group with sanitized name and attributes")
        void shouldCreateCompanyRegionGroup() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid,
                    "Region Norte", COUNTRY_NAME);
            when(identityProvider.findGroupIdByName("lc-company-country-mexico"))
                    .thenReturn(Optional.empty());

            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-region-region_norte",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should sanitize special characters in region name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid,
                    "Región Sur Este", COUNTRY_NAME);
            when(identityProvider.findGroupIdByName("lc-company-country-mexico"))
                    .thenReturn(Optional.empty());

            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-region-regi_n_sur_este",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in region name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid,
                    "Norte-Sur_test", COUNTRY_NAME);
            when(identityProvider.findGroupIdByName("lc-company-country-mexico"))
                    .thenReturn(Optional.empty());

            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-region-norte-sur_test",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should lowercase region name")
        void shouldLowercaseRegionName() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid,
                    "ZONA NORTE", COUNTRY_NAME);
            when(identityProvider.findGroupIdByName("lc-company-country-mexico"))
                    .thenReturn(Optional.empty());

            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-region-zona_norte",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyRegionCreatedEvent(this, companyRegionUuid, companyUuid,
                    "Region Norte", COUNTRY_NAME);
            when(identityProvider.findGroupIdByName("lc-company-country-mexico"))
                    .thenReturn(Optional.empty());
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroup(
                            "lc-company-region-region_norte",
                            Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                            Optional.empty());

            // Should not throw
            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-region-region_norte",
                    Map.of("company_region_id", List.of(companyRegionUuid.toString())),
                    Optional.empty()
            );
        }
    }

    @Nested
    @DisplayName("onCompanyZoneCreated")
    class OnCompanyZoneCreatedTests {

        private final UUID companyZoneUuid = UUID.randomUUID();
        private final UUID companyUuid = UUID.randomUUID();
        private static final String REGION_NAME = "North Region";

        @Test
        @DisplayName("should create company-zone group with sanitized name and attributes")
        void shouldCreateCompanyZoneGroup() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid,
                    "Zona A", REGION_NAME);
            when(identityProvider.findGroupIdByName("lc-company-region-north_region"))
                    .thenReturn(Optional.empty());

            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-zone-zona_a",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should sanitize special characters in zone name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid,
                    "Zona #1 (Sucursal)", REGION_NAME);
            when(identityProvider.findGroupIdByName("lc-company-region-north_region"))
                    .thenReturn(Optional.empty());

            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-zone-zona__1__sucursal_",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in zone name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid,
                    "Zona-Norte_test", REGION_NAME);
            when(identityProvider.findGroupIdByName("lc-company-region-north_region"))
                    .thenReturn(Optional.empty());

            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-zone-zona-norte_test",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should lowercase zone name")
        void shouldLowercaseZoneName() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid,
                    "ZONA CENTRAL", REGION_NAME);
            when(identityProvider.findGroupIdByName("lc-company-region-north_region"))
                    .thenReturn(Optional.empty());

            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-zone-zona_central",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyZoneCreatedEvent(this, companyZoneUuid, companyUuid,
                    "Zona A", REGION_NAME);
            when(identityProvider.findGroupIdByName("lc-company-region-north_region"))
                    .thenReturn(Optional.empty());
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroup(
                            "lc-company-zone-zona_a",
                            Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                            Optional.empty());

            // Should not throw
            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-zone-zona_a",
                    Map.of("company_zone_id", List.of(companyZoneUuid.toString())),
                    Optional.empty()
            );
        }
    }

    @Nested
    @DisplayName("onCompanyStoreCreated")
    class OnCompanyStoreCreatedTests {

        private final UUID companyStoreUuid = UUID.randomUUID();
        private final UUID companyUuid = UUID.randomUUID();
        private static final String ZONE_NAME = "Downtown Zone";

        @Test
        @DisplayName("should create company-store group with sanitized name and attributes")
        void shouldCreateCompanyStoreGroup() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid,
                    "Main Store", ZONE_NAME);
            when(identityProvider.findGroupIdByName("lc-company-zone-downtown_zone"))
                    .thenReturn(Optional.empty());

            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-store-main_store",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should sanitize special characters in store name")
        void shouldSanitizeSpecialCharacters() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid,
                    "Mi Tienda #1!", ZONE_NAME);
            when(identityProvider.findGroupIdByName("lc-company-zone-downtown_zone"))
                    .thenReturn(Optional.empty());

            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-store-mi_tienda__1_",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should handle underscores and hyphens in store name")
        void shouldHandleUnderscoresAndHyphens() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid,
                    "Store-North_test", ZONE_NAME);
            when(identityProvider.findGroupIdByName("lc-company-zone-downtown_zone"))
                    .thenReturn(Optional.empty());

            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-store-store-north_test",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should lowercase store name")
        void shouldLowercaseStoreName() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid,
                    "MAIN STORE", ZONE_NAME);
            when(identityProvider.findGroupIdByName("lc-company-zone-downtown_zone"))
                    .thenReturn(Optional.empty());

            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-store-main_store",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("should not re-throw when group creation fails")
        void shouldNotRethrowOnFailure() {
            var event = new CompanyStoreCreatedEvent(this, companyStoreUuid, companyUuid,
                    "Main Store", ZONE_NAME);
            when(identityProvider.findGroupIdByName("lc-company-zone-downtown_zone"))
                    .thenReturn(Optional.empty());
            doThrow(new IdentityProviderConnectionException("Keycloak unavailable"))
                    .when(identityProvider).createGroup(
                            "lc-company-store-main_store",
                            Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                            Optional.empty());

            // Should not throw
            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-store-main_store",
                    Map.of("company_store_id", List.of(companyStoreUuid.toString())),
                    Optional.empty()
            );
        }
    }

    @Nested
    @DisplayName("Parent Resolution — createGroup")
    class ParentResolutionTests {

        private static final String PARENT_ID = "parent-group-uuid";

        @Test
        @DisplayName("6.3: onCompanyCountryCreated calls createGroup with resolved parent company ID")
        void shouldCallCreateGroupForCountryWithCompanyParent() {
            var companyUuid = UUID.randomUUID();
            var countryUuid = UUID.randomUUID();
            var event = new CompanyCountryCreatedEvent(this, countryUuid, companyUuid,
                    "Argentina", "Acme Corp");
            when(identityProvider.findGroupIdByName("lc-company-acme_corp"))
                    .thenReturn(Optional.of(PARENT_ID));

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-country-argentina",
                    Map.of("company_country_id", List.of(countryUuid.toString())),
                    Optional.of(PARENT_ID)
            );
        }

        @Test
        @DisplayName("6.4: onCompanyRegionCreated calls createGroup with resolved country parent ID")
        void shouldCallCreateGroupForRegionWithCountryParent() {
            var companyUuid = UUID.randomUUID();
            var regionUuid = UUID.randomUUID();
            var event = new CompanyRegionCreatedEvent(this, regionUuid, companyUuid,
                    "South", "Argentina");
            when(identityProvider.findGroupIdByName("lc-company-country-argentina"))
                    .thenReturn(Optional.of(PARENT_ID));

            listener.onCompanyRegionCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-region-south",
                    Map.of("company_region_id", List.of(regionUuid.toString())),
                    Optional.of(PARENT_ID)
            );
        }

        @Test
        @DisplayName("6.5: onCompanyZoneCreated calls createGroup with resolved region parent ID")
        void shouldCallCreateGroupForZoneWithRegionParent() {
            var companyUuid = UUID.randomUUID();
            var zoneUuid = UUID.randomUUID();
            var event = new CompanyZoneCreatedEvent(this, zoneUuid, companyUuid,
                    "Downtown", "North");
            when(identityProvider.findGroupIdByName("lc-company-region-north"))
                    .thenReturn(Optional.of(PARENT_ID));

            listener.onCompanyZoneCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-zone-downtown",
                    Map.of("company_zone_id", List.of(zoneUuid.toString())),
                    Optional.of(PARENT_ID)
            );
        }

        @Test
        @DisplayName("6.6: onCompanyStoreCreated calls createGroup with resolved zone parent ID")
        void shouldCallCreateGroupForStoreWithZoneParent() {
            var companyUuid = UUID.randomUUID();
            var storeUuid = UUID.randomUUID();
            var event = new CompanyStoreCreatedEvent(this, storeUuid, companyUuid,
                    "Main Branch", "Downtown");
            when(identityProvider.findGroupIdByName("lc-company-zone-downtown"))
                    .thenReturn(Optional.of(PARENT_ID));

            listener.onCompanyStoreCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-store-main_branch",
                    Map.of("company_store_id", List.of(storeUuid.toString())),
                    Optional.of(PARENT_ID)
            );
        }

        @Test
        @DisplayName("6.7: fallback — when findGroupIdByName returns empty, createGroup with empty parent")
        void shouldFallbackToEmptyParentWhenParentNotFound() {
            var companyUuid = UUID.randomUUID();
            var countryUuid = UUID.randomUUID();
            var event = new CompanyCountryCreatedEvent(this, countryUuid, companyUuid,
                    "Chile", "MissingCorp");
            when(identityProvider.findGroupIdByName("lc-company-missingcorp"))
                    .thenReturn(Optional.empty());

            listener.onCompanyCountryCreated(event);

            verify(identityProvider).createGroup(
                    "lc-company-country-chile",
                    Map.of("company_country_id", List.of(countryUuid.toString())),
                    Optional.empty()
            );
        }
    }
}
