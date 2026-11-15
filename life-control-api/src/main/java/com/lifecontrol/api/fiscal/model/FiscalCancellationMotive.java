package com.lifecontrol.api.fiscal.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The closed vocabulary of a CFDI cancellation's motive — the SAT's {@code c_MotivoCancelacion}, as the one fact
 * {@code fiscal_document.cancellation_motive} may hold on a {@code CANCELLED} row (S7 of
 * {@code odd/tasks/cfdi-fiscal-document.md}, {@code V112__fiscal_cancellation_motive.sql}).
 *
 * <p><b>Why this catalog is pinned while {@code exportacion} and {@code tax_object} are not.</b> {@code V109}
 * deliberately leaves three columns unconstrained, on the argument that the authority's value sets are "stated
 * nowhere in this tree" and a {@code CHECK} written from memory would refuse a legal document the day the catalog
 * is reissued. That argument is sound and its premise no longer holds for <b>this</b> catalog: the four codes below
 * were read from the SAT's {@code cancelación.xsd} — a <b>required</b> {@code Motivo} attribute over
 * {@code <xsd:enumeration>} values {@code 01}–{@code 04} — and cross-read in Solución Factible's own cancellation
 * catalogue, which lists the same four and the rule that every request carries one since 2022-01-01. Neither
 * declares a fifth. The reversal is one column wide and is recorded as {@code L20}(e). What still separates a read
 * catalog from a recalled one is that adding a code here is a reviewed migration touching this enum, the
 * {@code CHECK} and the migration test's inventory together — never a silent widening.</p>
 *
 * <p><b>Why the column stores the code and not this constant's name.</b> Same reason as
 * {@link RegimenFiscal} and {@link UsoCfdi}: {@code fiscal_document.cancellation_motive} is a {@code VARCHAR(2)}
 * holding {@code '01'}, the value an auditor reads in a cancellation request, and {@link #code()} is the only
 * spelling that survives the boundary. No Java constant could equal a two-digit string.</p>
 *
 * <p><b>Why the substituting folio is a rule here and not a column constraint.</b> The authority's schema makes
 * {@code FolioSustitucion} {@code use="optional"} and documents it as required only when the motive is
 * {@code 01}. It does <b>not</b> forbid the attribute for {@code 02}–{@code 04}, and what the SAT rejects
 * (code {@code 312}) is a <i>wrong</i> UUID, not the attribute's presence. So {@link #requiresSubstitutingFolio()}
 * states the one-way rule the sources do state, and the symmetric rule nobody stated is deliberately absent
 * ({@code L20}(f)). The enforcement lives in the cancellation service, which can name the field in a 400, rather
 * than in a {@code CHECK} that the database would report as a violation of a rule with no text.</p>
 *
 * <p><b>What this vocabulary deliberately does not contain.</b> No {@code 05}, because no retrieved source
 * declares one, and no code invented to represent "motive not stated": a document that is not being cancelled has
 * no motive at all, and {@code ck_fiscal_document_cancellation} ({@code V108}) couples exactly that — the column is
 * {@code NULL} on every row that is not {@code CANCELLED}.</p>
 */
public enum FiscalCancellationMotive {

    /**
     * {@code 01} — Comprobante emitido con errores <b>con</b> relación: the document is replaced, so the request
     * must name the folio that substitutes it ({@code L20}(f)).
     */
    ERRORS_WITH_RELATION("01", true),

    /** {@code 02} — Comprobante emitido con errores <b>sin</b> relación: cancelled, nothing replaces it. */
    ERRORS_WITHOUT_RELATION("02", false),

    /** {@code 03} — No se llevó a cabo la operación: the sale never happened. */
    OPERATION_NOT_CARRIED_OUT("03", false),

    /** {@code 04} — Operación nominativa relacionada en una factura global. */
    NOMINAL_OPERATION_IN_GLOBAL_INVOICE("04", false);

    private final String code;
    private final boolean substitutingFolioRequired;

    FiscalCancellationMotive(String code, boolean substitutingFolioRequired) {
        this.code = code;
        this.substitutingFolioRequired = substitutingFolioRequired;
    }

    /** The two-character code the authority states and the column stores. */
    public String code() {
        return code;
    }

    /**
     * Whether a request with this motive must name the folio that substitutes the cancelled document.
     *
     * <p>True for {@link #ERRORS_WITH_RELATION} alone, because replacing and cancelling are one act only when the
     * motive says so. Callers enforce it one way only: a motive that requires a folio and states none is refused,
     * while a folio beside a motive that does not require it is <b>accepted</b> — see the class note on
     * {@code L20}(f).</p>
     */
    public boolean requiresSubstitutingFolio() {
        return substitutingFolioRequired;
    }

    /** Every code this vocabulary admits, in the order the authority lists them. */
    public static List<String> codes() {
        return Arrays.stream(values()).map(FiscalCancellationMotive::code).toList();
    }

    /**
     * The motive a stored or requested code names, or empty when it names none.
     *
     * <p>Empty rather than a thrown exception, because the two callers disagree about what an unknown code means:
     * the service answers a 400 naming the field, and a reader of a row written by someone else has nothing to
     * refuse and only needs to know it cannot interpret it.</p>
     */
    public static Optional<FiscalCancellationMotive> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(motive -> motive.code.equals(code))
                .findFirst();
    }
}
