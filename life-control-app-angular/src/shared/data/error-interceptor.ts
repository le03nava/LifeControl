import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from './notification';
import { httpErrorMessage } from './http-error-message';
import { SKIP_ERROR_NOTIFICATION } from './skip-error-notification';

/**
 * Functional HTTP interceptor for error handling
 * Centralized error handling with user notifications
 * Maneja caso donde Keycloak no está disponible
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notificationService = inject(NotificationService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      // NO llamar keycloak.login() aquí - causa loop infinito
      // El usuario debe iniciar sesión manualmente
      if (!req.context.get(SKIP_ERROR_NOTIFICATION)) {
        notificationService.showError(httpErrorMessage(error));
      }
      return throwError(() => error);
    }),
  );
};
