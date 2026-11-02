package com.lifecontrol.api.product.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.product.attribute.model.ProductAttributeLevel;
import com.lifecontrol.api.product.attribute.service.ProductAttributeCatalogService;
import com.lifecontrol.api.product.category.exception.ProductCategoryNotFoundException;
import com.lifecontrol.api.product.category.model.ProductCategory;
import com.lifecontrol.api.product.category.repository.ProductCategoryRepository;
import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductResponse;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.exception.DuplicateProductException;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.exception.VariantModeMismatchException;
import com.lifecontrol.api.product.exception.VariantsRequiredException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the company-scoped product catalog.
 *
 * <p>Mirrors {@link com.lifecontrol.api.product.category.service.ProductCategoryService}: every public
 * method starts by resolving the company through {@link #resolveCompany(UUID)}, whose first statement is
 * {@link CurrentUserContext#verifyCompanyAccess(UUID)}, and every single-row lookup is {@code companyId}
 * scoped, so a product of another company is a {@link ProductNotFoundException} rather than a 200.</p>
 *
 * <p>The company in the path is the <b>owner</b> of the product, not something derived from the
 * classification. The category a request names is therefore <em>checked against</em> that company
 * ({@link #resolveCategory(UUID, UUID)}) instead of saying who owns the product, which is what makes "a
 * product of company A filed under a category of company B" unreachable through this API.</p>
 *
 * <p><b>Creating a product creates its variants</b> (ADR-0006 decision 2, invariant C1): the request
 * declares them and this class writes the product <b>and</b> them inside one transaction. The variants
 * themselves are written through {@link ProductVariantService#createVariant(UUID, UUID,
 * ProductVariantRequest)} and not by a second copy of the rules — the unit, the barcode-per-mode rule and
 * the two duplicate keys are the ones that path already enforces and proves, so a variant the standalone
 * endpoint would refuse is refused here with the same answer, and the exception leaves this method so the
 * whole creation rolls back.</p>
 */
@Service
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final ProductAttributeCatalogService productAttributeCatalogService;
    private final ProductVariantService productVariantService;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public ProductService(
            ProductRepository productRepository,
            ProductCategoryRepository productCategoryRepository,
            ProductAttributeCatalogService productAttributeCatalogService,
            ProductVariantService productVariantService,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.productRepository = productRepository;
        this.productCategoryRepository = productCategoryRepository;
        this.productAttributeCatalogService = productAttributeCatalogService;
        this.productVariantService = productVariantService;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
    }

    @Transactional
    public ProductResponse createProduct(UUID companyId, ProductRequest request) {
        logger.info("Creating product with SKU {} in company {}", request.sku(), companyId);

        var company = resolveCompany(companyId);

        // The request's shape is checked here and before the catalog is read, because a request that cannot
        // describe a product has nothing to look up: `variants` is required (C1) and the mode decides how
        // many are allowed (decision 2). Neither refusal names a row, and neither writes one.
        var variants = requireVariants(request);
        var variantMode = variantMode(request);
        validateVariantCount(variantMode, variants);

        if (productRepository.existsBySkuAndCompanyId(request.sku(), companyId)) {
            throw new DuplicateProductException("Product with SKU '" + request.sku() + "' already exists");
        }

        var category = resolveCategory(companyId, request.categoryId());
        validateAttributes(category, request.attributes());

        var product = Product.builder()
                .sku(request.sku())
                .name(request.name())
                .shortName(request.shortName())
                .satCode(request.satCode())
                .productType(request.productType())
                .attributes(request.attributes())
                .category(category)
                .company(company)
                // ADR-0006 decision 1: the mode is a property of the article and it is stated at creation.
                // An absent one is `SIMPLE` — the single-variant article — and stating it here is what keeps
                // the column from depending on its default for a caller that does declare `MULTI`.
                .variantMode(variantMode)
                .enabled(true)
                .build();

        var saved = productRepository.save(product);

        // The insert is flushed before the first variant for a reason that is not cosmetic: every variant is
        // created through `ProductVariantService#createVariant`, whose first statement is a product lookup by
        // id, and that lookup runs a query. Flushing states the order instead of leaving it to the persistence
        // context's auto-flush, so a product is committed-to-the-transaction before anything hangs off it.
        productRepository.flush();

        for (var variantRequest : variants) {
            productVariantService.createVariant(companyId, saved.getId(), variantRequest);
        }

        logger.info("Product created successfully with id: {} and {} variant(s)", saved.getId(), variants.size());

        return mapToResponse(saved);
    }

    @Transactional
    public ProductResponse updateProduct(UUID companyId, UUID id, ProductRequest request) {
        logger.info("Updating product with id: {} in company {}", id, companyId);

        var company = resolveCompany(companyId);
        var product = requireProduct(companyId, id);

        // Check SKU uniqueness only if SKU changed
        if (!product.getSku().equals(request.sku())
                && productRepository.existsBySkuAndCompanyIdAndIdNot(request.sku(), companyId, id)) {
            throw new DuplicateProductException("Product with SKU '" + request.sku() + "' already exists");
        }

        // The category and the attribute set are validated together and before any setter runs, so a
        // refused write leaves the entity exactly as it was.
        var category = resolveCategory(companyId, request.categoryId());
        // The set that will end up stored: the request's, or the stored one when the request sends none.
        // Both are checked against the category being written, which is what stops a category change
        // from leaving behind attributes the new branch does not define.
        var attributesToStore = request.attributes() != null ? request.attributes() : product.getAttributes();
        validateAttributes(category, attributesToStore);

        // Update scalar fields
        product.setSku(request.sku());
        product.setName(request.name());
        product.setShortName(request.shortName());
        product.setSatCode(request.satCode());
        product.setProductType(request.productType());
        // `null` clears the classification on purpose: the form sends what its three selects hold, and
        // "sin categoría" has to be expressible.
        product.setCategory(category);
        // The path names the owner, so the write states it instead of deriving it. The lookup above is
        // already company-scoped, so this is the same company the row carried; stating it is what keeps
        // the column from depending on the classification, which a request may clear.
        product.setCompany(company);

        // The request is authoritative for the set it sends: it REPLACES the stored map instead of
        // merging into it. The previous shallow `putAll` made a key impossible to remove and kept the
        // attributes of the category the product no longer belongs to alive and invisible until someone
        // re-opened that branch (record L4). `null` still means "leave the stored set alone" so every
        // caller that predates the attribute catalog keeps its old meaning.
        if (request.attributes() != null) {
            product.setAttributes(request.attributes());
        }

        var updated = productRepository.save(product);
        logger.info("Product updated successfully with id: {}", updated.getId());

        return mapToResponse(updated);
    }

    @Transactional
    public void deleteProduct(UUID companyId, UUID id) {
        logger.info("Soft-deleting product with id: {} in company {}", id, companyId);

        resolveCompany(companyId);
        var product = requireProduct(companyId, id);

        if (!product.getEnabled()) {
            throw new ProductNotFoundException(id);
        }

        product.setEnabled(false);
        productRepository.save(product);

        logger.info("Product soft-deleted: id={}, sku={}", id, product.getSku());
    }

    @Transactional(readOnly = true)
    public ProductResponse findProduct(UUID companyId, UUID id) {
        resolveCompany(companyId);
        var product = requireProduct(companyId, id);

        if (!product.getEnabled()) {
            throw new ProductNotFoundException(id);
        }

        return mapToResponse(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> listProducts(
            UUID companyId, Pageable pageable, String search, boolean includeDisabled) {
        resolveCompany(companyId);

        Page<Product> page;

        if (search != null && !search.isBlank()) {
            if (includeDisabled) {
                page = productRepository.findByCompanyIdAndSearchTerm(companyId, search.trim(), pageable);
            } else {
                page = productRepository.findByCompanyIdAndSearchTermAndEnabledTrue(companyId, search.trim(), pageable);
            }
        } else {
            if (includeDisabled) {
                page = productRepository.findByCompanyId(companyId, pageable);
            } else {
                page = productRepository.findByCompanyIdAndEnabledTrue(companyId, pageable);
            }
        }

        return page.map(this::mapToResponse);
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company is
     * denied before any row is loaded, which is the contract
     * {@link com.lifecontrol.api.product.category.service.ProductCategoryService} established for the
     * company-scoped catalogs.</p>
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
     * Loads a product that belongs to the company named by the path, or answers 404.
     *
     * <p>The lookup is company-scoped rather than a load-then-compare, so a row of another company is not
     * even materialized and the answer cannot drift from the scope the query used.</p>
     */
    private Product requireProduct(UUID companyId, UUID id) {
        return productRepository
                .findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    /**
     * The mode a creation stores: the request's, or {@code SIMPLE} when it states none.
     *
     * <p>ADR-0006 decision 1 makes the mode a property of the article, set at creation. An absent one is
     * not an error: every caller that predates the mode creates the single-variant article, and
     * {@code SIMPLE} is what one product with one variant is. {@code updateProduct} deliberately
     * does not read it — a mode change is refused once a variant holds stock (migration step 6, which lands
     * with ADR-0001's ledger), so this endpoint states the mode once and never silently rewrites it.</p>
     */
    private String variantMode(ProductRequest request) {
        return request.variantMode() == null ? Product.VARIANT_MODE_SIMPLE : request.variantMode();
    }

    /**
     * The variants a creation writes, refusing a request that declares none.
     *
     * <p>ADR-0006 decision 2 / invariant C1: a product has at least one variant, and the creation request is
     * where they are declared. The schema forces the reading — {@code variant_name} is {@code NOT NULL} and
     * nothing may invent one — so an absent list and an empty list are the same refusal and it names
     * {@code variants} ({@link VariantsRequiredException}, a 400). Nothing has been written when it is thrown:
     * it runs before the product is built.</p>
     */
    private List<ProductVariantRequest> requireVariants(ProductRequest request) {
        var variants = request.variants();
        if (variants == null || variants.isEmpty()) {
            throw new VariantsRequiredException();
        }
        return variants;
    }

    /**
     * ADR-0006 decision 2: {@code SIMPLE} means <b>exactly</b> one, {@code MULTI} means one or more.
     *
     * <p>Only the {@code SIMPLE} half is a count rule: the empty list is refused above for both modes, so
     * {@code MULTI} accepts whatever is left. A second variant of a {@code SIMPLE} product is refused here —
     * before the product exists — with a message that names the field and the count, instead of by
     * {@code trg_variant_matches_mode} (V56), whose {@code 'product % is SIMPLE and already has a variant'} is
     * the half no other writer can skip and not what a client can render.</p>
     */
    private void validateVariantCount(String variantMode, List<ProductVariantRequest> variants) {
        if (Product.VARIANT_MODE_SIMPLE.equals(variantMode) && variants.size() != 1) {
            throw new VariantModeMismatchException(variantMode, variants.size());
        }
    }

    /**
     * Resolves the category a request points at, or {@code null} when it sends none, and checks that it
     * belongs to the company named by the path.
     *
     * <p>The company comes from the path and the product is owned by it; the category is therefore
     * <b>checked against</b> the owner instead of saying who the owner is. Both refusals are the same 404
     * on purpose: a category of another company is not addressable from here, so naming one must not
     * reveal that it exists — and the write stores nothing, because this runs before any setter.</p>
     *
     * <p>The {@code enabled} flag is not checked. A category is soft-deleted, so requiring it here
     * would freeze every later edit of the products already classified under it — an operator fixing a
     * typo in the name would be told the category is gone.</p>
     *
     * @throws ProductCategoryNotFoundException when the id names no category of the company, which the
     *     404 contract answers instead of letting the FK violation surface as a 500
     */
    private ProductCategory resolveCategory(UUID companyId, UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        var category = productCategoryRepository
                .findById(categoryId)
                .orElseThrow(() -> new ProductCategoryNotFoundException(categoryId));
        if (category.getCompany() == null
                || !companyId.equals(category.getCompany().getId())) {
            throw new ProductCategoryNotFoundException(categoryId);
        }
        return category;
    }

    /**
     * Validates a product attribute set against the profile that resolves for the category.
     *
     * <p>An unclassified product declares no profile: an empty set passes and any attribute is refused,
     * which is the same answer a category whose branch has no attachment gets. The company comes from
     * the category, because the profile lookup is company-scoped and the category is where the request
     * names one — a product does not carry an attribute profile of its own.</p>
     */
    private void validateAttributes(ProductCategory category, Map<String, Object> attributes) {
        var categoryId = category == null ? null : category.getId();
        var companyId = category == null || category.getCompany() == null
                ? null
                : category.getCompany().getId();
        productAttributeCatalogService.validate(companyId, categoryId, attributes, ProductAttributeLevel.PRODUCT);
    }

    private ProductResponse mapToResponse(Product product) {
        var category = product.getCategory();
        var company = product.getCompany();
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
                product.getUpdatedAt(),
                category == null ? null : category.getId(),
                category == null || category.getCompany() == null
                        ? null
                        : category.getCompany().getId(),
                company == null ? null : company.getId(),
                product.getVariantMode());
    }
}
