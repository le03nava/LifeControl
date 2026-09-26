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
import com.lifecontrol.api.store.dto.CreateStoreAreaRequest;
import com.lifecontrol.api.store.dto.StoreAreaResponse;
import com.lifecontrol.api.store.dto.UpdateStoreAreaRequest;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DisabledParentException;
import com.lifecontrol.api.store.exception.DuplicateStoreAreaException;
import com.lifecontrol.api.store.exception.StoreAreaNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for store areas (level 2 of the store location tree).
 *
 * <p>Every public operation resolves — and authorizes — the full
 * company &rarr; country &rarr; region &rarr; zone &rarr; store path through
 * {@link CurrentUserContext#verifyCompanyStoreAccess} before touching an area, so scoped roles keep
 * their broadest granted scope and no area is reachable outside its own store.</p>
 *
 * <p>The flat {@link #getAreaById(UUID) lookup} navigates the area's JPA associations to resolve
 * its chain before authorizing, so a caller that only holds the area id still goes through the
 * same single access check as the nested endpoints. Because that id is supplied by the caller, a
 * denial there is reported as not-found to avoid disclosing the existence of an area the caller
 * cannot reach, and the denial is logged.</p>
 */
@Service
public class StoreAreaService {

    private static final Logger logger = LoggerFactory.getLogger(StoreAreaService.class);

    /**
     * Message of the 412 raised when the request's optional version precondition does not hold. It
     * names no SQL or version number: the client only needs to know the area moved.
     */
    private static final String VERSION_CONFLICT_MESSAGE =
            "The store area conflicts with the current server state; reload and try again";

    private final StoreAreaRepository storeAreaRepository;
    private final StoreZoneService storeZoneService;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyZoneRepository companyZoneRepository;
    private final CompanyRegionRepository companyRegionRepository;
    private final CompanyCountryRepository companyCountryRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public StoreAreaService(
            StoreAreaRepository storeAreaRepository,
            StoreZoneService storeZoneService,
            CompanyStoreRepository companyStoreRepository,
            CompanyZoneRepository companyZoneRepository,
            CompanyRegionRepository companyRegionRepository,
            CompanyCountryRepository companyCountryRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.storeAreaRepository = storeAreaRepository;
        this.storeZoneService = storeZoneService;
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
     * Rejects an operation whose authorized store is soft-deleted.
     *
     * <p>Creating or re-enabling a node requires its entire enabled ancestor chain up to the store
     * to be enabled; the store is the top of the store subtree checked here.</p>
     */
    private void assertStoreEnabled(CompanyStore store, String action) {
        if (!Boolean.TRUE.equals(store.getEnabled())) {
            throw new DisabledParentException("Cannot " + action + ": store with id " + store.getId() + " is disabled");
        }
    }

    @Transactional(readOnly = true)
    public List<StoreAreaResponse> getAllAreas(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId, boolean includeDisabled) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);
        var chain = chainOf(store);

        var areas = includeDisabled
                ? storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(store.getId())
                : storeAreaRepository.findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(
                        store.getId());

        return areas.stream().map(area -> toResponse(area, chain)).toList();
    }

    @Transactional(readOnly = true)
    public StoreAreaResponse getAreaById(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId, UUID areaId) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);
        var chain = chainOf(store);

        var area = storeAreaRepository
                .findByIdAndCompanyStoreId(areaId, store.getId())
                .orElseThrow(() -> new StoreAreaNotFoundException(areaId));

        return toResponse(area, chain);
    }

    /**
     * Resolves a single area by its id without a nested path, then authorizes it against the chain
     * navigated from the area's own store association.
     *
     * <p>When the area exists but its resolved store scope is not accessible to the caller, the
     * denial is masked as not-found — the same exception type, status and message as a genuinely
     * missing id — so the flat lookup cannot be used as an existence oracle for resources the
     * caller cannot reach. The denial is logged as a warning with the resource id and the acting
     * user.</p>
     *
     * @throws StoreAreaNotFoundException when no area exists with the given id, or when the
     *     resolved store scope is not accessible to the current user
     */
    @Transactional(readOnly = true)
    public StoreAreaResponse getAreaById(UUID areaId) {
        var area = storeAreaRepository.findById(areaId).orElseThrow(() -> new StoreAreaNotFoundException(areaId));
        var chain = chainOf(area.getCompanyStore());
        try {
            currentUserContext.verifyCompanyStoreAccess(
                    chain.companyId(), chain.companyCountryId(), chain.regionId(), chain.zoneId(), chain.storeId());
        } catch (AccessDeniedException ex) {
            logger.warn(
                    "StoreArea access denied for id={} and actor={}; reporting as not found", areaId, currentActor());
            throw new StoreAreaNotFoundException(areaId);
        }
        return toResponse(area, chain);
    }

    /**
     * Creates an enabled area inside the resolved store.
     *
     * @throws DisabledParentException when the store is soft-deleted
     * @throws DuplicateStoreAreaException when the area code already exists in the store
     */
    @Transactional
    public StoreAreaResponse createArea(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            CreateStoreAreaRequest request) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);
        assertStoreEnabled(store, "create a store area");
        var chain = chainOf(store);

        if (storeAreaRepository.existsByCompanyStoreIdAndAreaCode(store.getId(), request.areaCode())) {
            throw new DuplicateStoreAreaException(
                    "Store area with code '" + request.areaCode() + "' already exists in this store");
        }

        var area = StoreArea.builder()
                .companyStore(store)
                .areaCode(request.areaCode())
                .areaName(request.areaName())
                .description(request.description())
                .displayOrder(request.displayOrder())
                .enabled(true)
                .build();

        // Flush so the response carries the real @Version and non-null createdAt/updatedAt: both the
        // version increment and the Auditable callbacks run at flush time.
        var saved = storeAreaRepository.saveAndFlush(area);
        logger.info("StoreArea created: code={}, storeId={}", saved.getAreaCode(), store.getId());
        return toResponse(saved, chain);
    }

    @Transactional
    public StoreAreaResponse updateArea(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UpdateStoreAreaRequest request) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);
        var chain = chainOf(store);

        var area = storeAreaRepository
                .findByIdAndCompanyStoreId(areaId, store.getId())
                .orElseThrow(() -> new StoreAreaNotFoundException(areaId));

        // Enforce the optional version precondition after the entity is loaded and before any
        // mutation, so a stale request leaves the row exactly as it was.
        assertVersionPrecondition(request.version(), area.getVersion());

        // Check uniqueness only when the code actually changes
        if (request.areaCode() != null
                && !area.getAreaCode().equals(request.areaCode())
                && storeAreaRepository.existsByCompanyStoreIdAndAreaCodeAndIdNot(
                        store.getId(), request.areaCode(), areaId)) {
            throw new DuplicateStoreAreaException(
                    "Store area with code '" + request.areaCode() + "' already exists in this store");
        }

        if (request.areaCode() != null) {
            area.setAreaCode(request.areaCode());
        }
        if (request.areaName() != null) {
            area.setAreaName(request.areaName());
        }
        if (request.description() != null) {
            area.setDescription(request.description());
        }
        if (request.displayOrder() != null) {
            area.setDisplayOrder(request.displayOrder());
        }

        // Flush, do not merely save: Hibernate increments the @Version at flush time, so mapping the
        // response from a non-flushed entity would answer with the pre-increment version. A client
        // that echoes that version would then be rejected with a false 409 on its next write.
        var saved = storeAreaRepository.saveAndFlush(area);
        logger.info("StoreArea updated: id={}, code={}", saved.getId(), saved.getAreaCode());
        return toResponse(saved, chain);
    }

    /**
     * Soft-deletes the area and cascades the soft delete to its still-enabled zones.
     *
     * <p>Disabling an area must not leave enabled zones hanging off a disabled area, so every
     * zone of the area that is still enabled is disabled in the same transaction.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyStoreNotFoundException when the store does not belong to the zone
     * @throws StoreAreaNotFoundException when the area does not belong to the store
     */
    @Transactional
    public void deleteArea(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId, UUID areaId) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        var area = storeAreaRepository
                .findByIdAndCompanyStoreId(areaId, store.getId())
                .orElseThrow(() -> new StoreAreaNotFoundException(areaId));

        area.setEnabled(false);
        storeAreaRepository.save(area);

        var cascadedZones = storeZoneService.disableZonesOfAreas(List.of(area));

        logger.info(
                "StoreArea soft-deleted: id={}, code={}, cascadedZones={}, actor={}",
                areaId,
                area.getAreaCode(),
                cascadedZones,
                currentActor());
    }

    /**
     * Soft-deletes every enabled area of a store and, through
     * {@link StoreZoneService#disableZonesOfAreas(List)}, its enabled zones and their locations.
     *
     * <p>Called by {@code CompanyStoreService} when a store is soft-deleted. It performs no
     * authorization of its own: the caller already resolved and authorized the store scope before
     * invoking it.</p>
     */
    @Transactional
    public void disableAreasOfStore(UUID companyStoreId) {
        var areas =
                storeAreaRepository.findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(companyStoreId);
        var cascadedZones = storeZoneService.disableZonesOfAreas(areas);
        areas.forEach(area -> area.setEnabled(false));
        storeAreaRepository.saveAll(areas);
        logger.info(
                "StoreAreas soft-deleted by store cascade: storeId={}, areas={}, cascadedZones={}",
                companyStoreId,
                areas.size(),
                cascadedZones);
    }

    /**
     * Re-enables a soft-deleted area.
     *
     * @throws DisabledParentException when the store is soft-deleted
     */
    @Transactional
    public StoreAreaResponse enableArea(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId, UUID areaId) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);
        assertStoreEnabled(store, "re-enable a store area");
        var chain = chainOf(store);

        var area = storeAreaRepository
                .findByIdAndCompanyStoreId(areaId, store.getId())
                .orElseThrow(() -> new StoreAreaNotFoundException(areaId));

        area.setEnabled(true);
        // Flush so the response carries the post-increment @Version and the fresh updatedAt.
        var saved = storeAreaRepository.saveAndFlush(area);

        logger.info("StoreArea re-enabled: id={}, code={}", areaId, saved.getAreaCode());
        return toResponse(saved, chain);
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
            logger.info("StoreArea version precondition failed: assertedVersion={}", assertedVersion);
            throw new VersionPreconditionException(VERSION_CONFLICT_MESSAGE);
        }
    }

    /**
     * Resolved company &rarr; country &rarr; region &rarr; zone &rarr; store identity of an area,
     * carried alongside the area so the response always exposes the full chain without a second
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

    private StoreAreaResponse toResponse(StoreArea area, StoreChain chain) {
        return new StoreAreaResponse(
                area.getId(),
                chain.storeId(),
                chain.companyId(),
                chain.companyCountryId(),
                chain.regionId(),
                chain.zoneId(),
                area.getAreaCode(),
                area.getAreaName(),
                area.getDescription(),
                area.getDisplayOrder(),
                area.getEnabled(),
                area.getCreatedAt(),
                area.getUpdatedAt(),
                area.getVersion());
    }
}
