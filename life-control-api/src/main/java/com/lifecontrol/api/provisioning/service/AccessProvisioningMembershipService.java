package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeStoreAssignmentRepository;
import com.lifecontrol.api.hr.service.EmployeeStatuses;
import com.lifecontrol.api.hr.service.EmployeeStoreScope;
import com.lifecontrol.api.hr.service.StoreScopeDerivation;
import com.lifecontrol.api.provisioning.exception.AccountNotLinkedException;
import com.lifecontrol.api.provisioning.exception.CompanyScopeInvariantException;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <b>membership projection</b> of the access-provisioning projection (unit W3): converge the five
 * top-level, multivalued {@code company_*} user attributes of an employee's Keycloak account from the
 * store-assignment derivation, and delete them when employment ends.
 *
 * <p>It is a capability with <b>no endpoint, no scheduler, no migration and no task write</b>: its
 * caller is the access-provisioning worker (W4). It <b>persists nothing</b> — no table, no migration
 * and no snapshot; writing the applied-membership snapshot is W4's job (records T32/T40). The five
 * {@code company_id} … {@code company_store_id} keys of {@link ScopeLevel} are the <b>only</b>
 * attributes this class owns: everything it did not write, it never touches (record T39).</p>
 *
 * <p>The projection <b>converges</b>: "the key present ⇔ the level is held". A derived set that is
 * non-empty is written whole through {@link IdentityProvider#updateUserAttribute(String, String, List)},
 * which replaces the list for that key; a level whose derived set is <b>empty</b> has its key
 * <b>deleted</b> instead of written empty, because {@code extractUuidSetFromClaim} reads an absent key
 * and an empty list identically, so the account is left in the honest state where the key's presence
 * means the level is held (T39). A retry is a no-op: the derivation is re-read and the five keys are
 * rewritten, so a partially applied run needs no compensation and nothing is reported as partial
 * (T37's rule).</p>
 *
 * <p>It <b>refuses by name rather than guessing</b>: an employee with no {@code keycloakUserId} throws
 * {@link AccountNotLinkedException} — the same refusal W2b makes, <b>reused</b> rather than duplicated
 * — and a derivation yielding anything other than exactly one {@code company_id} throws
 * {@link CompanyScopeInvariantException} <b>before</b> the first write, because this scope is an
 * authorization input and not a value to publish (T1/T39). A {@code Terminated} employee has all five
 * keys deleted and nothing written: employment ending removes the attributes instead of publishing a
 * scope derived from an ended employment (T6/T39). Nothing is swallowed: an
 * {@code IdentityProviderException} from any call propagates unchanged, exactly as
 * {@code AccessProvisioningRoleService} does.</p>
 *
 * <p>{@code @Transactional} is needed because {@code employee.getCompany()} is a <b>lazy</b>
 * association ({@code @ManyToOne(fetch = FetchType.LAZY)}): the company id is read inside the
 * transaction this annotation opens, so the derivation never runs against a detached employee.</p>
 */
@Service
public class AccessProvisioningMembershipService {

    /**
     * The scope an account holds once employment ends: five empty sets, because the five keys are
     * deleted and nothing is left to read at any level.
     */
    private static final EmployeeStoreScope EMPTY_SCOPE =
            new EmployeeStoreScope(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

    private final IdentityProvider identityProvider;
    private final EmployeeStoreAssignmentRepository assignmentRepository;

    public AccessProvisioningMembershipService(
            IdentityProvider identityProvider, EmployeeStoreAssignmentRepository assignmentRepository) {
        this.identityProvider = identityProvider;
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * Converges the account's membership attributes to the scope the employee holds today.
     *
     * <ol>
     *   <li><b>Linked account or refuse.</b> A {@code null} or blank {@code keycloakUserId} throws
     *       {@link AccountNotLinkedException} and nothing else happens — no repository call and no
     *       identity-provider call. It is the same refusal, and the same exception, W2b makes.</li>
     *   <li><b>Employment ended.</b> A {@code Terminated} employee ({@link EmployeeStatuses}) has all
     *       five keys deleted and nothing written, and the method returns an empty scope: the
     *       attributes are removed when employment ends, and the derivation is not even read.</li>
     *   <li><b>Derive.</b> {@link LocalDate#now()} is read once and passed both to
     *       {@code findEnabledAssignmentsCoveringDate} and to
     *       {@link StoreScopeDerivation#derive(UUID, java.util.Collection, LocalDate)}.</li>
     *   <li><b>Fail closed.</b> A derived {@code companyIds} whose size is not exactly one throws
     *       {@link CompanyScopeInvariantException} <b>before</b> the first write, so a scope this
     *       projection cannot justify is never published (T1/T39).</li>
     *   <li><b>Converge the five levels</b>, in {@link ScopeLevel}'s own hierarchy order: a non-empty
     *       set is written whole (the port replaces the list) in the derivation's own insertion order,
     *       and an empty one has its key deleted rather than written empty.</li>
     * </ol>
     *
     * @param employee the employee whose attributes converge; its company supplies the company-level
     *     fact
     * @return the scope the account holds after convergence: the derived scope, or five empty sets for
     *     a {@code Terminated} employee
     * @throws AccountNotLinkedException when the employee has no linked Keycloak account
     * @throws CompanyScopeInvariantException when the derivation yields anything other than exactly
     *     one {@code company_id}
     */
    @Transactional
    public EmployeeStoreScope convergeMembership(Employee employee) {
        var userId = employee.getKeycloakUserId();
        if (userId == null || userId.isBlank()) {
            throw new AccountNotLinkedException("Account not linked: employee " + employee.getId()
                    + " has no keycloakUserId, so its membership attributes have nowhere to go");
        }

        if (EmployeeStatuses.isTerminated(employee)) {
            for (var level : ScopeLevel.companyTo(ScopeLevel.STORE)) {
                identityProvider.deleteUserAttribute(userId, level.claim());
            }
            return EMPTY_SCOPE;
        }

        var today = LocalDate.now();
        var scope = StoreScopeDerivation.derive(
                employee.getCompany().getId(),
                assignmentRepository.findEnabledAssignmentsCoveringDate(employee.getId(), today),
                today);

        if (scope.companyIds().size() != 1) {
            throw new CompanyScopeInvariantException("Membership projection refused for employee " + employee.getId()
                    + ": the derivation produced " + scope.companyIds().size()
                    + " company ids and exactly one is required; nothing was written");
        }

        for (var level : ScopeLevel.companyTo(ScopeLevel.STORE)) {
            converge(userId, level, idsAt(scope, level));
        }

        return scope;
    }

    /**
     * The set of ids {@code scope} holds at {@code level}, so the claim key of each level is always
     * paired with its own set and the compiler must handle every level there is.
     */
    private Set<UUID> idsAt(EmployeeStoreScope scope, ScopeLevel level) {
        return switch (level) {
            case COMPANY -> scope.companyIds();
            case COUNTRY -> scope.companyCountryIds();
            case REGION -> scope.companyRegionIds();
            case ZONE -> scope.companyZoneIds();
            case STORE -> scope.companyStoreIds();
        };
    }

    /**
     * Writes the whole list of one level, or deletes the key when the level is not held.
     *
     * <p>The values keep the derivation's insertion order (the sets are {@code LinkedHashSet}s) and are
     * never sorted or rebuilt: the order the derivation published is the order the guard's claim parser
     * reads, and a reordered list would hide it. Exactly one call is made per level, and the port
     * replaces the list for that key.</p>
     */
    private void converge(String userId, ScopeLevel level, Set<UUID> ids) {
        if (ids.isEmpty()) {
            identityProvider.deleteUserAttribute(userId, level.claim());
            return;
        }
        identityProvider.updateUserAttribute(
                userId, level.claim(), ids.stream().map(UUID::toString).toList());
    }
}
