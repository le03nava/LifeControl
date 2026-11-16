package com.lifecontrol.api.fiscal.cancellation;

import com.lifecontrol.api.fiscal.exception.FiscalSubstitutingFolioRequiredException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import java.util.Objects;
import java.util.UUID;

/**
 * One document's cancellation, as a caller asks for it: the {@code UUID} being cancelled, the authority's motive,
 * and — only when the motive replaces the document — the folio that replaces it ({@code S7} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L20}(f)).
 *
 * <p><b>Why the rule about the substituting folio is enforced here and not in a database {@code CHECK}.</b>
 * {@code L20}(f) states the one-way rule the sources do state — a motive {@code 01} without a substituting folio is
 * a request the authority refuses — and deliberately does not state the symmetric one: the XSD makes
 * {@code FolioSustitucion} {@code use="optional"} and documents it as required only for {@code 01}, no retrieved
 * source forbids it for {@code 02}–{@code 04}, and what the SAT rejects is a <i>wrong</i> UUID (code {@code 312})
 * rather than the attribute's presence. So a folio beside a motive that does not require it is <b>accepted</b>, and
 * this record is where both halves live: a {@code CHECK} would refuse the legal half and report its refusal as a
 * rule with no text, while a record can name the field and answer a 400.</p>
 *
 * <p><b>Why the refusal names {@code FolioSustitucion} and not {@code substitutingFolio}.</b> The caller fixes a
 * request to the <b>authority</b>, whose attribute is spelled {@code FolioSustitucion}; a message that named this
 * record's field would send an operator looking for a name that appears in no document and in no vendor page.</p>
 *
 * <p><b>Why the UUID is a {@link UUID} and the document states it in upper case.</b> The row this comes from —
 * {@code fiscal_document.uuid_fiscal} — is a {@code UUID} column and its entity field is this same type, so the
 * request cannot disagree with the row about the value. The <b>spelling</b> is a different fact: every published
 * example, including the vendor's, states it in upper case, and {@link UUID#toString()} lower-cases. The authority's
 * pattern admits both cases and the value is the same 128 bits, so the renderer states the form the examples show —
 * see {@link CfdiCancellationRequestBuilder}.</p>
 *
 * @param uuid the fiscal UUID of the document being cancelled, {@code Folio/@UUID}
 * @param motive which of the authority's four motives this request states, {@code Folio/@Motivo}
 * @param substitutingFolio the fiscal UUID of the document that replaces this one, {@code Folio/@FolioSustitucion};
 *     required for {@link FiscalCancellationMotive#ERRORS_WITH_RELATION} and accepted for every other motive
 */
public record CancellationRequest(UUID uuid, FiscalCancellationMotive motive, UUID substitutingFolio) {

    /**
     * Refuses a request that cannot be rendered into a document the authority accepts.
     *
     * <p>Two absences are refused and one is not: a document to cancel and a motive are required by the schema, and
     * a substituting folio is required <b>only</b> by the motive that says the document is replaced. Everything
     * else about this record is a value the schema accepts, so nothing else is judged here.</p>
     */
    public CancellationRequest {
        Objects.requireNonNull(
                uuid,
                "A cancellation request states the UUID of the document it asks to cancel, "
                        + "because the authority cancels exactly one document per Folio.");
        Objects.requireNonNull(
                motive,
                "A cancellation request states its motivo: the authority's schema makes the "
                        + "attribute required, and no default is correct for a decision about why a document is cancelled.");
        if (motive.requiresSubstitutingFolio() && substitutingFolio == null) {
            throw new FiscalSubstitutingFolioRequiredException(uuid, motive);
        }
    }

    /** Whether this request names the document that replaces the cancelled one. */
    public boolean statesSubstitutingFolio() {
        return substitutingFolio != null;
    }
}
