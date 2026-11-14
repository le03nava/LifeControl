package com.lifecontrol.api.customer.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The tenant of this customer. Required by the schema since {@code V105}, which also arms the table, so
     * a customer built without it is refused at the insert rather than stored for nobody.
     */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "rfc")
    private String rfc;

    // ─── the receiver's fiscal profile (S2, V107) ─────────────────────────
    //
    // Seven nullable columns, and nullability is the rule and not an omission: S2 makes the profile required at
    // issue and never at the till, so a cash customer with none of them is a complete row. Which four are
    // required *together* is not a property of a row — a row with none and a row with all four are both legal —
    // so it is the write path's rule (CustomerRequest's @FiscalProfileComplete), and fiscalProfileComplete() is
    // the question the document slice asks at stamp time.

    @Column(name = "legal_name", length = 255)
    private String legalName;

    /** A {@code c_RegimenFiscal} code, resolved by the service against the fiscal vocabulary before it arrives. */
    @Column(name = "regimen_fiscal", length = 3)
    private String regimenFiscal;

    @Column(name = "fiscal_street", length = 255)
    private String fiscalStreet;

    /** Five digits, enforced by {@code ck_customers_fiscal_zip_code} as well as by the request's shape. */
    @Column(name = "fiscal_zip_code", length = 5)
    private String fiscalZipCode;

    @Column(name = "fiscal_state", length = 120)
    private String fiscalState;

    @Column(name = "fiscal_country_id")
    private UUID fiscalCountryId;

    /** A {@code c_UsoCFDI} code, resolved by the service against the fiscal vocabulary before it arrives. */
    @Column(name = "default_uso_cfdi", length = 5)
    private String defaultUsoCfdi;

    @Column(name = "sales_channel", nullable = false)
    private String salesChannel;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Customer() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getRfc() {
        return rfc;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getRegimenFiscal() {
        return regimenFiscal;
    }

    public String getFiscalStreet() {
        return fiscalStreet;
    }

    public String getFiscalZipCode() {
        return fiscalZipCode;
    }

    public String getFiscalState() {
        return fiscalState;
    }

    public UUID getFiscalCountryId() {
        return fiscalCountryId;
    }

    public String getDefaultUsoCfdi() {
        return defaultUsoCfdi;
    }

    /**
     * Whether this customer can be the receiver of a fiscal document — the <b>issue-time</b> half of S2
     * ({@code odd/tasks/cfdi-fiscal-document.md}, decision L8(f)).
     *
     * <p><b>Where the other half lives, and why they are two.</b> The <b>write-time</b> refusal is
     * {@code CustomerRequest}'s {@code @FiscalProfileComplete}: a request that states any of these four facts must
     * state all four, refused with a 400 that names each missing one. This method is the question the
     * <b>issue-time</b> call asks, and that call belongs to the document slice (A2), which is the only place a
     * stamp exists: it reads the customer of the sale and refuses to build a document when this answers
     * {@code false}. The two cannot disagree about what a profile is, because the four facts are the same four in
     * both — the write path checks them on the request and this method checks them on the row.</p>
     *
     * <p><b>Why the optional half is not consulted.</b> {@code fiscalStreet}, {@code fiscalState} and
     * {@code fiscalCountryId} are carried and optional (L8(e)): what a CFDI cannot omit of the receiver's address
     * is the postal code. Requiring them here would make a profile written under the write-time rule unusable at
     * issue.</p>
     *
     * @return {@code true} when the legal name, the regime, the fiscal postal code and the default use are all
     *     declared
     */
    public boolean fiscalProfileComplete() {
        return isDeclared(legalName)
                && isDeclared(regimenFiscal)
                && isDeclared(fiscalZipCode)
                && isDeclared(defaultUsoCfdi);
    }

    /**
     * A fact is declared when it holds something, and blank is not something: the canonical constructor of
     * {@code CustomerRequest} already collapses blank to {@code null}, and this keeps the answer the same for a
     * row written by another writer.
     */
    private static boolean isDeclared(String value) {
        return value != null && !value.isBlank();
    }

    public String getSalesChannel() {
        return salesChannel;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setRfc(String rfc) {
        this.rfc = rfc;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public void setRegimenFiscal(String regimenFiscal) {
        this.regimenFiscal = regimenFiscal;
    }

    public void setFiscalStreet(String fiscalStreet) {
        this.fiscalStreet = fiscalStreet;
    }

    public void setFiscalZipCode(String fiscalZipCode) {
        this.fiscalZipCode = fiscalZipCode;
    }

    public void setFiscalState(String fiscalState) {
        this.fiscalState = fiscalState;
    }

    public void setFiscalCountryId(UUID fiscalCountryId) {
        this.fiscalCountryId = fiscalCountryId;
    }

    public void setDefaultUsoCfdi(String defaultUsoCfdi) {
        this.defaultUsoCfdi = defaultUsoCfdi;
    }

    public void setSalesChannel(String salesChannel) {
        this.salesChannel = salesChannel;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Customer customer = new Customer();

        public Builder id(UUID id) {
            customer.id = id;
            return this;
        }

        public Builder companyId(UUID companyId) {
            customer.companyId = companyId;
            return this;
        }

        public Builder name(String name) {
            customer.name = name;
            return this;
        }

        public Builder email(String email) {
            customer.email = email;
            return this;
        }

        public Builder phone(String phone) {
            customer.phone = phone;
            return this;
        }

        public Builder rfc(String rfc) {
            customer.rfc = rfc;
            return this;
        }

        public Builder legalName(String legalName) {
            customer.legalName = legalName;
            return this;
        }

        public Builder regimenFiscal(String regimenFiscal) {
            customer.regimenFiscal = regimenFiscal;
            return this;
        }

        public Builder fiscalStreet(String fiscalStreet) {
            customer.fiscalStreet = fiscalStreet;
            return this;
        }

        public Builder fiscalZipCode(String fiscalZipCode) {
            customer.fiscalZipCode = fiscalZipCode;
            return this;
        }

        public Builder fiscalState(String fiscalState) {
            customer.fiscalState = fiscalState;
            return this;
        }

        public Builder fiscalCountryId(UUID fiscalCountryId) {
            customer.fiscalCountryId = fiscalCountryId;
            return this;
        }

        public Builder defaultUsoCfdi(String defaultUsoCfdi) {
            customer.defaultUsoCfdi = defaultUsoCfdi;
            return this;
        }

        public Builder salesChannel(String salesChannel) {
            customer.salesChannel = salesChannel;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            customer.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            customer.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            customer.setUpdatedAt(updatedAt);
            return this;
        }

        public Customer build() {
            return customer;
        }
    }
}
