package com.lifecontrol.api.company.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "companies")
public class Company extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_key", nullable = false, unique = true)
    private String companyKey;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "tipo_persona_id")
    private Integer tipoPersonaId;

    @Column(name = "razon_social")
    private String razonSocial;

    @Column(nullable = false, unique = true)
    private String rfc;

    private String phone;

    private String email;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Company() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getCompanyKey() {
        return companyKey;
    }

    public String getCompanyName() {
        return companyName;
    }

    public Integer getTipoPersonaId() {
        return tipoPersonaId;
    }

    public String getRazonSocial() {
        return razonSocial;
    }

    public String getRfc() {
        return rfc;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompanyKey(String companyKey) {
        this.companyKey = companyKey;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public void setTipoPersonaId(Integer tipoPersonaId) {
        this.tipoPersonaId = tipoPersonaId;
    }

    public void setRazonSocial(String razonSocial) {
        this.razonSocial = razonSocial;
    }

    public void setRfc(String rfc) {
        this.rfc = rfc;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Company company = new Company();

        public Builder id(UUID id) {
            company.id = id;
            return this;
        }

        public Builder companyKey(String companyKey) {
            company.companyKey = companyKey;
            return this;
        }

        public Builder companyName(String companyName) {
            company.companyName = companyName;
            return this;
        }

        public Builder tipoPersonaId(Integer tipoPersonaId) {
            company.tipoPersonaId = tipoPersonaId;
            return this;
        }

        public Builder razonSocial(String razonSocial) {
            company.razonSocial = razonSocial;
            return this;
        }

        public Builder rfc(String rfc) {
            company.rfc = rfc;
            return this;
        }

        public Builder phone(String phone) {
            company.phone = phone;
            return this;
        }

        public Builder email(String email) {
            company.email = email;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            company.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            company.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            company.setUpdatedAt(updatedAt);
            return this;
        }

        public Company build() {
            return company;
        }
    }
}