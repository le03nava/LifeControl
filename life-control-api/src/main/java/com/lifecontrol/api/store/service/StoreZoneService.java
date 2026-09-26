package com.lifecontrol.api.store.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.exception.VersionPreconditionException;
import com.lifecontrol.api.store.dto.CreateStoreZoneRequest;
import com.lifecontrol.api.store.dto.StoreZoneResponse;
import com.lifecontrol.api.store.dto.UpdateStoreZoneRequest;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DisabledParentException;
import com.lifecontrol.api.store.exception.DuplicateStoreZoneException;
import com.lifecontrol.api.store.exception.StoreAreaNotFoundException;
import com.lifecontrol.api.store.exception.StoreZoneNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.model.StoreZone;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import com.lifecontrol.api.store.repository.StoreZoneRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for store zones (level 3 of the store location tree).
 *
 * <p>Every public operation resolves — and authorizes — the full
 * company &rarr; country &rarr; region &rarr; zone &rarr; store &rarr; area path through
 * {@link CurrentUserContext#verifyCompanyStoreAccess} before touching a zone, so scoped roles keep
 * their broadest granted scope and no zone is reachable outside its own area.</p>
 *
 * <p>The flat {@link #getZoneById(UUID) lookup} navigates the zone's JPA associations to resolve
 * its chain before authorizing, so a caller that only holds the zone id still goes through the
 * same single access check as the nested endpoints. Because that id is supplied by the caller, a
 * denial there is reported as not-found to avoid disclosing the existence of a zone the caller
 * cannot reach, and the denial is logged.</p>
 */
@Service
public class StoreZoneService {

    private static final Logger logger = LoggerFactory.getLogger(StoreZoneService.class);

    /**
     * Message of the 412 raised when the request's optional version precondition does not hold. It
     * names no SQL or version number: the client only needs to know the zone moved.
     */
    private static final String VERSION_CONFLICT_MESSAGE =
            "The store zone conflicts with the current server state; reload and try again";

    private final StoreZoneRepository storeZoneRepository;
    private final StoreLocationRepository storeLocationRepository;
    private final StoreAreaRepository storeAreaRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyZoneRepository companyZoneRepository;
    private final CompanyRegionRepository companyRegionRepository;
    private final CompanyCountryRepository companyCountryRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public StoreZoneService(
            StoreZoneRepository storeZoneRepository,
            StoreLocationRepository storeLocationRepository,
            StoreAreaRepository storeAreaRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyZoneRepository companyZoneRepository,
            CompanyRegionRepository companyRegionRepository,
            CompanyCountryRepository companyCountryRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.storeZoneRepository = storeZoneRepository;
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
     * Verifies access and resolves the store identified by the full nested path.
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
     * Verifies access, resolves the store and then resolves the area inside that store.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyNotFoundException                                when the company does not exist
     * @throws CompanyCountryNotFoundException                         when the company-country does not belong to the company
     * @throws CompanyRegionNotFoundException                          when the region does not belong to the company-country
     * @throws CompanyZoneNotFoundException                            when the zone does not belong to the region
     * @throws CompanyStoreNotFoundException                           when the store does not belong to the zone
     * @throws StoreAreaNotFoundException                              when the area does not belong to the store
     */
    private StoreArea resolveArea(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId, UUID areaId) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        return storeAreaRepository
                .findByIdAndCompanyStoreId(areaId, store.getId())
                .orElseThrow(() -> new StoreAreaNotFoundException(areaId));
    }

    /**
     * Rejects an operation whose authorized area, or that area's store, is soft-deleted.
     *
     * <p>Creating or re-enabling a node requires its entire enabled ancestor chain up to the store
     * to be enabled, so the area and its store are both checked. The lazy {@code companyStore}
     * access is safe because the callers are transactional.</p>
     */
    private void assertAncestorsEnabled(StoreArea area, String action) {
        if (!Boolean.TRUE.equals(area.getEnabled())) {
            throw new DisabledParentException(
                    "Cannot " + action + ": store area with id " + area.getId() + " is disabled");
        }
        var store = area.getCompanyStore();
        if (!Boolean.TRUE.equals(store.getEnabled())) {
            throw new DisabledParentException("Cannot " + action + ": store with id " + store.getId() + " is disabled");
        }
    }

    /**
     * Lists the zones of an area, ordering them by display order and zone code.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     */
    @Transactional(readOnly = true)
    public List<StoreZoneResponse> getAllZones(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            boolean includeDisabled) {
        var area = resolveArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);
        var chain = chainOf(area.getCompanyStore());

        var zones = includeDisabled
                ? storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(area.getId())
                : storeZoneRepository.findByStoreAreaIdAndEnabledTrueOrderByDisplayOrderAscZoneCodeAsc(area.getId());

        return zones.stream().map(zone -> toResponse(zone, area, chain)).toList();
    }

    /**
     * Reads a single zone inside an area addressed by the full nested path.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     */
    @Transactional(readOnly = true)
    public StoreZoneResponse getZoneById(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId) {
        var area = resolveArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);
        var chain = chainOf(area.getCompanyStore());

        var zone = storeZoneRepository
                .findByIdAndStoreAreaId(storeZoneId, area.getId())
                .orElseThrow(() -> new StoreZoneNotFoundException(storeZoneId));

        return toResponse(zone, area, chain);
    }

    /**
     * Resolves a single zone by its id without a nested path, then authorizes it against the chain
     * navigated from the zone's own area and store associations.
     *
     * <p>When the zone exists but its resolved store scope is not accessible to the caller, the
     * denial is masked as not-found — the same exception type, status and message as a genuinely
     * missing id — so the flat lookup cannot be used as an existence oracle for resources the
     * caller cannot reach. The denial is logged as a warning with the resource id and the acting
     * user.</p>
     *
     * @throws StoreZoneNotFoundException when no zone exists with the given id, or when the
     *     resolved store scope is not accessible to the current user
     */
    @Transactional(readOnly = true)
    public StoreZoneResponse getZoneById(UUID storeZoneId) {
        var zone = storeZoneRepository
                .findById(storeZoneId)
                .orElseThrow(() -> new StoreZoneNotFoundException(storeZoneId));
        var area = zone.getStoreArea();
        var chain = chainOf(area.getCompanyStore());
        try {
            currentUserContext.verifyCompanyStoreAccess(
                    chain.companyId(), chain.companyCountryId(), chain.regionId(), chain.zoneId(), chain.storeId());
        } catch (AccessDeniedException ex) {
            logger.warn(
                    "StoreZone access denied for id={} and actor={}; reporting as not found",
                    storeZoneId,
                    currentActor());
            throw new StoreZoneNotFoundException(storeZoneId);
        }
        return toResponse(zone, area, chain);
    }

    /**
     * Creates an enabled zone inside the resolved area.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws DisabledParentException when the area or its store is soft-deleted
     * @throws DuplicateStoreZoneException when the zone code already exists in the area
     */
    @Transactional
    public StoreZoneResponse createZone(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            CreateStoreZoneRequest request) {
        var area = resolveArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);
        assertAncestorsEnabled(area, "create a store zone");
        var chain = chainOf(area.getCompanyStore());

        if (storeZoneRepository.existsByStoreAreaIdAndZoneCode(area.getId(), request.zoneCode())) {
            throw new DuplicateStoreZoneException(
                    "Store zone with code '" + request.zoneCode() + "' already exists in this area");
        }

        var zone = StoreZone.builder()
                .storeArea(area)
                .zoneCode(request.zoneCode())
                .zoneName(request.zoneName())
                .description(request.description())
                .displayOrder(request.displayOrder())
                .enabled(true)
                .build();

        // Flush so the response carries the real @Version and non-null createdAt/updatedAt: both the
        // version increment and the Auditable callbacks run at flush time.
        var saved = storeZoneRepository.saveAndFlush(zone);
        logger.info("StoreZone created: code={}, areaId={}", saved.getZoneCode(), area.getId());
        return toResponse(saved, area, chain);
    }

    /**
     * Applies the non-null fields of the request to the resolved zone.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     * @throws DuplicateStoreZoneException when the new zone code already exists in the area
     * @throws VersionPreconditionException when a non-null version does not match the current
     *     stored version
     */
    @Transactional
    public StoreZoneResponse updateZone(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId,
            UpdateStoreZoneRequest request) {
        var area = resolveArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);
        var chain = chainOf(area.getCompanyStore());

        var zone = storeZoneRepository
                .findByIdAndStoreAreaId(storeZoneId, area.getId())
                .orElseThrow(() -> new StoreZoneNotFoundException(storeZoneId));

        // Enforce the optional version precondition after the entity is loaded and before any
        // mutation, so a stale request leaves the row exactly as it was.
        assertVersionPrecondition(request.version(), zone.getVersion());

        // Check uniqueness only when the code actually changes
        if (request.zoneCode() != null
                && !zone.getZoneCode().equals(request.zoneCode())
                && storeZoneRepository.existsByStoreAreaIdAndZoneCodeAndIdNot(
                        area.getId(), request.zoneCode(), storeZoneId)) {
            throw new DuplicateStoreZoneException(
                    "Store zone with code '" + request.zoneCode() + "' already exists in this area");
        }

        if (request.zoneCode() != null) {
            zone.setZoneCode(request.zoneCode());
        }
        if (request.zoneName() != null) {
            zone.setZoneName(request.zoneName());
        }
        if (request.description() != null) {
            zone.setDescription(request.description());
        }
        if (request.displayOrder() != null) {
            zone.setDisplayOrder(request.displayOrder());
        }

        // Flush, do not merely save: Hibernate increments the @Version at flush time, so mapping the
        // response from a non-flushed entity would answer with the pre-increment version. A client
        // that echoes that version would then be rejected with a false 412 on its next write.
        var saved = storeZoneRepository.saveAndFlush(zone);
        logger.info("StoreZone updated: id={}, code={}", saved.getId(), saved.getZoneCode());
        return toResponse(saved, area, chain);
    }

    /**
     * Soft-deletes the zone and cascades the soft delete to its still-enabled locations.
     *
     * <p>Disabling a zone must not leave enabled locations hanging off a disabled zone, so every
     * location of the zone that is still enabled is disabled in the same transaction.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     */
    @Transactional
    public void deleteZone(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId) {
        var area = resolveArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);

        var zone = storeZoneRepository
                .findByIdAndStoreAreaId(storeZoneId, area.getId())
                .orElseThrow(() -> new StoreZoneNotFoundException(storeZoneId));

        disableZone(zone);

        logger.info(
                "StoreZone soft-deleted: id={}, code={}, actor={}", storeZoneId, zone.getZoneCode(), currentActor());
    }

    /**
     * Re-enables a soft-deleted zone.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     * @throws DisabledParentException when the area or its store is soft-deleted
     */
    @Transactional
    public StoreZoneResponse enableZone(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId) {
        var area = resolveArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);
        assertAncestorsEnabled(area, "re-enable a store zone");
        var chain = chainOf(area.getCompanyStore());

        var zone = storeZoneRepository
                .findByIdAndStoreAreaId(storeZoneId, area.getId())
                .orElseThrow(() -> new StoreZoneNotFoundException(storeZoneId));

        zone.setEnabled(true);
        // Flush so the response carries the post-increment @Version and the fresh updatedAt.
        var saved = storeZoneRepository.saveAndFlush(zone);

        logger.info("StoreZone re-enabled: id={}, code={}", storeZoneId, saved.getZoneCode());
        return toResponse(saved, area, chain);
    }

    /**
     * Soft-deletes every enabled zone of each given area and, through
     * {@link #disableZone(StoreZone)}, its enabled locations.
     *
     * <p>Called by {@code StoreAreaService} when an area — or a whole store — is soft-deleted. It
     * performs no authorization of its own: the caller already resolved and authorized the store
     * scope before invoking it.</p>
     *
     * @return the number of zones that were disabled, for logging at the call sites
     */
    @Transactional
    public int disableZonesOfAreas(List<StoreArea> areas) {
        var disabled = 0;
        for (var area : areas) {
            var zones = storeZoneRepository.findByStoreAreaIdAndEnabledTrue(area.getId());
            zones.forEach(this::disableZone);
            disabled += zones.size();
        }
        return disabled;
    }

    /**
     * Disables a single zone and cascades the soft delete to its still-enabled locations. Shared by
     * the zone soft delete and the area/store cascade so that "disable one zone and its locations"
     * has exactly one implementation.
     */
    private void disableZone(StoreZone zone) {
        zone.setEnabled(false);
        storeZoneRepository.save(zone);
        disableLocationsOfZones(List.of(zone));
    }

    /**
     * Disables every still-enabled location of each given zone and persists the change. It never
     * touches the zones themselves, so the zone&rarr;location cascade has exactly one
     * implementation shared by the zone soft delete and the area/store cascades.
     *
     * @return the number of locations that were disabled, for logging at the call sites
     */
    private int disableLocationsOfZones(List<StoreZone> zones) {
        var disabled = 0;
        for (var zone : zones) {
            var locations = storeLocationRepository.findByStoreZoneIdAndEnabledTrue(zone.getId());
            locations.forEach(location -> location.setEnabled(false));
            storeLocationRepository.saveAll(locations);
            disabled += locations.size();
        }
        return disabled;
    }

    /**
     * Enforces the request's optional version precondition against the freshly-loaded state.
     *
     * <p>{@code null} means "no precondition" and always passes, preserving the contract that
     * existing clients rely on. A non-null version must equal the stored one: a client that read a
     * version and finds the world moved gets a 412 instead of silently overwriting the other
     * writer.</p>
     *
     * @throws VersionPreconditionException when a non-null version does not match the current stored
     *     version
     */
    private void assertVersionPrecondition(Long assertedVersion, long storedVersion) {
        if (assertedVersion == null) {
            return;
        }
        if (assertedVersion != storedVersion) {
            logger.info("StoreZone version precondition failed: assertedVersion={}", assertedVersion);
            throw new VersionPreconditionException(VERSION_CONFLICT_MESSAGE);
        }
    }

    /**
     * Resolved company &rarr; country &rarr; region &rarr; zone &rarr; store identity of a zone,
     * carried alongside the zone so the response always exposes the full chain without a second
     * traversal per element.
     */
    private record StoreChain(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {}

    private StoreChain chainOf(CompanyStore store) {
        var zone = store.getCompanyZone();
        var region = zone.getCompanyRegion();
        var companyCountry = region.getCompanyCountry();
        var company = companyCountry.getCompany();

        return new StoreChain(company.getId(), companyCountry.getId(), region.getId(), zone.getId(), store.getId());
    }

    /**
     * Resolves the acting user for audit log lines: the JWT {@code preferred_username}, falling
     * back to the {@code sub} claim. Both may be {@code null} outside a request, so callers must
     * tolerate a {@code null} actor instead of failing the operation.
     */
    private String currentActor() {
        var username = currentUserContext.getUsername();
        return username != null ? username : currentUserContext.getUserId();
    }

    private StoreZoneResponse toResponse(StoreZone zone, StoreArea area, StoreChain chain) {
        return new StoreZoneResponse(
                zone.getId(),
                area.getId(),
                chain.storeId(),
                chain.companyId(),
                chain.companyCountryId(),
                chain.regionId(),
                chain.zoneId(),
                zone.getZoneCode(),
                zone.getZoneName(),
                zone.getDescription(),
                zone.getDisplayOrder(),
                zone.getEnabled(),
                zone.getCreatedAt(),
                zone.getUpdatedAt(),
                zone.getVersion());
    }
}
