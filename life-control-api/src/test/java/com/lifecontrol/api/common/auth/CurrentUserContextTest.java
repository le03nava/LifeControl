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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

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
    @DisplayName("getCompanyIds")
    class GetCompanyIdsTests {

        @Test
        @DisplayName("should extract single UUID from company_id claim")
        void singleUuid() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaimAsString("company_id")).thenReturn(id.toString());

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should extract multiple comma-separated UUIDs")
        void multipleUuids() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(jwt.getClaimAsString("company_id")).thenReturn(id1 + "," + id2);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).containsExactlyInAnyOrder(id1, id2);
        }

        @Test
        @DisplayName("should deduplicate repeated UUIDs")
        void deduplicates() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaimAsString("company_id")).thenReturn(id + "," + id);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).hasSize(1);
            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should handle whitespace-padded UUIDs")
        void whitespacePadded() {
            UUID id = UUID.randomUUID();
            when(jwt.getClaimAsString("company_id")).thenReturn(" " + id + " , " + id);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).containsExactly(id);
        }

        @Test
        @DisplayName("should silently skip malformed UUIDs")
        void malformedUuids() {
            UUID validId = UUID.randomUUID();
            when(jwt.getClaimAsString("company_id")).thenReturn("not-a-uuid," + validId);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).containsExactly(validId);
        }

        @Test
        @DisplayName("all-malformed returns empty set")
        void allMalformed() {
            when(jwt.getClaimAsString("company_id")).thenReturn("not-a-uuid,also-bad");

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is missing")
        void missingClaim() {
            when(jwt.getClaimAsString("company_id")).thenReturn(null);

            Set<UUID> result = currentUserContext.getCompanyIds();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when claim is blank")
        void blankClaim() {
            when(jwt.getClaimAsString("company_id")).thenReturn("   ");

            Set<UUID> result = currentUserContext.getCompanyIds();

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
            when(jwt.getClaimAsString("company_id")).thenReturn(companyId.toString());

            currentUserContext.verifyCompanyAccess(companyId);
        }

        @Test
        @DisplayName("country-role cannot access non-assigned company")
        void countryRoleCannotAccessNonAssignedCompany() {
            mockAuthorities(List.of((GrantedAuthority) () -> "ROLE_life-control-country"));
            UUID assignedId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            when(jwt.getClaimAsString("company_id")).thenReturn(assignedId.toString());

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> currentUserContext.verifyCompanyAccess(otherId)
            );
        }
    }
}
