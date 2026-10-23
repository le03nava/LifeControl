import { Routes } from '@angular/router';

/**
 * Feature routes for `/reports`.
 *
 * The single child is the activity-log screen. The admin gate lives on the
 * parent `reports` entry in `app.routes.ts` — `keycloakRoleGuard` with
 * `roles: ['lc-admin']` and `clientId: 'life-control-client'`, the client-role
 * shape the endpoint's `hasRole('lc-admin')` mirrors — so a direct URL is
 * refused before this lazy chunk is requested.
 *
 * No guard is re-declared here on purpose: the legacy `data: { role: 'admin' }`
 * form this feature's `users-admin` sibling carries is read against the
 * **realm** roles, so it would refuse every holder of the `lc-admin` client role
 * — exactly the callers the endpoint admits.
 */
export const reportsRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./pages/activity-log-list/activity-log-list').then((m) => m.ActivityLogList),
  },
];
