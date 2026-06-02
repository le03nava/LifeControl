package com.lifecontrol.api.supplier.service;

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

    public SupplierService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
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

        // Build entity
        var supplier = Supplier.builder()
                .supplierName(request.supplierName())
                .razonSocial(request.razonSocial())
                .rfc(request.rfc())
                .email(request.email())
                .phoneNumber(request.phoneNumber())
                .street(request.street())
                .streetNumber(request.streetNumber())
                .neighborhood(request.neighborhood())
                .zipCode(request.zipCode())
                .city(request.city())
                .state(request.state())
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
        supplier.setStreet(request.street());
        supplier.setStreetNumber(request.streetNumber());
        supplier.setNeighborhood(request.neighborhood());
        supplier.setZipCode(request.zipCode());
        supplier.setCity(request.city());
        supplier.setState(request.state());
        supplier.setEnabled(request.enabled() != null ? request.enabled() : true);

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
                supplier.getStreet(),
                supplier.getStreetNumber(),
                supplier.getNeighborhood(),
                supplier.getZipCode(),
                supplier.getCity(),
                supplier.getState(),
                supplier.getEnabled(),
                supplier.getCreatedAt(),
                supplier.getUpdatedAt()
        );
    }
}
