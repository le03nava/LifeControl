package com.lifecontrol.api.store.service;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.common.address.dto.AddressResponse;
import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.dto.CreateCompanyStoreRequest;
import com.lifecontrol.api.store.dto.UpdateCompanyStoreRequest;
import com.lifecontrol.api.store.event.CompanyStoreCreatedEvent;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DuplicateCompanyStoreException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyStoreService {

    private static final Logger logger = LoggerFactory.getLogger(CompanyStoreService.class);

    /**
     * Message of the 409 raised when the request's optional version precondition does not hold. It
     * names no SQL or version number: the client only needs to know the store moved.
     */
    private static final String VERSION_CONFLICT_MESSAGE =
            "The company store conflicts with the current server state; reload and try again";

    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyZoneRepository companyZoneRepository;
    private final CompanyRegionRepository companyRegionRepository;
    private final CompanyCountryRepository companyCountryRepository;
    private final CompanyRepository companyRepository;
    private final CountryRepository countryRepository;
    private final CurrentUserContext currentUserContext;
    private final ApplicationEventPublisher eventPublisher;
    private final StoreAreaService storeAreaService;

    public CompanyStoreService(
            CompanyStoreRepository companyStoreRepository,
            CompanyZoneRepository companyZoneRepository,
            CompanyRegionRepository companyRegionRepository,
            CompanyCountryRepository companyCountryRepository,
            CompanyRepository companyRepository,
            CountryRepository countryRepository,
            CurrentUserContext currentUserContext,
            ApplicationEventPublisher eventPublisher,
            StoreAreaService storeAreaService) {
        this.companyStoreRepository = companyStoreRepository;
        this.companyZoneRepository = companyZoneRepository;
        this.companyRegionRepository = companyRegionRepository;
        this.companyCountryRepository = companyCountryRepository;
        this.companyRepository = companyRepository;
        this.countryRepository = countryRepository;
        this.currentUserContext = currentUserContext;
        this.eventPublisher = eventPublisher;
        this.storeAreaService = storeAreaService;
    }

    private CompanyZone resolveCompanyZone(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId) {
        currentUserContext.verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, null);

        companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndId(companyId, companyCountryId)
                .orElseThrow(() -> new CompanyCountryNotFoundException(companyCountryId));

        var region = companyRegionRepository
                .findByIdAndCompanyCountryId(regionId, companyCountry.getId())
                .orElseThrow(() -> new CompanyRegionNotFoundException("Company region not found with id: " + regionId));

        return companyZoneRepository
                .findByIdAndCompanyRegionId(zoneId, region.getId())
                .orElseThrow(() -> new CompanyZoneNotFoundException("Company zone not found with id: " + zoneId));
    }

    @Transactional(readOnly = true)
    public List<CompanyStoreResponse> getAllStores(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, boolean includeDisabled) {
        var zone = resolveCompanyZone(companyId, companyCountryId, regionId, zoneId);

        List<CompanyStore> stores;
        if (currentUserContext.hasCompanyStoreRole() || currentUserContext.hasCompanyStoreReadRole()) {
            var storeIds = currentUserContext.getCompanyStoreIds();
            // The storeIds scoping is the security boundary and is passed through untouched; only
            // the enabled filter follows includeDisabled, mirroring the non-store-scoped branch.
            stores = includeDisabled
                    ? companyStoreRepository.findByIdInAndCompanyZoneId(storeIds, zone.getId())
                    : companyStoreRepository.findByIdInAndCompanyZoneIdAndEnabledTrue(storeIds, zone.getId());
        } else {
            stores = includeDisabled
                    ? companyStoreRepository.findByCompanyZoneId(zone.getId())
                    : companyStoreRepository.findByCompanyZoneIdAndEnabledTrue(zone.getId());
        }

        return stores.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CompanyStoreResponse getStoreById(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {
        var zone = resolveCompanyZone(companyId, companyCountryId, regionId, zoneId);

        var store = companyStoreRepository
                .findByIdAndCompanyZoneId(storeId, zone.getId())
                .orElseThrow(() -> new CompanyStoreNotFoundException(storeId));

        return toResponse(store);
    }

    @Transactional
    public CompanyStoreResponse createStore(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, CreateCompanyStoreRequest request) {
        if (currentUserContext.hasCompanyStoreRole()) {
            throw new AccessDeniedException("Store-scoped users cannot create stores");
        }
        var zone = resolveCompanyZone(companyId, companyCountryId, regionId, zoneId);

        if (companyStoreRepository.existsByStoreNameAndCompanyZoneId(request.storeName(), zone.getId())) {
            throw new DuplicateCompanyStoreException(
                    "Store with name '" + request.storeName() + "' already exists in this zone");
        }

        var address = request.address() != null ? buildAddress(request.address()) : null;

        var store = CompanyStore.builder()
                .companyZone(zone)
                .storeName(request.storeName())
                .email(request.email())
                .phoneNumber(request.phoneNumber())
                .address(address)
                .enabled(true)
                .build();

        // Flush so the response carries the real @Version and non-null createdAt/updatedAt: both the
        // version increment and the Auditable callbacks run at flush time.
        var saved = companyStoreRepository.saveAndFlush(store);
        eventPublisher.publishEvent(
                new CompanyStoreCreatedEvent(this, saved.getId(), companyId, saved.getStoreName(), zone.getZoneName()));
        logger.info("CompanyStore created: name={}, zoneId={}", saved.getStoreName(), zone.getId());
        return toResponse(saved);
    }

    @Transactional
    public CompanyStoreResponse updateStore(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UpdateCompanyStoreRequest request) {
        var zone = resolveCompanyZone(companyId, companyCountryId, regionId, zoneId);

        var store = companyStoreRepository
                .findByIdAndCompanyZoneId(storeId, zone.getId())
                .orElseThrow(() -> new CompanyStoreNotFoundException(storeId));

        // Enforce the optional version precondition after the entity is loaded and before any
        // mutation, so a stale request leaves the row exactly as it was.
        assertVersionPrecondition(request.version(), store.getVersion());

        // Check uniqueness if storeName changed
        if (request.storeName() != null
                && !store.getStoreName().equals(request.storeName())
                && companyStoreRepository.existsByStoreNameAndCompanyZoneIdAndIdNot(
                        request.storeName(), zone.getId(), storeId)) {
            throw new DuplicateCompanyStoreException(
                    "Store with name '" + request.storeName() + "' already exists in this zone");
        }

        // Update store fields
        if (request.storeName() != null) {
            store.setStoreName(request.storeName());
        }
        if (request.email() != null) {
            store.setEmail(request.email());
        }
        if (request.phoneNumber() != null) {
            store.setPhoneNumber(request.phoneNumber());
        }

        // Handle address (dual-path)
        if (request.address() != null) {
            if (store.getAddress() != null) {
                updateAddress(store.getAddress(), request.address());
            } else {
                store.setAddress(buildAddress(request.address()));
            }
        }
        // If request.address() == null → leave address as-is

        // Flush, do not merely save: Hibernate increments the @Version at flush time, so mapping the
        // response from a non-flushed entity would answer with the pre-increment version. A client
        // that echoes that version would then be rejected with a false 409 on its next write.
        var saved = companyStoreRepository.saveAndFlush(store);
        logger.info("CompanyStore updated: id={}, name={}", saved.getId(), saved.getStoreName());
        return toResponse(saved);
    }

    @Transactional
    public void deleteStore(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {
        var zone = resolveCompanyZone(companyId, companyCountryId, regionId, zoneId);

        var store = companyStoreRepository
                .findByIdAndCompanyZoneId(storeId, zone.getId())
                .orElseThrow(() -> new CompanyStoreNotFoundException(storeId));

        store.setEnabled(false);
        companyStoreRepository.save(store);

        // Cascade the soft delete to the store's enabled areas and their enabled zones, in the
        // same transaction. StoreAreaService owns the single area -> zone cascade implementation.
        storeAreaService.disableAreasOfStore(store.getId());

        logger.info(
                "CompanyStore soft-deleted: id={}, name={}; areas and zones cascaded, actor={}",
                storeId,
                store.getStoreName(),
                currentActor());
    }

    @Transactional
    public CompanyStoreResponse enableStore(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {
        var zone = resolveCompanyZone(companyId, companyCountryId, regionId, zoneId);

        var store = companyStoreRepository
                .findByIdAndCompanyZoneId(storeId, zone.getId())
                .orElseThrow(() -> new CompanyStoreNotFoundException(storeId));

        store.setEnabled(true);
        // Flush so the response carries the post-increment @Version and the fresh updatedAt.
        var saved = companyStoreRepository.saveAndFlush(store);

        logger.info("CompanyStore re-enabled: id={}, name={}", storeId, saved.getStoreName());
        return toResponse(saved);
    }

    /**
     * Enforces the request's optional version precondition against the freshly-loaded state.
     *
     * <p>{@code null} means "no precondition" and always passes, preserving the contract that
     * existing clients rely on. A non-null version must equal the stored one: a client that read a
     * version and finds the world moved gets a 409 instead of silently overwriting the other
     * writer.</p>
     *
     * @throws ConflictException when a non-null version does not match the current stored version
     */
    private void assertVersionPrecondition(Long assertedVersion, long storedVersion) {
        if (assertedVersion == null) {
            return;
        }
        if (assertedVersion != storedVersion) {
            logger.info("CompanyStore version precondition failed: assertedVersion={}", assertedVersion);
            throw new ConflictException(VERSION_CONFLICT_MESSAGE);
        }
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

    private CompanyStoreResponse toResponse(CompanyStore store) {
        var zone = store.getCompanyZone();
        var region = zone.getCompanyRegion();
        var companyCountry = region.getCompanyCountry();

        return new CompanyStoreResponse(
                store.getId(),
                companyCountry.getCompany().getId(),
                companyCountry.getId(),
                region.getId(),
                zone.getId(),
                store.getStoreName(),
                store.getEmail(),
                store.getPhoneNumber(),
                buildAddressResponse(store),
                store.getEnabled(),
                store.getCreatedAt(),
                store.getUpdatedAt(),
                store.getVersion());
    }

    private AddressResponse buildAddressResponse(CompanyStore store) {
        var address = store.getAddress();
        if (address != null) {
            return new AddressResponse(
                    address.getId(),
                    address.getStreet(),
                    address.getStreetNumber(),
                    address.getInternalNumber(),
                    address.getNeighborhood(),
                    address.getZipCode(),
                    address.getCity(),
                    address.getState(),
                    address.getCountry() != null ? address.getCountry().getId() : null);
        }
        // Legacy CompanyStoreAddress fallback — for migrated data, check here
        // For now, return null (address was not migrated)
        return null;
    }

    private Address buildAddress(AddressRequest request) {
        if (request == null) return null;
        Country country = null;
        if (request.countryId() != null) {
            country = countryRepository
                    .findById(request.countryId())
                    .orElseThrow(() -> new CountryNotFoundException(request.countryId()));
        }
        return Address.builder()
                .street(request.street())
                .streetNumber(request.streetNumber())
                .internalNumber(request.internalNumber())
                .neighborhood(request.neighborhood())
                .zipCode(request.zipCode())
                .city(request.city())
                .state(request.state())
                .country(country)
                .enabled(true)
                .build();
    }

    private void updateAddress(Address address, AddressRequest request) {
        address.setStreet(request.street());
        address.setStreetNumber(request.streetNumber());
        address.setInternalNumber(request.internalNumber());
        address.setNeighborhood(request.neighborhood());
        address.setZipCode(request.zipCode());
        address.setCity(request.city());
        address.setState(request.state());
        if (request.countryId() != null) {
            Country country = countryRepository
                    .findById(request.countryId())
                    .orElseThrow(() -> new CountryNotFoundException(request.countryId()));
            address.setCountry(country);
        } else {
            address.setCountry(null);
        }
    }
}
