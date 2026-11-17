import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { CLIENT_ID, LC_ADMIN, LC_SALES } from '@core/security/roles';

/**
 * The roles that reach the customers screen, taken from the API and not from this feature's taste
 * (`S3`, `L3(c)` of `odd/tasks/customers-screen.md`).
 *
 * `CustomerController` gates **all six** methods — reads included — on
 * `hasAnyRole('lc-admin','lc-sales')` (`CustomerController.java:44,55,66,83,100,111`), so there is no
 * read-only role to hide the screen from and no narrower pair to declare. The consequence is stated
 * because it is uncomfortable: `lc-sales` can delete a customer, and this screen does not invent a
 * stricter rule than the API enforces.
 *
 * The names come from `@core/security/roles`, the frontend's single source of truth for the `lc-*`
 * client roles, so a renamed role fails to resolve here instead of silently gating nothing.
 */
export const CUSTOMER_ROLES = [LC_ADMIN, LC_SALES];

/**
 * Lazy routes for the customers feature: the list, the create page and the edit page.
 *
 * One component serves both write modes (`pages/customer-edit`), so the two routes differ only in the
 * presence of `:id` — which is what the page branches on.
 *
 * The gate sits on the parent **and** on each child, the way `companies.routes.ts:22-33` declares it: the
 * parent covers everything the subtree grows, and each child states the pair its own screen needs. A screen
 * that hides its menu entry but answers a direct URL is a screen a user cannot use; one that gates the route
 * but shows the entry is one they can see and cannot open. `header.ts` decides visibility with the same two
 * roles, and this file decides access.
 *
 * The list's `pathMatch: 'full'` is load-bearing: without it the empty path is a prefix route beside two
 * siblings, and `create`/`edit/:id` would be matched by it first.
 */
export const customerRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: CUSTOMER_ROLES, clientId: CLIENT_ID },
    children: [
      {
        path: 'create',
        canActivate: [keycloakRoleGuard],
        data: { roles: CUSTOMER_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/customer-edit/customer-edit').then((m) => m.CustomerEdit),
      },
      {
        path: 'edit/:id',
        canActivate: [keycloakRoleGuard],
        data: { roles: CUSTOMER_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/customer-edit/customer-edit').then((m) => m.CustomerEdit),
      },
      {
        path: '',
        pathMatch: 'full',
        canActivate: [keycloakRoleGuard],
        data: { roles: CUSTOMER_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/customer-list/customer-list').then((m) => m.CustomerList),
      },
    ],
  },
];
