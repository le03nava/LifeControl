package com.lifecontrol.api.product.repository;

import com.lifecontrol.api.product.model.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Product persistence.
 *
 * <p>Every read the product resource serves is <b>company-scoped</b>: the company is part of the path
 * ({@code /api/companies/{companyId}/products}), so the finders carry it instead of letting the caller
 * filter afterwards, and a page can never be post-filtered into a lie about its own totals. The
 * unscoped finders that fed the previous global catalog are gone rather than left unused, because one of
 * them being picked up later is exactly how a cross-tenant read would come back.</p>
 *
 * <p>Every finder that feeds a response carries the graph {@code {"category", "category.company",
 * "company"}}: the response projects {@code categoryId}, {@code categoryCompanyId} and {@code companyId},
 * and all three hops are {@code LAZY}. Fetching only {@code category} would leave the companies a proxy,
 * so a page of twenty classified products would still issue forty extra selects — the classic N+1 the
 * relation would otherwise introduce on a read that had none. Three joins answer it instead.</p>
 *
 * <p>The SKU key is per company ({@code (company_id, sku)}), which is why both uniqueness finders take
 * the company: the same SKU in two companies is two legitimate catalog entries. The database enforces
 * the same shape since {@code V40} replaced V1's global key, so a direct SQL insert cannot disagree
 * with the API.</p>
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    /** Whether the company already holds this SKU. */
    boolean existsBySkuAndCompanyId(String sku, UUID companyId);

    /** Whether another product of the same company already holds this SKU. */
    boolean existsBySkuAndCompanyIdAndIdNot(String sku, UUID companyId, UUID id);

    /** Overridden only to attach the graph: {@code findById} is still used by the order modules. */
    @Override
    @EntityGraph(attributePaths = {"category", "category.company", "company"})
    Optional<Product> findById(UUID id);

    /** A product of the company, or empty — a row of another company is indistinguishable from none. */
    @EntityGraph(attributePaths = {"category", "category.company", "company"})
    Optional<Product> findByIdAndCompanyId(UUID id, UUID companyId);

    @EntityGraph(attributePaths = {"category", "category.company", "company"})
    Page<Product> findByCompanyIdAndEnabledTrue(UUID companyId, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.company", "company"})
    Page<Product> findByCompanyId(UUID companyId, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.company", "company"})
    @Query("""
        SELECT p FROM Product p
        WHERE p.company.id = :companyId
        AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))
        AND p.enabled = true
        """)
    Page<Product> findByCompanyIdAndSearchTermAndEnabledTrue(
            @Param("companyId") UUID companyId, @Param("search") String search, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.company", "company"})
    @Query("""
        SELECT p FROM Product p
        WHERE p.company.id = :companyId
        AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<Product> findByCompanyIdAndSearchTerm(
            @Param("companyId") UUID companyId, @Param("search") String search, Pageable pageable);

    /**
     * C1's audit: how many products of the company have no variant at all.
     *
     * <p>ADR-0006 states the invariant in two halves — "a product has at least one variant", enforced by the
     * creation transaction, and "a delete that would leave zero is refused by the write path and counted by a
     * query". This is the query half: the refusal lives in
     * {@code ProductVariantService#deleteVariant}, and without a read that asks the question of the whole
     * catalog, nothing would tell an operator whether the invariant already holds. It is deliberately
     * {@code NOT EXISTS} over the rows and not a count of the <b>enabled</b> ones: C1 is about the family
     * existing, while the delete guard refuses on the live subset, and collapsing the two would make the audit
     * report a state the schema does not actually forbid.</p>
     *
     * <p>The company is a predicate because the count is meaningful per tenant — the response is
     * company-scoped everywhere else, and an unscoped total would answer a question no operator asks.</p>
     */
    @Query("""
        SELECT COUNT(p)
        FROM Product p
        WHERE p.company.id = :companyId
          AND NOT EXISTS (SELECT 1 FROM ProductVariant pv WHERE pv.productId = p.id)
        """)
    long countProductsWithoutVariants(@Param("companyId") UUID companyId);
}
