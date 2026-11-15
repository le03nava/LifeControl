package com.lifecontrol.api.fiscal.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What a stamp attempt answers: the document the authority witnessed, and the bytes that prove it
 * ({@code S10}, {@code S11} of {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L12}(g) of the same
 * record).
 *
 * <p><b>Why the XML travels here and is not read back from the artefact.</b> The attempt already holds the
 * provider's answer at the moment it keeps it, and a caller that had to come back for the bytes would be doing
 * two reads of one fact. What makes the artefact the authority of a reprint ({@code S10}) is not this record
 * but the table: it is written once, insert-only by trigger and by revoked privilege, so a reprint reads it
 * rather than regenerating anything.</p>
 *
 * <p><b>Why {@code stampedByThisAttempt} is part of the answer.</b> A replay and a stamp are the same command
 * — "make sure this attempt is stamped" — and a caller that could not tell them apart would report a
 * provider charge that never happened. The same reason {@code FiscalDocumentDraft.written()} exists one state
 * earlier.</p>
 *
 * <p><b>What is not here, and where it lives.</b> The timbre's {@code RfcProvCertif},
 * {@code NoCertificadoSat}, {@code SelloCFD} and {@code SelloSAT} come back from the provider and are kept
 * <b>inside {@link #xml()}</b>, byte for byte: {@code fiscal_document} has no columns for them (V108, V109), and
 * adding them is a migration this slice does not own. They are stated by the document the authority signed,
 * which is exactly where an auditor reads them.</p>
 *
 * @param documentId the stamped document
 * @param companyId its tenant
 * @param salesOrderId the sale it derives from
 * @param attemptKey the attempt it answers
 * @param status the document's state, {@code STAMPED}
 * @param uuidFiscal the authority's UUID, frozen on the row
 * @param stampedAt when the authority stamped it, frozen on the row
 * @param xml the bytes the provider returned; {@code null} only for a replay of a stamped row that kept no
 *     artefact, a state the schema does not forbid and that the reprint path is where to answer
 * @param stampedByThisAttempt {@code true} when this call called the provider and transitioned the document,
 *     {@code false} when it found the attempt already stamped
 */
public record FiscalDocumentStamp(
        UUID documentId,
        UUID companyId,
        UUID salesOrderId,
        String attemptKey,
        String status,
        UUID uuidFiscal,
        OffsetDateTime stampedAt,
        String xml,
        boolean stampedByThisAttempt) {}
