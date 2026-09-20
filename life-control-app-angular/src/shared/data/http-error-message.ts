import { HttpErrorResponse } from '@angular/common/http';

const UNKNOWN_ERROR_MESSAGE = 'Ocurrió un error inesperado. Intentá de nuevo más tarde.';

/**
 * Maps any HTTP error to a user-facing Spanish message.
 *
 * Also unwraps errors encapsulated by Angular's resource API: rxResource wraps
 * non-Error values in a `ResourceWrappedError` whose `cause` holds the original
 * `HttpErrorResponse`. Returns a safe fallback when no HTTP error is found.
 */
export function httpErrorMessage(error: unknown): string {
  const httpError = unwrapHttpError(error);
  if (!httpError) {
    return UNKNOWN_ERROR_MESSAGE;
  }

  switch (httpError.status) {
    case 400:
      return 'La solicitud es inválida. Verificá los datos ingresados e intentá de nuevo.';
    case 401:
      return 'Tu sesión expiró o no estás autorizado. Volvé a iniciar sesión.';
    case 403:
      return 'No tenés permisos para realizar esta acción.';
    case 404:
      return 'No se encontró el recurso solicitado.';
    case 500:
      return 'Ocurrió un error en el servidor. Intentá de nuevo más tarde.';
    default:
      return UNKNOWN_ERROR_MESSAGE;
  }
}

/**
 * Walks the `cause` chain (guarding against cycles) to find an HttpErrorResponse.
 *
 * Exported so a caller that needs the status code rather than the copy can
 * resolve the very same wrapped error instead of duplicating the walk.
 */
export function unwrapHttpError(error: unknown): HttpErrorResponse | undefined {
  let current: unknown = error;
  const visited = new Set<unknown>();

  while (current != null && !visited.has(current)) {
    if (current instanceof HttpErrorResponse) {
      return current;
    }
    visited.add(current);
    current = (current as { cause?: unknown }).cause;
  }

  return undefined;
}
