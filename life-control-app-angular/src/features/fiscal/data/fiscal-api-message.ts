import { httpErrorMessage, unwrapHttpError } from '@shared/data';
import type { ApiError } from '@shared/models';

/**
 * The API's own sentence for an answer, or the shared fallback when the answer carries none.
 *
 * `ApiError.message` is the server's words — the one sentence that names the fact it refused or stated — and
 * it is preferred over any mapping a screen could make. The shared map is the fallback for an answer that
 * carries no such body (a transport failure, a proxy's HTML), which is the only case where there is no server
 * sentence to show; that is not a generic banner replacing a specific one, it is the absence of anything
 * better.
 *
 * It lives here, in the fiscal feature, and not in `shared/data`: every fiscal call answers with the same
 * envelope and the same preference, and widening the shared map would change the copy of every other screen.
 * `features/fiscal` is also the only place that has to distinguish `503` and `202`, which the shared map does
 * not know (`L6`).
 */
export function fiscalApiMessage(error: unknown): string {
  const body = unwrapHttpError(error)?.error as Partial<ApiError> | null | undefined;
  const message = body?.message;

  return typeof message === 'string' && message.trim() !== '' ? message : httpErrorMessage(error);
}
