package com.lifecontrol.api.product.attribute.repository;

import com.lifecontrol.api.product.attribute.model.ProductAttributeProfileRoot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the root-to-profile attachment.
 *
 * <p>Kept as two lookups, and the split is the override precedence itself: the service asks the
 * company's own row first and falls back to the global one. A single query with an {@code OR} would
 * work, but the order of the two answers is exactly the rule this feature exists to state, so it is
 * written where it can be read.</p>
 *
 * <p>Both return {@link Optional} because the uniqueness index
 * {@code uq_product_attribute_profile_roots_owner_root} makes the pair (owner, root name) unique —
 * including the global owner, whose {@code company_id} is {@code NULL}.</p>
 */
@Repository
public interface ProductAttributeProfileRootRepository extends JpaRepository<ProductAttributeProfileRoot, UUID> {

    Optional<ProductAttributeProfileRoot> findByCompanyIdAndRootNameIgnoreCase(UUID companyId, String rootName);

    Optional<ProductAttributeProfileRoot> findByCompanyIsNullAndRootNameIgnoreCase(String rootName);
}
