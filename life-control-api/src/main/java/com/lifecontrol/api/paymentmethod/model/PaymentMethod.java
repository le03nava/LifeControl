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

    /**
     * Whether this method is cash, which is what ADR-0002's M4 reads: change is returned only for a cash
     * method, never on a refund, and never beyond the tender (the arithmetic half is
     * {@code ck_payment_tender}).
     *
     * <p>A platform fact and not a tenant one: {@code payment_methods} is a global catalog with no
     * {@code company_id} ({@code V1:302-309}). {@code is_cash} lands in {@code V83} with its backfill by the
     * seed's exact name ({@code Efectivo}), because a method created after that deploy could never have been
     * cash without this field on the request.</p>
     */
    @Column(name = "is_cash", nullable = false)
    private Boolean isCash = false;

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

    public Boolean getIsCash() {
        return isCash;
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

    public void setIsCash(Boolean isCash) {
        this.isCash = isCash;
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

        public Builder isCash(Boolean isCash) {
            paymentMethod.isCash = isCash;
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
