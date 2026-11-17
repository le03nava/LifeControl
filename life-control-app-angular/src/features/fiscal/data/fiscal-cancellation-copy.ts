import { unwrapHttpError } from '@shared/data';
import { CANCELLATION_MOTIVE_LABELS } from '@shared/fiscal/cancellation-motive';
import { fiscalApiMessage } from './fiscal-api-message';
import type { FiscalCancellationResult } from './fiscal-document.models';

/**
 * The one copy of a cancellation's answer, for the dialog that asks and the panel that keeps the answer.
 *
 * **Why it is shared and not written twice.** `S5` is a distinction that must survive every rendering: a
 * `202` says the request was *submitted* and nothing was written, and a screen that says "cancelled" over it
 * contradicts the API's own honesty. Two copies of the sentence are two chances to lose the word, so the
 * dialog (which receives the answer) and the panel (which keeps it after the dialog closes) read the same
 * function.
 */

/** The motive as the authority's own wording, or the stored code when the vocabulary does not name it. */
export function motiveLabel(code: string): string {
  return CANCELLATION_MOTIVE_LABELS[code] ?? code;
}

/** One answer's copy, as the two facts a screen renders: what happened, and what the authority said. */
export interface CancellationOutcome {
  /** The answer's own kind, so the markup and a spec can name which of the two is on screen. */
  readonly kind: 'granted' | 'submitted';
  /** The sentence that says what happened — the one place `S5`'s distinction is worded. */
  readonly headline: string;
  /** The motive, the authority's code and its own sentence, untranslated. */
  readonly detail: string;
  /** The authority's own code (`201`, `202`, `211`, …), which is what an operator quotes. */
  readonly authorityCode: string;
}

/**
 * The answer of a cancellation, worded as one of its two kinds.
 *
 * The granted arm is the only one that may say the document is cancelled: the API writes a row for it. The
 * submitted arm says the request is not accepted **and that the document is still valid**, because that is
 * what a `202` means and the operator's next action depends on it.
 */
export function cancellationOutcomeCopy(result: FiscalCancellationResult): CancellationOutcome {
  const motive = `Motivo ${result.motive} — ${motiveLabel(result.motive)}.`;
  const detail = `${motive} Código de la autoridad: ${result.statusUuid}. ${result.message}`;

  return result.kind === 'granted'
    ? {
        kind: 'granted',
        headline: 'El documento se canceló ante el SAT.',
        detail,
        authorityCode: result.statusUuid,
      }
    : {
        kind: 'submitted',
        headline:
          'La cancelación se envió y todavía no está aceptada: el documento sigue vigente hasta que el SAT la acepte.',
        detail,
        authorityCode: result.statusUuid,
      };
}

/**
 * Which of the "why nothing happened" answers a refusal is (`S6`), as a closed set.
 *
 * `409` is **two causes under one status**: a document a row already cancels, and a request the authority
 * refused. The API's own `message` is the only thing that says which — it names the issue codes when the
 * authority refused — so the copy names both possibilities instead of inventing a distinction the response
 * does not carry.
 */
export type CancellationRefusalCause =
  | 'already-cancelled-or-refused'
  | 'unknown-document'
  | 'cannot-cancel-here'
  | 'invalid-request'
  | 'other';

/** One refusal: what the screen can add, and the API's own sentence. */
export interface CancellationRefusal {
  readonly cause: CancellationRefusalCause;
  /** The sentence naming the cause, or `null` where only the API's own words are true. */
  readonly headline: string | null;
  /** The API's sentence, untranslated — never replaced by the headline. */
  readonly message: string;
}

/**
 * A cancellation refusal, worded by its cause (`S4`, `S6`).
 *
 * The three states `S6` names are told apart by their own sentence and their own hook:
 * `404` is "no such stamped document in this company" (a draft or another company's), `409` is "already
 * cancelled or refused", and **`503` says the deployment cannot cancel** — never that the document cannot be
 * cancelled, which is a different and untrue statement about the operator's own invoice.
 *
 * A `400` keeps a sentence of its own because this form can raise one: a substituting folio that names no
 * stamped document of this company is refused with the field's name, and the operator has to fix that field.
 */
export function cancellationRefusalCopy(error: unknown): CancellationRefusal {
  const message = fiscalApiMessage(error);

  switch (unwrapHttpError(error)?.status) {
    case 409:
      return {
        cause: 'already-cancelled-or-refused',
        headline:
          'El documento ya se había cancelado, o el SAT rechazó esta solicitud: el mensaje dice cuál de las dos.',
        message,
      };
    case 404:
      return {
        cause: 'unknown-document',
        headline:
          'Este documento no está timbrado en esta empresa, así que no hay nada que cancelar: puede ser un borrador o de otra compañía.',
        message,
      };
    case 503:
      return {
        cause: 'cannot-cancel-here',
        headline:
          'Esta instalación no puede cancelar: no hay un proveedor de cancelación configurado, así que el problema es del despliegue y no del documento.',
        message,
      };
    case 400:
      return {
        cause: 'invalid-request',
        headline:
          'El SAT no admite esta solicitud de cancelación tal como está: corregí el campo que nombra.',
        message,
      };
    default:
      return { cause: 'other', headline: null, message };
  }
}
