import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import Keycloak from 'keycloak-js';

/**
 * Functional interceptor for adding Bearer token to requests
 * Handles case where Keycloak is not yet available
 */
export const bearerTokenInterceptor: HttpInterceptorFn = (req, next) => {
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
