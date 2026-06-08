package com.lifecontrol.api.common.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("CurrentUserContext Tests")
class CurrentUserContextTest {

    private CurrentUserContext currentUserContext;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @Mock
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.getPrincipal()).thenReturn(jwt);
        SecurityContextHolder.setContext(securityContext);
        currentUserContext = new CurrentUserContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("getUserId / getUsername — JWT claim extraction")
    class UserIdentityTests {

        @Test
        @DisplayName("getUserId should return the sub claim")
        void getUserIdReturnsSub() {
            when(jwt.getClaimAsString("sub")).thenReturn("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

            var result = currentUserContext.getUserId();

            assertThat(result).isEqualTo("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        }

        @Test
        @DisplayName("getUserId should return null when JWT has no sub claim")
        void getUserIdReturnsNullWhenMissing() {
            when(jwt.getClaimAsString("sub")).thenReturn(null);

            var result = currentUserContext.getUserId();

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getUsername should return the preferred_username claim")
        void getUsernameReturnsPreferredUsername() {
            when(jwt.getClaimAsString("preferred_username")).thenReturn("jdoe");

            var result = currentUserContext.getUsername();

            assertThat(result).isEqualTo("jdoe");
        }

        @Test
        @DisplayName("getUsername should return null when JWT has no preferred_username claim")
        void getUsernameReturnsNullWhenMissing() {
            when(jwt.getClaimAsString("preferred_username")).thenReturn(null);

            var result = currentUserContext.getUsername();

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getCompanyIds")
    class GetCompanyIdsTests {

        @Test
        @DisplayName("should extract single UUID from company_id claim")
        void singleUuid() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(id.toString());

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should extract multiple comma-separated UUIDs")
        void multipleUuids() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(id1 + "," + id2);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).containsExactlyInAnyOrder(id1, id2);
        }

        @Test
        @DisplayName("should deduplicate repeated UUIDs")
        void deduplicates() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(id + "," + id);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).hasSize(1);
            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should handle whitespace-padded UUIDs")
        void whitespacePadded() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(" " + id + " , " + id);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should silently skip malformed UUIDs")
        void malformedUuids() {
            UUID validId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn("not-a-uuid," + validId);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).containsExactly(validId);
        }

        @Test
        @DisplayName("all-malformed returns empty set")
        void allMalformed() {
            when(jwt.getClaim("company_id")).thenReturn("not-a-uuid,also-bad");

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is missing")
        void missingClaim() {
            when(jwt.getClaim("company_id")).thenReturn(null);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is blank")
        void blankClaim() {
            when(jwt.getClaim("company_id")).thenReturn("   ");

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getCompanyRegionIds")
    class GetCompanyRegionIdsTests {

        @Test
        @DisplayName("should extract single UUID from company_region_id claim")
        void singleUuid() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_region_id")).thenReturn(id.toString());

            Set<UUID> result = currentUserContext.getCompanyRegionIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should extract multiple comma-separated UUIDs")
        void multipleUuids() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(jwt.getClaim("company_region_id")).thenReturn(id1 + "," + id2);

            Set<UUID> result = currentUserContext.getCompanyRegionIds();

            assertThat(result).containsExactlyInAnyOrder(id1, id2);
        }

        @Test
        @DisplayName("should deduplicate repeated UUIDs")
        void deduplicates() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_region_id")).thenReturn(id + "," + id);

            Set<UUID> result = currentUserContext.getCompanyRegionIds();

            assertThat(result).hasSize(1);
            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should handle whitespace-padded UUIDs")
        void whitespacePadded() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_region_id")).thenReturn(" " + id + " , " + id);

            Set<UUID> result = currentUserContext.getCompanyRegionIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should silently skip malformed UUIDs")
        void malformedUuids() {
            UUID validId = UUID.randomUUID();
            when(jwt.getClaim("company_region_id")).thenReturn("not-a-uuid," + validId);

            Set<UUID> result = currentUserContext.getCompanyRegionIds();

            assertThat(result).containsExactly(validId);
        }

        @Test
        @DisplayName("all-malformed returns empty set")
        void allMalformed() {
            when(jwt.getClaim("company_region_id")).thenReturn("not-a-uuid,also-bad");

            Set<UUID> result = currentUserContext.getCompanyRegionIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is missing")
        void missingClaim() {
            when(jwt.getClaim("company_region_id")).thenReturn(null);

            Set<UUID> result = currentUserContext.getCompanyRegionIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is blank")
        void blankClaim() {
            when(jwt.getClaim("company_region_id")).thenReturn("   ");

            Set<UUID> result = currentUserContext.getCompanyRegionIds();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getCompanyCountryIds")
    class GetCompanyCountryIdsTests {

        @Test
        @DisplayName("should extract single UUID from company_country_id claim")
        void singleUuid() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_country_id")).thenReturn(id.toString());

            Set<UUID> result = currentUserContext.getCompanyCountryIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should extract multiple comma-separated UUIDs")
        void multipleUuids() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(jwt.getClaim("company_country_id")).thenReturn(id1 + "," + id2);

            Set<UUID> result = currentUserContext.getCompanyCountryIds();

            assertThat(result).containsExactlyInAnyOrder(id1, id2);
        }

        @Test
        @DisplayName("should deduplicate repeated UUIDs")
        void deduplicates() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_country_id")).thenReturn(id + "," + id);

            Set<UUID> result = currentUserContext.getCompanyCountryIds();

            assertThat(result).hasSize(1);
            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should handle whitespace-padded UUIDs")
        void whitespacePadded() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_country_id")).thenReturn(" " + id + " , " + id);

            Set<UUID> result = currentUserContext.getCompanyCountryIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should silently skip malformed UUIDs")
        void malformedUuids() {
            UUID validId = UUID.randomUUID();
            when(jwt.getClaim("company_country_id")).thenReturn("not-a-uuid," + validId);

            Set<UUID> result = currentUserContext.getCompanyCountryIds();

            assertThat(result).containsExactly(validId);
        }

        @Test
        @DisplayName("all-malformed returns empty set")
        void allMalformed() {
            when(jwt.getClaim("company_country_id")).thenReturn("not-a-uuid,also-bad");

            Set<UUID> result = currentUserContext.getCompanyCountryIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is missing")
        void missingClaim() {
            when(jwt.getClaim("company_country_id")).thenReturn(null);

            Set<UUID> result = currentUserContext.getCompanyCountryIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is blank")
        void blankClaim() {
            when(jwt.getClaim("company_country_id")).thenReturn("   ");

            Set<UUID> result = currentUserContext.getCompanyCountryIds();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getCompanyZoneIds")
    class GetCompanyZoneIdsTests {

        @Test
        @DisplayName("should extract single UUID from company_zone_id claim")
        void singleUuid() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_zone_id")).thenReturn(id.toString());

            Set<UUID> result = currentUserContext.getCompanyZoneIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should extract multiple comma-separated UUIDs")
        void multipleUuids() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(jwt.getClaim("company_zone_id")).thenReturn(id1 + "," + id2);

            Set<UUID> result = currentUserContext.getCompanyZoneIds();

            assertThat(result).containsExactlyInAnyOrder(id1, id2);
        }

        @Test
        @DisplayName("should deduplicate repeated UUIDs")
        void deduplicates() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_zone_id")).thenReturn(id + "," + id);

            Set<UUID> result = currentUserContext.getCompanyZoneIds();

            assertThat(result).hasSize(1);
            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should handle whitespace-padded UUIDs")
        void whitespacePadded() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_zone_id")).thenReturn(" " + id + " , " + id);

            Set<UUID> result = currentUserContext.getCompanyZoneIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should silently skip malformed UUIDs")
        void malformedUuids() {
            UUID validId = UUID.randomUUID();
            when(jwt.getClaim("company_zone_id")).thenReturn("not-a-uuid," + validId);

            Set<UUID> result = currentUserContext.getCompanyZoneIds();

            assertThat(result).containsExactly(validId);
        }

        @Test
        @DisplayName("all-malformed returns empty set")
        void allMalformed() {
            when(jwt.getClaim("company_zone_id")).thenReturn("not-a-uuid,also-bad");

            Set<UUID> result = currentUserContext.getCompanyZoneIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is missing")
        void missingClaim() {
            when(jwt.getClaim("company_zone_id")).thenReturn(null);

            Set<UUID> result = currentUserContext.getCompanyZoneIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is blank")
        void blankClaim() {
            when(jwt.getClaim("company_zone_id")).thenReturn("   ");

            Set<UUID> result = currentUserContext.getCompanyZoneIds();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Role detection")
    class RoleDetectionTests {

        @SuppressWarnings("unchecked")
        private void mockAuthorities(Collection<GrantedAuthority> authorities) {
            when(authentication.getAuthorities()).thenReturn((Collection) authorities);
        }

        @Test
        @DisplayName("isAdmin returns true when user has ROLE_life-control-admin")
        void adminRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-admin"));

            assertThat(currentUserContext.isAdmin()).isTrue();
            assertThat(currentUserContext.isCountryRole()).isFalse();
        }

        @Test
        @DisplayName("isAdmin returns true when user has ROLE_lc-admin")
        void lcAdminRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-admin"));

            assertThat(currentUserContext.isAdmin()).isTrue();
            assertThat(currentUserContext.isCountryRole()).isFalse();
        }

        @Test
        @DisplayName("isCountryRole returns true when user has ROLE_life-control-country")
        void countryRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-country"));

            assertThat(currentUserContext.isAdmin()).isFalse();
            assertThat(currentUserContext.isCountryRole()).isTrue();
        }

        @Test
        @DisplayName("both roles return true for both checks")
        void bothRoles() {
            mockAuthorities(List.of(
                    (GrantedAuthority) () -> "ROLE_life-control-admin",
                    (GrantedAuthority) () -> "ROLE_life-control-country"
            ));

            assertThat(currentUserContext.isAdmin()).isTrue();
            assertThat(currentUserContext.isCountryRole()).isTrue();
        }

        @Test
        @DisplayName("neither role returns false for both checks")
        void neitherRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThat(currentUserContext.isAdmin()).isFalse();
            assertThat(currentUserContext.isCountryRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyRole returns true when user has ROLE_lc-company")
        void companyRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));

            assertThat(currentUserContext.hasCompanyRole()).isTrue();
            assertThat(currentUserContext.hasCompanyCountryRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyRole returns false when role not present")
        void noCompanyRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThat(currentUserContext.hasCompanyRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyCountryRole returns true when user has ROLE_lc-company-country")
        void companyCountryRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));

            assertThat(currentUserContext.hasCompanyCountryRole()).isTrue();
            assertThat(currentUserContext.hasCompanyRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyCountryRole returns false when role not present")
        void noCompanyCountryRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThat(currentUserContext.hasCompanyCountryRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyRegionRole returns true when user has ROLE_lc-company-region")
        void companyRegionRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-region"));

            assertThat(currentUserContext.hasCompanyRegionRole()).isTrue();
            assertThat(currentUserContext.hasCompanyCountryRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyRegionRole returns false when role not present")
        void noCompanyRegionRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThat(currentUserContext.hasCompanyRegionRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyZoneRole returns true when user has ROLE_lc-company-zone")
        void companyZoneRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-zone"));

            assertThat(currentUserContext.hasCompanyZoneRole()).isTrue();
            assertThat(currentUserContext.hasCompanyRegionRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyZoneRole returns false when role not present")
        void noCompanyZoneRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThat(currentUserContext.hasCompanyZoneRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyStoreRole returns true when user has ROLE_lc-company-store")
        void companyStoreRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-store"));

            assertThat(currentUserContext.hasCompanyStoreRole()).isTrue();
            assertThat(currentUserContext.hasCompanyZoneRole()).isFalse();
        }

        @Test
        @DisplayName("hasCompanyStoreRole returns false when role not present")
        void noCompanyStoreRole() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThat(currentUserContext.hasCompanyStoreRole()).isFalse();
        }
    }

    @Nested
    @DisplayName("verifyCompanyAccess")
    class VerifyCompanyAccessTests {

        @SuppressWarnings("unchecked")
        private void mockAuthorities(Collection<GrantedAuthority> authorities) {
            when(authentication.getAuthorities()).thenReturn((Collection) authorities);
        }

        @Test
        @DisplayName("admin can access any company")
        void adminCanAccessAnyCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-admin"));

            // Should not throw for any company ID
            currentUserContext.verifyCompanyAccess(UUID.randomUUID());
        }

        @Test
        @DisplayName("country-role can access assigned company")
        void countryRoleCanAccessAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-country"));
            UUID companyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());

            currentUserContext.verifyCompanyAccess(companyId);
        }

        @Test
        @DisplayName("country-role cannot access non-assigned company")
        void countryRoleCannotAccessNonAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-country"));
            UUID assignedId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedId.toString());

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyAccess(otherId)
            );
        }

        @Test
        @DisplayName("lc-company role can access assigned company")
        void lcCompanyRoleCanAccessAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID companyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());

            // Should not throw — lc-company user has matching company_id
            currentUserContext.verifyCompanyAccess(companyId);
        }

        @Test
        @DisplayName("lc-company role cannot access non-assigned company")
        void lcCompanyRoleCannotAccessNonAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID assignedId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedId.toString());

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyAccess(otherId)
            );
        }
    }

    @Nested
    @DisplayName("verifyCompanyCountryAccess")
    class VerifyCompanyCountryAccessTests {

        @SuppressWarnings("unchecked")
        private void mockAuthorities(Collection<GrantedAuthority> authorities) {
            when(authentication.getAuthorities()).thenReturn((Collection) authorities);
        }

        @Test
        @DisplayName("admin can access any company country")
        void adminCanAccessAnyCompanyCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-admin"));

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyCountryAccess(UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company can access company country via delegated company access")
        void lcCompanyCanAccessAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID companyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyCountryAccess(companyId, UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company cannot access non-assigned company")
        void lcCompanyCannotAccessNonAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID assignedId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyCountryAccess(otherId, UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-country can access matching company country")
        void countryUserCanAccessMatchingCompanyCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyCountryAccess(companyId, countryId));
        }

        @Test
        @DisplayName("lc-company-country cannot access mismatched company country")
        void countryUserCannotAccessMismatchedCompanyCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            UUID assignedCountryId = UUID.randomUUID();
            UUID otherCountryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(assignedCountryId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyCountryAccess(companyId, otherCountryId));
        }

        @Test
        @DisplayName("lc-company-country cannot access wrong company")
        void countryUserCannotAccessWrongCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID assignedCompanyId = UUID.randomUUID();
            UUID wrongCompanyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedCompanyId.toString());
            // company_country_id claim not needed — verifyCompanyAccess throws first

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyCountryAccess(wrongCompanyId, countryId));
        }

        @Test
        @DisplayName("lc-company-country throws when companyCountryId is null")
        void countryUserThrowsForNullCompanyCountryId() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            // company_country_id claim not needed — null check fires before claim parsing

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyCountryAccess(companyId, null));
        }

        @Test
        @DisplayName("lc-company-country throws when company_country_id claim is null")
        void countryUserThrowsForNullClaim() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(null);

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyCountryAccess(companyId, countryId));
        }

        @Test
        @DisplayName("no recognized role throws AccessDeniedException")
        void noRecognizedRoleThrows() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyCountryAccess(UUID.randomUUID(), UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("verifyCompanyRegionAccess")
    class VerifyCompanyRegionAccessTests {

        @SuppressWarnings("unchecked")
        private void mockAuthorities(Collection<GrantedAuthority> authorities) {
            when(authentication.getAuthorities()).thenReturn((Collection) authorities);
        }

        // ── 3.3: Admin bypass ──

        @Test
        @DisplayName("admin can access any region")
        void adminCanAccessAnyRegion() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-admin"));

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyRegionAccess(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── 3.4: lc-company delegates to verifyCompanyAccess ──

        @Test
        @DisplayName("lc-company can access assigned company's regions")
        void lcCompanyCanAccessAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID companyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyRegionAccess(companyId, UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company cannot access non-assigned company's regions")
        void lcCompanyCannotAccessNonAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID assignedId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyRegionAccess(otherId, UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── 3.5: lc-company-country ──

        @Test
        @DisplayName("lc-company-country can access matching company country region")
        void countryUserCanAccessMatchingCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyRegionAccess(companyId, countryId, UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-country cannot access mismatched country")
        void countryUserCannotAccessMismatchedCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            UUID assignedCountryId = UUID.randomUUID();
            UUID otherCountryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(assignedCountryId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyRegionAccess(companyId, otherCountryId, UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-country cannot access wrong company")
        void countryUserCannotAccessWrongCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID assignedCompanyId = UUID.randomUUID();
            UUID wrongCompanyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedCompanyId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyRegionAccess(wrongCompanyId, UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── 3.6: lc-company-region ──

        @Test
        @DisplayName("lc-company-region can access assigned region with all hierarchy matching")
        void regionUserCanAccessMatchingRegion() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-region"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyRegionAccess(companyId, countryId, regionId));
        }

        @Test
        @DisplayName("lc-company-region cannot access mismatched region")
        void regionUserCannotAccessMismatchedRegion() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-region"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID assignedRegionId = UUID.randomUUID();
            UUID otherRegionId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(assignedRegionId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyRegionAccess(companyId, countryId, otherRegionId));
        }

        @Test
        @DisplayName("lc-company-region cannot access mismatched country")
        void regionUserCannotAccessMismatchedCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-region"));
            UUID companyId = UUID.randomUUID();
            UUID assignedCountryId = UUID.randomUUID();
            UUID otherCountryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(assignedCountryId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyRegionAccess(companyId, otherCountryId, UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-region cannot access wrong company")
        void regionUserCannotAccessWrongCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-region"));
            UUID assignedCompanyId = UUID.randomUUID();
            UUID wrongCompanyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedCompanyId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyRegionAccess(wrongCompanyId, UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── 3.7: Unrecognized role ──

        @Test
        @DisplayName("no recognized role throws AccessDeniedException")
        void noRecognizedRoleThrows() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyRegionAccess(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("verifyCompanyZoneAccess")
    class VerifyCompanyZoneAccessTests {

        @SuppressWarnings("unchecked")
        private void mockAuthorities(Collection<GrantedAuthority> authorities) {
            when(authentication.getAuthorities()).thenReturn((Collection) authorities);
        }

        // ── Admin bypass ──

        @Test
        @DisplayName("admin can access any zone")
        void adminCanAccessAnyZone() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-admin"));

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyZoneAccess(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── lc-company delegates to verifyCompanyAccess ──

        @Test
        @DisplayName("lc-company can access assigned company's zones")
        void lcCompanyCanAccessAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID companyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyZoneAccess(companyId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company cannot access non-assigned company's zones")
        void lcCompanyCannotAccessNonAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID assignedId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyZoneAccess(otherId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── lc-company-country ──

        @Test
        @DisplayName("lc-company-country can access matching country's zones")
        void countryUserCanAccessMatchingCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyZoneAccess(companyId, countryId, UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-country cannot access mismatched country's zones")
        void countryUserCannotAccessMismatchedCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            UUID assignedCountryId = UUID.randomUUID();
            UUID otherCountryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(assignedCountryId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyZoneAccess(companyId, otherCountryId, UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── lc-company-region ──

        @Test
        @DisplayName("lc-company-region can access matching region's zones")
        void regionUserCanAccessMatchingRegion() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-region"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyZoneAccess(companyId, countryId, regionId, UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-region cannot access mismatched region's zones")
        void regionUserCannotAccessMismatchedRegion() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-region"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID assignedRegionId = UUID.randomUUID();
            UUID otherRegionId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(assignedRegionId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyZoneAccess(companyId, countryId, otherRegionId, UUID.randomUUID()));
        }

        // ── lc-company-zone ──

        @Test
        @DisplayName("lc-company-zone can access assigned zone with all hierarchy matching")
        void zoneUserCanAccessMatchingZone() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-zone"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            UUID zoneId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());
            when(jwt.getClaim("company_zone_id")).thenReturn(zoneId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyZoneAccess(companyId, countryId, regionId, zoneId));
        }

        @Test
        @DisplayName("lc-company-zone cannot access mismatched zone")
        void zoneUserCannotAccessMismatchedZone() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-zone"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            UUID assignedZoneId = UUID.randomUUID();
            UUID otherZoneId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());
            when(jwt.getClaim("company_zone_id")).thenReturn(assignedZoneId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyZoneAccess(companyId, countryId, regionId, otherZoneId));
        }

        @Test
        @DisplayName("lc-company-zone allows null zoneId for create ops")
        void zoneUserAllowsNullZoneId() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-zone"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());
            // company_zone_id claim is not stubbed — it won't be read when zoneId is null

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyZoneAccess(companyId, countryId, regionId, null));
        }

        // ── Unrecognized role ──

        @Test
        @DisplayName("no recognized role throws AccessDeniedException")
        void noRecognizedRoleThrows() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyZoneAccess(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("getCompanyStoreIds")
    class GetCompanyStoreIdsTests {

        @Test
        @DisplayName("should extract single UUID from company_store_id claim")
        void singleUuid() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_store_id")).thenReturn(id.toString());

            Set<UUID> result = currentUserContext.getCompanyStoreIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should extract multiple comma-separated UUIDs")
        void multipleUuids() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(jwt.getClaim("company_store_id")).thenReturn(id1 + "," + id2);

            Set<UUID> result = currentUserContext.getCompanyStoreIds();

            assertThat(result).containsExactlyInAnyOrder(id1, id2);
        }

        @Test
        @DisplayName("should deduplicate repeated UUIDs")
        void deduplicates() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_store_id")).thenReturn(id + "," + id);

            Set<UUID> result = currentUserContext.getCompanyStoreIds();

            assertThat(result).hasSize(1);
            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should handle whitespace-padded UUIDs")
        void whitespacePadded() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaim("company_store_id")).thenReturn(" " + id + " , " + id);

            Set<UUID> result = currentUserContext.getCompanyStoreIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should silently skip malformed UUIDs")
        void malformedUuids() {
            UUID validId = UUID.randomUUID();
            when(jwt.getClaim("company_store_id")).thenReturn("not-a-uuid," + validId);

            Set<UUID> result = currentUserContext.getCompanyStoreIds();

            assertThat(result).containsExactly(validId);
        }

        @Test
        @DisplayName("all-malformed returns empty set")
        void allMalformed() {
            when(jwt.getClaim("company_store_id")).thenReturn("not-a-uuid,also-bad");

            Set<UUID> result = currentUserContext.getCompanyStoreIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is missing")
        void missingClaim() {
            when(jwt.getClaim("company_store_id")).thenReturn(null);

            Set<UUID> result = currentUserContext.getCompanyStoreIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is blank")
        void blankClaim() {
            when(jwt.getClaim("company_store_id")).thenReturn("   ");

            Set<UUID> result = currentUserContext.getCompanyStoreIds();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("verifyCompanyStoreAccess")
    class VerifyCompanyStoreAccessTests {

        @SuppressWarnings("unchecked")
        private void mockAuthorities(Collection<GrantedAuthority> authorities) {
            when(authentication.getAuthorities()).thenReturn((Collection) authorities);
        }

        // ── Admin bypass ──

        @Test
        @DisplayName("admin can access any store")
        void adminCanAccessAnyStore() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-admin"));

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyStoreAccess(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── lc-company delegates to verifyCompanyAccess ──

        @Test
        @DisplayName("lc-company can access assigned company's stores")
        void lcCompanyCanAccessAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID companyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyStoreAccess(companyId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company cannot access non-assigned company's stores")
        void lcCompanyCannotAccessNonAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company"));
            UUID assignedId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyStoreAccess(otherId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── lc-company-country ──

        @Test
        @DisplayName("lc-company-country can access matching country's stores")
        void countryUserCanAccessMatchingCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyStoreAccess(companyId, countryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-country cannot access mismatched country's stores")
        void countryUserCannotAccessMismatchedCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-country"));
            UUID companyId = UUID.randomUUID();
            UUID assignedCountryId = UUID.randomUUID();
            UUID otherCountryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(assignedCountryId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyStoreAccess(companyId, otherCountryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── lc-company-region ──

        @Test
        @DisplayName("lc-company-region can access matching region's stores")
        void regionUserCanAccessMatchingRegion() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-region"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyStoreAccess(companyId, countryId, regionId, UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-region cannot access mismatched region's stores")
        void regionUserCannotAccessMismatchedRegion() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-region"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID assignedRegionId = UUID.randomUUID();
            UUID otherRegionId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(assignedRegionId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyStoreAccess(companyId, countryId, otherRegionId, UUID.randomUUID(), UUID.randomUUID()));
        }

        // ── lc-company-zone ──

        @Test
        @DisplayName("lc-company-zone can access matching zone's stores")
        void zoneUserCanAccessMatchingZone() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-zone"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            UUID zoneId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());
            when(jwt.getClaim("company_zone_id")).thenReturn(zoneId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyStoreAccess(companyId, countryId, regionId, zoneId, UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-zone cannot access mismatched zone's stores")
        void zoneUserCannotAccessMismatchedZone() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-zone"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            UUID assignedZoneId = UUID.randomUUID();
            UUID otherZoneId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());
            when(jwt.getClaim("company_zone_id")).thenReturn(assignedZoneId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyStoreAccess(companyId, countryId, regionId, otherZoneId, UUID.randomUUID()));
        }

        // ── lc-company-store ──

        @Test
        @DisplayName("lc-company-store can access assigned store with all hierarchy matching")
        void storeUserCanAccessMatchingStore() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-store"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            UUID zoneId = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());
            when(jwt.getClaim("company_zone_id")).thenReturn(zoneId.toString());
            when(jwt.getClaim("company_store_id")).thenReturn(storeId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyStoreAccess(companyId, countryId, regionId, zoneId, storeId));
        }

        @Test
        @DisplayName("lc-company-store cannot access mismatched store")
        void storeUserCannotAccessMismatchedStore() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-store"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            UUID zoneId = UUID.randomUUID();
            UUID assignedStoreId = UUID.randomUUID();
            UUID otherStoreId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());
            when(jwt.getClaim("company_zone_id")).thenReturn(zoneId.toString());
            when(jwt.getClaim("company_store_id")).thenReturn(assignedStoreId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyStoreAccess(companyId, countryId, regionId, zoneId, otherStoreId));
        }

        @Test
        @DisplayName("lc-company-store allows null storeId for create ops")
        void storeUserAllowsNullStoreId() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-store"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            UUID zoneId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());
            when(jwt.getClaim("company_zone_id")).thenReturn(zoneId.toString());

            assertDoesNotThrow(() ->
                    currentUserContext.verifyCompanyStoreAccess(companyId, countryId, regionId, zoneId, null));
        }

        @Test
        @DisplayName("lc-company-store cannot access mismatched company")
        void storeUserCannotAccessMismatchedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-store"));
            UUID assignedCompanyId = UUID.randomUUID();
            UUID otherCompanyId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(assignedCompanyId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyStoreAccess(otherCompanyId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-store cannot access mismatched country")
        void storeUserCannotAccessMismatchedCountry() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-store"));
            UUID companyId = UUID.randomUUID();
            UUID assignedCountryId = UUID.randomUUID();
            UUID otherCountryId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(assignedCountryId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyStoreAccess(companyId, otherCountryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        @DisplayName("lc-company-store cannot access mismatched zone")
        void storeUserCannotAccessMismatchedZone() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_lc-company-store"));
            UUID companyId = UUID.randomUUID();
            UUID countryId = UUID.randomUUID();
            UUID regionId = UUID.randomUUID();
            UUID assignedZoneId = UUID.randomUUID();
            UUID otherZoneId = UUID.randomUUID();
            when(jwt.getClaim("company_id")).thenReturn(companyId.toString());
            when(jwt.getClaim("company_country_id")).thenReturn(countryId.toString());
            when(jwt.getClaim("company_region_id")).thenReturn(regionId.toString());
            when(jwt.getClaim("company_zone_id")).thenReturn(assignedZoneId.toString());

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyStoreAccess(companyId, countryId, regionId, otherZoneId, UUID.randomUUID()));
        }

        // ── Unrecognized role ──

        @Test
        @DisplayName("no recognized role throws AccessDeniedException")
        void noRecognizedRoleThrows() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_other"));

            assertThrows(AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyStoreAccess(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }
    }
}

