package com.lifecontrol.api.product.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.product.category.model.ProductCategory;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "products")
public class Product extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 50, nullable = false, unique = true)
    private String sku;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(length = 100)
    private String shortName;

    @Column(length = 20)
    private String satCode;

    @Column(length = 50)
    private String productType;

    /**
     * The company's product category this product is classified under, or {@code null} while nobody
     * classified it.
     *
     * <p>The link points at the <b>deepest selected node</b> of the tree, never at an intermediate
     * one: the {@code categoria} / {@code subcategoria} / {@code sub-subcategoria} selects the product
     * form shows are a view of this single column, recovered by walking {@code parentCategory} up.
     * Three columns would make states the tree forbids representable — a level-2 node that is not the
     * child of the stored level-1 node — and enforcing that consistency on every write would be an
     * application rule where one link is already structural.</p>
     *
     * <p>Deliberately <b>not</b> company-scoped: {@code products} has no {@code company_id} (V1), while
     * a category belongs to a company. The company a product is classified under is therefore derived
     * from its category, not stored twice, and the response reports it as {@code categoryCompanyId}.</p>
     *
     * <p>No {@code ON DELETE} action: the category API soft-deletes ({@code enabled = false}), so no
     * hard delete exists and the default {@code NO ACTION} is the guard against orphaning.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private ProductCategory category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> attributes;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Product() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getShortName() {
        return shortName;
    }

    public String getSatCode() {
        return satCode;
    }

    public String getProductType() {
        return productType;
    }

    public ProductCategory getCategory() {
        return category;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public void setSatCode(String satCode) {
        this.satCode = satCode;
    }

    public void setProductType(String productType) {
        this.productType = productType;
    }

    public void setCategory(ProductCategory category) {
        this.category = category;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Product product = new Product();

        public Builder id(UUID id) {
            product.id = id;
            return this;
        }

        public Builder sku(String sku) {
            product.sku = sku;
            return this;
        }

        public Builder name(String name) {
            product.name = name;
            return this;
        }

        public Builder shortName(String shortName) {
            product.shortName = shortName;
            return this;
        }

        public Builder satCode(String satCode) {
            product.satCode = satCode;
            return this;
        }

        public Builder productType(String productType) {
            product.productType = productType;
            return this;
        }

        public Builder category(ProductCategory category) {
            product.category = category;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            product.attributes = attributes;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            product.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            product.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            product.setUpdatedAt(updatedAt);
            return this;
        }

        public Product build() {
            return product;
        }
    }
}
