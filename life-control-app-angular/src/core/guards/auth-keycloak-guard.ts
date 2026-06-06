import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import Keycloak from 'keycloak-js';

/**
 * Dual-mode role guard supporting both realm roles (legacy) and client roles.
 *
 * When `clientId` is present in route data, roles are resolved from
 * `resource_access[clientId].roles`. When absent, the guard falls back to
 * `realm_access.roles` for backward compatibility.
 *
 * Usage — realm roles (legacy, backward compat):
 * {
 *   path: 'admin',
 *   canActivate: [keycloakRoleGuard],
 *   data: { role: 'admin' }  // single role or roles array
 * }
 *
 * Usage — client roles:
 * {
 *   path: 'companies',
 *   canActivate: [keycloakRoleGuard],
 *   data: { roles: ['lc-admin', 'lc-company'], clientId: 'life-control-client' }
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

  // Dual-mode: resolve roles from client or realm based on presence of clientId
  const token = keycloak.tokenParsed;
  const clientId = route.data['clientId'] as string | undefined;

  const availableRoles: string[] = clientId
    ? token?.resource_access?.[clientId]?.roles ?? []
    : token?.realm_access?.roles ?? [];

  // Comprobar si el usuario tiene al menos uno de los roles requeridos
  const hasRole = requiredRoles.some((role) => availableRoles.includes(role));

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
