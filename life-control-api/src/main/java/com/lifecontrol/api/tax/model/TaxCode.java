package com.lifecontrol.api.tax.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the platform tax-code catalog (ADR-0002 decision 6, migration {@code V82__tax_rate.sql}).
 *
 * <p><b>Why it is a platform catalog and not a tenant table.</b> What the tax <i>is</i> — {@code IVA},
 * {@code TASA_0}, {@code EXENTO} — is the same fact for every tenant, so this table has no
 * {@code company_id}, no policy and no {@code tenant_table} row, exactly like {@code currency}
 * ({@code V57}). The company's fact is how much, and that lives in {@link TaxRate}, which is armed.
 * {@code V82:22-29} states the split and seeds the four codes.</p>
 *
 * <p><b>Why it carries no {@code Auditable}.</b> The table has three columns and no timestamps
 * ({@code V82:23-26}), so a superclass would map {@code created_at}/{@code updated_at} that the database
 * does not have — and, like {@code currency}, the catalog is edited by a reviewed migration, so a
 * timestamp would date a deploy rather than a decision.</p>
 *
 * <p><b>Why the primary key is the code itself.</b> {@code tax_code.code} is a {@code VARCHAR(20)}
 * primary key and {@code sales_order_items.tax_code} is the same type: the snapshot a line keeps is the
 * code, so the identity of the row <i>is</i> the value a document carries — a surrogate id would add a
 * column nothing references and make "which code is this" a second lookup. {@code VARCHAR} and not
 * {@code CHAR} because the code varies in length ({@code NA}, {@code IVA}, {@code TASA_0},
 * {@code EXENTO}), so no {@code @JdbcTypeCode} is needed: the JDBC type is the default {@code varchar}.</p>
 *
 * <p><b>Why there is no constructor besides the JPA one.</b> Nothing in the application writes a code:
 * {@code V82} seeds the catalog, a new code is an {@code INSERT} in a reviewed migration, and this unit
 * only adds the read. A writable entity with no writer is a promise nothing keeps — the same reason
 * {@code TaxRate} states it.</p>
 */
@Entity
@Table(name = "tax_code")
public class TaxCode {

    /**
     * The catalog code, which is the identity: {@code sales_order_items.tax_code} snapshots it and
     * {@code tax_rate.tax_code} references it.
     */
    @Id
    @Column(name = "code", length = 20, nullable = false)
    private String code;

    /**
     * What the code is printed as — {@code 'No aplica'}, {@code 'Tasa 0%'} — presentation only.
     *
     * <p>{@code V82:31-32} says why the label is not the key: it is the string a screen shows and a
     * reviewed migration may rewrite, while {@code code} is what a report filters and what a line
     * snapshots.</p>
     */
    @Column(name = "label", length = 120, nullable = false)
    private String label;

    /**
     * {@code false} retires the code: the row stays so history that names it keeps resolving, and the
     * default answer of {@code GET /api/tax-codes} stops offering it.
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** Default constructor for JPA. */
    protected TaxCode() {}

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public Boolean getEnabled() {
        return enabled;
    }
}
