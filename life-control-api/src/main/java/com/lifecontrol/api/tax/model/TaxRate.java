package com.lifecontrol.api.tax.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <p><b>Why it has a factory and exactly one setter.</b> The rate a company files is stated through
 * {@code /api/companies/{companyId}/tax-rates} (issue #858), and the only way into this entity is
 * {@link #declare}, which <b>creates</b> a row. There is no setter for {@code rate}, {@code taxCode} or
 * {@code effectiveFrom}, and the columns are {@code updatable = false}: the rule that a rate change appends the
 * next {@code effective_from} instead of rewriting the old row is therefore not a convention this class trusts
 * a caller to follow, it is the only thing the type can do. {@link #setEnabled(boolean)} is the exception and
 * the reason {@code enabled} exists: retiring a row is not editing history, it is deciding that history stops
 * applying.</p>
 */
@Entity
@Table(name = "tax_rate")
public class TaxRate {

    /** The scale of {@code rate}, which is {@code NUMERIC(9,6)} and the scale of {@code sales_order_items.tax_rate}. */
    private static final int RATE_SCALE = 6;

    /** One half of the key {@code variant_listing.tax_rate_id} references since {@code V88}, with {@code company_id}. */
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

    /**
     * Declares a rate, i.e. appends one row. The only way to obtain an instance.
     *
     * <p>The id is chosen here rather than left to the column's {@code DEFAULT gen_random_uuid()}: this entity
     * carries no {@code @GeneratedValue}, so Hibernate needs an assigned identity before the insert, and the
     * response can name the row without a second read.</p>
     *
     * <p>It does not validate the caller's input — bounds, the catalog code and the duplicate day are the
     * service's job, where the refusal can name what is wrong. What it does not offer at all is the possibility
     * of a mutation: there is no builder and no setter for the four historical columns.</p>
     *
     * <p><b>Why the rate is stated at the column's own scale.</b> {@code tax_rate.rate} is
     * {@code NUMERIC(9,6)}, so {@code 0.16} is stored as {@code 0.160000} and read back that way — but the
     * declaration response, and every resolution answered <i>before</i> the persistence context is cleared,
     * hand out the instance this factory built. Without this normalization the same row would be answered
     * {@code 0.16} by the writer and {@code 0.160000} by every later reader, and a line's snapshot would carry
     * the one value that does not match the row it was copied from. {@code HALF_UP} is PostgreSQL's own
     * {@code numeric} rounding, so a caller that states more decimals than the column carries is normalized
     * the way the insert would normalize it instead of keeping a value the row cannot hold.</p>
     *
     * @param companyId the company filing the rate, never {@code null} since {@code tax_rate.company_id} is
     * @param taxCode the catalog code this rate is for, e.g. {@code IVA}
     * @param rate the rate as a fraction, e.g. {@code 0.160000}
     * @param effectiveFrom the first day it applies
     * @param enabled whether the resolution may answer it
     */
    public static TaxRate declare(
            UUID companyId, String taxCode, BigDecimal rate, LocalDate effectiveFrom, boolean enabled) {
        var taxRate = new TaxRate();
        taxRate.id = UUID.randomUUID();
        taxRate.companyId = companyId;
        taxRate.taxCode = taxCode;
        taxRate.rate = rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        taxRate.effectiveFrom = effectiveFrom;
        taxRate.enabled = enabled;
        return taxRate;
    }

    /**
     * Retires or reinstates this rate. The one mutable field on the row.
     *
     * <p>A retired row keeps resolving for the documents it applied to — that is why it is retired and not
     * deleted — while {@code TaxRateRepository}'s resolver steps over it and answers the previous enabled row.
     *</p>
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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
