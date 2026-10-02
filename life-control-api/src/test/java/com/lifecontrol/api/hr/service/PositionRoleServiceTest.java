package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.dto.PositionRoleRequest;
import com.lifecontrol.api.hr.dto.PositionRolesRequest;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.PositionRole;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.PositionRoleRepository;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PositionRoleService Tests")
class PositionRoleServiceTest {

    private static final String APPLICATION_CLIENT_ID = "life-control-client";

    @Mock
    private PositionRoleRepository roleRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private IdentityProvider identityProvider;

    private PositionRoleService positionRoleService;

    private UUID companyId;
    private UUID departmentId;
    private UUID positionId;
    private Company testCompany;
    private Position testPosition;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        positionId = UUID.randomUUID();

        testCompany = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();
        testPosition = Position.builder()
                .id(positionId)
                .department(Department.builder()
                        .id(departmentId)
                        .company(testCompany)
                        .departmentCode("OPS")
                        .departmentName("Operations")
                        .enabled(true)
                        .build())
                .positionCode("OP1")
                .positionName("Operator")
                .enabled(true)
                .build();

        positionRoleService = new PositionRoleService(
                roleRepository,
                positionRepository,
                companyRepository,
                currentUserContext,
                identityProvider,
                new ApplicationClientProperties(APPLICATION_CLIENT_ID));
    }

    private PositionRole stored(UUID id, String roleName, boolean enabled) {
        return PositionRole.builder()
                .id(id)
                .position(testPosition)
                .roleName(roleName)
                .enabled(enabled)
                .build();
    }

    private PositionRoleRequest item(String roleName) {
        return new PositionRoleRequest(roleName);
    }

    private PositionRolesRequest request(String... roleNames) {
        return new PositionRolesRequest(Arrays.stream(roleNames).map(this::item).toList());
    }

    private void companyExists() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
    }

    private void positionExists() {
        when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                .thenReturn(Optional.of(testPosition));
    }

    private void storedRoles(PositionRole... roles) {
        when(roleRepository.findByPositionIdOrderByRoleNameAsc(positionId)).thenReturn(List.of(roles));
    }

    private void clientRoles(String... names) {
        when(identityProvider.listClientRoles(APPLICATION_CLIENT_ID))
                .thenReturn(Arrays.stream(names)
                        .map(name -> new RoleDto(name, null, false, RoleScope.CLIENT, APPLICATION_CLIENT_ID))
                        .toList());
    }

    /**
     * Pins the whole {@code delete*} family inherited from {@link org.springframework.data.jpa.repository.JpaRepository},
     * not just the three overloads a naive implementation would reach for: this resource may never
     * delete a role, an omitted one is disabled instead (record T20).
     */
    private void verifyNoRoleDeletes() {
        verify(roleRepository, never()).delete(any(PositionRole.class));
        verify(roleRepository, never()).deleteById(any(UUID.class));
        verify(roleRepository, never()).deleteAll();
        verify(roleRepository, never()).deleteAll(anyList());
        verify(roleRepository, never()).deleteAllById(anyList());
        verify(roleRepository, never()).deleteAllInBatch();
        verify(roleRepository, never()).deleteAllInBatch(anyList());
        verify(roleRepository, never()).deleteAllByIdInBatch(anyList());
        verify(roleRepository, never()).deleteInBatch(anyList());
    }

    @Nested
    @DisplayName("company scope contract")
    class CompanyScopeContractTests {

        @Test
        @DisplayName("getRoles verifies company access before loading the company")
        void getRoles_VerifiesCompanyAccessFirst() {
            companyExists();
            positionExists();
            storedRoles();

            positionRoleService.getRoles(companyId, positionId);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("replaceRoles verifies company access before loading the company")
        void replaceRoles_VerifiesCompanyAccessFirst() {
            companyExists();
            positionExists();
            storedRoles();
            clientRoles("lc-position");
            when(roleRepository.findByPositionIdAndRoleName(positionId, "lc-position"))
                    .thenReturn(Optional.empty());
            when(roleRepository.save(any(PositionRole.class))).thenAnswer(inv -> inv.getArgument(0));

            positionRoleService.replaceRoles(companyId, positionId, request("lc-position"));

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("throws CompanyNotFoundException and still checks access when the company does not exist")
        void getRoles_CompanyMissing_ThrowsException() {
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionRoleService.getRoles(companyId, positionId))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
            verify(currentUserContext).verifyCompanyAccess(companyId);
        }
    }

    @Nested
    @DisplayName("getRoles")
    class GetRolesTests {

        @Test
        @DisplayName("returns every stored role including disabled ones")
        void getRoles_ReturnsDisabledRowsToo() {
            var enabledId = UUID.randomUUID();
            var disabledId = UUID.randomUUID();
            companyExists();
            positionExists();
            storedRoles(stored(enabledId, "lc-department", true), stored(disabledId, "lc-position", false));

            var result = positionRoleService.getRoles(companyId, positionId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(r -> r.roleName()).containsExactly("lc-department", "lc-position");
            assertThat(result.get(0).enabled()).isTrue();
            assertThat(result.get(1).enabled()).isFalse();
            verify(roleRepository).findByPositionIdOrderByRoleNameAsc(positionId);
        }

        @Test
        @DisplayName("returns an empty list when the position has no roles")
        void getRoles_None_ReturnsEmpty() {
            companyExists();
            positionExists();
            storedRoles();

            assertThat(positionRoleService.getRoles(companyId, positionId)).isEmpty();
        }

        @Test
        @DisplayName("throws PositionNotFoundException for a position of another company")
        void getRoles_OtherCompanyPosition_IsNotFound() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionRoleService.getRoles(companyId, positionId))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
            verifyNoInteractions(roleRepository);
        }
    }

    @Nested
    @DisplayName("replaceRoles")
    class ReplaceRolesTests {

        @Test
        @DisplayName("inserts a new role when the natural key is not stored")
        void replaceRoles_NewKey_Inserts() {
            var newId = UUID.randomUUID();
            companyExists();
            positionExists();
            storedRoles();
            clientRoles("lc-position");
            when(roleRepository.findByPositionIdAndRoleName(positionId, "lc-position"))
                    .thenReturn(Optional.empty());
            when(roleRepository.save(any(PositionRole.class))).thenAnswer(inv -> {
                PositionRole role = inv.getArgument(0);
                role.setId(newId);
                return role;
            });

            var result = positionRoleService.replaceRoles(companyId, positionId, request("lc-position"));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(newId);
            assertThat(result.get(0).positionId()).isEqualTo(positionId);
            assertThat(result.get(0).roleName()).isEqualTo("lc-position");
            assertThat(result.get(0).enabled()).isTrue();
            verify(identityProvider).listClientRoles(APPLICATION_CLIENT_ID);
            verify(roleRepository).save(any(PositionRole.class));
        }

        @Test
        @DisplayName("updates the stored row in place when the natural key already exists")
        void replaceRoles_ExistingKey_UpdatesInPlace() {
            var roleId = UUID.randomUUID();
            var existing = stored(roleId, "lc-department", true);
            companyExists();
            positionExists();
            storedRoles(existing);
            clientRoles("lc-department");
            when(roleRepository.findByPositionIdAndRoleName(positionId, "lc-department"))
                    .thenReturn(Optional.of(existing));
            when(roleRepository.save(any(PositionRole.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = positionRoleService.replaceRoles(companyId, positionId, request("lc-department"));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(roleId);
            verify(roleRepository).save(existing);
        }

        @Test
        @DisplayName("re-enables a stored disabled role that the request resubmits")
        void replaceRoles_ResubmittedDisabledKey_Reenables() {
            var roleId = UUID.randomUUID();
            var disabled = stored(roleId, "lc-position", false);
            companyExists();
            positionExists();
            storedRoles(disabled);
            clientRoles("lc-position");
            when(roleRepository.findByPositionIdAndRoleName(positionId, "lc-position"))
                    .thenReturn(Optional.of(disabled));
            when(roleRepository.save(any(PositionRole.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = positionRoleService.replaceRoles(companyId, positionId, request("lc-position"));

            assertThat(disabled.getEnabled()).isTrue();
            assertThat(result.get(0).enabled()).isTrue();
        }

        @Test
        @DisplayName("disables a stored role the request omits and keeps the row")
        void replaceRoles_OmittedKey_DisablesButKeepsRow() {
            var department = stored(UUID.randomUUID(), "lc-department", true);
            var position = stored(UUID.randomUUID(), "lc-position", true);
            companyExists();
            positionExists();
            storedRoles(department, position);
            clientRoles("lc-department");
            when(roleRepository.findByPositionIdAndRoleName(positionId, "lc-department"))
                    .thenReturn(Optional.of(department));
            when(roleRepository.save(any(PositionRole.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = positionRoleService.replaceRoles(companyId, positionId, request("lc-department"));

            assertThat(department.getEnabled()).isTrue();
            assertThat(position.getEnabled()).isFalse();
            assertThat(result).hasSize(2);
            assertThat(result).extracting(r -> r.enabled()).containsExactly(true, false);
            verify(roleRepository).save(position);
            verifyNoRoleDeletes();
        }

        @Test
        @DisplayName("an empty request disables every stored role without deleting a single row")
        void replaceRoles_EmptyRequest_DisablesAllNeverDeletes() {
            var existing = stored(UUID.randomUUID(), "lc-department", true);
            companyExists();
            positionExists();
            storedRoles(existing);
            when(roleRepository.save(any(PositionRole.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = positionRoleService.replaceRoles(companyId, positionId, new PositionRolesRequest(List.of()));

            assertThat(existing.getEnabled()).isFalse();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).enabled()).isFalse();
            verify(roleRepository).save(existing);
            verifyNoRoleDeletes();
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("an empty request with no stored roles saves nothing")
        void replaceRoles_EmptyRequestNothingStored_SavesNothing() {
            companyExists();
            positionExists();
            storedRoles();

            var result = positionRoleService.replaceRoles(companyId, positionId, new PositionRolesRequest(List.of()));

            assertThat(result).isEmpty();
            verify(roleRepository, never()).save(any());
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("throws PositionNotFoundException for a position of another company before any role work")
        void replaceRoles_OtherCompanyPosition_IsNotFound() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionRoleService.replaceRoles(companyId, positionId, request("lc-position")))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
            verifyNoInteractions(roleRepository, identityProvider);
        }

        @Test
        @DisplayName("rejects a role outside the frozen allowlist as a 400 without calling the identity provider")
        void replaceRoles_RoleOutsideAllowlist_IsBadRequest() {
            companyExists();
            positionExists();
            storedRoles();

            assertThatThrownBy(() -> positionRoleService.replaceRoles(companyId, positionId, request("lc-admin")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a grantable position role");

            verifyNoInteractions(identityProvider);
            verify(roleRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects an allowlisted role that is not a client role of the application client as a 400")
        void replaceRoles_AllowlistedButUnknownClientRole_IsBadRequest() {
            companyExists();
            positionExists();
            storedRoles();
            clientRoles("lc-company");

            assertThatThrownBy(() -> positionRoleService.replaceRoles(companyId, positionId, request("lc-position")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a client role of the application client");

            verify(identityProvider).listClientRoles(APPLICATION_CLIENT_ID);
            verify(roleRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects an unknown client role in a later item before writing the earlier valid item")
        void replaceRoles_UnknownClientRoleLater_RejectsBeforeAnyWrite() {
            companyExists();
            positionExists();
            storedRoles();
            clientRoles("lc-department");

            assertThatThrownBy(() -> positionRoleService.replaceRoles(
                            companyId, positionId, request("lc-department", "lc-position")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a client role of the application client");

            // Every name is validated before the write loop, so the valid first item is never saved.
            verify(roleRepository, never()).save(any(PositionRole.class));
            verify(roleRepository, never()).findByPositionIdAndRoleName(any(), any());
        }

        @Test
        @DisplayName("throws IllegalArgumentException when one request repeats a role name")
        void replaceRoles_DuplicateRoleName_IsBadRequest() {
            companyExists();
            positionExists();
            storedRoles();

            assertThatThrownBy(() -> positionRoleService.replaceRoles(
                            companyId, positionId, request("lc-position", "lc-position")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate roleName");
            verifyNoInteractions(identityProvider);
            verify(roleRepository, never()).save(any());
        }
    }
}
