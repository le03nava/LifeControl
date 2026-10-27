package com.lifecontrol.api.product.attribute.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * One allowed value of an {@link ProductAttributeKind#ENUM} or {@link ProductAttributeKind#MULTI_ENUM}
 * definition, mapped to the {@code product_attribute_values} table created by
 * {@code V37__product_attribute_catalog.sql}.
 *
 * <p>A child table rather than a {@code jsonb} array inside the definition: the form renders these in
 * {@code displayOrder}, the API validates membership by equality on {@code valueCode}, and a value can
 * be retired with {@code enabled = false} without editing the profile that mentions it. The user's
 * labels ("Sintético", "Wi-Fi") live in {@code label}; {@code valueCode} is what the product JSON
 * stores.</p>
 */
@Entity
@Table(name = "product_attribute_values")
public class ProductAttributeValue extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "definition_id", nullable = false)
    private ProductAttributeDefinition definition;

    @Column(name = "value_code", length = 50, nullable = false)
    private String valueCode;

    @Column(length = 100, nullable = false)
    private String label;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    public ProductAttributeValue() {}

    public UUID getId() {
        return id;
    }

    public ProductAttributeDefinition getDefinition() {
        return definition;
    }

    public String getValueCode() {
        return valueCode;
    }

    public String getLabel() {
        return label;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public void setDefinition(ProductAttributeDefinition definition) {
        this.definition = definition;
    }

    public void setValueCode(String valueCode) {
        this.valueCode = valueCode;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
