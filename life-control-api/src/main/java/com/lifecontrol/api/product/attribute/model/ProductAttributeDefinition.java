package com.lifecontrol.api.product.attribute.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * One attribute of a {@link ProductAttributeProfile}, mapped to the
 * {@code product_attribute_definitions} table created by {@code V37__product_attribute_catalog.sql}.
 *
 * <p>{@code attributeCode} is the key stored in the product's JSON, never the {@code label}: a label is
 * presentation and can be reworded, while a report filters on the code. Its format is a database
 * {@code CHECK} ({@code ^[a-z][a-z0-9_]{1,49}$}) so a code is addressable from a query without quoting
 * games. Uniqueness is per profile — {@code uq_product_attribute_definitions_profile_code} — because two
 * branches legitimately reuse the same code while it means the same concept.</p>
 *
 * <p>{@code unitLabel} is a <b>hint</b> the form prints beside a
 * {@link ProductAttributeKind#MEASUREMENT} input ("cm", "kg", "W") and it is never enforced: the user's
 * own example is "1/2 pulgada" in a branch whose default is millimetres, so a definition that forced its
 * unit would make a value the user asked for unrepresentable. A database {@code CHECK} keeps it on
 * measurements only, where it means something.</p>
 *
 * <p>{@code required} is enforced by the service, not by the database: it depends on the profile that
 * resolves for a category — a join plus a walk up the tree — which a {@code CHECK} cannot express.</p>
 */
@Entity
@Table(name = "product_attribute_definitions")
public class ProductAttributeDefinition extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    private ProductAttributeProfile profile;

    @Column(name = "attribute_code", length = 50, nullable = false)
    private String attributeCode;

    @Column(length = 100, nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "attribute_level", length = 10, nullable = false)
    private ProductAttributeLevel attributeLevel;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private ProductAttributeKind kind;

    @Column(name = "unit_label", length = 20)
    private String unitLabel;

    @Column(nullable = false)
    private Boolean required = false;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    public ProductAttributeDefinition() {}

    public UUID getId() {
        return id;
    }

    public ProductAttributeProfile getProfile() {
        return profile;
    }

    public String getAttributeCode() {
        return attributeCode;
    }

    public String getLabel() {
        return label;
    }

    public ProductAttributeLevel getAttributeLevel() {
        return attributeLevel;
    }

    public ProductAttributeKind getKind() {
        return kind;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public Boolean getRequired() {
        return required;
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

    public void setProfile(ProductAttributeProfile profile) {
        this.profile = profile;
    }

    public void setAttributeCode(String attributeCode) {
        this.attributeCode = attributeCode;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setAttributeLevel(ProductAttributeLevel attributeLevel) {
        this.attributeLevel = attributeLevel;
    }

    public void setKind(ProductAttributeKind kind) {
        this.kind = kind;
    }

    public void setUnitLabel(String unitLabel) {
        this.unitLabel = unitLabel;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
