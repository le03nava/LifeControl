package com.lifecontrol.api.product.attribute.repository;

import com.lifecontrol.api.product.attribute.model.ProductAttributeProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the attribute profiles.
 *
 * <p>Two lookups, kept apart on purpose. {@code findByCompanyIsNull…} is the seeded global catalog;
 * {@code findByCompanyId…} is a company's own override of it. A single query matching "null or this
 * company" would make the precedence a caller's guess, and the resolution rule — a company row wins —
 * belongs in one place, the service.</p>
 */
@Repository
public interface ProductAttributeProfileRepository extends JpaRepository<ProductAttributeProfile, UUID> {

    Optional<ProductAttributeProfile> findByCompanyIsNullAndProfileKeyIgnoreCase(String profileKey);

    Optional<ProductAttributeProfile> findByCompanyIdAndProfileKeyIgnoreCase(UUID companyId, String profileKey);
}
