package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.dto.PositionRoleResponse;
import com.lifecontrol.api.hr.dto.PositionRolesRequest;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.PositionRole;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.PositionRoleRepository;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the role template of a company-scoped position.
 *
 * <p>Every public method starts by resolving the company through {@link #resolveCompany(UUID)}, whose
 * first statement is {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the company-scope
 * contract (record E12). The position is then resolved through a {@code companyId}-scoped query, so a
 * position of another company is a {@link PositionNotFoundException} (404), exactly as
 * {@code PositionSalaryBandService} does.</p>
 *
 * <p>The save is a <b>full-set upsert</b> on the natural key {@code (position_id, role_name)} (record
 * T20): the request is the position's complete set of roles, a submitted name is inserted when
 * missing and updated in place when present, and a stored name the request omits is <b>disabled</b>,
 * never deleted. There is no DELETE endpoint for this resource: the only way to clear a role is the
 * omission rule, and it keeps the row so the read can distinguish "explicitly cleared" from "never
 * configured".</p>
 *
 * <p>A submitted {@code roleName} is rejected as a 400 through {@link IllegalArgumentException} on two
 * grounds: it is not in the frozen allowlist {@link GrantablePositionRoles} (decision D7), or it is not
 * a real client role of the configured application client (record T18, E15) — the latter checked with
 * a single read-only {@link IdentityProvider#listClientRoles(String)} call. A {@code roleName}
 * repeated inside one request is a 400 too, rejected explicitly instead of last-wins. This service
 * never writes to the identity provider: the template is a provisioning <b>seed</b>, not a live
 * authority (decision D6).</p>
 *
 * <p>The reads are deliberately <b>not</b> cached (record T16): a {@code @Cacheable} would serve a
 * cache hit without ever running {@link CurrentUserContext#verifyCompanyAccess(UUID)}, trading the
 * scope check for a lookup. A proxy-based test pins that.</p>
 */
@Service
public class PositionRoleService {

    private static final Logger logger = LoggerFactory.getLogger(PositionRoleService.class);

    private final PositionRoleRepository roleRepository;
    private final PositionRepository positionRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;
    private final IdentityProvider identityProvider;
    private final ApplicationClientProperties applicationClientProperties;

    public PositionRoleService(
            PositionRoleRepository roleRepository,
            PositionRepository positionRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext,
            IdentityProvider identityProvider,
            ApplicationClientProperties applicationClientProperties) {
        this.roleRepository = roleRepository;
        this.positionRepository = positionRepository;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
        this.identityProvider = identityProvider;
        this.applicationClientProperties = applicationClientProperties;
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company
     * is denied before any row is loaded (record E12).</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     */
    private Company resolveCompany(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);
        return companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    /**
     * Resolves the position scoped to the company of the path.
     *
     * <p>The lookup is company-scoped, so a position that exists but belongs to another company is
     * reported as not found instead of being accepted.</p>
     *
     * @throws PositionNotFoundException when the position does not belong to the company
     */
    private Position resolvePosition(UUID companyId, UUID positionId) {
        return positionRepository
                .findByDepartmentCompanyIdAndId(companyId, positionId)
                .orElseThrow(() -> new PositionNotFoundException(positionId));
    }

    /**
     * Returns every stored role of the position, disabled rows included, ordered by role name.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     * @throws PositionNotFoundException                               when the position does not belong to the company
     */
    @Transactional(readOnly = true)
    public List<PositionRoleResponse> getRoles(UUID companyId, UUID positionId) {
        resolveCompany(companyId);
        resolvePosition(companyId, positionId);

        return roleRepository.findByPositionIdOrderByRoleNameAsc(positionId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Replaces the position's complete set of roles with the request's set.
     *
     * <p>Every submitted name is upserted on {@code (position_id, role_name)}; every stored name the
     * request omits is disabled and kept. The whole save runs in one transaction.</p>
     *
     * @return the complete stored set after the save, disabled rows included
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     * @throws PositionNotFoundException                               when the position does not belong to the company
     * @throws IllegalArgumentException                                when a name is duplicated, is not grantable or is not a
     *     client role of the application client (400)
     */
    @Transactional
    public List<PositionRoleResponse> replaceRoles(UUID companyId, UUID positionId, PositionRolesRequest request) {
        resolveCompany(companyId);
        var position = resolvePosition(companyId, positionId);

        // The full stored set is the source for the omission rule; the result map is seeded with it so
        // the response carries disabled rows too and matches the read ordering.
        var stored = roleRepository.findByPositionIdOrderByRoleNameAsc(positionId);
        var result = new LinkedHashMap<String, PositionRole>();
        stored.forEach(role -> result.put(role.getRoleName(), role));

        // Shape validation first, so a bad request is rejected before any write and before the remote
        // client-role lookup: duplicated names and names the frozen allowlist refuses (D7).
        var submittedNames = new LinkedHashSet<String>();
        for (var item : request.roles()) {
            if (!submittedNames.add(item.roleName())) {
                throw new IllegalArgumentException("Duplicate roleName in position roles: " + item.roleName());
            }
            if (!GrantablePositionRoles.includes(item.roleName())) {
                throw new IllegalArgumentException("roleName is not a grantable position role: " + item.roleName());
            }
        }

        // Every submitted name must be a real client role of the application client (T18, E15). This
        // is a single read-only lookup, done before any write so a bad name can never leave a partial
        // save behind. No write to the identity provider happens here (D6).
        if (!submittedNames.isEmpty()) {
            var clientRoleNames = identityProvider.listClientRoles(applicationClientProperties.clientId()).stream()
                    .map(role -> role.name())
                    .collect(Collectors.toSet());
            for (var roleName : submittedNames) {
                if (!clientRoleNames.contains(roleName)) {
                    throw new IllegalArgumentException(
                            "roleName is not a client role of the application client: " + roleName);
                }
            }
        }

        for (var roleName : submittedNames) {
            // Upsert on the natural key: present -> update in place (same row), absent -> insert.
            var role = roleRepository
                    .findByPositionIdAndRoleName(positionId, roleName)
                    .orElseGet(() -> PositionRole.builder()
                            .position(position)
                            .roleName(roleName)
                            .enabled(true)
                            .build());

            role.setEnabled(true);

            result.put(roleName, roleRepository.save(role));
        }

        // Omission means "cleared": disable, never delete. The row stays for the read contract.
        var disabled = 0;
        for (var role : stored) {
            if (!submittedNames.contains(role.getRoleName())) {
                role.setEnabled(false);
                roleRepository.save(role);
                disabled++;
            }
        }

        logger.info(
                "Position roles replaced: positionId={}, companyId={}, submitted={}, disabled={}",
                positionId,
                companyId,
                submittedNames.size(),
                disabled);

        return result.values().stream()
                .sorted(Comparator.comparing(PositionRole::getRoleName))
                .map(this::toResponse)
                .toList();
    }

    private PositionRoleResponse toResponse(PositionRole role) {
        return new PositionRoleResponse(
                role.getId(),
                role.getPosition().getId(),
                role.getRoleName(),
                role.getEnabled(),
                role.getCreatedAt(),
                role.getUpdatedAt());
    }
}
