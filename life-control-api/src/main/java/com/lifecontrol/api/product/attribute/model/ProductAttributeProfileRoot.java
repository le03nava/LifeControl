package com.lifecontrol.api.product.attribute.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.company.model.Company;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * Attaches a {@link ProductAttributeProfile} to a company's category <b>root, by name</b>, mapped to the
 * {@code product_attribute_profile_roots} table created by
 * {@code V37__product_attribute_catalog.sql}.
 *
 * <p>By name, and not by {@code product_categories.id}, because the two sides live at different scopes:
 * the catalog is global while every category row belongs to one company ({@code company_id NOT NULL},
 * V31) and each new company receives its own copy of the starter tree (V35). A definition holding a
 * category id would therefore have to hold one per tenant, and a company created tomorrow would need new
 * definitions. Matching the root's name keeps one catalog for every tenant and needs no change to
 * {@code StarterProductCategoryApplier}.</p>
 *
 * <p>The cost is named rather than hidden: renaming a root stops the attachment from resolving, and the
 * endpoint then answers "no profile" instead of inventing one.</p>
 *
 * <p>{@code company} is {@code null} for the seeded global attachment; a company row naming the same
 * root wins, which is the whole override mechanism. Uniqueness is per owner through
 * {@code uq_product_attribute_profile_roots_owner_root}.</p>
 */
@Entity
@Table(name = "product_attribute_profile_roots")
public class ProductAttributeProfileRoot extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    private ProductAttributeProfile profile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "root_name", length = 100, nullable = false)
    private String rootName;

    public ProductAttributeProfileRoot() {}

    public UUID getId() {
        return id;
    }

    public ProductAttributeProfile getProfile() {
        return profile;
    }

    public Company getCompany() {
        return company;
    }

    public String getRootName() {
        return rootName;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public void setProfile(ProductAttributeProfile profile) {
        this.profile = profile;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public void setRootName(String rootName) {
        this.rootName = rootName;
    }
}
