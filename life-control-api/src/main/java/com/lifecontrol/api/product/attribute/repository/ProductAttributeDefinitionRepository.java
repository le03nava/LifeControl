package com.lifecontrol.api.product.attribute.repository;

import com.lifecontrol.api.product.attribute.model.ProductAttributeDefinition;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for a profile's attribute definitions.
 *
 * <p>Only enabled definitions are ever read: a retired definition stays in the table because product
 * JSON already stored under its code would otherwise lose its meaning, but it is not offered on any
 * screen and it is not a valid key for a new write.</p>
 */
@Repository
public interface ProductAttributeDefinitionRepository extends JpaRepository<ProductAttributeDefinition, UUID> {

    List<ProductAttributeDefinition> findByProfileIdAndEnabledTrueOrderByDisplayOrderAscAttributeCodeAsc(
            UUID profileId);
}
