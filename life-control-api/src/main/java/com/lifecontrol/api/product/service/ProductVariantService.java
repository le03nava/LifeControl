package com.lifecontrol.api.product.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.dto.ProductVariantResponse;
import com.lifecontrol.api.product.dto.ProductVariantSearchResponse;
import com.lifecontrol.api.product.dto.ProductVariantStoreStockRequest;
import com.lifecontrol.api.product.dto.ProductVariantStoreStockResponse;
import com.lifecontrol.api.product.exception.DuplicateProductVariantException;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Variant definition CRUD plus the upsert of the per-store stock/pricing row.
 *
 * <p>The definition is GLOBAL: creating or updating a variant never takes a store. Stock and prices
 * live per store in {@code product_variant_store_stock} and are written through
 * {@link #upsertStoreStock(UUID, UUID, ProductVariantStoreStockRequest)}, the second write endpoint
 * of decision D6(b).</p>
 */
@Service
public class ProductVariantService {

    private static final Logger logger = LoggerFactory.getLogger(ProductVariantService.class);

    private final ProductVariantRepository productVariantRepository;
    private final ProductVariantStoreStockRepository productVariantStoreStockRepository;
    private final ProductRepository productRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CurrentUserContext currentUserContext;

    public ProductVariantService(
            ProductVariantRepository productVariantRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            ProductRepository productRepository,
            CompanyStoreRepository companyStoreRepository,
            CurrentUserContext currentUserContext) {
        this.productVariantRepository = productVariantRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.productRepository = productRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.currentUserContext = currentUserContext;
    }

    @Transactional(readOnly = true)
    public Page<ProductVariantResponse> listVariants(UUID productId, Pageable pageable) {
        return listVariants(productId, null, pageable);
    }

    /**
     * Lists a product's enabled variants, optionally narrowed to a single store.
     *
     * <p>When {@code companyStoreId} is {@code null} the global definitions are returned and the
     * store-scoped fields of {@link ProductVariantResponse} ({@code companyStoreId},
     * {@code listPrice}, {@code costPrice}, {@code stock}) are {@code null}. When it is present, the
     * store row is joined and those fields are populated from it.</p>
     */
    @Transactional(readOnly = true)
    public Page<ProductVariantResponse> listVariants(UUID productId, UUID companyStoreId, Pageable pageable) {
        var product = validateProductExists(productId);

        if (companyStoreId == null) {
            return productVariantRepository
                    .findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable)
                    .map(variant -> toResponse(variant, product.getSku()));
        }
        return productVariantRepository.findStoreScopedByProductIdAndStoreId(productId, companyStoreId, pageable);
    }

    @Transactional(readOnly = true)
    public ProductVariantResponse getVariant(UUID productId, UUID variantId) {
        var product = validateProductExists(productId);

        var variant = findVariantOfProduct(productId, variantId);
        return toResponse(variant, product.getSku());
    }

    /**
     * Creates the GLOBAL definition of a variant. The owning product is the path variable, not part
     * of the request. Both uniqueness rules are checked here and, ultimately, by the database:
     * {@code bar_code} globally (D2) and {@code (product_id, variant_name)} (D3).
     *
     * @throws ProductNotFoundException when the product does not exist
     * @throws DuplicateProductVariantException when the barcode or the (product, name) pair already
     *     exists
     */
    @Transactional
    public ProductVariantResponse createVariant(UUID productId, ProductVariantRequest request) {
        var product = validateProductExists(productId);

        logger.info(
                "Creating product variant: productId={}, barCode={}, variantName={}",
                productId,
                request.barCode(),
                request.variantName());

        if (productVariantRepository.existsByBarCode(request.barCode())) {
            throw new DuplicateProductVariantException(
                    "Product variant with barCode already exists: " + request.barCode());
        }
        if (productVariantRepository.existsByProductIdAndVariantName(productId, request.variantName())) {
            throw new DuplicateProductVariantException(
                    "Product variant with name " + request.variantName() + " already exists for product " + productId);
        }

        var variant = ProductVariant.builder()
                .productId(productId)
                .barCode(request.barCode())
                .variantName(request.variantName())
                .enabled(true)
                .build();

        var saved = productVariantRepository.save(variant);
        logger.info("Product variant created: id={}, productId={}", saved.getId(), productId);

        return toResponse(saved, product.getSku());
    }

    @Transactional
    public ProductVariantResponse updateVariant(UUID productId, UUID variantId, ProductVariantRequest request) {
        var product = validateProductExists(productId);

        logger.info("Updating product variant: variantId={}, productId={}", variantId, productId);

        var variant = findVariantOfProduct(productId, variantId);

        if (!variant.getBarCode().equals(request.barCode())
                && productVariantRepository.existsByBarCodeAndIdNot(request.barCode(), variantId)) {
            throw new DuplicateProductVariantException(
                    "Product variant with barCode already exists: " + request.barCode());
        }
        if (!variant.getVariantName().equals(request.variantName())
                && productVariantRepository.existsByProductIdAndVariantNameAndIdNot(
                        productId, request.variantName(), variantId)) {
            throw new DuplicateProductVariantException(
                    "Product variant with name " + request.variantName() + " already exists for product " + productId);
        }

        variant.setBarCode(request.barCode());
        variant.setVariantName(request.variantName());

        var updated = productVariantRepository.save(variant);
        logger.info("Product variant updated: id={}", variantId);

        return toResponse(updated, product.getSku());
    }

    /** Soft delete of the definition ({@code enabled = false}); the store rows are left intact. */
    @Transactional
    public void deleteVariant(UUID productId, UUID variantId) {
        validateProductExists(productId);

        logger.info("Soft-deleting product variant: variantId={}, productId={}", variantId, productId);

        var variant = findVariantOfProduct(productId, variantId);
        variant.setEnabled(false);
        productVariantRepository.save(variant);
        logger.info("Product variant soft-deleted: id={}", variantId);
    }

    /**
     * Re-enables a soft-deleted definition.
     *
     * <p>The definition request no longer carries {@code enabled} (the flag is not part of the global
     * identity), so without this operation a soft-deleted variant could never come back. It mirrors
     * the repository-wide re-enable convention ({@code PATCH /{id}/enable}, as in promotion,
     * customer, measure unit and the store tree levels), and the lookup is intentionally not filtered
     * by {@code enabled} so a disabled row can be loaded.</p>
     *
     * @throws ProductNotFoundException when the product does not exist
     * @throws ProductVariantNotFoundException when the variant does not exist for that product
     */
    @Transactional
    public ProductVariantResponse enableVariant(UUID productId, UUID variantId) {
        var product = validateProductExists(productId);

        logger.info("Re-enabling product variant: variantId={}, productId={}", variantId, productId);

        var variant = findVariantOfProduct(productId, variantId);
        variant.setEnabled(true);
        var saved = productVariantRepository.save(variant);
        logger.info("Product variant re-enabled: id={}", variantId);

        return toResponse(saved, product.getSku());
    }

    /**
     * Upserts the PER-STORE row of a variant: stock and prices for one store. The row is created
     * with its column defaults when it does not exist yet, then updated in place.
     *
     * <p>The row is created with the conflict-tolerant insert before the pessimistic lock, mirroring
     * {@code ProductVariantLocationRepository#insertBalanceIfAbsent}: an {@code ON CONFLICT DO
     * NOTHING} avoids the unique violation that would abort the transaction, so the lock that follows
     * is always held over an existing row.</p>
     *
     * @throws ProductVariantNotFoundException when the variant does not exist
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller holds no
     *     grant for the store's scope (403)
     */
    @Transactional
    public ProductVariantStoreStockResponse upsertStoreStock(
            UUID variantId, UUID companyStoreId, ProductVariantStoreStockRequest request) {
        if (!productVariantRepository.existsById(variantId)) {
            throw new ProductVariantNotFoundException(variantId);
        }

        var store = companyStoreRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new CompanyStoreNotFoundException(companyStoreId));
        verifyStoreAccess(store);

        logger.info("Upserting variant store stock: variantId={}, companyStoreId={}", variantId, companyStoreId);

        productVariantStoreStockRepository.insertStoreStockIfAbsent(variantId, companyStoreId);
        var row = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(variantId, companyStoreId)
                .orElseThrow(() -> new IllegalStateException("Product variant store stock row not found for variant "
                        + variantId + " and store " + companyStoreId + " after it was locked or created"));

        if (request.listPrice() != null) {
            row.setListPrice(request.listPrice());
        }
        if (request.costPrice() != null) {
            row.setCostPrice(request.costPrice());
        }
        if (request.stock() != null) {
            row.setStock(request.stock());
        }

        var saved = productVariantStoreStockRepository.save(row);
        return toStoreResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<ProductVariantSearchResponse> searchVariants(String query, UUID storeId, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return Page.empty(pageable);
        }

        var trimmed = query.trim();
        return productVariantRepository.searchByQuery(trimmed, storeId, pageable);
    }

    // ─── Private Helpers ──────────────────────────────────────────────────

    private Product validateProductExists(UUID productId) {
        return productRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    }

    /**
     * Authorizes the caller for the store that owns the row being written.
     *
     * <p>The path carries only the store id, so the company &rarr; country &rarr; region &rarr; zone
     * &rarr; store chain the guard needs is derived from the store itself. This mirrors the check
     * every other store-scoped write applies ({@code StoreLocationService},
     * {@code StoreInventorySettingsService}, {@code GoodsReceiptService}); {@code lc-admin} is
     * exempt inside {@link CurrentUserContext#verifyCompanyStoreAccess}. Without it, any principal
     * holding {@code lc-sales} could set the sellable stock and the list and cost prices of a store
     * of another company.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the caller holds no
     *     grant for the store's scope
     */
    private void verifyStoreAccess(CompanyStore store) {
        var zone = store.getCompanyZone();
        var region = zone.getCompanyRegion();
        var country = region.getCompanyCountry();

        currentUserContext.verifyCompanyStoreAccess(
                country.getCompany().getId(), country.getId(), region.getId(), zone.getId(), store.getId());
    }

    private ProductVariant findVariantOfProduct(UUID productId, UUID variantId) {
        return productVariantRepository
                .findById(variantId)
                .filter(v -> v.getProductId().equals(productId))
                .orElseThrow(() -> new ProductVariantNotFoundException(variantId));
    }

    private ProductVariantResponse toResponse(ProductVariant variant, String productSku) {
        return new ProductVariantResponse(
                variant.getId(),
                variant.getProductId(),
                null,
                variant.getBarCode(),
                productSku,
                variant.getVariantName(),
                null,
                null,
                null,
                variant.getEnabled(),
                variant.getCreatedAt(),
                variant.getUpdatedAt());
    }

    private ProductVariantStoreStockResponse toStoreResponse(ProductVariantStoreStock row) {
        return new ProductVariantStoreStockResponse(
                row.getCompanyStoreId(), row.getListPrice(), row.getCostPrice(), row.getStock());
    }
}
