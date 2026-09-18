import { inject } from '@angular/core';
import Keycloak from 'keycloak-js';

/**
 * Client-role helper mirroring `Roles.java` and the token path used by
 * `core/layout/header/header.ts` and `core/guards/auth-keycloak-guard.ts`.
 *
 * All `lc-*` roles are client roles of `life-control-client`; this module is the
 * single frontend source of truth for their names. It deliberately stays minimal:
 * it does not rank roles, expand hierarchies, or model realm roles.
 */

export const CLIENT_ID = 'life-control-client';

export const LC_ADMIN = 'lc-admin';
export const LC_COMPANY = 'lc-company';
export const LC_COMPANY_READ = 'lc-company-read';
export const LC_COMPANY_COUNTRY = 'lc-company-country';
export const LC_COMPANY_COUNTRY_READ = 'lc-company-country-read';
export const LC_COMPANY_REGION = 'lc-company-region';
export const LC_COMPANY_REGION_READ = 'lc-company-region-read';
export const LC_COMPANY_ZONE = 'lc-company-zone';
export const LC_COMPANY_ZONE_READ = 'lc-company-zone-read';
export const LC_COMPANY_STORE = 'lc-company-store';
export const LC_COMPANY_STORE_READ = 'lc-company-store-read';

/** Const array mirroring `Roles.java`. */
export const CLIENT_ROLES = [
  LC_ADMIN,
  LC_COMPANY,
  LC_COMPANY_READ,
  LC_COMPANY_COUNTRY,
  LC_COMPANY_COUNTRY_READ,
  LC_COMPANY_REGION,
  LC_COMPANY_REGION_READ,
  LC_COMPANY_ZONE,
  LC_COMPANY_ZONE_READ,
  LC_COMPANY_STORE,
  LC_COMPANY_STORE_READ,
] as const;

/**
 * Returns `true` when the authenticated user holds at least one of `roles` as a
 * client role of `life-control-client`.
 *
 * Must be called in an Angular injection context (component field initializer or
 * constructor); it is meant to be evaluated once, not inside a reactive function.
 */
export function hasAnyClientRole(roles: string[]): boolean {
  const keycloak = inject(Keycloak);
  const clientRoles: string[] = keycloak.tokenParsed?.resource_access?.[CLIENT_ID]?.roles ?? [];
  return roles.some((role) => clientRoles.includes(role));
}
