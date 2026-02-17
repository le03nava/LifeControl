import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import Keycloak from 'keycloak-js';

/**
 * Guard para proteger rutas basándose en roles de Keycloak
 *
 * Uso en rutas:
 * {
 *   path: 'admin',
 *   canActivate: [keycloakRoleGuard],
 *   data: { role: 'admin' }  // rol requerido
 * }
 *
 * También soporta múltiples roles (cualquiera de ellos):
 * {
 *   path: 'admin',
 *   canActivate: [keycloakRoleGuard],
 *   data: { roles: ['admin', 'superadmin'] }
 * }
 */
export const keycloakRoleGuard: CanActivateFn = async (route, state) => {
  // Inyectamos la instancia base de Keycloak (de keycloak-js)
  const keycloak = inject(Keycloak);
  const router = inject(Router);

  // Verificamos autenticación
  if (!keycloak.authenticated) {
    await keycloak.login({
      redirectUri: window.location.origin + state.url,
    });
    return false;
  }

  // Verificación de roles
  const requiredRoles = route.data['roles'] as string[];
  if (!requiredRoles || requiredRoles.length === 0) {
    return true;
  }

  // Comprobar si el usuario tiene al menos uno de los roles requeridos
  const hasRole = requiredRoles.some((role) => keycloak.hasResourceRole(role));

  if (!hasRole) {
    router.navigate(['/unauthorized']);
    return false;
  }

  return true;
};
