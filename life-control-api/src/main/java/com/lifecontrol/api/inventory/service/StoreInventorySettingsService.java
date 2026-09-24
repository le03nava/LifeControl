package com.lifecontrol.api.inventory.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsRequest;
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsResponse;
import com.lifecontrol.api.inventory.dto.StoreLocationSummaryResponse;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.exception.StoreLocationNotInStoreException;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the per-store inventory settings: which store location receives goods and which
 * one sales will later deduct from.
 *
 * <p>Every operation resolves — and authorizes — the full company &rarr; country &rarr; region
 * &rarr; zone &rarr; store path through {@link CurrentUserContext#verifyCompanyStoreAccess} before
 * touching the settings, mirroring {@code StoreLocationService.resolveStore}.</p>
 *
 * <p>The two location columns in {@code store_inventory_settings} are plain foreign keys, so they
 * only prove that the location exists. "This location belongs to this store" follows from the
 * {@code NOT NULL} foreign-key chain {@code store_locations -> store_zones -> store_areas ->
 * company_stores}, and is enforced here, before any write, by
 * {@link #assertLocationBelongsToStore(UUID, UUID)}.</p>
 *
 * <p>Deliberately out of scope: whether a configured location is {@code enabled}. A disabled
 * location is currently accepted as receiving or sales location; that rule is left for a later
 * slice (W2c).</p>
 */
@Service
public class StoreInventorySettingsService {

    private static final Logger logger = LoggerFactory.getLogger(StoreInventorySettingsService.class);

    /**
     * Message of the 409 raised when the request's optional version precondition does not hold. It
     * names no entity, SQL or version number: the client only needs to know the settings moved.
     */
    private static final String VERSION_CONFLICT_MESSAGE =
            "The store inventory settings conflict with the current server state; reload and try again";

    private final StoreInventorySettingsRepository storeInventorySettingsRepository;
    private final StoreLocationRepository storeLocationRepository;
    private final StoreAreaRepository storeAreaRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyZoneRepository companyZoneRepository;
    private final CompanyRegionRepository companyRegionRepository;
    private final CompanyCountryRepository companyCountryRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public StoreInventorySettingsService(
            StoreInventorySettingsRepository storeInventorySettingsRepository,
            StoreLocationRepository storeLocationRepository,
            StoreAreaRepository storeAreaRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyZoneRepository companyZoneRepository,
            CompanyRegionRepository companyRegionRepository,
            CompanyCountryRepository companyCountryRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.storeInventorySettingsRepository = storeInventorySettingsRepository;
        this.storeLocationRepository = storeLocationRepository;
        this.storeAreaRepository = storeAreaRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyZoneRepository = companyZoneRepository;
        this.companyRegionRepository = companyRegionRepository;
        this.companyCountryRepository = companyCountryRepository;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Verifies access and resolves the store identified by the full nested path, mirroring
     * {@code StoreLocationService.resolveStore}.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyNotFoundException                                when the company does not exist
     * @throws CompanyCountryNotFoundException                         when the company-country does not belong to the company
     * @throws CompanyRegionNotFoundException                          when the region does not belong to the company-country
     * @throws CompanyZoneNotFoundException                            when the zone does not belong to the region
     * @throws CompanyStoreNotFoundException                           when the store does not belong to the zone
     */
    private CompanyStore resolveStore(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {
        currentUserContext.verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);

        companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndId(companyId, companyCountryId)
                .orElseThrow(() -> new CompanyCountryNotFoundException(companyCountryId));

        var region = companyRegionRepository
                .findByIdAndCompanyCountryId(regionId, companyCountry.getId())
                .orElseThrow(() -> new CompanyRegionNotFoundException("Company region not found with id: " + regionId));

        var zone = companyZoneRepository
                .findByIdAndCompanyRegionId(zoneId, region.getId())
                .orElseThrow(() -> new CompanyZoneNotFoundException("Company zone not found with id: " + zoneId));

        return companyStoreRepository
                .findByIdAndCompanyZoneId(storeId, zone.getId())
                .orElseThrow(() -> new CompanyStoreNotFoundException(storeId));
    }

    /**
     * Reads the settings of a store.
     *
     * @throws StoreInventorySettingsNotFoundException when the store has never been configured; the
     *     client treats that 404 as "not configured yet"
     */
    @Transactional(readOnly = true)
    public StoreInventorySettingsResponse getSettings(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        var settings = storeInventorySettingsRepository
                .findById(store.getId())
                .orElseThrow(() -> new StoreInventorySettingsNotFoundException(store.getId()));

        return toResponse(settings);
    }

    /**
     * Creates the store's settings when absent, or updates them in place when already configured.
     *
     * <p>Both locations are validated against the store <b>before</b> anything is written, so a
     * rejected request leaves no row created and no row changed. The two ids may point at the same
     * location: a small store may receive and sell from the same place.</p>
     *
     * <p>The update path mutates the loaded entity and relies on its {@code @Version} column for
     * optimistic locking, exactly like the rest of the store tree after
     * {@code V8__store_optimistic_locking.sql}: a concurrent update makes the stale commit fail with
     * Spring's {@code ObjectOptimisticLockingFailureException}, instead of silently winning the last
     * write. No dedicated exception or status code is introduced for it.</p>
     *
     * @throws StoreLocationNotInStoreException when either location does not belong to the store
     *     (or does not exist)
     */
    @Transactional
    public StoreInventorySettingsResponse upsertSettings(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            StoreInventorySettingsRequest request) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        // Validate both locations before writing anything.
        assertLocationBelongsToStore(request.receivingLocationId(), store.getId());
        assertLocationBelongsToStore(request.salesLocationId(), store.getId());

        var existing = storeInventorySettingsRepository.findById(store.getId());
        if (existing.isEmpty()) {
            assertVersionPrecondition(request.version(), null);
            var created = StoreInventorySettings.builder()
                    .companyStoreId(store.getId())
                    .receivingLocationId(request.receivingLocationId())
                    .salesLocationId(request.salesLocationId())
                    .build();
            var saved = storeInventorySettingsRepository.save(created);
            logger.info("StoreInventorySettings created: companyStoreId={}", store.getId());
            return toResponse(saved);
        }

        var settings = existing.get();
        assertVersionPrecondition(request.version(), settings.getVersion());
        settings.setReceivingLocationId(request.receivingLocationId());
        settings.setSalesLocationId(request.salesLocationId());
        // Flush, do not merely save: Hibernate increments the @Version at flush time, so mapping the
        // response from a non-flushed entity would answer with the pre-increment version. A client
        // that echoes that version would then be rejected with a false 409 on its next write.
        var saved = storeInventorySettingsRepository.saveAndFlush(settings);
        logger.info("StoreInventorySettings updated: companyStoreId={}", store.getId());
        return toResponse(saved);
    }

    /**
     * Enforces the request's optional version precondition against the freshly-loaded state.
     *
     * <p>{@code null} means "no precondition" and always passes, preserving the contract the
     * settings screen already relies on. A non-null version must equal the stored one: a client
     * that read a version and finds the world moved gets a 409 instead of silently overwriting the
     * other writer. Asserting a version for a row that does not exist is the same conflict.</p>
     *
     * @throws ConflictException when a non-null version does not match the current stored version
     */
    private void assertVersionPrecondition(Long assertedVersion, Long storedVersion) {
        if (assertedVersion == null) {
            return;
        }
        if (!assertedVersion.equals(storedVersion)) {
            logger.info("StoreInventorySettings version precondition failed: assertedVersion={}", assertedVersion);
            throw new ConflictException(VERSION_CONFLICT_MESSAGE);
        }
    }

    /**
     * Lists the enabled store locations of a store, in the repository's deterministic order. This is
     * the data source of the receiving/sales location picker.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     */
    @Transactional(readOnly = true)
    public List<StoreLocationSummaryResponse> listStoreLocations(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        return storeLocationRepository.findEnabledByCompanyStoreId(store.getId()).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Enforces "this location belongs to this store" with a single load-bearing check: the store
     * area that owns the location must belong to {@code companyStoreId}, via
     * {@link StoreAreaRepository#findByIdAndCompanyStoreId(UUID, UUID)}.
     *
     * <p>One check is enough, and it is not the first of several independent hops. {@code
     * store_locations -> store_zones -> store_areas} and {@code store_areas -> company_stores} are
     * all {@code NOT NULL} foreign keys, so the location's area is by construction the unique area
     * that owns that location, and that area's store is the unique store that owns that area. "The
     * location's area belongs to store S" is therefore equivalent to "the location belongs to
     * store S"; any lookup seeded from those same foreign keys would only re-query values already
     * known.</p>
     *
     * @throws StoreLocationNotInStoreException when the location does not exist or its area does
     *     not belong to {@code companyStoreId}
     */
    private void assertLocationBelongsToStore(UUID storeLocationId, UUID companyStoreId) {
        var location = storeLocationRepository
                .findById(storeLocationId)
                .orElseThrow(() -> new StoreLocationNotInStoreException(storeLocationId));

        var area = location.getStoreZone().getStoreArea();

        storeAreaRepository
                .findByIdAndCompanyStoreId(area.getId(), companyStoreId)
                .orElseThrow(() -> new StoreLocationNotInStoreException(storeLocationId));
    }

    private StoreInventorySettingsResponse toResponse(StoreInventorySettings settings) {
        return new StoreInventorySettingsResponse(
                settings.getCompanyStoreId(),
                settings.getReceivingLocationId(),
                settings.getSalesLocationId(),
                settings.getVersion());
    }

    private StoreLocationSummaryResponse toSummary(StoreLocation location) {
        var zone = location.getStoreZone();
        var area = zone.getStoreArea();

        return new StoreLocationSummaryResponse(
                location.getId(),
                location.getLocationCode(),
                location.getLocationName(),
                zone.getId(),
                zone.getZoneCode(),
                zone.getZoneName(),
                area.getId(),
                area.getAreaCode(),
                area.getAreaName());
    }
}
