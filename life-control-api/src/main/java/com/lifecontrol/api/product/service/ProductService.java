package com.lifecontrol.api.product.service;

import com.lifecontrol.api.product.attribute.model.ProductAttributeLevel;
import com.lifecontrol.api.product.attribute.service.ProductAttributeCatalogService;
import com.lifecontrol.api.product.category.exception.ProductCategoryNotFoundException;
import com.lifecontrol.api.product.category.model.ProductCategory;
import com.lifecontrol.api.product.category.repository.ProductCategoryRepository;
import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductResponse;
import com.lifecontrol.api.product.exception.DuplicateProductException;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final ProductAttributeCatalogService productAttributeCatalogService;

    public ProductService(
            ProductRepository productRepository,
            ProductCategoryRepository productCategoryRepository,
            ProductAttributeCatalogService productAttributeCatalogService) {
        this.productRepository = productRepository;
        this.productCategoryRepository = productCategoryRepository;
        this.productAttributeCatalogService = productAttributeCatalogService;
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        logger.info("Creating product with SKU: {}", request.sku());

        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateProductException("Product with SKU '" + request.sku() + "' already exists");
        }

        var category = resolveCategory(request.categoryId());
        validateAttributes(category, request.attributes());

        var product = Product.builder()
                .sku(request.sku())
                .name(request.name())
                .shortName(request.shortName())
                .satCode(request.satCode())
                .productType(request.productType())
                .attributes(request.attributes())
                .category(category)
                .enabled(true)
                .build();

        var saved = productRepository.save(product);
        logger.info("Product created successfully with id: {}", saved.getId());

        return mapToResponse(saved);
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, ProductRequest request) {
        logger.info("Updating product with id: {}", id);

        var product = productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));

        // Check SKU uniqueness only if SKU changed
        if (!product.getSku().equals(request.sku()) && productRepository.existsBySkuAndIdNot(request.sku(), id)) {
            throw new DuplicateProductException("Product with SKU '" + request.sku() + "' already exists");
        }

        // The category and the attribute set are validated together and before any setter runs, so a
        // refused write leaves the entity exactly as it was.
        var category = resolveCategory(request.categoryId());
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
    public void deleteProduct(UUID id) {
        logger.info("Soft-deleting product with id: {}", id);

        var product = productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));

        if (!product.getEnabled()) {
            throw new ProductNotFoundException(id);
        }

        product.setEnabled(false);
        productRepository.save(product);

        logger.info("Product soft-deleted: id={}, sku={}", id, product.getSku());
    }

    @Transactional(readOnly = true)
    public ProductResponse findProduct(UUID id) {
        var product = productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));

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

    /**
     * Resolves the category a request points at, or {@code null} when it sends none.
     *
     * <p>The lookup is deliberately <b>not</b> company-scoped: a {@code ProductRequest} carries no
     * company — {@code products} has none (V1) — so there is nothing to scope it by, and the category's
     * own company is what the response reports. That is safe because the column is a real foreign key:
     * the id either names one existing row of one company, or it does not exist at all.</p>
     *
     * <p>The {@code enabled} flag is not checked. A category is soft-deleted, so requiring it here would
     * freeze every later edit of the products already classified under it — an operator fixing a typo
     * in the name would be told the category is gone.</p>
     *
     * @throws ProductCategoryNotFoundException when the id names no category, which the existing 404
     *     contract answers instead of letting the FK violation surface as a 500
     */
    private ProductCategory resolveCategory(java.util.UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return productCategoryRepository
                .findById(categoryId)
                .orElseThrow(() -> new ProductCategoryNotFoundException(categoryId));
    }

    /**
     * Validates a product attribute set against the profile that resolves for the category.
     *
     * <p>An unclassified product declares no profile: an empty set passes and any attribute is refused,
     * which is the same answer a category whose branch has no attachment gets. The company comes from
     * the category, because {@code products} carries none (V1) and the profile lookup is
     * company-scoped.</p>
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
                category == null ? null : category.getCompany().getId());
    }
}
