package com.lifecontrol.api.product.supplier.service;

import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierRequest;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierResponse;
import com.lifecontrol.api.product.supplier.dto.SupplierProductResponse;
import com.lifecontrol.api.product.supplier.exception.DuplicateProductSupplierException;
import com.lifecontrol.api.product.supplier.exception.ProductSupplierNotFoundException;
import com.lifecontrol.api.product.supplier.model.ProductSupplier;
import com.lifecontrol.api.product.supplier.repository.ProductSupplierRepository;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProductSupplierService {

    private static final Logger logger = LoggerFactory.getLogger(ProductSupplierService.class);

    private final ProductSupplierRepository productSupplierRepository;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;

    public ProductSupplierService(ProductSupplierRepository productSupplierRepository,
                                   ProductRepository productRepository,
                                   SupplierRepository supplierRepository) {
        this.productSupplierRepository = productSupplierRepository;
        this.productRepository = productRepository;
        this.supplierRepository = supplierRepository;
    }

    @Transactional(readOnly = true)
    public List<ProductSupplierResponse> listSuppliersByProductId(UUID productId) {
        // Verify product exists
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }

        return productSupplierRepository.findByProductId(productId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierProductResponse> listProductsBySupplier(UUID supplierId, String search) {
        logger.debug("Listing products for supplier {} with search: {}", supplierId, search);

        return productSupplierRepository.findBySupplierIdWithSearch(supplierId, search).stream()
                .map(ps -> new SupplierProductResponse(
                        ps.getProduct().getId(),
                        ps.getProduct().getName(),
                        ps.getProduct().getSku()))
                .toList();
    }

    @Transactional
    public ProductSupplierResponse addSupplierToProduct(UUID productId, ProductSupplierRequest request) {
        logger.info("Adding supplier {} to product {}", request.supplierId(), productId);

        var product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        var supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new SupplierNotFoundException(request.supplierId()));

        if (productSupplierRepository.existsByProductIdAndSupplierId(productId, request.supplierId())) {
            throw new DuplicateProductSupplierException(supplier.getSupplierName());
        }

        // Handle main flag: if new relation is main, unset any existing main
        if (Boolean.TRUE.equals(request.main())) {
            unsetPreviousMain(productId, null);
        }

        var entity = ProductSupplier.builder()
                .product(product)
                .supplier(supplier)
                .purchaseCost(request.purchaseCost())
                .main(request.main())
                .enabled(request.enabled())
                .build();

        var saved = productSupplierRepository.save(entity);
        logger.info("Supplier {} added to product {} successfully", request.supplierId(), productId);

        return toResponse(saved);
    }

    @Transactional
    public ProductSupplierResponse updateSupplier(UUID productId, UUID id, ProductSupplierRequest request) {
        logger.info("Updating supplier relation {} for product {}", id, productId);

        var relation = productSupplierRepository.findByProductIdAndId(productId, id)
                .orElseThrow(() -> new ProductSupplierNotFoundException(id));

        // Handle main flag: if setting main=true and wasn't already main, transfer the flag
        if (Boolean.TRUE.equals(request.main()) && !Boolean.TRUE.equals(relation.getMain())) {
            unsetPreviousMain(productId, id);
        }

        relation.setPurchaseCost(request.purchaseCost());
        relation.setMain(request.main());
        relation.setEnabled(request.enabled());

        var saved = productSupplierRepository.save(relation);
        logger.info("Supplier relation {} updated for product {} successfully", id, productId);

        return toResponse(saved);
    }

    @Transactional
    public void removeSupplierFromProduct(UUID productId, UUID id) {
        logger.info("Removing supplier relation {} from product {}", id, productId);

        var relation = productSupplierRepository.findByProductIdAndId(productId, id)
                .orElseThrow(() -> new ProductSupplierNotFoundException(id));

        productSupplierRepository.delete(relation);
        logger.info("Supplier relation {} removed from product {}", id, productId);
    }

    /**
     * Unsets the main flag on any existing main supplier relation for the given product.
     * If an excludeId is provided, that relation will not be modified (used during updates
     * when transferring the main flag to a different relation).
     */
    private void unsetPreviousMain(UUID productId, UUID excludeId) {
        productSupplierRepository.findMainByProductId(productId)
                .filter(existing -> excludeId == null || !existing.getId().equals(excludeId))
                .ifPresent(existing -> {
                    existing.setMain(false);
                    productSupplierRepository.save(existing);
                });
    }

    private ProductSupplierResponse toResponse(ProductSupplier ps) {
        return new ProductSupplierResponse(
                ps.getId(),
                ps.getProduct().getId(),
                ps.getSupplier().getId(),
                ps.getSupplier().getSupplierName(),
                ps.getPurchaseCost(),
                ps.getMain(),
                ps.getEnabled(),
                ps.getCreatedAt(),
                ps.getUpdatedAt()
        );
    }
}
