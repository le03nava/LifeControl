import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import Keycloak from 'keycloak-js';

/**
 * Guard para proteger rutas basándose en roles de realm de Keycloak.
 *
 * Lee los roles directamente del claim `realm_access.roles` del token
 * decodificado (NO usa hasResourceRole, que verifica client roles).
 *
 * Uso en rutas:
 * {
 *   path: 'admin',
 *   canActivate: [keycloakRoleGuard],
 *   data: { role: 'admin' }  // un solo rol requerido
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
  const keycloak = inject(Keycloak);
  const router = inject(Router);

  // Verificamos autenticación
  if (!keycloak.authenticated) {
    await keycloak.login({
      redirectUri: window.location.origin + state.url,
    });
    return false;
  }

  // Normalizar roles requeridos: soporta data['role'] (string) y data['roles'] (array)
  const requiredRoles = normalizeRequiredRoles(route.data);

  if (requiredRoles.length === 0) {
    return true;
  }

  // Leer realm roles del token decodificado
  const token = keycloak.tokenParsed;
  const realmRoles: string[] = token?.realm_access?.roles ?? [];

  // Comprobar si el usuario tiene al menos uno de los roles requeridos
  const hasRole = requiredRoles.some((role) => realmRoles.includes(role));

  if (!hasRole) {
    router.navigate(['/unauthorized']);
    return false;
  }

  return true;
};

/**
 * Normaliza los roles requeridos desde route.data.
 * Soporta tanto data['role'] (single string) como data['roles'] (array).
 */
function normalizeRequiredRoles(data: any): string[] {
  const role = data['role'] as string | undefined;
  const roles = data['roles'] as string[] | undefined;

  if (roles && roles.length > 0) {
    return roles;
  }
  if (role) {
    return [role];
  }
  return [];
}
