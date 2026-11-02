package com.lifecontrol.api.currency.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the platform currency catalog (ADR-0002 decision 2, migration
 * {@code V57__company_currency.sql}).
 *
 * <p><b>Why it is not a tenant table.</b> A currency is not a company's fact — the catalog is the same
 * for every tenant, so it carries no {@code company_id} and no policy, which is what keeps it out of
 * {@code tenant_table} and out of the startup check built on it. The tenant fact is the value a company
 * picks, and that lives on {@code companies.currency}, mapped by
 * {@link com.lifecontrol.api.company.model.Company#getCurrency()}.</p>
 *
 * <p><b>Why {@code CHAR} and not {@code VARCHAR}.</b> The column is {@code CHAR(3)} because an ISO 4217
 * code is exactly three characters, and Hibernate's schema validation compares the JDBC type of the
 * mapped attribute against the one PostgreSQL reports: a {@code String} mapped by default expects
 * {@code varchar(3)} and the catalog answers {@code bpchar} (JDBC {@code CHAR}), which fails
 * {@code ddl-auto: validate} at boot. {@link JdbcTypeCode} states the type the column actually has.</p>
 *
 * <p><b>Why no {@code Auditable}.</b> The ADR's {@code ## Schema} block gives this catalog four columns
 * and no timestamps, and a superclass would map {@code created_at}/{@code updated_at} that do not exist.</p>
 */
@Entity
@Table(name = "currency")
public class Currency {

    /** The ISO 4217 code, which is the identity: {@code companies.currency} references it. */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "code", length = 3, nullable = false)
    private String code;

    /** How many decimals the currency is rounded and printed with ({@code 2} for MXN, {@code 0} for JPY). */
    @Column(name = "minor_units", nullable = false)
    private Short minorUnits;

    /** What the currency is printed as, presentation only. */
    @Column(name = "symbol", length = 6)
    private String symbol;

    /** {@code false} is a retired code: it stays referenceable by history and cannot be assigned anew. */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** Default constructor for JPA. */
    protected Currency() {}

    public Currency(String code, Short minorUnits, String symbol, Boolean enabled) {
        this.code = code;
        this.minorUnits = minorUnits;
        this.symbol = symbol;
        this.enabled = enabled;
    }

    public String getCode() {
        return code;
    }

    public Short getMinorUnits() {
        return minorUnits;
    }

    public String getSymbol() {
        return symbol;
    }

    public Boolean getEnabled() {
        return enabled;
    }
}
