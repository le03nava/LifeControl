package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.dto.PositionRoleRequest;
import com.lifecontrol.api.hr.dto.PositionRolesRequest;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.PositionRoleRepository;
import com.lifecontrol.api.hr.service.PositionRoleService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-level verification of the full-set role-template save against real PostgreSQL, with
 * Flyway enabled and {@code ddl-auto=validate}.
 *
 * <p>The upsert semantics of record T20 are only real if the database agrees: this test proves that a
 * stored natural key is updated in place (same row id, no duplicate), that an omitted stored name is
 * disabled and still exists, and that the row count after a save is exactly what the model predicts.
 * It reuses the real repositories and service and mocks only the company-scope authorization and the
 * read-only client-role lookup, which are different concerns. A green run also proves the entity
 * mapping validates against V19.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Position role persistence integration tests")
class PositionRoleIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PositionRoleRepository roleRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private PositionRoleService positionRoleService;

    @Autowired
    private ApplicationClientProperties applicationClientProperties;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    @MockitoBean
    private IdentityProvider identityProvider;

    private UUID companyId;
    private UUID positionId;

    @BeforeEach
    void setUp() {
        var company = companyRepository.save(Company.builder()
                .companyKey("PR-" + UUID.randomUUID())
                .companyName("Position Role Company")
                .rfc(uniqueRfc())
                .enabled(true)
                .build());
        companyId = company.getId();

        var department = departmentRepository.save(Department.builder()
                .company(company)
                .departmentCode("OPS")
                .departmentName("Operations")
                .enabled(true)
                .build());

        var position = positionRepository.save(Position.builder()
                .department(department)
                .positionCode("OP1")
                .positionName("Operator")
                .enabled(true)
                .build());
        positionId = position.getId();
    }

    @Test
    @DisplayName("the application client id binds from application.properties")
    void applicationClientIdIsConfigured() {
        // If the property line were missing this would be null, and replaceRoles would call
        // listClientRoles(null) instead of failing loudly (record T18).
        assertThat(applicationClientProperties.clientId()).isNotBlank();
    }

    @Test
    @DisplayName("upserts a stored natural key in place without duplicating the row")
    void existingNaturalKeyUpdatesInPlace() {
        clientRolesAvailable("lc-position");

        var first = positionRoleService.replaceRoles(companyId, positionId, request("lc-position"));
        var firstId = first.get(0).id();

        var second = positionRoleService.replaceRoles(companyId, positionId, request("lc-position"));

        assertThat(second).hasSize(1);
        assertThat(second.get(0).id()).isEqualTo(firstId);
        assertThat(roleRepository.findByPositionIdOrderByRoleNameAsc(positionId))
                .hasSize(1);
    }

    @Test
    @DisplayName("disables an omitted stored name and keeps the row and its id, with the exact row count")
    void omittedKeyIsDisabledButStillExists() {
        clientRolesAvailable("lc-department", "lc-position");

        var first = positionRoleService.replaceRoles(
                companyId,
                positionId,
                new PositionRolesRequest(
                        List.of(new PositionRoleRequest("lc-department"), new PositionRoleRequest("lc-position"))));
        var positionRowId = first.stream()
                .filter(role -> role.roleName().equals("lc-position"))
                .findFirst()
                .orElseThrow()
                .id();

        positionRoleService.replaceRoles(companyId, positionId, request("lc-department"));

        var stored = roleRepository.findByPositionIdOrderByRoleNameAsc(positionId);
        assertThat(stored).hasSize(2);

        var positionRow = stored.stream()
                .filter(role -> role.getRoleName().equals("lc-position"))
                .findFirst()
                .orElseThrow();
        assertThat(positionRow.getId()).isEqualTo(positionRowId);
        assertThat(positionRow.getEnabled()).isFalse();

        // The read returns disabled rows too, so "explicitly cleared" stays distinguishable.
        var read = positionRoleService.getRoles(companyId, positionId);
        assertThat(read).hasSize(2);
        assertThat(read.stream()
                        .filter(role -> role.roleName().equals("lc-position"))
                        .findFirst()
                        .orElseThrow()
                        .enabled())
                .isFalse();
    }

    @Test
    @DisplayName("re-enables a stored disabled name the request resubmits, on the same row")
    void omittedKeyReenablesOnResubmit() {
        clientRolesAvailable("lc-department", "lc-position");

        positionRoleService.replaceRoles(
                companyId,
                positionId,
                new PositionRolesRequest(
                        List.of(new PositionRoleRequest("lc-department"), new PositionRoleRequest("lc-position"))));
        var disabled = positionRoleService.replaceRoles(companyId, positionId, request("lc-department"));
        var positionRowId = disabled.stream()
                .filter(role -> role.roleName().equals("lc-position"))
                .findFirst()
                .orElseThrow()
                .id();

        var reenabled = positionRoleService.replaceRoles(
                companyId,
                positionId,
                new PositionRolesRequest(
                        List.of(new PositionRoleRequest("lc-department"), new PositionRoleRequest("lc-position"))));

        assertThat(reenabled).hasSize(2);
        var positionRow = reenabled.stream()
                .filter(role -> role.roleName().equals("lc-position"))
                .findFirst()
                .orElseThrow();
        assertThat(positionRow.id()).isEqualTo(positionRowId);
        assertThat(positionRow.enabled()).isTrue();
        assertThat(roleRepository.findByPositionIdOrderByRoleNameAsc(positionId))
                .hasSize(2);
    }

    @Test
    @DisplayName("a role outside the frozen allowlist is rejected and writes nothing")
    void roleOutsideAllowlistWritesNothing() {
        assertThatThrownBy(() -> positionRoleService.replaceRoles(companyId, positionId, request("lc-admin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a grantable position role");

        assertThat(roleRepository.findByPositionIdOrderByRoleNameAsc(positionId))
                .isEmpty();
    }

    @Test
    @DisplayName("an allowlisted name that is not a client role of the application client writes nothing")
    void unknownClientRoleWritesNothing() {
        clientRolesAvailable("lc-company");

        assertThatThrownBy(() -> positionRoleService.replaceRoles(companyId, positionId, request("lc-position")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a client role of the application client");

        assertThat(roleRepository.findByPositionIdOrderByRoleNameAsc(positionId))
                .isEmpty();
    }

    private void clientRolesAvailable(String... names) {
        when(identityProvider.listClientRoles(applicationClientProperties.clientId()))
                .thenReturn(Arrays.stream(names)
                        .map(name -> new RoleDto(
                                name, null, false, RoleScope.CLIENT, applicationClientProperties.clientId()))
                        .toList());
    }

    private PositionRolesRequest request(String... roleNames) {
        return new PositionRolesRequest(
                Arrays.stream(roleNames).map(PositionRoleRequest::new).toList());
    }

    /** {@code companies.rfc} is a unique 13-char column, so each fixture needs its own value. */
    private String uniqueRfc() {
        return ("PRLF" + UUID.randomUUID().toString().replace("-", "").substring(0, 9)).toUpperCase();
    }
}
