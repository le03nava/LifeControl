package com.lifecontrol.api.profile;

import com.lifecontrol.api.profile.dto.ProfileResponse;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.service.CompanyStoreService;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives the profile's {@code location} chain from the <b>resolved</b> store preference.
 *
 * <p>It exists so that the two readers of the profile — {@code getProfile} and the response
 * {@code updateProfile} builds — derive the chain in <b>one</b> place, reusing the authorized read
 * {@code GET /api/stores/{storeId}} uses ({@link CompanyStoreService#getStoreById(UUID)}: load by id,
 * walk the store's own JPA associations, verify access, report a denial as not-found) instead of
 * re-deriving the chain from anything the caller sent. Nothing is duplicated and no new read is
 * introduced (S1).</p>
 *
 * <p><b>The chain is read under each claimed company</b> (S1/S4 of
 * {@code odd/tasks/profile-tenant-scope.md}). {@code /api/profile} is a tenant-agnostic path, so the
 * request carries no company and {@code company_stores} — armed with {@code ENABLE} + {@code FORCE} row
 * level security on {@code company_id} — answered zero rows, which degraded every read to
 * {@code location: null}. The store read therefore goes through
 * {@link ProfileTenantReads#underEachClaimedCompany(java.util.function.Supplier)}, which states one
 * claimed company per attempt: the chain resolves only through a company the token actually claims (no
 * widening), and a token claiming several still resolves whichever of them owns the store (S5).</p>
 *
 * <p><b>Every failure to derive is a {@code null}, never an error</b> (S2): the store may have been
 * deleted, or the caller may have lost access to it, and a profile read that answered 404 or 500 for
 * that would turn a degraded preselect into a broken screen. The three cases S2 names — no
 * preference, a stored store no assignment covers (resolved to {@code null} before it reaches this
 * class), and an unreadable store — all end as {@code location: null} with the rest of the profile
 * intact. Each failed attempt is silent on purpose: with a multi-company token, one attempt failing is
 * the expected answer for the companies that do not own the store, and only "no attempt resolved it" is
 * worth a line — which is the single {@code WARN} below, kept verbatim because operators grep it.</p>
 *
 * <p><b>It runs with the caller's transaction suspended, on purpose.</b> That is not a stylistic
 * choice, it is what makes the catch above work: a {@link RuntimeException} crossing a
 * <em>participating</em> {@code @Transactional} boundary marks the surrounding transaction
 * rollback-only (Spring's documented behaviour for propagations that join an existing transaction), so
 * catching {@link CompanyStoreNotFoundException} inside the caller's transaction would leave the
 * profile to die at commit with an {@code UnexpectedRollbackException} — a 500 on the very read S2
 * requires to succeed, and one that no unit test of the caller can see. Suspending the caller's
 * transaction lets the store read own its own, so a denial rolls back only that one and the profile
 * read returns its normal payload. The suspension is unchanged by the scoped read: every attempt under
 * a claimed company is its own transaction, stated with that company.</p>
 */
@Component
public class ProfileLocationResolver {

    private static final Logger log = LoggerFactory.getLogger(ProfileLocationResolver.class);

    private final CompanyStoreService companyStoreService;
    private final ProfileTenantReads profileTenantReads;

    public ProfileLocationResolver(CompanyStoreService companyStoreService, ProfileTenantReads profileTenantReads) {
        this.companyStoreService = companyStoreService;
        this.profileTenantReads = profileTenantReads;
    }

    /**
     * The chain of the given resolved store preference, or {@code null} when there is nothing to
     * derive (no preference) or when the store can no longer be read by this caller under any company it
     * claims.
     *
     * @param resolvedStoreId the value {@code resolveStorePreference} returned, never the stored column
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProfileResponse.LocationChain resolve(UUID resolvedStoreId) {
        if (resolvedStoreId == null) {
            return null;
        }
        var chain = profileTenantReads.underEachClaimedCompany(() -> tryStore(resolvedStoreId));
        if (chain.isPresent()) {
            return chain.get();
        }
        log.warn("Store preference {} is no longer readable; reporting no location", resolvedStoreId);
        return null;
    }

    /**
     * One attempt under one claimed company: the store's own chain, or empty when this company cannot read
     * it.
     *
     * <p>Neither catch logs, deliberately. Under a multi-company token a miss is the normal answer for
     * every company that does not own the store, so logging per attempt would turn one degraded preselect
     * into a burst of warnings; the single line emitted by {@link #resolve(UUID)} is the one an operator
     * looks for.</p>
     */
    private Optional<ProfileResponse.LocationChain> tryStore(UUID storeId) {
        try {
            var store = companyStoreService.getStoreById(storeId);
            return Optional.of(new ProfileResponse.LocationChain(
                    store.companyId(),
                    store.companyCountryId(),
                    store.regionId(),
                    store.zoneId(),
                    store.id(),
                    store.storeName()));
        } catch (CompanyStoreNotFoundException ex) {
            // Deleted, or out of this company's reach: the store read reports both as not found, and both
            // degrade to "no preselection" instead of failing the profile.
            return Optional.empty();
        } catch (AccessDeniedException ex) {
            // Defensive: getStoreById already converts a denial into not-found, so this only fires if a
            // future read stops doing it. The profile must degrade here too, not 403.
            return Optional.empty();
        }
    }
}
