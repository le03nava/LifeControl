/**
 * What a sale's fiscal document reads and answers as, as the API states it.
 *
 * **Why the read is addressed by the sale and has no `xml`.** `GET /api/fiscal-documents/by-sale/{salesOrderId}`
 * is the only read of a sale's document (`#947`), and it answers the identity the sale produced — never the
 * stamped bytes. `GET /api/fiscal-documents/{id}/xml` is the reprint's only carrier (`S3`), and a read that
 * carried them too would be a second carrier of the one artefact this tree promises is byte-for-byte the
 * authority's. The same holds for the stamp's answer: the bytes it may carry are dropped here, because the
 * download goes through the reprint and nowhere else.
 */

/**
 * The cancellation that ended a document, as `FiscalDocumentRead.Cancellation` states it.
 *
 * The id is the **cancellation row's own** id and not the cancelled document's — a caller already holds the
 * document it asked about. The authority's numeric code and message are not here because the read does not
 * store them: a reload can state "cancelled, motive, date", and `S5`'s code comes from the answer of the
 * call in flight.
 */
export interface FiscalDocumentCancellation {
  /** The `CANCELLED` row that names {@link FiscalDocument.documentId}. */
  cancellationDocumentId: string;
  /** The authority's motive the row carries, `01` to `04` — a code, never a label. */
  motive: string;
  /** When the cancellation row was written, as the API states it (`2026-11-17T10:00:00-06:00`). */
  cancelledAt: string;
}

/**
 * A sale's stamped document (`FiscalDocumentRead`): `200` of `GET /api/fiscal-documents/by-sale/{id}`.
 *
 * A `204` from that endpoint is **not** this type — it is `null`, because a sale that was never invoiced is
 * a state the screen renders and not a failure. A cancelled document is still this type: the stamped row is
 * never edited, the cancellation arrives in {@link cancellation} beside it.
 */
export interface FiscalDocument {
  documentId: string;
  salesOrderId: string;
  /** The document's state, always `STAMPED`: a cancelled document keeps its stamped row. */
  status: string;
  /** The authority's UUID, frozen on the stamped row — what an operator quotes. */
  uuidFiscal: string;
  /** When the authority stamped it, frozen on the stamped row. */
  stampedAt: string;
  /** The optional local convention the document states, or `null` when it states none (ADR-0007). */
  folio: string | null;
  /** The cancellation that names this document, or `null` when it was never cancelled. */
  cancellation: FiscalDocumentCancellation | null;
}

/**
 * The document a stamp attempt produced, as much of `FiscalDocumentStamp` as a screen reads.
 *
 * **Why it is not the whole answer.** That record also carries the tenant, the attempt key and the provider's
 * bytes. The tenant and the attempt key are the caller's own input, and the bytes are the reprint's business
 * (`S3`), so a result type that carried them would offer a second path to the same artefact with no screen
 * asking for it. What remains is the document's identity — enough for `S1` to report its id and `uuid_fiscal`,
 * and enough to address the reprint and the cancellation.
 *
 * It is deliberately **not** {@link FiscalDocument}: a stamp states no folio and no cancellation block, and
 * filling those in with `null` would report a fact the answer never stated.
 */
export interface StampedFiscalDocument {
  documentId: string;
  salesOrderId: string;
  status: string;
  uuidFiscal: string;
  stampedAt: string;
}

/**
 * What a stamp attempt answered (`S1`, `S2`): the document, and whether **this** attempt stamped it.
 *
 * The two halves are one answer because the endpoint answers two success statuses for one command — `201`
 * when this call called the authority, `200` when the attempt it names already had a stamped document. The
 * body states which happened in `stampedByThisAttempt`, so the screen reports a replay as the same document
 * instead of claiming a second stamp; the status is not parsed to decide it.
 */
export interface FiscalDocumentIssueResult {
  document: StampedFiscalDocument;
  /** `true` when this call stamped the document, `false` when its attempt already had one. */
  stampedByThisAttempt: boolean;
}

/**
 * The authority granted the cancellation and the API wrote the row: `201` (`S5`).
 *
 * This is the only shape that may be rendered as *cancelled*, and the row's id is what a later reprint of the
 * cancellation names.
 */
export interface FiscalCancellationGranted {
  readonly kind: 'granted';
  /** The `CANCELLED` row the API wrote. */
  readonly cancellationDocumentId: string;
  /** The document the cancellation is about — the one the caller named. */
  readonly cancelledDocumentId: string;
  /** The motive the row carries, `01` to `04`. */
  readonly motive: string;
  /** The row's state, `CANCELLED`. */
  readonly status: string;
  /** The authority's own code for what it did (`201` or `202`), untranslated. */
  readonly statusUuid: string;
  /** The authority's sentence for that code, untranslated. */
  readonly message: string;
}

/**
 * The authority received the request and has not decided: `202`, and **nothing was written** (`S5`).
 *
 * There is no `cancellationDocumentId` and no `status` because there is no row: a submitted cancellation is
 * not a cancelled document, and rendering it as one is the lie the API refuses to store. The authority's code
 * (`211`) and its message are what the screen shows.
 */
export interface FiscalCancellationSubmitted {
  readonly kind: 'submitted';
  readonly cancelledDocumentId: string;
  readonly motive: string;
  readonly statusUuid: string;
  readonly message: string;
}

/**
 * What a cancellation answered: **two kinds, never a boolean** (`S5`).
 *
 * The status of the response *is* the answer — `201` wrote a row, `202` wrote nothing — so the distinction is
 * a discriminated union instead of a flag a caller can ignore or invert: a screen switches on `kind`, and the
 * submitted arm has no row to render as cancelled.
 */
export type FiscalCancellationResult = FiscalCancellationGranted | FiscalCancellationSubmitted;
