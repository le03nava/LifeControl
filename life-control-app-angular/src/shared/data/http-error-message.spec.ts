import { HttpErrorResponse } from '@angular/common/http';
import { httpErrorMessage } from './http-error-message';

const ERROR_CASES: [status: number, message: string][] = [
  [400, 'La solicitud es inválida. Verificá los datos ingresados e intentá de nuevo.'],
  [401, 'Tu sesión expiró o no estás autorizado. Volvé a iniciar sesión.'],
  [403, 'No tenés permisos para realizar esta acción.'],
  [404, 'No se encontró el recurso solicitado.'],
  [409, 'El dato cambió mientras lo editabas. Recargá la página y volvé a intentar.'],
  [500, 'Ocurrió un error en el servidor. Intentá de nuevo más tarde.'],
];

describe('httpErrorMessage', () => {
  it.each(ERROR_CASES)('should map status %i to a Spanish message', (status, message) => {
    const error = new HttpErrorResponse({ status, statusText: 'Error' });
    expect(httpErrorMessage(error)).toBe(message);
  });

  it('should return the generic message for an unmapped status', () => {
    const error = new HttpErrorResponse({ status: 418, statusText: "I'm a teapot" });
    expect(httpErrorMessage(error)).toBe(
      'Ocurrió un error inesperado. Intentá de nuevo más tarde.',
    );
  });

  it('should return the generic message for a non-HTTP error', () => {
    expect(httpErrorMessage(new Error('boom'))).toBe(
      'Ocurrió un error inesperado. Intentá de nuevo más tarde.',
    );
  });

  it('should return the generic message for an unknown value', () => {
    expect(httpErrorMessage('nope')).toBe(
      'Ocurrió un error inesperado. Intentá de nuevo más tarde.',
    );
  });

  it('should unwrap errors encapsulated by the resource API via cause', () => {
    const httpError = new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' });
    const wrapped = new Error('Resource returned an error that is not an Error instance', {
      cause: httpError,
    });

    expect(httpErrorMessage(wrapped)).toBe(
      'Tu sesión expiró o no estás autorizado. Volvé a iniciar sesión.',
    );
  });

  it('should return the generic message when no HttpErrorResponse is in the cause chain', () => {
    const wrapped = new Error('outer', { cause: new Error('inner') });

    expect(httpErrorMessage(wrapped)).toBe(
      'Ocurrió un error inesperado. Intentá de nuevo más tarde.',
    );
  });
});
