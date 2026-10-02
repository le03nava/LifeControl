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
export const LC_SALES = 'lc-sales';
export const LC_RECEIVING = 'lc-receiving';
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
export const LC_SCHEDULING = 'lc-scheduling';
export const LC_SCHEDULING_READ = 'lc-scheduling-read';
export const LC_DEPARTMENT = 'lc-department';
export const LC_POSITION = 'lc-position';
export const LC_SENIORITY_LEVEL = 'lc-seniority-level';

/** Const array mirroring `Roles.java`. */
export const CLIENT_ROLES = [
  LC_ADMIN,
  LC_SALES,
  LC_RECEIVING,
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
  LC_SCHEDULING,
  LC_SCHEDULING_READ,
  LC_DEPARTMENT,
  LC_POSITION,
  LC_SENIORITY_LEVEL,
] as const;

/**
 * Roles allowed to reach the variant administration screens.
 *
 * Mirrors the `@PreAuthorize` set of every variant endpoint in
 * `ProductController`, `ProductVariantStoreController` and
 * `ProductVariantSearchController`, which all gate on
 * `hasAnyRole('lc-admin','lc-sales')`. A flat allow-list, not a role
 * hierarchy. The `products` route gates are its only consumer today; the
 * navigation entry that exposes the variant screens arrives with the sales
 * flow in the next slice.
 */
export const VARIANT_ROLES = [LC_ADMIN, LC_SALES];

/**
 * Roles allowed to create, edit, disable or re-enable a store sub-entity
 * (area, zone, location).
 *
 * Mirrors the `@PreAuthorize` write sets of `StoreAreaController`,
 * `StoreZoneController` and `StoreLocationController`, and is the same list the
 * `store-areas` / `store-zones` / `store-locations` `create` and `edit` routes
 * carry.
 *
 * `lc-company-store-read` is deliberately absent: the read role reaches the
 * listing routes but must not render a control it can never use. A flat
 * allow-list — not a role hierarchy, and never a "not read-only" test, so a
 * user holding neither a write nor a read role stays gated too.
 */
export const STORE_WRITE_ROLES = [
  LC_ADMIN,
  LC_COMPANY,
  LC_COMPANY_COUNTRY,
  LC_COMPANY_REGION,
  LC_COMPANY_ZONE,
  LC_COMPANY_STORE,
];

/**
 * Roles allowed to read the scheduling catalog (activities today, availability
 * in the next slice).
 *
 * Mirrors the `@PreAuthorize` set of every read endpoint in
 * `SchedulingActivityController`, which gate on
 * `hasAnyRole('lc-admin','lc-scheduling','lc-scheduling-read')`. No role is
 * deliberately absent: this is the full read set, and it grants no write.
 */
export const SCHEDULING_READ_ROLES = [LC_ADMIN, LC_SCHEDULING, LC_SCHEDULING_READ];

/**
 * Roles allowed to create, edit, disable or re-enable a scheduling activity.
 *
 * Mirrors the `@PreAuthorize` set of every write endpoint in
 * `SchedulingActivityController`, which gate on
 * `hasAnyRole('lc-admin','lc-scheduling')`.
 *
 * `lc-scheduling-read` is deliberately absent: the read role reaches the list
 * but must not render a control it can never use. A flat allow-list — not a
 * role hierarchy, and never a "not read-only" test, so a user holding neither a
 * write nor a read role stays gated too.
 */
export const SCHEDULING_WRITE_ROLES = [LC_ADMIN, LC_SCHEDULING];

/**
 * Roles allowed to create, edit, disable or re-enable a department.
 *
 * Mirrors the `@PreAuthorize` write set of `DepartmentController`, which gates
 * every write on `hasAnyRole('lc-admin','lc-department')`. A flat allow-list,
 * not a role hierarchy. The reads of the same controller are `isAuthenticated()`
 * only, so the screen keeps its informative text for every authenticated caller
 * and gates its controls on this list; it is also the write set the
 * `departments` `create` and `edit/:id` routes carry.
 */
export const DEPARTMENT_WRITE_ROLES = [LC_ADMIN, LC_DEPARTMENT];

/**
 * Roles allowed to create, edit, disable or re-enable a position.
 *
 * Mirrors the `@PreAuthorize` write set of `PositionController`, which gates
 * every write on `hasAnyRole('lc-admin','lc-position')`. Registered now, with
 * the departments slice, so the positions screens do not have to re-edit this
 * file; the `positions` routes are its first consumer.
 */
export const POSITION_WRITE_ROLES = [LC_ADMIN, LC_POSITION];

/**
 * Roles allowed to create, edit, disable or re-enable a global seniority level.
 *
 * Mirrors the `@PreAuthorize` write set of `SeniorityLevelController`, which
 * gates every write on `hasAnyRole('lc-admin','lc-seniority-level')`. Registered
 * now for the same reason as {@link POSITION_WRITE_ROLES}: the seniority-level
 * screen is the secondary read reached from Puestos, and its write routes are
 * its first consumer.
 */
export const SENIORITY_LEVEL_WRITE_ROLES = [LC_ADMIN, LC_SENIORITY_LEVEL];

/**
 * Union of the three HR write sets — the set that decides whether the
 * `Recursos Humanos` menu entry is rendered (D17).
 *
 * This is a **menu-visibility set, not a gate for any endpoint**: the header
 * offers the entry to anyone who can write in at least one HR catalog, and each
 * screen then gates its own controls on the narrower list above. It is also not
 * a read roster: every HR read endpoint admits any authenticated caller, and a
 * holder of no HR write role still reads them, only without the menu entry.
 */
export const HR_WRITE_ROLES = [LC_ADMIN, LC_DEPARTMENT, LC_POSITION, LC_SENIORITY_LEVEL];

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
