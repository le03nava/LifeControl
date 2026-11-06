package com.lifecontrol.api.tax.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of a company's tax rates (ADR-0002 decision 6, migration {@code V82__tax_rate.sql}).
 *
 * <p><b>Why it is a tenant table and {@code TaxCode} would not be.</b> A rate belongs to the company that
 * files it, so this table carries {@code company_id}, is armed by {@code apply_tenant_isolation} and its row
 * lives in {@code tenant_table}. What the tax <i>is</i> — {@code IVA}, {@code TASA_0}, {@code EXENTO} — is a
 * platform fact and lives in the {@code tax_code} catalog, which has no {@code company_id} and no policy,
 * exactly like {@code currency}.</p>
 *
 * <p><b>Why it carries no {@code Auditable}.</b> The ADR's {@code ## Schema} block gives this table six
 * columns and no timestamps: a rate is corrected by appending the next {@code effective_from}, never by
 * editing a row, so the history <i>is</i> the audit.</p>
 *
 * <p><b>Why there is no constructor and no setter.</b> Nothing in the application writes a rate yet: the
 * migration seeds the catalog, an operator states a company's rates by migration, and the units that take
 * money (T6, T7) only read through {@link com.lifecontrol.api.tax.service.TaxRateService}. A writable
 * entity with no writer is a promise nothing keeps.</p>
 */
@Entity
@Table(name = "tax_rate")
public class TaxRate {

    /** Surrogate key, and the column {@code variant_listing.tax_rate_id} will reference later (V58:26-32). */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The company that files this rate; the policy's key column, and half of the resolution's predicate. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * The catalog code this rate is for, mapped as the plain string the column holds.
     *
     * <p>Deliberately <b>not</b> a {@code @ManyToOne} to a {@code TaxCode} entity: the code is a natural key
     * the resolution matches on, and an association here would make every read of a rate fetch a catalog row
     * the caller did not ask for.</p>
     */
    @Column(name = "tax_code", length = 20, nullable = false, updatable = false)
    private String taxCode;

    /**
     * The rate, as a fraction: {@code 0.160000} is 16%.
     *
     * <p>{@code precision 9, scale 6} because that is the column ({@code NUMERIC(9,6)}) and because
     * {@code sales_order_items.tax_rate} is the same type: a narrower mapping would round the copy and make
     * {@code ck_item_tax} disagree with the row it came from.</p>
     */
    @Column(name = "rate", precision = 9, scale = 6, nullable = false, updatable = false)
    private BigDecimal rate;

    /** The first day this rate applies. The resolution is {@code effective_from <= the document date}. */
    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    /**
     * {@code false} retires the rate: it stays in the table so history keeps resolving, and the resolution
     * skips it in favour of the previous enabled row.
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** Default constructor for JPA. */
    protected TaxRate() {}

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getTaxCode() {
        return taxCode;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public Boolean getEnabled() {
        return enabled;
    }
}
