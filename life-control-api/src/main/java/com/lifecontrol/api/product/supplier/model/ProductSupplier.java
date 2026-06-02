package com.lifecontrol.api.product.supplier.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.supplier.model.Supplier;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "product_suppliers",
       uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "supplier_id"}))
public class ProductSupplier extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "purchase_cost", precision = 12, scale = 2)
    private BigDecimal purchaseCost;

    @Column(name = "main")
    private Boolean main = false;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public ProductSupplier() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public BigDecimal getPurchaseCost() {
        return purchaseCost;
    }

    public Boolean getMain() {
        return main;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    public void setPurchaseCost(BigDecimal purchaseCost) {
        this.purchaseCost = purchaseCost;
    }

    public void setMain(Boolean main) {
        this.main = main;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ProductSupplier productSupplier = new ProductSupplier();

        public Builder id(UUID id) {
            productSupplier.id = id;
            return this;
        }

        public Builder product(Product product) {
            productSupplier.product = product;
            return this;
        }

        public Builder supplier(Supplier supplier) {
            productSupplier.supplier = supplier;
            return this;
        }

        public Builder purchaseCost(BigDecimal purchaseCost) {
            productSupplier.purchaseCost = purchaseCost;
            return this;
        }

        public Builder main(Boolean main) {
            productSupplier.main = main;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            productSupplier.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            productSupplier.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            productSupplier.setUpdatedAt(updatedAt);
            return this;
        }

        public ProductSupplier build() {
            return productSupplier;
        }
    }
}
