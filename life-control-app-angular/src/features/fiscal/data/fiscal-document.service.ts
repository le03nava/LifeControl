import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { requiresSubstitutingFolio } from '@shared/fiscal/cancellation-motive';
import type {
  FiscalCancellationResult,
  FiscalDocument,
  FiscalDocumentIssueResult,
  StampedFiscalDocument,
} from './fiscal-document.models';

/**
 * The wire body of `POST /api/fiscal-documents` (`FiscalDocumentStamp`), as far as this client reads it.
 *
 * `xml`, `companyId` and `attemptKey` are in the answer and are deliberately not modelled: the bytes are the
 * reprint's business (`S3`) and the other two are the caller's own input. A field this client does not read
 * is a field it cannot disagree with the API about.
 */
interface StampResponse {
  documentId: string;
  salesOrderId: string;
  status: string;
  uuidFiscal: string;
  stampedAt: string;
  stampedByThisAttempt: boolean;
}

/**
 * The wire body of `POST /api/fiscal-documents/{id}/cancellation` (`FiscalDocumentCancellation`).
 *
 * The row and its state are `null` together exactly when nothing was written; the pair is typed nullable
 * because the server is the side that decides it, and {@link cancellationResult} states the invariant.
 */
interface CancellationResponse {
  cancellationDocumentId: string | null;
  cancelledDocumentId: string;
  motive: string;
  status: string | null;
  statusUuid: string;
  message: string;
}

/** The request body of the cancellation: the motive, and the folio only where it replaces the document. */
interface CancellationRequest {
  motive: string;
  substitutingFolio?: string;
}

/**
 * The fiscal document's four operations, as one root service (`#932`, `T1`).
 *
 * **Why it is one service and not three.** They are one resource (`/api/fiscal-documents`) and one screen
 * reads all four: the read by sale says whether there is a document, the issue creates it, the reprint
 * downloads its bytes and the cancellation ends it. Splitting them would put four base URLs and four
 * error shapes behind one operator workflow.
 *
 * **Why the base URL names no company.** The API does not require one: `TenantScopeFilter` states the
 * caller's single `company_id` claim as the operation's tenant before each method runs, so a path carrying a
 * company would be a second statement of a fact the policy already owns — and a wrong second statement is
 * the 404 of another tenant's document.
 *
 * **Why this service swallows nothing.** No `catchError` and no default value: `S6` distinguishes three
 * "why nothing happened" answers (`503` no provider, `404` a draft or another company's document, `409`
 * already cancelled) and `S5` distinguishes `202` from `201`, so the screen is the only place that can map
 * them to copy. A service that answered `null` or a boolean on failure would delete the distinction before
 * the screen sees it — the same reason `TaxRateService` rethrows.
 *
 * **Why there is no error signal here, unlike `TaxRateService`.** That copy belongs to the screen that knows
 * which of the three answers it holds; a single sentence set here would have to be generic, which is exactly
 * what `L6` refuses.
 */
@Injectable({
  providedIn: 'root',
})
export class FiscalDocumentService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  /** The resource root: every call is beside it, none nests under a company. */
  get apiUrl(): string {
    return `${this.configService.apiUrl}/fiscal-documents`;
  }

  /**
   * Reads the document a sale produced, by the sale alone — the one id every caller already holds.
   *
   * `observe: 'response'` is load-bearing: a **`204`** states that nothing was stamped, and it is a state, not
   * a failure (`S2`) — mapping it here is what keeps "not yet invoiced" from reaching the error interceptor's
   * toast, which is what a `404` would have done on every normal completed sale. A `200` returns the body,
   * cancellation block included.
   */
  readBySale(salesOrderId: string): Observable<FiscalDocument | null> {
    return this.http
      .get<FiscalDocument>(`${this.apiUrl}/by-sale/${salesOrderId}`, { observe: 'response' })
      .pipe(map((response) => response.body ?? null));
  }

  /**
   * Issues one completed sale's document under an attempt key — the command that makes a stamp happen.
   *
   * The endpoint answers `201` when this call stamped and `200` when the attempt already had a document, but
   * the body states which in `stampedByThisAttempt`, so the status is not parsed: `S2` needs the screen to
   * report the same document on a replay, and a caller that inferred it from a status would be re-deriving a
   * fact the answer already carries.
   */
  issue(salesOrderId: string, attemptKey: string): Observable<FiscalDocumentIssueResult> {
    return this.http.post<StampResponse>(this.apiUrl, { salesOrderId, attemptKey }).pipe(
      map((stamp) => ({
        document: stampedDocument(stamp),
        stampedByThisAttempt: stamp.stampedByThisAttempt,
      })),
    );
  }

  /**
   * Downloads the XML a stamp kept, exactly as the API serves it (`S3`).
   *
   * This is the first blob path in the tree (`L5`): `responseType: 'blob'` and an `observe: 'response'` for the
   * headers, so the file is saved by the caller under the name the API states instead of being parsed as
   * text. **No new dependency**: no `file-saver`, no `saveAs` — the caller builds the object URL and the
   * synthetic `<a download>`, which is three lines the browser already has.
   *
   * The name comes from the `Content-Disposition` the API builds (`FiscalDocumentController#reprintXml`), which
   * is the folio when the document states one and its id otherwise. Both forms the header can take are read
   * here, and the id is the last resort: a download without a name is not a reason to fail a reprint.
   */
  reprintXml(documentId: string): Observable<{ blob: Blob; fileName: string }> {
    return this.http
      .get(`${this.apiUrl}/${documentId}/xml`, { observe: 'response', responseType: 'blob' })
      .pipe(
        map((response) => {
          if (!response.body) {
            // A 200 with no bytes is not an answer this endpoint produces; handing an empty file to the
            // operator would be inventing one, so the failure is stated where it is observed.
            throw new Error(
              `The XML reprint of ${documentId} answered ${response.status} with no body.`,
            );
          }
          return {
            blob: response.body,
            fileName: fileNameFrom(response.headers.get('Content-Disposition'), documentId),
          };
        }),
      );
  }

  /**
   * Cancels one stamped document and answers **which of the two outcomes it was** (`S4`, `S5`).
   *
   * `observe: 'response'` is required here for the reason `S5` exists: the status *is* the answer — `201` means
   * the authority granted it and a row was written, `202` means the request was submitted and nothing was
   * written. A boolean could be inverted or ignored; the union's two arms cannot be confused, and only the
   * granted one carries a cancellation row.
   *
   * `substitutingFolio` is sent **only** under motive `01`. The field is omitted rather than sent as `null`
   * (`S4`): a `null` states a value the caller does not have, while an absent field states the truth, and the
   * API's own rule makes the folio required for `01` alone. A folio handed with any other motive is dropped
   * here instead of travelling to a request the motive denies.
   */
  cancel(
    documentId: string,
    motive: string,
    substitutingFolio?: string,
  ): Observable<FiscalCancellationResult> {
    const request: CancellationRequest =
      requiresSubstitutingFolio(motive) && substitutingFolio
        ? { motive, substitutingFolio }
        : { motive };

    return this.http
      .post<CancellationResponse>(`${this.apiUrl}/${documentId}/cancellation`, request, {
        observe: 'response',
      })
      .pipe(map((response) => cancellationResult(response.status, response.body, documentId)));
  }
}

