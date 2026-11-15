package com.lifecontrol.api.fiscal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the authority answered a stamp: a stamped document, or a rejection with the provider's reasons
 * ({@code S10}, {@code S11} and decision {@code L12}(g) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why two shapes and not one plus a nullable field.</b> A stamped answer and a rejected one are not the
 * same fact with a flag: the first carries the authority's UUID, its instant and the signed XML, and the second
 * carries neither — a provider that answered a rejection has stamped nothing. Two records in a sealed
 * interface make the caller's branch exhaustive and unrepresentable states impossible.</p>
 *
 * <p><b>Why a rejection is a value and not an exception.</b> «A PAC's error catalogue is the operator's only
 * way to fix the request», so the codes are the payload: a rejection thrown at the boundary would arrive at
 * the caller as a message, and a message is not something a screen can list one row at a time. The refusal the
 * attempt raises ({@code FiscalStampRejectedException}) carries these very issues and adds nothing to them.</p>
 *
 * <p><b>Why {@link Stamped#xml()} is the document the provider returned.</b> A reprint reads these bytes and
 * never regenerates a document ({@code S10}): the stamp is what proves the document is the one the authority
 * witnessed, and a regenerated one cannot be proven to be it. The tree's renderer is what the provider
 * receives; the provider's own rendering — with its {@code TimbreFiscalDigital} — is what the artefact keeps,
 * byte for byte.</p>
 */
public sealed interface FiscalStampResult permits FiscalStampResult.Stamped, FiscalStampResult.Rejected {

    /**
     * The authority witnessed the document.
     *
     * @param uuid the authority's UUID, {@code TimbreFiscalDigital/@UUID} — the identity that survives
     *     everything else
     * @param stampedAt when the authority stamped it, {@code TimbreFiscalDigital/@FechaTimbrado}
     * @param rfcProvCertif the RFC of the certificate authority that stamped it
     * @param noCertificadoSat the serial of the authority's certificate
     * @param selloCfd the issuer's seal over the document
     * @param selloSat the authority's seal over the document plus the timbre
     * @param xml the document as the provider returned it, timbre included: the artefact's bytes ({@code S10})
     */
    record Stamped(
            UUID uuid,
            OffsetDateTime stampedAt,
            String rfcProvCertif,
            String noCertificadoSat,
            String selloCfd,
            String selloSat,
            String xml)
            implements FiscalStampResult {

        public Stamped {
            // Refused at the boundary and not at the row: a stamped document must state the authority's UUID
            // (ck_fiscal_document_uuid_matches_status) and an artefact must state its XML (V108), so an answer
            // missing either is a provider defect this record names before anything is written.
            Objects.requireNonNull(uuid, "a stamped document states the authority's UUID");
            Objects.requireNonNull(stampedAt, "a stamped document states when it was stamped");
            if (xml == null || xml.isBlank()) {
                throw new IllegalArgumentException(
                        "A stamped document states the XML the provider returned: it is the artefact a reprint "
                                + "reads (S10), and a stamp that kept no bytes could never be proven original.");
            }
        }
    }

    /**
     * The authority refused the document, and nothing was stamped.
     *
     * @param issues at least one provider code with its message — the operator's only way to fix the request
     */
    record Rejected(List<Issue> issues) implements FiscalStampResult {

        public Rejected {
            issues = List.copyOf(issues);
            if (issues.isEmpty()) {
                throw new IllegalArgumentException(
                        "A rejection names at least one issue: the provider's codes are the only way the request "
                                + "is fixed, and a refusal that names none is a provider defect.");
            }
        }
    }

    /**
     * One reason a provider refused a document, in the provider's own vocabulary.
     *
     * @param code the provider's code ({@code CFDI40102}, {@code 301}, …), never translated: it is what an
     *     operator looks up in the PAC's catalogue
     * @param message the provider's sentence for that code
     */
    record Issue(String code, String message) {}
}
