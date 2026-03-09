import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from './notification';
import Keycloak from 'keycloak-js';

/**
 * Functional HTTP interceptor for error handling
 * Centralized error handling with user notifications
 * Maneja caso donde Keycloak no está disponible
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notificationService = inject(NotificationService);
  
  // Manejar caso donde Keycloak no está disponible
  let keycloak: Keycloak | undefined;
  try {
    keycloak = inject(Keycloak);
  } catch (e) {
    // Keycloak no disponible aún, continuar sin él
    console.warn('[errorInterceptor] Keycloak not available yet');
  }

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      let errorMessage = 'An unexpected error occurred';

      switch (error.status) {
        case 400:
          errorMessage = 'Bad request. Please check your input.';
          break;
        case 401:
          errorMessage = 'You are not authorized. Please log in.';
          // NO llamar keycloak.login() aquí - causa loop infinito
          // El usuario debe iniciar sesión manualmente
          break;
        case 403:
          errorMessage = 'You do not have permission to perform this action.';
          break;
        case 404:
          errorMessage = 'The requested resource was not found.';
          break;
        case 500:
          errorMessage = 'Server error. Please try again later.';
          break;
        default:
          if (error.error?.message) {
            errorMessage = error.error.message;
          }
      }

      notificationService.showError(errorMessage);
      return throwError(() => error);
    }),
  );
};
