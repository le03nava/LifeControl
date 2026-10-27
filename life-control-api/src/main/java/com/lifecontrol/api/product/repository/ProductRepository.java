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
 * <p>Every finder that feeds a response carries the graph {@code {"category", "category.company"}}: the
 * response projects {@code categoryCompanyId}, which is a property of the category's <em>own</em> parent
 * row, and both hops are {@code LAZY}. Fetching only {@code category} would leave the company a proxy,
 * so a page of twenty classified products would still issue twenty extra selects — the classic N+1 the
 * relation would otherwise introduce on a read that had none. Two joins answer it instead.</p>
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    /** Overridden only to attach the graph: {@code findById} feeds the response of every write too. */
    @Override
    @EntityGraph(attributePaths = {"category", "category.company"})
    Optional<Product> findById(UUID id);

    @Override
    @EntityGraph(attributePaths = {"category", "category.company"})
    Page<Product> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.company"})
    Page<Product> findByEnabledTrue(Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.company"})
    @Query("""
        SELECT p FROM Product p
        WHERE (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))
        AND p.enabled = true
        """)
    Page<Product> findBySearchTermAndEnabledTrue(@Param("search") String search, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.company"})
    @Query("""
        SELECT p FROM Product p
        WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%'))
        """)
    Page<Product> findBySearchTerm(@Param("search") String search, Pageable pageable);
}
