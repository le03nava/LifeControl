package com.lifecontrol.api.fiscal.dto;

import java.util.UUID;

/**
 * What a snapshot attempt answers: the document it wrote, or the one it found when the attempt had already
 * run ({@code S11} of {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L12(h)}).
 *
 * <p><b>Why {@code written} is part of the answer.</b> The two outcomes are one command — "make sure this
 * attempt has a draft" — and a caller that could not tell them apart would report a replay as a new document,
 * or a new document as an ignored retry. The flag is what an operator's log line and a later stamp both read:
 * a replay found its own key, and nothing moved.</p>
 *
 * <p><b>Why it is not the entity.</b> A draft is a fact about a transaction; the document is a row set whose
 * money the schema compares at commit. Handing the entity out would let a caller read a
 * {@code total_amount} the database is still about to judge, and would tie every future reader to this
 * mapping. The record states the four facts a caller acts on and nothing it could mistake for evidence.</p>
 *
 * @param id the document, whether this attempt wrote it or found it
 * @param companyId the tenant the document belongs to
 * @param salesOrderId the sale it derives from
 * @param attemptKey the attempt it answers
 * @param status the document's state, {@code DRAFT} in this slice
 * @param lineCount how many concepts the document states
 * @param written {@code true} when this call wrote the row set, {@code false} when it found it already there
 */
public record FiscalDocumentDraft(
        UUID id, UUID companyId, UUID salesOrderId, String attemptKey, String status, int lineCount, boolean written) {}
