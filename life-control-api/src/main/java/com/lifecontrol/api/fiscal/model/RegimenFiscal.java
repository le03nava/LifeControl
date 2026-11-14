package com.lifecontrol.api.fiscal.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The closed vocabulary of the issuer's fiscal regime — SAT's {@code c_RegimenFiscal} catalog, as the one
 * fact {@code companies.regimen_fiscal} may hold (S1 of {@code odd/tasks/cfdi-fiscal-document.md},
 * {@code V102__company_fiscal_identity.sql}).
 *
 * <p><b>Why this vocabulary is not the issuer's.</b> It was {@code com.lifecontrol.api.company.model} while the
 * issuer was its only user. {@code odd/tasks/cfdi-fiscal-document.md} decision L8(a) moved it here, to
 * {@code com.lifecontrol.api.fiscal.model}, because the receiver's fiscal profile
 * ({@code customers.regimen_fiscal}, {@code V107}) reads <b>the same</b> catalog: two copies, one for the issuer
 * and one for the receiver, would be two copies free to drift, and a customer importing
 * {@code company.model} would say the vocabulary belongs to the issuer. The SAT catalog is a fact of the fiscal
 * document, not of a company.</p>
 *
 * <p><b>Why the column stores the code and not this constant's name.</b> {@code companies.regimen_fiscal}
 * is a {@code VARCHAR(3)} holding {@code '601'} — the same shape {@code companies.currency} has, a code
 * string validated against a vocabulary, and deliberately not the {@code @Enumerated} mapping
 * {@link com.lifecontrol.api.product.attribute.model.ProductAttributeKind} uses. The codes are digits, so
 * no Java constant could equal one, and the value an auditor reads in a CFDI is the digit string, not the
 * constant this tree happens to name it by: {@link #code()} is the only spelling that survives the
 * boundary.</p>
 *
 * <p><b>Why the mirror lives in two places and neither may drift.</b> {@code V102} declares a named
 * {@code CHECK} over the same list and this enum states it again, exactly as
 * {@code ProductAttributeKind} mirrors {@code ck_product_attribute_definitions_kind}: the database is the
 * authority for every writer that bypasses the API, and the enum is what lets the API refuse an unknown
 * code with a 400 that names the field instead of a constraint violation that names only the rule.
 * {@code CompanyFiscalIdentityHttpIntegrationTest} pins the two together for the issuer and
 * {@code CustomerFiscalProfileHttpIntegrationTest} for the receiver, each by reading the constraint back.</p>
 *
 * <p><b>Why the codes are not derived from the ADR.</b> Decision 11 of
 * {@code docs/adr/0007-fiscal-document-is-separate.md} refuses to pin catalog values in the ADR: they
 * belong to the authority, and a reissue is a migration plus a constant, never a silent acceptance. The
 * list below is the catalog in force when {@code V102} was written; adding a code is a reviewed migration
 * that touches this enum, the {@code CHECK} and nothing else.</p>
 *
 * <p><b>What this vocabulary deliberately does not contain.</b> The {@code RENAPO}-era codes
 * ({@code 600}/{@code 602}/{@code 604}/{@code 609}/{@code 613}) were retired by the authority and are not
 * re-admitted here, and no code outside the catalog is invented to represent "not declared": the column is
 * <b>nullable</b>, and {@code NULL} is the honest way to say an issuer has not declared its regime yet
 * (S1: required to issue, not to sell).</p>
 */
public enum RegimenFiscal {

    /** {@code 601} — General de Ley Personas Morales. */
    GENERAL_DE_LEY_PERSONAS_MORALES("601"),

    /** {@code 603} — Personas Morales con Fines no Lucrativos. */
    PERSONAS_MORALES_CON_FINES_NO_LUCRATIVOS("603"),

    /** {@code 605} — Sueldos y Salarios e Ingresos Asimilados a Salarios. */
    SUELDOS_Y_SALARIOS_E_INGRESOS_ASIMILADOS("605"),

    /** {@code 606} — Arrendamiento. */
    ARRENDAMIENTO("606"),

    /** {@code 607} — Régimen de Enajenación o Adquisición de Bienes. */
    ENAJENACION_O_ADQUISICION_DE_BIENES("607"),

    /** {@code 608} — Demás ingresos. */
    DEMAS_INGRESOS("608"),

    /** {@code 610} — Residentes en el Extranjero sin Establecimiento Permanente en México. */
    RESIDENTES_EN_EL_EXTRANJERO_SIN_ESTABLECIMIENTO_PERMANENTE("610"),

    /** {@code 611} — Ingresos por Dividendos (socios y accionistas). */
    INGRESOS_POR_DIVIDENDOS("611"),

    /** {@code 612} — Personas Físicas con Actividades Empresariales y Profesionales. */
    PERSONAS_FISICAS_CON_ACTIVIDADES_EMPRESARIALES_Y_PROFESIONALES("612"),

    /** {@code 614} — Ingresos por intereses. */
    INGRESOS_POR_INTERESES("614"),

    /** {@code 615} — Régimen de los ingresos por obtención de premios. */
    INGRESOS_POR_OBTENCION_DE_PREMIOS("615"),

    /** {@code 616} — Sin obligaciones fiscales. */
    SIN_OBLIGACIONES_FISCALES("616"),

    /** {@code 620} — Sociedades Cooperativas de Producción que optan por diferir sus ingresos. */
    SOCIEDADES_COOPERATIVAS_DE_PRODUCCION("620"),

    /** {@code 621} — Incorporación Fiscal. */
    INCORPORACION_FISCAL("621"),

    /** {@code 622} — Actividades Agrícolas, Ganaderas, Silvícolas y Pesqueras. */
    ACTIVIDADES_AGRICOLAS_GANADERAS_SILVICOLAS_Y_PESQUERAS("622"),

    /** {@code 623} — Opcional para Grupos de Sociedades. */
    OPCIONAL_PARA_GRUPOS_DE_SOCIEDADES("623"),

    /** {@code 624} — Coordinados. */
    COORDINADOS("624"),

    /** {@code 625} — Actividades Empresariales con ingresos a través de Plataformas Tecnológicas. */
    ACTIVIDADES_EMPRESARIALES_POR_PLATAFORMAS_TECNOLOGICAS("625"),

    /** {@code 626} — Régimen Simplificado de Confianza (RESICO). */
    REGIMEN_SIMPLIFICADO_DE_CONFIANZA("626");

    private final String code;

    RegimenFiscal(String code) {
        this.code = code;
    }

    /**
     * The SAT code this regime is declared by — the value {@code companies.regimen_fiscal} and
     * {@code customers.regimen_fiscal} store and a CFDI states.
     */
    public String code() {
        return code;
    }

    /**
     * Resolves a stored or requested code to its constant.
     *
     * <p><b>Exact match, no trimming.</b> A padded {@code "601 "} is not the code the authority issues, and
     * the {@code CHECK} on the column refuses it; accepting it here would make the API the only writer that
     * can store a form the schema refuses, which is the divergence the mirror exists to prevent. Callers
     * normalize blank to {@code null} <b>before</b> asking, because {@code null} is the absent value and an
     * empty string is not a regime.</p>
     *
     * @param code the code a caller stated; {@code null} yields an empty result, since the column admits
     *     {@code NULL}
     * @return the matching constant, or empty when the code is not in the catalog
     */
    public static Optional<RegimenFiscal> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(regime -> regime.code.equals(code))
                .findFirst();
    }

    /**
     * The vocabulary, in declaration order: what a refusal names and what {@code V102}'s {@code CHECK}
     * states, in one place.
     */
    public static List<String> codes() {
        return Arrays.stream(values()).map(RegimenFiscal::code).toList();
    }
}
