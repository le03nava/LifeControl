package com.lifecontrol.api.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Pins the single rule that turns a caller's {@link ScopeLevel} into the store ids it may read.
 *
 * <p>It is the same rule {@code CurrentUserContext#verifyCompanyStoreAccess} applies to the
 * id-addressed reads, which is the point: the list filter and the detail check must agree, or a
 * caller sees either a false empty page or a row it cannot open. Every level is covered because a
 * level that silently falls through to another level's claim is a cross-tenant read — including the
 * <em>ancestor</em> claims, which the detail verifies and which the finders therefore receive.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CompanyStoreScopeResolver")
class CompanyStoreScopeResolverTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c5");
    private static final UUID OTHER_STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c6");

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    private CompanyStoreScopeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CompanyStoreScopeResolver(currentUserContext, companyStoreRepository);
        when(currentUserContext.isAdmin()).thenReturn(false);
        // A complete claim path by default; each case removes what it wants to be missing.
        when(currentUserContext.getCompanyIds()).thenReturn(Set.of(COMPANY_ID));
        when(currentUserContext.getCompanyCountryIds()).thenReturn(Set.of(COUNTRY_ID));
        when(currentUserContext.getCompanyRegionIds()).thenReturn(Set.of(REGION_ID));
        when(currentUserContext.getCompanyZoneIds()).thenReturn(Set.of(ZONE_ID));
        when(currentUserContext.getCompanyStoreIds()).thenReturn(Set.of(STORE_ID, OTHER_STORE_ID));
    }

    private void grant(ScopeLevel level) {
        when(currentUserContext.broadestGrantedScope(ScopeLevel.STORE)).thenReturn(Optional.of(level));
    }

    @Nested
    @DisplayName("administrator")
    class Administrator {

        @Test
        @DisplayName("should be unscoped and resolve no store ids at all")
        void isUnscoped() {
            when(currentUserContext.isAdmin()).thenReturn(true);

            var scope = resolver.scopeForCurrentUser();

            assertThat(scope.unscoped()).isTrue();
            assertThat(scope.storeIds()).isEmpty();
            verifyNoInteractions(companyStoreRepository);
            verify(currentUserContext, never()).broadestGrantedScope(any());
        }
    }

    @Nested
    @DisplayName("broadest granted scope")
    class BroadestGrantedScope {

        @Test
        @DisplayName("COMPANY resolves the caller's companies with no ancestor constraint")
        void companyScope() {
            grant(ScopeLevel.COMPANY);
            when(companyStoreRepository.findIdsByCompanyIdIn(Set.of(COMPANY_ID)))
                    .thenReturn(List.of(STORE_ID, OTHER_STORE_ID));

            var scope = resolver.scopeForCurrentUser();

            assertThat(scope.unscoped()).isFalse();
            assertThat(scope.storeIds()).containsExactlyInAnyOrder(STORE_ID, OTHER_STORE_ID);
        }

        @Test
        @DisplayName("COUNTRY constrains the country claim by the company claim")
        void countryScope() {
            grant(ScopeLevel.COUNTRY);
            when(companyStoreRepository.findIdsByCompanyCountryIdIn(Set.of(COUNTRY_ID), Set.of(COMPANY_ID)))
                    .thenReturn(List.of(STORE_ID));

            assertThat(resolver.scopeForCurrentUser().storeIds()).containsExactly(STORE_ID);
            verify(companyStoreRepository).findIdsByCompanyCountryIdIn(Set.of(COUNTRY_ID), Set.of(COMPANY_ID));
        }

        @Test
        @DisplayName("REGION constrains the region claim by the country and company claims")
        void regionScope() {
            grant(ScopeLevel.REGION);
            when(companyStoreRepository.findIdsByCompanyRegionIdIn(
                            Set.of(REGION_ID), Set.of(COUNTRY_ID), Set.of(COMPANY_ID)))
                    .thenReturn(List.of(STORE_ID));

            assertThat(resolver.scopeForCurrentUser().storeIds()).containsExactly(STORE_ID);
            verify(companyStoreRepository)
                    .findIdsByCompanyRegionIdIn(Set.of(REGION_ID), Set.of(COUNTRY_ID), Set.of(COMPANY_ID));
        }

        @Test
        @DisplayName("ZONE constrains the zone claim by every ancestor claim")
        void zoneScope() {
            grant(ScopeLevel.ZONE);
            when(companyStoreRepository.findIdsByCompanyZoneIdIn(
                            Set.of(ZONE_ID), Set.of(REGION_ID), Set.of(COUNTRY_ID), Set.of(COMPANY_ID)))
                    .thenReturn(List.of(STORE_ID));

            assertThat(resolver.scopeForCurrentUser().storeIds()).containsExactly(STORE_ID);
            verify(companyStoreRepository)
                    .findIdsByCompanyZoneIdIn(
                            Set.of(ZONE_ID), Set.of(REGION_ID), Set.of(COUNTRY_ID), Set.of(COMPANY_ID));
        }

        @Test
        @DisplayName("STORE constrains the store claim by the whole ancestor chain")
        void storeScope() {
            grant(ScopeLevel.STORE);
            when(companyStoreRepository.findIdsByStoreIdIn(
                            Set.of(STORE_ID, OTHER_STORE_ID),
                            Set.of(ZONE_ID),
                            Set.of(REGION_ID),
                            Set.of(COUNTRY_ID),
                            Set.of(COMPANY_ID)))
                    .thenReturn(List.of(STORE_ID));

            assertThat(resolver.scopeForCurrentUser().storeIds()).containsExactly(STORE_ID);
        }
    }

    @Nested
    @DisplayName("fail-closed cases")
    class FailClosed {

        @Test
        @DisplayName("should resolve nothing when no role in range is granted")
        void noRoleInRange() {
            when(currentUserContext.broadestGrantedScope(ScopeLevel.STORE)).thenReturn(Optional.empty());

            var scope = resolver.scopeForCurrentUser();

            assertThat(scope.unscoped()).isFalse();
            assertThat(scope.storeIds()).isEmpty();
            verifyNoInteractions(companyStoreRepository);
        }

        @Test
        @DisplayName("should resolve nothing without querying when the level's own claim is empty")
        void emptyOwnClaimNeverQueries() {
            grant(ScopeLevel.REGION);
            when(currentUserContext.getCompanyRegionIds()).thenReturn(Set.of());

            assertThat(resolver.scopeForCurrentUser().storeIds()).isEmpty();
            verifyNoInteractions(companyStoreRepository);
        }

        @Test
        @DisplayName("should resolve nothing when a region reader has no country claim, exactly as the detail denies")
        void regionWithoutCountryClaimNeverQueries() {
            grant(ScopeLevel.REGION);
            when(currentUserContext.getCompanyCountryIds()).thenReturn(Set.of());

            assertThat(resolver.scopeForCurrentUser().storeIds()).isEmpty();
            verifyNoInteractions(companyStoreRepository);
        }

        @Test
        @DisplayName("should resolve nothing when a country reader has no company claim")
        void countryWithoutCompanyClaimNeverQueries() {
            grant(ScopeLevel.COUNTRY);
            when(currentUserContext.getCompanyIds()).thenReturn(Set.of());

            assertThat(resolver.scopeForCurrentUser().storeIds()).isEmpty();
            verifyNoInteractions(companyStoreRepository);
        }

        @Test
        @DisplayName("should resolve nothing when a zone reader has no region claim")
        void zoneWithoutRegionClaimNeverQueries() {
            grant(ScopeLevel.ZONE);
            when(currentUserContext.getCompanyRegionIds()).thenReturn(Set.of());

            assertThat(resolver.scopeForCurrentUser().storeIds()).isEmpty();
            verifyNoInteractions(companyStoreRepository);
        }

        @Test
        @DisplayName("should resolve nothing when a store reader has no zone claim")
        void storeWithoutAncestorClaimNeverQueries() {
            grant(ScopeLevel.STORE);
            when(currentUserContext.getCompanyZoneIds()).thenReturn(Set.of());

            assertThat(resolver.scopeForCurrentUser().storeIds()).isEmpty();
            verifyNoInteractions(companyStoreRepository);
        }
    }
}
