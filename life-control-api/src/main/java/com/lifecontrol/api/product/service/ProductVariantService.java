package com.lifecontrol.api.product.service;

import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.dto.ProductVariantResponse;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductVariantService {

    private static final Logger logger = LoggerFactory.getLogger(ProductVariantService.class);

    private final ProductVariantRepository productVariantRepository;
    private final ProductRepository productRepository;

    public ProductVariantService(ProductVariantRepository productVariantRepository,
                                  ProductRepository productRepository) {
        this.productVariantRepository = productVariantRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public Page<ProductVariantResponse> listVariants(UUID productId, Pageable pageable) {
        validateProductExists(productId);

        return productVariantRepository.findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductVariantResponse getVariant(UUID productId, UUID variantId) {
        validateProductExists(productId);

        var variant = productVariantRepository.findById(variantId)
                .filter(v -> v.getProductId().equals(productId))
                .orElseThrow(() -> new ProductVariantNotFoundException(variantId));

        return toResponse(variant);
    }

    @Transactional
    public ProductVariantResponse createVariant(UUID productId, ProductVariantRequest request) {
        validateProductExists(productId);

        logger.info("Creating product variant: productId={}, barCode={}, sku={}",
                productId, request.barCode(), request.sku());

        var variant = ProductVariant.builder()
                .productId(productId)
                .companyStoreId(request.companyStoreId())
                .barCode(request.barCode())
                .sku(request.sku())
                .variantName(request.variantName())
                .listPrice(request.listPrice())
                .costPrice(request.costPrice())
                .stock(request.stock())
                .enabled(request.enabled())
                .build();

        var saved = productVariantRepository.save(variant);
        logger.info("Product variant created: id={}, productId={}", saved.getId(), productId);

        return toResponse(saved);
    }

    @Transactional
    public ProductVariantResponse updateVariant(UUID productId, UUID variantId, ProductVariantRequest request) {
        validateProductExists(productId);

        logger.info("Updating product variant: variantId={}, productId={}", variantId, productId);

        var variant = productVariantRepository.findById(variantId)
                .filter(v -> v.getProductId().equals(productId))
                .orElseThrow(() -> new ProductVariantNotFoundException(variantId));

        variant.setCompanyStoreId(request.companyStoreId());
        variant.setBarCode(request.barCode());
        variant.setSku(request.sku());
        variant.setVariantName(request.variantName());
        variant.setListPrice(request.listPrice());
        variant.setCostPrice(request.costPrice());
        variant.setStock(request.stock());
        variant.setEnabled(request.enabled());

        var updated = productVariantRepository.save(variant);
        logger.info("Product variant updated: id={}", variantId);

        return toResponse(updated);
    }

    @Transactional
    public void deleteVariant(UUID productId, UUID variantId) {
        validateProductExists(productId);

        logger.info("Soft-deleting product variant: variantId={}, productId={}", variantId, productId);

        var variant = productVariantRepository.findById(variantId)
                .filter(v -> v.getProductId().equals(productId))
                .orElseThrow(() -> new ProductVariantNotFoundException(variantId));

        variant.setEnabled(false);
        productVariantRepository.save(variant);
        logger.info("Product variant soft-deleted: id={}", variantId);
    }

    // ─── Private Helpers ──────────────────────────────────────────────────

    private void validateProductExists(UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }
    }

    private ProductVariantResponse toResponse(ProductVariant variant) {
        return new ProductVariantResponse(
                variant.getId(),
                variant.getProductId(),
                variant.getCompanyStoreId(),
                variant.getBarCode(),
                variant.getSku(),
                variant.getVariantName(),
                variant.getListPrice(),
                variant.getCostPrice(),
                variant.getStock(),
                variant.getEnabled(),
                variant.getCreatedAt(),
                variant.getUpdatedAt()
        );
    }
}
