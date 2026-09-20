import {
  HttpContext,
  HttpErrorResponse,
  HttpHandlerFn,
  HttpRequest,
  HttpResponse,
} from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import Keycloak from 'keycloak-js';
import { firstValueFrom, of, throwError } from 'rxjs';
import { errorInterceptor } from './error-interceptor';
import { NotificationService } from './notification';
import { SKIP_ERROR_NOTIFICATION } from './skip-error-notification';

const API_URL = 'http://localhost:9000/api/test';

const ERROR_CASES: [status: number, message: string][] = [
  [400, 'La solicitud es inválida. Verificá los datos ingresados e intentá de nuevo.'],
  [401, 'Tu sesión expiró o no estás autorizado. Volvé a iniciar sesión.'],
  [403, 'No tenés permisos para realizar esta acción.'],
  [404, 'No se encontró el recurso solicitado.'],
  [500, 'Ocurrió un error en el servidor. Intentá de nuevo más tarde.'],
];

describe('errorInterceptor', () => {
  let notificationMock: { showError: ReturnType<typeof vi.fn> };
  let keycloakMock: { login: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notificationMock = { showError: vi.fn() };
    keycloakMock = { login: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        { provide: NotificationService, useValue: notificationMock },
        { provide: Keycloak, useValue: keycloakMock },
      ],
    });
  });

  function runRequest(status: number | null, context?: HttpContext): Promise<unknown> {
    const next: HttpHandlerFn = () => {
      if (status === null) {
        return of(new HttpResponse({ status: 200 }));
      }
      return throwError(
        () =>
          new HttpErrorResponse({
            status,
            statusText: 'Error',
            url: API_URL,
          }),
      );
    };

    const req = new HttpRequest('GET', API_URL, { context });
    return firstValueFrom(TestBed.runInInjectionContext(() => errorInterceptor(req, next)));
  }

  it.each(ERROR_CASES)(
    'should show an error notification on status %i',
    async (status, message) => {
      await expect(runRequest(status)).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(notificationMock.showError).toHaveBeenCalledWith(message);
    },
  );

  it('should NOT call keycloak.login() on 401', async () => {
    await expect(runRequest(401)).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(keycloakMock.login).not.toHaveBeenCalled();
  });

  it('should handle errors when Keycloak is not available', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [{ provide: NotificationService, useValue: notificationMock }],
    });

    await expect(runRequest(500)).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(notificationMock.showError).toHaveBeenCalledWith(
      'Ocurrió un error en el servidor. Intentá de nuevo más tarde.',
    );
  });

  it('should pass through successful responses without notification', async () => {
    const response = await runRequest(null);
    expect(response).toBeInstanceOf(HttpResponse);
    expect(notificationMock.showError).not.toHaveBeenCalled();
  });

  it('should NOT toast when SKIP_ERROR_NOTIFICATION is set, and still rethrow', async () => {
    const context = new HttpContext().set(SKIP_ERROR_NOTIFICATION, true);

    await expect(runRequest(404, context)).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(notificationMock.showError).not.toHaveBeenCalled();
  });
});
