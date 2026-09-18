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
import com.lifecontrol.api.store.dto.CreateStoreLocationRequest;
import com.lifecontrol.api.store.dto.StoreLocationResponse;
import com.lifecontrol.api.store.dto.UpdateStoreLocationRequest;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DisabledParentException;
import com.lifecontrol.api.store.exception.DuplicateStoreLocationException;
import com.lifecontrol.api.store.exception.StoreAreaNotFoundException;
import com.lifecontrol.api.store.exception.StoreLocationNotFoundException;
import com.lifecontrol.api.store.exception.StoreZoneNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.model.StoreLocation;
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
 * Business logic for store locations (level 4 of the store location tree).
 *
 * <p>Every public operation resolves — and authorizes — the full
 * company &rarr; country &rarr; region &rarr; zone &rarr; store &rarr; area &rarr; zone path
 * through {@link CurrentUserContext#verifyCompanyStoreAccess} before touching a location, so
 * scoped roles keep their broadest granted scope and no location is reachable outside its own
 * zone.</p>
 *
 * <p>The flat {@link #getLocationById(UUID) lookup} navigates the location's JPA associations to
 * resolve its chain before authorizing, so a caller that only holds the location id still goes
 * through the same single access check as the nested endpoints. Because that id is supplied by the
 * caller, a denial there is reported as not-found to avoid disclosing the existence of a location
 * the caller cannot reach, and the denial is logged.</p>
 */
@Service
public class StoreLocationService {

    private static final Logger logger = LoggerFactory.getLogger(StoreLocationService.class);

    private final StoreLocationRepository storeLocationRepository;
    private final StoreZoneRepository storeZoneRepository;
    private final StoreAreaRepository storeAreaRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyZoneRepository companyZoneRepository;
    private final CompanyRegionRepository companyRegionRepository;
    private final CompanyCountryRepository companyCountryRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public StoreLocationService(
            StoreLocationRepository storeLocationRepository,
            StoreZoneRepository storeZoneRepository,
            StoreAreaRepository storeAreaRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyZoneRepository companyZoneRepository,
            CompanyRegionRepository companyRegionRepository,
            CompanyCountryRepository companyCountryRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.storeLocationRepository = storeLocationRepository;
        this.storeZoneRepository = storeZoneRepository;
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
     * Verifies access and resolves the store zone identified by the full nested path.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException                           when the store does not belong to the zone
     * @throws StoreAreaNotFoundException                              when the area does not belong to the store
     * @throws StoreZoneNotFoundException                              when the zone does not belong to the area
     */
    private StoreZone resolveZone(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId) {
        var area = resolveArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);

        return storeZoneRepository
                .findByIdAndStoreAreaId(storeZoneId, area.getId())
                .orElseThrow(() -> new StoreZoneNotFoundException(storeZoneId));
    }

    /**
     * Rejects an operation whose authorized zone, that zone's area, or that area's store is
     * soft-deleted.
     *
     * <p>Creating or re-enabling a node requires its entire enabled ancestor chain up to the store
     * to be enabled, so the zone, its area and the store are all checked. The lazy association
     * access is safe because the callers are transactional.</p>
     */
    private void assertAncestorsEnabled(StoreZone zone, String action) {
        if (!Boolean.TRUE.equals(zone.getEnabled())) {
            throw new DisabledParentException(
                    "Cannot " + action + ": store zone with id " + zone.getId() + " is disabled");
        }
        var area = zone.getStoreArea();
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
     * Lists the locations of a zone, ordering them by display order and location code.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     */
    @Transactional(readOnly = true)
    public List<StoreLocationResponse> getAllLocations(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId,
            boolean includeDisabled) {
        var zone = resolveZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);
        var area = zone.getStoreArea();
        var chain = chainOf(area.getCompanyStore());

        var locations = includeDisabled
                ? storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(zone.getId())
                : storeLocationRepository.findByStoreZoneIdAndEnabledTrueOrderByDisplayOrderAscLocationCodeAsc(
                        zone.getId());

        return locations.stream()
                .map(location -> toResponse(location, zone, area, chain))
                .toList();
    }

    /**
     * Reads a single location inside a zone addressed by the full nested path.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     * @throws StoreLocationNotFoundException when the location does not belong to the zone
     */
    @Transactional(readOnly = true)
    public StoreLocationResponse getLocationById(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId,
            UUID storeLocationId) {
        var zone = resolveZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);
        var area = zone.getStoreArea();
        var chain = chainOf(area.getCompanyStore());

        var location = storeLocationRepository
                .findByIdAndStoreZoneId(storeLocationId, zone.getId())
                .orElseThrow(() -> new StoreLocationNotFoundException(storeLocationId));

        return toResponse(location, zone, area, chain);
    }

    /**
     * Resolves a single location by its id without a nested path, then authorizes it against the
     * chain navigated from the location's own zone, area and store associations.
     *
     * <p>When the location exists but its resolved store scope is not accessible to the caller, the
     * denial is masked as not-found — the same exception type, status and message as a genuinely
     * missing id — so the flat lookup cannot be used as an existence oracle for resources the
     * caller cannot reach. The denial is logged as a warning with the resource id and the acting
     * user.</p>
     *
     * @throws StoreLocationNotFoundException when no location exists with the given id, or when the
     *     resolved store scope is not accessible to the current user
     */
    @Transactional(readOnly = true)
    public StoreLocationResponse getLocationById(UUID storeLocationId) {
        var location = storeLocationRepository
                .findById(storeLocationId)
                .orElseThrow(() -> new StoreLocationNotFoundException(storeLocationId));
        var zone = location.getStoreZone();
        var area = zone.getStoreArea();
        var chain = chainOf(area.getCompanyStore());
        try {
            currentUserContext.verifyCompanyStoreAccess(
                    chain.companyId(), chain.companyCountryId(), chain.regionId(), chain.zoneId(), chain.storeId());
        } catch (AccessDeniedException ex) {
            logger.warn(
                    "StoreLocation access denied for id={} and actor={}; reporting as not found",
                    storeLocationId,
                    currentActor());
            throw new StoreLocationNotFoundException(storeLocationId);
        }
        return toResponse(location, zone, area, chain);
    }

    /**
     * Creates an enabled location inside the resolved zone.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     * @throws DisabledParentException when the zone, its area or its store is soft-deleted
     * @throws DuplicateStoreLocationException when the location code already exists in the zone
     */
    @Transactional
    public StoreLocationResponse createLocation(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId,
            CreateStoreLocationRequest request) {
        var zone = resolveZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);
        assertAncestorsEnabled(zone, "create a store location");
        var area = zone.getStoreArea();
        var chain = chainOf(area.getCompanyStore());

        if (storeLocationRepository.existsByStoreZoneIdAndLocationCode(zone.getId(), request.locationCode())) {
            throw new DuplicateStoreLocationException(
                    "Store location with code '" + request.locationCode() + "' already exists in this zone");
        }

        var location = StoreLocation.builder()
                .storeZone(zone)
                .locationCode(request.locationCode())
                .locationName(request.locationName())
                .description(request.description())
                .displayOrder(request.displayOrder())
                .enabled(true)
                .build();

        var saved = storeLocationRepository.save(location);
        logger.info("StoreLocation created: code={}, storeZoneId={}", saved.getLocationCode(), zone.getId());
        return toResponse(saved, zone, area, chain);
    }

    /**
     * Applies the non-null fields of the request to the resolved location.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     * @throws StoreLocationNotFoundException when the location does not belong to the zone
     * @throws DuplicateStoreLocationException when the new location code already exists in the zone
     */
    @Transactional
    public StoreLocationResponse updateLocation(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId,
            UUID storeLocationId,
            UpdateStoreLocationRequest request) {
        var zone = resolveZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);
        var area = zone.getStoreArea();
        var chain = chainOf(area.getCompanyStore());

        var location = storeLocationRepository
                .findByIdAndStoreZoneId(storeLocationId, zone.getId())
                .orElseThrow(() -> new StoreLocationNotFoundException(storeLocationId));

        // Check uniqueness only when the code actually changes
        if (request.locationCode() != null
                && !location.getLocationCode().equals(request.locationCode())
                && storeLocationRepository.existsByStoreZoneIdAndLocationCodeAndIdNot(
                        zone.getId(), request.locationCode(), storeLocationId)) {
            throw new DuplicateStoreLocationException(
                    "Store location with code '" + request.locationCode() + "' already exists in this zone");
        }

        if (request.locationCode() != null) {
            location.setLocationCode(request.locationCode());
        }
        if (request.locationName() != null) {
            location.setLocationName(request.locationName());
        }
        if (request.description() != null) {
            location.setDescription(request.description());
        }
        if (request.displayOrder() != null) {
            location.setDisplayOrder(request.displayOrder());
        }

        var saved = storeLocationRepository.save(location);
        logger.info("StoreLocation updated: id={}, code={}", saved.getId(), saved.getLocationCode());
        return toResponse(saved, zone, area, chain);
    }

    /**
     * Soft-deletes the location by setting {@code enabled = false}, keeping the row.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     * @throws StoreLocationNotFoundException when the location does not belong to the zone
     */
    @Transactional
    public void deleteLocation(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId,
            UUID storeLocationId) {
        var zone = resolveZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);

        var location = storeLocationRepository
                .findByIdAndStoreZoneId(storeLocationId, zone.getId())
                .orElseThrow(() -> new StoreLocationNotFoundException(storeLocationId));

        location.setEnabled(false);
        storeLocationRepository.save(location);

        logger.info(
                "StoreLocation soft-deleted: id={}, code={}, actor={}",
                storeLocationId,
                location.getLocationCode(),
                currentActor());
    }

    /**
     * Re-enables a soft-deleted location.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     * @throws StoreZoneNotFoundException when the zone does not belong to the area
     * @throws StoreLocationNotFoundException when the location does not belong to the zone
     * @throws DisabledParentException when the zone, its area or its store is soft-deleted
     */
    @Transactional
    public StoreLocationResponse enableLocation(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UUID storeZoneId,
            UUID storeLocationId) {
        var zone = resolveZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);
        assertAncestorsEnabled(zone, "re-enable a store location");
        var area = zone.getStoreArea();
        var chain = chainOf(area.getCompanyStore());

        var location = storeLocationRepository
                .findByIdAndStoreZoneId(storeLocationId, zone.getId())
                .orElseThrow(() -> new StoreLocationNotFoundException(storeLocationId));

        location.setEnabled(true);
        var saved = storeLocationRepository.save(location);

        logger.info("StoreLocation re-enabled: id={}, code={}", storeLocationId, saved.getLocationCode());
        return toResponse(saved, zone, area, chain);
    }

    /**
     * Resolved company &rarr; country &rarr; region &rarr; zone &rarr; store identity of a
     * location, carried alongside the location so the response always exposes the full chain
     * without a second traversal per element.
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

    private StoreLocationResponse toResponse(StoreLocation location, StoreZone zone, StoreArea area, StoreChain chain) {
        return new StoreLocationResponse(
                location.getId(),
                zone.getId(),
                area.getId(),
                chain.storeId(),
                chain.companyId(),
                chain.companyCountryId(),
                chain.regionId(),
                chain.zoneId(),
                location.getLocationCode(),
                location.getLocationName(),
                location.getDescription(),
                location.getDisplayOrder(),
                location.getEnabled(),
                location.getCreatedAt(),
                location.getUpdatedAt());
    }
}
