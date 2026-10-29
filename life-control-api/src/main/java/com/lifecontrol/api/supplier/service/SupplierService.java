package com.lifecontrol.api.supplier.service;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.common.address.dto.AddressResponse;
import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.supplier.dto.SupplierRequest;
import com.lifecontrol.api.supplier.dto.SupplierResponse;
import com.lifecontrol.api.supplier.exception.DuplicateSupplierException;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Business logic for the company-scoped supplier catalog.
 *
 * <p>Mirrors {@link com.lifecontrol.api.product.service.ProductService}: every public method starts by
 * resolving the company through {@link #resolveCompany(UUID)}, whose first statement is
 * {@link CurrentUserContext#verifyCompanyAccess(UUID)}, and every single-row lookup is
 * {@code companyId} scoped, so a supplier of another company is a {@link SupplierNotFoundException}
 * rather than a 200.</p>
 *
 * <p>The company in the path is the <b>owner</b> of the supplier, which is why {@link SupplierRequest}
 * does not carry one: a request cannot name a tenant, and therefore cannot name another one. The
 * uniqueness rule follows the owner — an RFC is unique <em>inside</em> a company, so two separate
 * businesses may register the same one.</p>
 */
@Service
public class SupplierService {

    private static final Logger logger = LoggerFactory.getLogger(SupplierService.class);

    private final SupplierRepository supplierRepository;
    private final CountryRepository countryRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public SupplierService(
            SupplierRepository supplierRepository,
            CountryRepository countryRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.supplierRepository = supplierRepository;
        this.countryRepository = countryRepository;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
    }

    @Transactional(readOnly = true)
    public Page<SupplierResponse> getAllSuppliers(
            UUID companyId, Pageable pageable, String search, boolean includeDisabled) {
        resolveCompany(companyId);

        Page<Supplier> suppliers;

        if (StringUtils.hasText(search)) {
            suppliers = includeDisabled
                    ? supplierRepository.findByCompanyIdAndSearchTerm(companyId, search.trim(), pageable)
                    : supplierRepository.findByCompanyIdAndSearchTermAndEnabledTrue(companyId, search.trim(), pageable);
        } else {
            suppliers = includeDisabled
                    ? supplierRepository.findByCompanyId(companyId, pageable)
                    : supplierRepository.findByCompanyIdAndEnabledTrue(companyId, pageable);
        }

        return suppliers.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierResponse getSupplierById(UUID companyId, UUID id) {
        resolveCompany(companyId);

        return toResponse(requireSupplier(companyId, id));
    }

    @Transactional
    public SupplierResponse createSupplier(UUID companyId, SupplierRequest request) {
        var company = resolveCompany(companyId);

        // The RFC key is per company: the same RFC in another company is a legitimate second catalog
        // entry, and only a duplicate inside this one is refused.
        if (supplierRepository.existsByCompanyIdAndRfc(companyId, request.rfc())) {
            throw new DuplicateSupplierException("Supplier with RFC '" + request.rfc() + "' already exists");
        }

        // Build Address entity from nested DTO
        Address address = buildAddress(request.address());

        // Build entity
        var supplier = Supplier.builder()
                .supplierName(request.supplierName())
                .razonSocial(request.razonSocial())
                .rfc(request.rfc())
                .email(request.email())
                .phoneNumber(request.phoneNumber())
                .internalNumber(request.internalNumber())
                .company(company)
                .address(address)
                .enabled(request.enabled() != null ? request.enabled() : true)
                .build();

        var saved = supplierRepository.save(supplier);

        logger.info(
                "Supplier created: id={}, supplierName={}, rfc={}, companyId={}",
                saved.getId(),
                saved.getSupplierName(),
                saved.getRfc(),
                companyId);

        return toResponse(saved);
    }

    @Transactional
    public SupplierResponse updateSupplier(UUID companyId, UUID id, SupplierRequest request) {
        resolveCompany(companyId);

        var supplier = requireSupplier(companyId, id);

        // Same-RFC-vs-another-row, scoped to the company: another company's supplier holding this RFC
        // is not this company's conflict.
        if (!supplier.getRfc().equals(request.rfc())
                && supplierRepository.existsByCompanyIdAndRfcAndIdNot(companyId, request.rfc(), id)) {
            throw new DuplicateSupplierException("Supplier with RFC '" + request.rfc() + "' already exists");
        }

        // Update fields
        supplier.setSupplierName(request.supplierName());
        supplier.setRazonSocial(request.razonSocial());
        supplier.setRfc(request.rfc());
        supplier.setEmail(request.email());
        supplier.setPhoneNumber(request.phoneNumber());
        supplier.setInternalNumber(request.internalNumber());
        supplier.setEnabled(request.enabled() != null ? request.enabled() : true);

        // Update address (dual-path: update existing Address entity or create new one)
        if (request.address() != null) {
            if (supplier.getAddress() != null) {
                updateAddress(supplier.getAddress(), request.address());
            } else {
                supplier.setAddress(buildAddress(request.address()));
            }
        }

        var updated = supplierRepository.save(supplier);

        logger.info(
                "Supplier updated: id={}, supplierName={}, rfc={}, companyId={}",
                updated.getId(),
                updated.getSupplierName(),
                updated.getRfc(),
                companyId);

        return toResponse(updated);
    }

    @Transactional
    public void deleteSupplier(UUID companyId, UUID id) {
        resolveCompany(companyId);

        var supplier = requireSupplier(companyId, id);

        supplier.setEnabled(false);
        supplierRepository.save(supplier);

        logger.info(
                "Supplier soft-deleted: id={}, supplierName={}, timestamp={}, companyId={}",
                id,
                supplier.getSupplierName(),
                java.time.LocalDateTime.now(),
                companyId);
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company
     * is denied before any row is loaded, which is the contract
     * {@link com.lifecontrol.api.product.service.ProductService} established for the company-scoped
     * catalogs.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException when the company does not exist
     */
    private Company resolveCompany(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);
        return companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    /**
     * Loads a supplier that belongs to the company named by the path, or answers 404.
     *
     * <p>The lookup is company-scoped rather than a load-then-compare, so a row of another company is
     * not even materialized and the answer cannot drift from the scope the query used.</p>
     */
    private Supplier requireSupplier(UUID companyId, UUID id) {
        return supplierRepository
                .findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new SupplierNotFoundException(id));
    }

    private SupplierResponse toResponse(Supplier supplier) {
        return new SupplierResponse(
                supplier.getId(),
                supplier.getSupplierName(),
                supplier.getRazonSocial(),
                supplier.getRfc(),
                supplier.getEmail(),
                supplier.getPhoneNumber(),
                supplier.getInternalNumber(),
                buildAddressResponse(supplier),
                supplier.getEnabled(),
                supplier.getCreatedAt(),
                supplier.getUpdatedAt(),
                supplier.getCompany() == null ? null : supplier.getCompany().getId());
    }

    private AddressResponse buildAddressResponse(Supplier supplier) {
        if (supplier.getAddress() != null) {
            // NEW record: Address entity exists → map from entity
            var addr = supplier.getAddress();
            return new AddressResponse(
                    addr.getId(),
                    addr.getStreet(),
                    addr.getStreetNumber(),
                    addr.getInternalNumber(),
                    addr.getNeighborhood(),
                    addr.getZipCode(),
                    addr.getCity(),
                    addr.getState(),
                    addr.getCountry() != null ? addr.getCountry().getId() : null);
        } else if (supplier.getStreet() != null) {
            // LEGACY record: inline columns → map to AddressResponse
            return new AddressResponse(
                    null,
                    supplier.getStreet(),
                    supplier.getStreetNumber(),
                    supplier.getInternalNumber(),
                    supplier.getNeighborhood(),
                    supplier.getZipCode(),
                    supplier.getCity(),
                    supplier.getState(),
                    null // Supplier did NOT have inline countryId
                    );
        }
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
