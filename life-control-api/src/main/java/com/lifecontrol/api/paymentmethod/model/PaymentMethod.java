package com.lifecontrol.api.paymentmethod.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_methods")
public class PaymentMethod extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_method_name", length = 100, nullable = false, unique = true)
    private String paymentMethodName;

    @Column(name = "payment_method_short_name", length = 50, nullable = false)
    private String paymentMethodShortName;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public PaymentMethod() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getPaymentMethodName() {
        return paymentMethodName;
    }

    public String getPaymentMethodShortName() {
        return paymentMethodShortName;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setPaymentMethodName(String paymentMethodName) {
        this.paymentMethodName = paymentMethodName;
    }

    public void setPaymentMethodShortName(String paymentMethodShortName) {
        this.paymentMethodShortName = paymentMethodShortName;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final PaymentMethod paymentMethod = new PaymentMethod();

        public Builder id(UUID id) {
            paymentMethod.id = id;
            return this;
        }

        public Builder paymentMethodName(String paymentMethodName) {
            paymentMethod.paymentMethodName = paymentMethodName;
            return this;
        }

        public Builder paymentMethodShortName(String paymentMethodShortName) {
            paymentMethod.paymentMethodShortName = paymentMethodShortName;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            paymentMethod.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            paymentMethod.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            paymentMethod.setUpdatedAt(updatedAt);
            return this;
        }

        public PaymentMethod build() {
            return paymentMethod;
        }
    }
}
