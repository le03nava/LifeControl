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
}
