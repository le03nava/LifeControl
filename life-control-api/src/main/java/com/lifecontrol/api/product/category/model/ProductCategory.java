package com.lifecontrol.api.product.category.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.company.model.Company;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One node of the company-scoped product category tree, mapped to the {@code product_categories}
 * table created by {@code V31__product_categories.sql}.
 *
 * <p>{@code parentCategory} is {@code null} exactly for a root, so the depth of a branch is data and
 * not a set of level columns. The tree is capped at three levels — {@code categoria} &rarr;
 * {@code subcategoria} &rarr; {@code sub-subcategoria} — and that ceiling is a service rule, not a
 * column here: a depth {@code CHECK} cannot be expressed without a trigger, the same split
 * {@code positions.reports_to_position_id} uses (V19).</p>
 *
 * <p>Two invariants live in the database and this entity must never produce a row that violates them.
 * Sibling names are unique per company, roots included, through the expression index
 * {@code uq_product_categories_sibling_name} — the {@code COALESCE} is what makes two roots siblings,
 * because {@code NULL <> NULL} would otherwise satisfy a plain unique constraint. And a parent must
 * belong to the same company, through the composite key
 * {@code (company_id, parent_category_id) -> (company_id, id)}: a cross-company link is impossible,
 * not merely unvalidated.</p>
 *
 * <p>There is no {@code version} column: a catalog carries no optimistic-locking precondition, the
 * same decision {@link com.lifecontrol.api.hr.model.Department} records. Soft deletion is
 * {@code enabled = false}, and disabling a node disables its subtree through the service.</p>
 */
@Entity
@Table(name = "product_categories")
public class ProductCategory extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** {@code null} for a root; always a category of the same company. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_category_id")
    private ProductCategory parentCategory;

    @Column(name = "category_name", length = 100, nullable = false)
    private String categoryName;

    @Column(name = "description", length = 255)
    private String description;

    /**
     * Optional SAT {@code ClaveProdServ}, eight numeric digits. {@code null} for a category whose fiscal
     * key nobody has looked up yet — the column is nullable on purpose and not unique, because the SAT
     * catalog repeats a key across levels (V34 holds the reasoning). The database only checks the shape;
     * no rule of the tree depends on this value.
     */
    @Column(name = "sat_code", length = 8)
    private String satCode;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public ProductCategory() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public ProductCategory getParentCategory() {
        return parentCategory;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public String getDescription() {
        return description;
    }

    public String getSatCode() {
        return satCode;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public void setParentCategory(ProductCategory parentCategory) {
        this.parentCategory = parentCategory;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setSatCode(String satCode) {
        this.satCode = satCode;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ProductCategory category = new ProductCategory();

        public Builder id(UUID id) {
            category.id = id;
            return this;
        }

        public Builder company(Company company) {
            category.company = company;
            return this;
        }

        public Builder parentCategory(ProductCategory parentCategory) {
            category.parentCategory = parentCategory;
            return this;
        }

        public Builder categoryName(String categoryName) {
            category.categoryName = categoryName;
            return this;
        }

        public Builder description(String description) {
            category.description = description;
            return this;
        }

        public Builder satCode(String satCode) {
            category.satCode = satCode;
            return this;
        }

        public Builder displayOrder(Integer displayOrder) {
            category.displayOrder = displayOrder;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            category.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            category.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            category.setUpdatedAt(updatedAt);
            return this;
        }

        public ProductCategory build() {
            return category;
        }
    }
}
