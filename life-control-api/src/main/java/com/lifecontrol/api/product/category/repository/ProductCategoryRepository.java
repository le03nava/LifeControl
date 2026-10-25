package com.lifecontrol.api.product.category.repository;

import com.lifecontrol.api.product.category.model.ProductCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Company-scoped persistence for the product category tree.
 *
 * <p>Every single-row lookup is {@code companyId}-scoped, which is what turns a category of another
 * company into a {@code 404} instead of a {@code 200} — the same contract
 * {@link com.lifecontrol.api.hr.repository.DepartmentRepository} holds.</p>
 *
 * <p>The sibling-name queries come in two shapes, and the split is not cosmetic: a root has no parent,
 * so {@code parentCategoryId = null} has to be expressed as {@code parentCategoryIsNull} rather than
 * passed as a parameter. A single query with a nullable id would silently match nothing for the root
 * case, and the uniqueness check would then pass on a duplicate root that the database index rejects
 * anyway — a 500 where a 409 belongs.</p>
 *
 * <p>The list query is unscoped by parent on purpose: with a three-level ceiling the whole company
 * tree is small, so the service loads it in one statement and orders it depth-first in memory. The
 * cache is deliberately absent — a company-scoped read must run its scope check on every call.</p>
 */
@Repository
public interface ProductCategoryRepository extends JpaRepository<ProductCategory, UUID> {

    List<ProductCategory> findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(UUID companyId);

    List<ProductCategory> findByCompanyIdAndEnabledTrueOrderByDisplayOrderAscCategoryNameAsc(UUID companyId);

    Optional<ProductCategory> findByCompanyIdAndId(UUID companyId, UUID id);

    List<ProductCategory> findByCompanyIdAndParentCategoryIdOrderByDisplayOrderAscCategoryNameAsc(
            UUID companyId, UUID parentCategoryId);

    boolean existsByCompanyIdAndParentCategoryIsNullAndCategoryNameIgnoreCase(UUID companyId, String categoryName);

    boolean existsByCompanyIdAndParentCategoryIsNullAndCategoryNameIgnoreCaseAndIdNot(
            UUID companyId, String categoryName, UUID excludeId);

    boolean existsByCompanyIdAndParentCategoryIdAndCategoryNameIgnoreCase(
            UUID companyId, UUID parentCategoryId, String categoryName);

    boolean existsByCompanyIdAndParentCategoryIdAndCategoryNameIgnoreCaseAndIdNot(
            UUID companyId, UUID parentCategoryId, String categoryName, UUID excludeId);

    boolean existsByCompanyIdAndParentCategoryId(UUID companyId, UUID parentCategoryId);
}
