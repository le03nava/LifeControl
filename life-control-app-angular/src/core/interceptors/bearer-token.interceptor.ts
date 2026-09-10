import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import Keycloak from 'keycloak-js';
import { ConfigService } from '@app/services/config.service';

/**
 * Functional interceptor for adding Bearer token to requests.
 * Only attaches the token when the request targets the API Gateway,
 * so third-party API calls never leak the Keycloak token.
 * Handles case where ConfigService or Keycloak is not yet available.
 */
export const bearerTokenInterceptor: HttpInterceptorFn = (req, next) => {
  // Only attach the token to API Gateway requests
  let apiUrl: string;
  try {
    apiUrl = inject(ConfigService).apiUrl;
  } catch {
    // ConfigService not available during initialization, skip token
    return next(req);
  }

  if (!req.url.startsWith(apiUrl)) {
    return next(req);
  }

  try {
    const keycloak = inject(Keycloak);

    // Only add token if Keycloak is authenticated
    if (keycloak.authenticated && keycloak.token) {
      const cloned = req.clone({
        setHeaders: {
          Authorization: `Bearer ${keycloak.token}`
        }
      });
      return next(cloned);
    }
  } catch (e) {
    // Keycloak not available yet, continue without token
    console.warn('[bearerTokenInterceptor] Keycloak not available');
  }

  return next(req);
};
