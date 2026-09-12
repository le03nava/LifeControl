import { HttpErrorResponse, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import Keycloak from 'keycloak-js';
import { firstValueFrom, of, throwError } from 'rxjs';
import { errorInterceptor } from './error-interceptor';
import { NotificationService } from './notification';

const API_URL = 'http://localhost:9000/api/test';

const ERROR_CASES: [status: number, message: string][] = [
  [401, 'You are not authorized. Please log in.'],
  [403, 'You do not have permission to perform this action.'],
  [404, 'The requested resource was not found.'],
  [500, 'Server error. Please try again later.'],
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

  function runRequest(status: number | null): Promise<unknown> {
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

    const req = new HttpRequest('GET', API_URL);
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
      'Server error. Please try again later.',
    );
  });

  it('should pass through successful responses without notification', async () => {
    const response = await runRequest(null);
    expect(response).toBeInstanceOf(HttpResponse);
    expect(notificationMock.showError).not.toHaveBeenCalled();
  });
});
