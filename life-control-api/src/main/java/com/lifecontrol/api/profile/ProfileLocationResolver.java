package com.lifecontrol.api.profile;

import com.lifecontrol.api.profile.dto.ProfileResponse;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.service.CompanyStoreService;
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
 * <p><b>Every failure to derive is a {@code null}, never an error</b> (S2): the store may have been
 * deleted, or the caller may have lost access to it, and a profile read that answered 404 or 500 for
 * that would turn a degraded preselect into a broken screen. The three cases S2 names — no
 * preference, a stored store no assignment covers (resolved to {@code null} before it reaches this
 * class), and an unreadable store — all end as {@code location: null} with the rest of the profile
 * intact.</p>
 *
 * <p><b>It runs with the caller's transaction suspended, on purpose.</b> That is not a stylistic
 * choice, it is what makes the catch above work: a {@link RuntimeException} crossing a
 * <em>participating</em> {@code @Transactional} boundary marks the surrounding transaction
 * rollback-only (Spring's documented behaviour for propagations that join an existing transaction), so
 * catching {@link CompanyStoreNotFoundException} inside the caller's transaction would leave the
 * profile to die at commit with an {@code UnexpectedRollbackException} — a 500 on the very read S2
 * requires to succeed, and one that no unit test of the caller can see. Suspending the caller's
 * transaction lets the store read own its own, so a denial rolls back only that one and the profile
 * read returns its normal payload.</p>
 */
@Component
public class ProfileLocationResolver {

    private static final Logger log = LoggerFactory.getLogger(ProfileLocationResolver.class);

    private final CompanyStoreService companyStoreService;

    public ProfileLocationResolver(CompanyStoreService companyStoreService) {
        this.companyStoreService = companyStoreService;
    }

    /**
     * The chain of the given resolved store preference, or {@code null} when there is nothing to
     * derive (no preference) or when the store can no longer be read by this caller.
     *
     * @param resolvedStoreId the value {@code resolveStorePreference} returned, never the stored column
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProfileResponse.LocationChain resolve(UUID resolvedStoreId) {
        if (resolvedStoreId == null) {
            return null;
        }
        try {
            var store = companyStoreService.getStoreById(resolvedStoreId);
            return new ProfileResponse.LocationChain(
                    store.companyId(),
                    store.companyCountryId(),
                    store.regionId(),
                    store.zoneId(),
                    store.id(),
                    store.storeName());
        } catch (CompanyStoreNotFoundException ex) {
            // Deleted, or out of the caller's reach: the store read reports both as not found, and both
            // degrade to "no preselection" instead of failing the profile.
            log.warn("Store preference {} is no longer readable; reporting no location", resolvedStoreId);
            return null;
        } catch (AccessDeniedException ex) {
            // Defensive: getStoreById already converts a denial into not-found, so this only fires if a
            // future read stops doing it. The profile must degrade here too, not 403.
            log.warn("Store preference {} is not accessible by this caller; reporting no location", resolvedStoreId);
            return null;
        }
    }
}
