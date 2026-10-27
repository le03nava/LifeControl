package com.lifecontrol.api.product.attribute.repository;

import com.lifecontrol.api.product.attribute.model.ProductAttributeValue;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the allowed values of the enumerable definitions.
 *
 * <p>The lookup takes the whole definition set of a profile in one statement instead of one query per
 * definition: a profile has up to a dozen definitions and four screens read it, so a per-definition
 * fetch would be a dozen round trips per render. The caller must not pass an empty collection — an
 * {@code IN ()} is not valid SQL — which the service guards.</p>
 */
@Repository
public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, UUID> {

    List<ProductAttributeValue> findByDefinitionIdInAndEnabledTrueOrderByDisplayOrderAscLabelAsc(
            Collection<UUID> definitionIds);
}
