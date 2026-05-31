package com.lifecontrol.api.product.service;

import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductResponse;
import com.lifecontrol.api.product.exception.DuplicateProductException;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        logger.info("Creating product with SKU: {}", request.sku());

        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateProductException(
                    "Ya existe un producto con SKU: " + request.sku());
        }

        var product = Product.builder()
                .sku(request.sku())
                .name(request.name())
                .shortName(request.shortName())
                .satCode(request.satCode())
                .productType(request.productType())
                .attributes(request.attributes())
                .enabled(true)
                .build();

        var saved = productRepository.save(product);
        logger.info("Product created successfully with id: {}", saved.getId());

        return mapToResponse(saved);
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, ProductRequest request) {
        logger.info("Updating product with id: {}", id);

        var product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        // Check SKU uniqueness only if SKU changed
        if (!product.getSku().equals(request.sku())
                && productRepository.existsBySkuAndIdNot(request.sku(), id)) {
            throw new DuplicateProductException(
                    "Ya existe un producto con SKU: " + request.sku());
        }

        // Update scalar fields
        product.setSku(request.sku());
        product.setName(request.name());
        product.setShortName(request.shortName());
        product.setSatCode(request.satCode());
        product.setProductType(request.productType());

        // JSONB merge: shallow via HashMap.putAll()
        // preserve existing attributes, overwrite/add from request
        // null = preserve; empty map = clear; non-empty = merge
        if (request.attributes() != null) {
            if (request.attributes().isEmpty()) {
                product.setAttributes(new HashMap<>());
            } else {
                Map<String, Object> existingAttrs = product.getAttributes();
                Map<String, Object> merged = existingAttrs != null
                        ? new HashMap<>(existingAttrs)
                        : new HashMap<>();
                merged.putAll(request.attributes());
                product.setAttributes(merged);
            }
        }
        // If request.attributes() is null, keep existing attributes unchanged

        var updated = productRepository.save(product);
        logger.info("Product updated successfully with id: {}", updated.getId());

        return mapToResponse(updated);
    }

    @Transactional
    public void deleteProduct(UUID id) {
        logger.info("Soft-deleting product with id: {}", id);

        var product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        if (!product.getEnabled()) {
            throw new ProductNotFoundException(id);
        }

        product.setEnabled(false);
        productRepository.save(product);

        logger.info("Product soft-deleted: id={}, sku={}", id, product.getSku());
    }

    @Transactional(readOnly = true)
    public ProductResponse findProduct(UUID id) {
        var product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        if (!product.getEnabled()) {
            throw new ProductNotFoundException(id);
        }

        return mapToResponse(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> listProducts(Pageable pageable, String search, boolean includeDisabled) {
        Page<Product> page;

        if (search != null && !search.isBlank()) {
            if (includeDisabled) {
                page = productRepository.findBySearchTerm(search.trim(), pageable);
            } else {
                page = productRepository.findBySearchTermAndEnabledTrue(search.trim(), pageable);
            }
        } else {
            if (includeDisabled) {
                page = productRepository.findAll(pageable);
            } else {
                page = productRepository.findByEnabledTrue(pageable);
            }
        }

        return page.map(this::mapToResponse);
    }

    private ProductResponse mapToResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getShortName(),
                product.getSatCode(),
                product.getProductType(),
                product.getAttributes(),
                product.getEnabled(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
