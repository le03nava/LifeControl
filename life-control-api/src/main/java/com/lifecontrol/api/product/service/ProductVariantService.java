package com.lifecontrol.api.product.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.measureunit.exception.MeasureUnitNotFoundException;
import com.lifecontrol.api.measureunit.model.MeasureUnit;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.attribute.model.ProductAttributeLevel;
import com.lifecontrol.api.product.attribute.service.ProductAttributeCatalogService;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.dto.ProductVariantResponse;
import com.lifecontrol.api.product.dto.ProductVariantSearchResponse;
import com.lifecontrol.api.product.dto.ProductVariantStoreStockRequest;
import com.lifecontrol.api.product.dto.ProductVariantStoreStockResponse;
import com.lifecontrol.api.product.exception.BarcodeRequiredException;
import com.lifecontrol.api.product.exception.DisabledMeasureUnitException;
import com.lifecontrol.api.product.exception.DuplicateProductVariantException;
import com.lifecontrol.api.product.exception.MeasureUnitChangeNotAllowedException;
import com.lifecontrol.api.product.exception.MeasureUnitRequiredException;
import com.lifecontrol.api.product.exception.MeasureUnitTypeMismatchException;
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
import com.lifecontrol.api.store.service.CompanyStoreOwnerResolver;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
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
 *
 * <p>Both write paths also carry the variant's measure unit (V29), which is <b>mandatory</b> since
 * ADR-0006 decision 3: the request must name a unit id, this
 * service resolves it against the catalog and applies the two guards that keep the stored quantities
 * readable — {@code unit_type} coherence inside the product (D4) and immutability once the variant
 * acquired a quantity meaning (D3).</p>
 *
 * <p>The product-scoped methods take the company named by the path as well, and every one of them
 * resolves the product <b>within</b> it ({@link #validateProductExists(UUID, UUID)}) before reading or
 * writing a variant: the variant table hangs off the product, so the product's company is the scope —
 * including the barcode key, which is unique per company and not per table.
 *
 * <p>The two store-scoped operations ({@link #upsertStoreStock} and {@link #searchVariants}) address the
 * variant directly and derive their scope from the store. For them the derived company is not only the
 * thing the access guard verifies ({@link #verifyStoreAccess}) but a <b>predicate of the query</b>: the
 * store grant says who may read the store, it says nothing about which tenant's rows a per-store row
 * joins onto, so the company travels into the SQL of every store-scoped read that returns variants.
 * It is also what makes the store-stock write refuse a pair whose two sides belong to different
 * companies ({@link #upsertStoreStock}).
 */
@Service
public class ProductVariantService {

    private static final Logger logger = LoggerFactory.getLogger(ProductVariantService.class);

    private final ProductVariantRepository productVariantRepository;
    private final ProductVariantStoreStockRepository productVariantStoreStockRepository;
    private final ProductRepository productRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyStoreOwnerResolver companyStoreOwnerResolver;
    private final CurrentUserContext currentUserContext;
    private final InventoryService inventoryService;
    private final MeasureUnitRepository measureUnitRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final ProductAttributeCatalogService productAttributeCatalogService;

    public ProductVariantService(
            ProductVariantRepository productVariantRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            ProductRepository productRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyStoreOwnerResolver companyStoreOwnerResolver,
            CurrentUserContext currentUserContext,
            InventoryService inventoryService,
            MeasureUnitRepository measureUnitRepository,
            InventoryMovementRepository inventoryMovementRepository,
            ProductAttributeCatalogService productAttributeCatalogService) {
        this.productVariantRepository = productVariantRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.productRepository = productRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyStoreOwnerResolver = companyStoreOwnerResolver;
        this.currentUserContext = currentUserContext;
        this.inventoryService = inventoryService;
        this.measureUnitRepository = measureUnitRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.productAttributeCatalogService = productAttributeCatalogService;
    }

    @Transactional(readOnly = true)
    public Page<ProductVariantResponse> listVariants(UUID companyId, UUID productId, Pageable pageable) {
        return listVariants(companyId, productId, null, false, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProductVariantResponse> listVariants(
            UUID companyId, UUID productId, UUID companyStoreId, Pageable pageable) {
        return listVariants(companyId, productId, companyStoreId, false, pageable);
    }

    /**
     * Lists a product's variants, optionally narrowed to a single store, with an opt-in switch for
     * the soft-deleted global definitions.
     *
     * <p>When {@code companyStoreId} is {@code null} the global definitions are returned and the
     * store-scoped fields of {@link ProductVariantResponse} ({@code companyStoreId},
     * {@code listPrice}, {@code costPrice}, {@code stock}) are {@code null}. That branch reads no
     * store and applies no guard. {@code includeDisabled} affects this branch only: it defaults to
     * {@code false} (the soft-deleted definitions are filtered out, so the parameterless read keeps
     * its previous behavior) and, when {@code true}, lists them too so a soft-deleted definition can
     * be re-enabled with {@code PATCH .../enable}.</p>
     *
     * <p>When {@code companyStoreId} is present the store row is joined and those fields are
     * populated from it, so the caller must hold a grant covering that store: the store id comes from
     * the query string, so {@code @PreAuthorize} alone only proves the caller holds some variant role.
     * The company &rarr; country &rarr; region &rarr; zone &rarr; store chain is derived from the
     * store and verified with {@link #verifyStoreAccess} before the query, exactly like
     * {@link #searchVariants}. The company that chain reaches is also the <b>query predicate</b>: a
     * store belongs to one tenant, and the rows its per-store table joins onto are rows of whatever
     * tenant wrote them, so a store of another tenant cannot be used to read this product's variants.
     * This branch and {@link #searchVariants} always filter
     * {@code enabled = true} regardless of {@code includeDisabled}, because they feed the
     * purchase-order variant picker and the point-of-sale selector.</p>
     *
     * @throws CompanyStoreNotFoundException when the store-scoped branch names a store that does not
     *     exist
     * @throws org.springframework.security.access.AccessDeniedException when the store-scoped branch
     *     names a store the caller holds no grant for (403)
     */
    @Transactional(readOnly = true)
    public Page<ProductVariantResponse> listVariants(
            UUID companyId, UUID productId, UUID companyStoreId, boolean includeDisabled, Pageable pageable) {
        var product = validateProductExists(companyId, productId);

        if (companyStoreId == null) {
            var page = includeDisabled
                    ? productVariantRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable)
                    : productVariantRepository.findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable);
            return page.map(variant -> toResponse(variant, product.getSku()));
        }

        var store = companyStoreRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new CompanyStoreNotFoundException(companyStoreId));
        verifyStoreAccess(store);

        return productVariantRepository.findStoreScopedByProductIdAndStoreId(
                companyStoreOwnerResolver.companyIdOf(store), productId, companyStoreId, pageable);
    }

    @Transactional(readOnly = true)
    public ProductVariantResponse getVariant(UUID companyId, UUID productId, UUID variantId) {
        var product = validateProductExists(companyId, productId);

        var variant = findVariantOfProduct(productId, variantId);
        return toResponse(variant, product.getSku());
    }

    /**
     * Creates the company-scoped definition of a variant. The owning product is the path variable, not
     * part of the request, and the variant takes the company from the path (V39) because the barcode key
     * has to live on the row it covers — the product was already checked to belong to that company, and
     * since {@code V40} the composite foreign key makes a disagreeing pair unwritable. Both uniqueness
     * rules are checked here and enforced by the database too: {@code (company_id, bar_code)} and
     * {@code (product_id, variant_name)} (D3).
     *
     * <p>The barcode gate is asked for <b>this company's</b> key, never for the table's: a barcode taken
     * in another company is not a conflict, because two tenants are separate businesses. V56 replaced
     * V40's full key with the partial {@code uq_variant_bar_code}, so the service and the index answer the
     * same question — a barcode that exists, in this company — and this method is where the 409 gets a
     * message instead of an index name. Whether a barcode is required at all is a different question and
     * it is the product's mode that answers it ({@link #validateBarcodeForMode}).</p>
     *
     * @throws ProductNotFoundException when the product does not exist
     * @throws DuplicateProductVariantException when the barcode or the (product, name) pair already
     *     exists
     * @throws MeasureUnitRequiredException when the request omits the unit (400) — one that names no
     *     unit is refused here, before the catalog is read, instead of by the {@code NOT NULL} column
     * @throws MeasureUnitNotFoundException when the named measure unit does not exist (404)
     * @throws DisabledMeasureUnitException when the named measure unit is soft-deleted (400)
     * @throws MeasureUnitTypeMismatchException when the unit's type contradicts the product's other
     *     variants (400)
     * @throws BarcodeRequiredException when the product is {@code MULTI} and the request names no
     *     barcode (400) — ADR-0006 decision 4, asked of the product and not of an annotation
     */
    @Transactional
    public ProductVariantResponse createVariant(UUID companyId, UUID productId, ProductVariantRequest request) {
        var product = validateProductExists(companyId, productId);

        logger.info(
                "Creating product variant: productId={}, barCode={}, variantName={}",
                productId,
                request.barCode(),
                request.variantName());

        validateBarcodeForMode(product, request.barCode());

        if (productVariantRepository.existsByBarCodeAndCompanyId(request.barCode(), companyId)) {
            throw new DuplicateProductVariantException(
                    "Product variant with barCode already exists: " + request.barCode());
        }
        if (productVariantRepository.existsByProductIdAndVariantName(productId, request.variantName())) {
            throw new DuplicateProductVariantException(
                    "Product variant with name " + request.variantName() + " already exists for product " + productId);
        }

        // No variant is excluded on create: the type gate reads the product's other variants only.
        var measureUnit = validateMeasureUnit(productId, null, request.measureUnitId());

        // The variant's attributes are the VARIANT-level entries of the product's category profile, so
        // the category decides them and the validation runs before anything is written.
        validateAttributes(product, request.attributes());

        var variant = ProductVariant.builder()
                .productId(productId)
                .companyId(companyId)
                .measureUnitId(measureUnit.getId())
                .barCode(request.barCode())
                .variantName(request.variantName())
                .attributes(request.attributes())
                .enabled(true)
                .build();

        var saved = productVariantRepository.save(variant);
        logger.info("Product variant created: id={}, productId={}", saved.getId(), productId);

        return toResponse(saved, product.getSku());
    }

    /**
     * Updates the GLOBAL definition of a variant, including the measure unit it declares.
     *
     * <p>The unit and its two guards are validated <strong>before</strong> any setter runs, so a
     * refused write leaves the entity exactly as it was.</p>
     *
     * @throws ProductNotFoundException when the product does not exist
     * @throws ProductVariantNotFoundException when the variant does not exist for that product
     * @throws DuplicateProductVariantException when the barcode or the (product, name) pair already
     *     belongs to another variant
     * @throws MeasureUnitRequiredException when the request omits the unit (400)
     * @throws MeasureUnitNotFoundException when the named measure unit does not exist (404)
     * @throws DisabledMeasureUnitException when the named measure unit is soft-deleted (400)
     * @throws MeasureUnitTypeMismatchException when the unit's type contradicts the product's other
     *     variants (400)
     * @throws MeasureUnitChangeNotAllowedException when the variant already holds stock or ledger
     *     movements and the unit would change (409)
     * @throws BarcodeRequiredException when the product is {@code MULTI} and the write would leave the
     *     variant without a barcode (400) — ADR-0006 decision 4
     */
    @Transactional
    public ProductVariantResponse updateVariant(
            UUID companyId, UUID productId, UUID variantId, ProductVariantRequest request) {
        var product = validateProductExists(companyId, productId);

        logger.info("Updating product variant: variantId={}, productId={}", variantId, productId);

        var variant = findVariantOfProduct(productId, variantId);

        validateBarcodeForMode(product, request.barCode());

        // `Objects.equals` and not `variant.getBarCode().equals(...)`: the stored barcode may be absent
        // since ADR-0006 decision 4, and a `SIMPLE` product's variant legitimately has none.
        if (!Objects.equals(variant.getBarCode(), request.barCode())
                && productVariantRepository.existsByBarCodeAndCompanyIdAndIdNot(
                        request.barCode(), companyId, variantId)) {
            throw new DuplicateProductVariantException(
                    "Product variant with barCode already exists: " + request.barCode());
        }
        if (!variant.getVariantName().equals(request.variantName())
                && productVariantRepository.existsByProductIdAndVariantNameAndIdNot(
                        productId, request.variantName(), variantId)) {
            throw new DuplicateProductVariantException(
                    "Product variant with name " + request.variantName() + " already exists for product " + productId);
        }

        // Validation order matters: existence/enabled (404/400) and the D4 type gate (400) run before
        // the D3 state conflict (409), so a request that is not even valid never reports a conflict.
        var measureUnit = validateMeasureUnit(productId, variantId, request.measureUnitId());
        verifyMeasureUnitChangeAllowed(variant, measureUnit.getId());

        // Same rule as create, over the EFFECTIVE set: `null` still means "leave the stored set alone"
        // (which is why a caller that predates the attribute catalog cannot wipe it), but the state the
        // write leaves behind is what gets validated. Validating only the request's field would let a
        // variant created before its product was classified keep a missing required colour forever.
        validateAttributes(product, request.attributes() != null ? request.attributes() : variant.getAttributes());

        variant.setBarCode(request.barCode());
        variant.setVariantName(request.variantName());
        variant.setMeasureUnitId(measureUnit.getId());
        if (request.attributes() != null) {
            variant.setAttributes(request.attributes());
        }

        var updated = productVariantRepository.save(variant);
        logger.info("Product variant updated: id={}", variantId);

        return toResponse(updated, product.getSku());
    }

    /** Soft delete of the definition ({@code enabled = false}); the store rows are left intact. */
    @Transactional
    public void deleteVariant(UUID companyId, UUID productId, UUID variantId) {
        validateProductExists(companyId, productId);

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
    public ProductVariantResponse enableVariant(UUID companyId, UUID productId, UUID variantId) {
        var product = validateProductExists(companyId, productId);

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
     * <p>Prices are per-store profile and are written here. Stock is not: every stock move belongs to
     * the engine, so this method delegates a stock edit to
     * {@link InventoryService#applyStockAdjustment} (W3-D14). Writing
     * {@code product_variant_store_stock.stock} here directly is the F16 defect — the aggregate moved
     * while no location row and no movement did — so the arithmetic stays in the engine, and a store
     * without a settings row is refused by it rather than guessed here.</p>
     *
     * <p>The row pairs one company's variant with one company's store, and nothing in the schema
     * forbids the two being different: {@code product_variant_store_stock} has no company of its
     * own, its unique key is {@code (product_variant_id, company_store_id)}, and the composite
     * foreign keys only tie the variant to its own product and the store to its own zone. Tolerating
     * that pair is what keeps the store-scoped reads filtering it out afterwards
     * ({@link #searchVariants}, {@code listVariants}), and it would let a caller holding a grant on
     * one store attach another tenant's catalog to it. The write is refused with the vocabulary the
     * catalog already uses for a pair that is not addressable — {@link
     * ProductVariantNotFoundException}, which is a 404: the same answer the purchase-order path gives
     * for a variant that cannot be reached from the document's store. A variant of another company is
     * indistinguishable from one that does not exist, so no new status is invented for it.</p>
     *
     * <p>The pair check runs <b>after</b> {@link #verifyStoreAccess}, so a caller with no grant still
     * receives the 403 that says so before anything about tenants is disclosed.</p>
     *
     * @throws ProductVariantNotFoundException when the variant does not exist, or does not belong to
     *     the company that owns the store
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller holds no
     *     grant for the store's scope (403)
     * @throws com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException when a
     *     stock value is sent for a store that has no inventory settings row (404)
     */
    @Transactional
    public ProductVariantStoreStockResponse upsertStoreStock(
            UUID variantId, UUID companyStoreId, ProductVariantStoreStockRequest request) {
        var variant = productVariantRepository
                .findById(variantId)
                .orElseThrow(() -> new ProductVariantNotFoundException(variantId));

        var store = companyStoreRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new CompanyStoreNotFoundException(companyStoreId));
        verifyStoreAccess(store);

        if (!companyStoreOwnerResolver.companyIdOf(store).equals(variant.getCompanyId())) {
            throw new ProductVariantNotFoundException(variantId);
        }

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
        productVariantStoreStockRepository.save(row);

        // Stock is the engine's invariant, not this service's state: delegate the mutation and use
        // the value it returns. A null stock means "leave stock untouched", as before.
        var stock = request.stock() != null
                ? inventoryService.applyStockAdjustment(
                        variantId, companyStoreId, request.stock(), currentUserContext.getUsername())
                : row.getStock();

        return toStoreResponse(row, stock);
    }

    /**
     * Searches the per-store rows of the variant table: stock and list and cost prices are read for
     * the store named by {@code storeId}, so the caller must hold a grant covering that store.
     *
     * <p>The store id comes from the query string, so {@code @PreAuthorize} alone only proves the
     * caller holds some variant role; the company &rarr; country &rarr; region &rarr; zone &rarr;
     * store chain is derived from the store and verified with {@link #verifyStoreAccess}. Without it
     * any store-scoped principal could read another company's stock and prices. A blank or absent
     * query short-circuits to an empty page before the store is read, so it neither leaks nor
     * requires the grant.</p>
     *
     * <p>The company that chain reaches then travels into the query as a <b>predicate</b>, not as a
     * post-filter: the grant proves who may read this store, never which tenant the variants joined
     * onto it belong to, and a per-store row can name a variant of another company (the pair is not
     * constrained by the schema — {@code company_stores} has no {@code company_id} at all). Filtering
     * after the fact would answer the same question one step too late.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller holds no
     *     grant for the store's scope (403)
     */
    @Transactional(readOnly = true)
    public Page<ProductVariantSearchResponse> searchVariants(String query, UUID storeId, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return Page.empty(pageable);
        }

        var store =
                companyStoreRepository.findById(storeId).orElseThrow(() -> new CompanyStoreNotFoundException(storeId));
        verifyStoreAccess(store);

        var trimmed = query.trim();
        return productVariantRepository.searchByQuery(
                companyStoreOwnerResolver.companyIdOf(store), trimmed, storeId, pageable);
    }

    // ─── Private Helpers ──────────────────────────────────────────────────

    /**
     * Loads a product that belongs to the company named by the path, or answers 404.
     *
     * <p>Every nested endpoint starts here, so the product is resolved against the company before any
     * variant is read or written: a variant of another company's product is indistinguishable from one
     * that does not exist, and nothing downstream has to remember the scope.</p>
     *
     * @throws ProductNotFoundException when no enabled-or-not product of that company carries the id
     */
    private Product validateProductExists(UUID companyId, UUID productId) {
        currentUserContext.verifyCompanyAccess(companyId);
        return productRepository
                .findByIdAndCompanyId(productId, companyId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
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
                companyStoreOwnerResolver.companyIdOf(store),
                country.getId(),
                region.getId(),
                zone.getId(),
                store.getId());
    }

    private ProductVariant findVariantOfProduct(UUID productId, UUID variantId) {
        return productVariantRepository
                .findById(variantId)
                .filter(v -> v.getProductId().equals(productId))
                .orElseThrow(() -> new ProductVariantNotFoundException(variantId));
    }

    /**
     * ADR-0006 decision 4: the product's mode, not the request's annotation, decides whether a barcode is
     * required — required for {@code MULTI}, optional for {@code SIMPLE}, because "a single-variant article
     * has no barcode to give".
     *
     * <p>It runs on create <b>and</b> on update, over the state the write leaves behind: clearing the
     * barcode of a {@code MULTI} variant is the same violation as creating one without it. A blank value
     * counts as absent, because {@code @Size} is the only constraint the request keeps. The refusal names
     * {@code barCode} ({@link BarcodeRequiredException}, a 400) so a client renders a field and not the
     * message {@code trg_variant_matches_mode} raises, and the trigger stays the half no other writer can
     * skip.</p>
     */
    private void validateBarcodeForMode(Product product, String barCode) {
        if (barCode == null || barCode.isBlank()) {
            if (Product.VARIANT_MODE_MULTI.equals(product.getVariantMode())) {
                throw new BarcodeRequiredException(product.getId());
            }
        }
    }

    /**
     * Resolves the measure unit a write names and validates it: it must be present (400), it must exist
     * (404), it must be enabled (400) and, when it exists, it must agree with the {@code unit_type}
     * already in use by the product's other variants (D4).
     *
     * <p>The unit is mandatory since ADR-0006 decision 3, and the refusal is this service's job rather
     * than the column's: {@code V55} makes {@code measure_unit_id} {@code NOT NULL}, so a request that
     * omits it would otherwise reach the database and fail there with a constraint name that names no
     * field. {@link MeasureUnitRequiredException} is a 400 that names {@code measureUnitId}, and it is
     * thrown before the catalog is read — there is no id to look up.
     *
     * @param excludedVariantId the variant being updated, excluded from the type gate so it cannot
     *     contradict itself; {@code null} on create
     */
    private MeasureUnit validateMeasureUnit(UUID productId, UUID excludedVariantId, UUID measureUnitId) {
        if (measureUnitId == null) {
            throw new MeasureUnitRequiredException();
        }

        var measureUnit = measureUnitRepository
                .findById(measureUnitId)
                .orElseThrow(() -> new MeasureUnitNotFoundException(measureUnitId));
        if (!Boolean.TRUE.equals(measureUnit.getEnabled())) {
            throw new DisabledMeasureUnitException(measureUnit);
        }

        verifyMeasureUnitTypeCoherence(productId, excludedVariantId, measureUnit);
        return measureUnit;
    }

    /**
     * D4: a product cannot mix {@code PRODUCT} and {@code SERVICE} units across its variants.
     *
     * <p>The types in use come from the product's own variants, and the variant being updated is
     * excluded so its current unit is not counted as somebody else's. An empty result means no
     * constraint: a product none of whose other variants declares a unit accepts the first unit of
     * either type — a first variant, or the {@code V55} window during which an existing row still has
     * no unit. The
     * type is not derived from {@code products.product_type}, which is free text.</p>
     */
    private void verifyMeasureUnitTypeCoherence(UUID productId, UUID excludedVariantId, MeasureUnit measureUnit) {
        var typesInUse = excludedVariantId == null
                ? productVariantRepository.findUsedMeasureUnitTypesByProductId(productId)
                : productVariantRepository.findUsedMeasureUnitTypesByProductIdExcludingVariantId(
                        productId, excludedVariantId);

        for (var typeInUse : typesInUse) {
            if (typeInUse != measureUnit.getUnitType()) {
                throw new MeasureUnitTypeMismatchException(productId, measureUnit.getUnitType(), typeInUse);
            }
        }
    }

    /**
     * D3: the unit may be set on create and changed afterwards only while the variant holds no
     * quantity meaning.
     *
     * <p>Three cases, and the asymmetry between the first two is deliberate and load-bearing. An
     * <strong>unchanged</strong> value is allowed. {@code null -> unit} is <strong>always</strong>
     * allowed, even with stock or movements: it is the first declaration, so it reinterprets nothing
     * that claimed otherwise, and the whole pre-V29 catalog is {@code null} and carries stock, so
     * blocking it would make the feature unusable on existing data — and since ADR-0006 decision 3 it is
     * the only way a row the {@code V55} report names ever becomes valid, because the new value is
     * required and the stored one is absent. <strong>Any other</strong>
     * transition — {@code unit A -> unit B} — is allowed only when
     * no store row holds stock above zero (a {@code stock} of exactly {@code 0} is not a history: it
     * is the default of a row that was never filled) and {@code inventory_movements} has no row for
     * the variant. Otherwise the write is refused with 409 and the entity is left untouched.</p>
     *
     * <p>{@code unit -> null} is no longer reachable from here: the request itself refuses an absent
     * unit ({@link #validateMeasureUnit}) and {@code V55} makes the column reject it.</p>
     */
    private void verifyMeasureUnitChangeAllowed(ProductVariant variant, UUID newMeasureUnitId) {
        var currentMeasureUnitId = variant.getMeasureUnitId();
        if (Objects.equals(currentMeasureUnitId, newMeasureUnitId)) {
            return;
        }
        if (currentMeasureUnitId == null) {
            // First declaration (null -> unit): nothing stored was ever expressed in another unit.
            return;
        }

        if (productVariantStoreStockRepository.existsByProductVariantIdAndStockNot(variant.getId(), BigDecimal.ZERO)) {
            throw new MeasureUnitChangeNotAllowedException(
                    variant.getId(), "it holds a non-zero stock balance in at least one store");
        }
        if (inventoryMovementRepository.existsByProductVariantId(variant.getId())) {
            throw new MeasureUnitChangeNotAllowedException(
                    variant.getId(), "the inventory ledger already holds movements for it");
        }
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
                variant.getUpdatedAt(),
                variant.getMeasureUnitId(),
                variant.getAttributes());
    }

    /**
     * Validates a variant attribute set against the product's category profile.
     *
     * <p>The variant has no classification of its own: its attributes are the {@code VARIANT}-level
     * entries of the profile that applies to the <b>product's</b> category, so an unclassified product
     * declares no profile and any variant attribute is refused. The company comes from that category,
     * because the profile lookup is company-scoped.</p>
     */
    private void validateAttributes(Product product, Map<String, Object> attributes) {
        var category = product.getCategory();
        var categoryId = category == null ? null : category.getId();
        var companyId = category == null || category.getCompany() == null
                ? null
                : category.getCompany().getId();
        productAttributeCatalogService.validate(companyId, categoryId, attributes, ProductAttributeLevel.VARIANT);
    }

    private ProductVariantStoreStockResponse toStoreResponse(ProductVariantStoreStock row, BigDecimal stock) {
        return new ProductVariantStoreStockResponse(
                row.getCompanyStoreId(), row.getListPrice(), row.getCostPrice(), stock);
    }
}
