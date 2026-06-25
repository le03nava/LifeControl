package com.lifecontrol.api.supplier.service;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.common.address.dto.AddressResponse;
import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.supplier.dto.SupplierRequest;
import com.lifecontrol.api.supplier.dto.SupplierResponse;
import com.lifecontrol.api.supplier.exception.DuplicateSupplierException;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
public class SupplierService {

    private static final Logger logger = LoggerFactory.getLogger(SupplierService.class);

    private final SupplierRepository supplierRepository;
    private final CountryRepository countryRepository;

    public SupplierService(SupplierRepository supplierRepository,
                           CountryRepository countryRepository) {
        this.supplierRepository = supplierRepository;
        this.countryRepository = countryRepository;
    }

    @Transactional(readOnly = true)
    public Page<SupplierResponse> getAllSuppliers(Pageable pageable, String search) {
        Page<Supplier> suppliers;

        if (StringUtils.hasText(search)) {
            suppliers = supplierRepository.findBySearchTerm(search.trim(), pageable);
        } else {
            suppliers = supplierRepository.findAll(pageable);
        }

        return suppliers.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierResponse getSupplierById(UUID id) {
        return supplierRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new SupplierNotFoundException(id));
    }

    @Transactional
    public SupplierResponse createSupplier(SupplierRequest request) {
        // Validate RFC uniqueness
        if (supplierRepository.existsByRfc(request.rfc())) {
            throw new DuplicateSupplierException("Ya existe un proveedor con RFC: " + request.rfc());
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
                .address(address)
                .enabled(request.enabled() != null ? request.enabled() : true)
                .build();

        var saved = supplierRepository.save(supplier);

        logger.info("Supplier created: id={}, supplierName={}, rfc={}",
                saved.getId(), saved.getSupplierName(), saved.getRfc());

        return toResponse(saved);
    }

    @Transactional
    public SupplierResponse updateSupplier(UUID id, SupplierRequest request) {
        // Fetch existing supplier
        var supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new SupplierNotFoundException(id));

        // Validate RFC uniqueness (excluding current supplier)
        if (!supplier.getRfc().equals(request.rfc()) && supplierRepository.existsByRfcAndIdNot(request.rfc(), id)) {
            throw new DuplicateSupplierException("Ya existe un proveedor con RFC: " + request.rfc());
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

        logger.info("Supplier updated: id={}, supplierName={}, rfc={}",
                updated.getId(), updated.getSupplierName(), updated.getRfc());

        return toResponse(updated);
    }

    @Transactional
    public void deleteSupplier(UUID id) {
        var supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new SupplierNotFoundException(id));

        supplier.setEnabled(false);
        supplierRepository.save(supplier);

        logger.info("Supplier soft-deleted: id={}, supplierName={}, timestamp={}",
                id, supplier.getSupplierName(), java.time.LocalDateTime.now());
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
                supplier.getUpdatedAt()
        );
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
                    addr.getCountry() != null ? addr.getCountry().getId() : null
            );
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
                    null  // Supplier did NOT have inline countryId
            );
        }
        return null;
    }

    private Address buildAddress(AddressRequest request) {
        if (request == null) return null;
        Country country = null;
        if (request.countryId() != null) {
            country = countryRepository.findById(request.countryId())
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
            Country country = countryRepository.findById(request.countryId())
                    .orElseThrow(() -> new CountryNotFoundException(request.countryId()));
            address.setCountry(country);
        } else {
            address.setCountry(null);
        }
    }
}
