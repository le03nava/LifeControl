package com.lifecontrol.api.fiscal.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The closed vocabulary of the receiver's default use of the voucher — SAT's {@code c_UsoCFDI} catalog, as the
 * one fact {@code customers.default_uso_cfdi} may hold (S2 of {@code odd/tasks/cfdi-fiscal-document.md},
 * decision L8(b) of the same record, {@code V107__customer_fiscal_profile.sql}).
 *
 * <p><b>Why the receiver gets a default at all.</b> The use of the voucher is a fact of <b>each</b> document —
 * a customer may buy office supplies one day ({@code G03}) and a fixed asset the next ({@code I04}) — so what a
 * customer declares is a <b>default</b>, the value a stamp proposes instead of asking the cashier. It is
 * required as part of the profile because a default the stamp cannot use is worse than no default at all
 * (L8(b)); the document slice copies it into {@code fiscal_document.receptor_uso_cfdi} at stamp time and may
 * state a different one for that document.</p>
 *
 * <p><b>Why the column stores the code and not this constant's name.</b> {@code customers.default_uso_cfdi} is a
 * {@code VARCHAR(5)} holding {@code 'G03'} — the same shape {@link RegimenFiscal} has for the regime, and not
 * the {@code @Enumerated} mapping {@code ProductAttributeKind} uses. The code is what an auditor reads in a
 * CFDI, so {@link #code()} is the only spelling that survives the boundary.</p>
 *
 * <p><b>Why the mirror lives in two places and neither may drift.</b> {@code V107} declares a named
 * {@code CHECK} ({@code ck_customers_default_uso_cfdi}) over the same list and this enum states it again,
 * exactly as {@link RegimenFiscal} mirrors {@code ck_companies_regimen_fiscal}: the database is the authority
 * for every writer that bypasses the API, and the enum is what lets the API refuse an unknown code with a 400
 * that names the field instead of a constraint violation that names only the rule.
 * {@code CustomerFiscalProfileHttpIntegrationTest} pins the two together by reading the constraint back.</p>
 *
 * <p><b>Why the codes are not derived from the ADR.</b> Decision 11 of
 * {@code docs/adr/0007-fiscal-document-is-separate.md} refuses to pin catalog values in the ADR: they belong to
 * the authority, and a reissue is a migration plus a constant, never a silent acceptance.</p>
 *
 * <p><b>What this vocabulary deliberately does not contain.</b> The {@code c_UsoCFDI} of the third revision
 * carried codes this list does not: the fourth revision replaced that catalog with the twenty-five values
 * below, and re-admitting a retired one would let a receiver declare a use the authority no longer accepts.
 * Nothing is invented to represent "not declared" either: the column is <b>nullable</b>, and {@code NULL} is
 * the honest way to say the customer has no fiscal profile yet (S2: required to issue, not to sell).</p>
 */
public enum UsoCfdi {

    /** {@code G01} — Adquisición de mercancías. */
    ADQUISICION_DE_MERCANCIAS("G01"),

    /** {@code G02} — Devoluciones, descuentos o bonificaciones. */
    DEVOLUCIONES_DESCUENTOS_O_BONIFICACIONES("G02"),

    /** {@code G03} — Gastos en general. */
    GASTOS_EN_GENERAL("G03"),

    /** {@code I01} — Construcciones. */
    CONSTRUCCIONES("I01"),

    /** {@code I02} — Mobiliario y equipo de oficina por inversiones. */
    MOBILIARIO_Y_EQUIPO_DE_OFICINA_POR_INVERSIONES("I02"),

    /** {@code I03} — Equipo de transporte. */
    EQUIPO_DE_TRANSPORTE("I03"),

    /** {@code I04} — Equipo de cómputo y accesorios. */
    EQUIPO_DE_COMPUTO_Y_ACCESORIOS("I04"),

    /** {@code I05} — Dados, troqueles, moldes, matrices y herramental. */
    DADOS_TROQUELES_MOLDES_MATRICES_Y_HERRAMENTAL("I05"),

    /** {@code I06} — Comunicaciones telefónicas. */
    COMUNICACIONES_TELEFONICAS("I06"),

    /** {@code I07} — Comunicaciones satelitales. */
    COMUNICACIONES_SATELITALES("I07"),

    /** {@code I08} — Otra maquinaria y equipo. */
    OTRA_MAQUINARIA_Y_EQUIPO("I08"),

    /** {@code D01} — Honorarios médicos, dentales y gastos hospitalarios. */
    HONORARIOS_MEDICOS_DENTALES_Y_GASTOS_HOSPITALARIOS("D01"),

    /** {@code D02} — Gastos médicos por incapacidad o discapacidad. */
    GASTOS_MEDICOS_POR_INCAPACIDAD_O_DISCAPACIDAD("D02"),

    /** {@code D03} — Gastos funerales. */
    GASTOS_FUNERALES("D03"),

    /** {@code D04} — Donativos. */
    DONATIVOS("D04"),

    /** {@code D05} — Intereses reales efectivamente pagados por créditos hipotecarios (casa habitación). */
    INTERESES_REALES_DE_CREDITOS_HIPOTECARIOS("D05"),

    /** {@code D06} — Aportaciones voluntarias al SAR. */
    APORTACIONES_VOLUNTARIAS_AL_SAR("D06"),

    /** {@code D07} — Primas por seguros de gastos médicos. */
    PRIMAS_POR_SEGUROS_DE_GASTOS_MEDICOS("D07"),

    /** {@code D08} — Gastos de transportación escolar obligatoria. */
    GASTOS_DE_TRANSPORTACION_ESCOLAR_OBLIGATORIA("D08"),

    /** {@code D09} — Depósitos en cuentas para el ahorro, primas que tengan como base planes de pensiones. */
    DEPOSITOS_EN_CUENTAS_PARA_EL_AHORRO("D09"),

    /** {@code D10} — Pagos por servicios educativos (colegiaturas). */
    PAGOS_POR_SERVICIOS_EDUCATIVOS("D10"),

    /** {@code P01} — Por definir. */
    POR_DEFINIR("P01"),

    /** {@code S01} — Sin efectos fiscales. */
    SIN_EFECTOS_FISCALES("S01"),

    /** {@code CP01} — Pagos. */
    PAGOS("CP01"),

    /** {@code CN01} — Nómina. */
    NOMINA("CN01");

    private final String code;

    UsoCfdi(String code) {
        this.code = code;
    }

    /**
     * The SAT code this use is declared by — the value {@code customers.default_uso_cfdi} stores and a CFDI
     * states in {@code UsoCFDI}.
     */
    public String code() {
        return code;
    }

    /**
     * Resolves a stored or requested code to its constant.
     *
     * <p><b>Exact match, no trimming.</b> A padded {@code "G01 "} is not the code the authority issues, and the
     * {@code CHECK} on the column refuses it; accepting it here would make the API the only writer that can
     * store a form the schema refuses, which is the divergence the mirror exists to prevent. Callers normalize
     * blank to {@code null} <b>before</b> asking, because {@code null} is the absent value and an empty string
     * is not a use.</p>
     *
     * @param code the code a caller stated; {@code null} yields an empty result, since the column admits
     *     {@code NULL}
     * @return the matching constant, or empty when the code is not in the catalog
     */
    public static Optional<UsoCfdi> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(use -> use.code.equals(code)).findFirst();
    }

    /**
     * The vocabulary, in declaration order: what a refusal names and what {@code V107}'s {@code CHECK} states,
     * in one place.
     */
    public static List<String> codes() {
        return Arrays.stream(values()).map(UsoCfdi::code).toList();
    }
}
