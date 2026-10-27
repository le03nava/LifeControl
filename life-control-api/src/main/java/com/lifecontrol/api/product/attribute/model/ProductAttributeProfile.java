package com.lifecontrol.api.product.attribute.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.company.model.Company;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * A named set of product attributes ("calzado", "ferreteria"), mapped to the
 * {@code product_attribute_profiles} table created by {@code V37__product_attribute_catalog.sql}.
 *
 * <p>{@code company} is {@code null} for a global profile — the seeded ones — and set for a company's
 * own. That nullable column is the entire per-company override seam: it ships unused, and a company
 * that needs its own vocabulary adds a row instead of a table. {@code profileKey} is unique per owner,
 * including the global one, through the expression index
 * {@code uq_product_attribute_profiles_owner_key} — the {@code COALESCE} is what makes two global
 * profiles siblings, because {@code NULL <> NULL} would otherwise let both claim {@code calzado}.</p>
 *
 * <p>Retirement is {@code enabled = false}: no definition is ever hard-deleted, because deleting one
 * would silently blank the meaning of product JSON already stored under its code.</p>
 */
@Entity
@Table(name = "product_attribute_profiles")
public class ProductAttributeProfile extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "profile_key", length = 50, nullable = false)
    private String profileKey;

    @Column(length = 100, nullable = false)
    private String label;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private Boolean enabled = true;

    public ProductAttributeProfile() {}

    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public String getProfileKey() {
        return profileKey;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public void setProfileKey(String profileKey) {
        this.profileKey = profileKey;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