/** The stamp's answer, narrowed to the document a screen may address. */
function stampedDocument(stamp: StampResponse): StampedFiscalDocument {
  return {
    documentId: stamp.documentId,
    salesOrderId: stamp.salesOrderId,
    status: stamp.status,
    uuidFiscal: stamp.uuidFiscal,
    stampedAt: stamp.stampedAt,
  };
}

/**
 * The cancellation's answer as one of its two kinds, from the status alone.
 *
 * `201` is the receipt of a written row and `202` is a submission with none — the two the API distinguishes
 * and the only two it answers. Anything else is answered with the status it actually was instead of being
 * pressed into one of the kinds: a success code nobody documented is not a cancellation, and inventing one
 * would put the operator's document in a state the server never wrote.
 */
function cancellationResult(
  status: number,
  answer: CancellationResponse | null,
  documentId: string,
): FiscalCancellationResult {
  if (!answer) {
    throw new Error(`The cancellation of ${documentId} answered ${status} with no body.`);
  }

  switch (status) {
    case 201:
      // The server's own invariant, restated where it is consumed: a granted cancellation states the row it
      // wrote and that row's state. Half of either would be a receipt for something nobody wrote.
      if (!answer.cancellationDocumentId || !answer.status) {
        throw new Error(
          `The cancellation of ${documentId} answered 201 without the row it claims to have written.`,
        );
      }
      return {
        kind: 'granted',
        cancellationDocumentId: answer.cancellationDocumentId,
        cancelledDocumentId: answer.cancelledDocumentId,
        motive: answer.motive,
        status: answer.status,
        statusUuid: answer.statusUuid,
        message: answer.message,
      };

    case 202:
      return {
        kind: 'submitted',
        cancelledDocumentId: answer.cancelledDocumentId,
        motive: answer.motive,
        statusUuid: answer.statusUuid,
        message: answer.message,
      };

    default:
      throw new Error(
        `The cancellation of ${documentId} answered ${status}, which is neither 201 nor 202.`,
      );
  }
}

/**
 * The file name the API states for a reprint, or `<documentId>.xml` when the header states none.
 *
 * Spring's `ContentDisposition.attachment().filename(name, UTF_8)` states the name twice: the RFC 5987 form
 * (`filename*=UTF-8''Factura%20A-1234.xml`), which survives a non-ASCII name, and a quoted fallback
 * (`filename="…"`). The extended form wins when both are present because it is the one that can be decoded
 * without guessing a charset; the quoted form is read for a server or proxy that states only that. The value
 * is never used as a path — the caller hands it to `<a download>` — so nothing here decides where it goes.
 */
function fileNameFrom(disposition: string | null, documentId: string): string {
  if (disposition) {
    const extended = /filename\*\s*=\s*[^']*'[^']*'([^;]+)/i.exec(disposition);
    if (extended) {
      const stated = unquote((extended[1] as string).trim());
      try {
        return decodeURIComponent(stated);
      } catch {
        // A percent-escape the server got wrong is not a reason to lose the name it did state.
        return stated;
      }
    }

    const quoted = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition);
    if (quoted) {
      return unquote((quoted[1] as string).trim());
    }
  }

  return `${documentId}.xml`;
}

/** The value without the quotes a disposition may wrap it in. */
function unquote(value: string): string {
  return value.replace(/^"(.*)"$/, '$1');
}
