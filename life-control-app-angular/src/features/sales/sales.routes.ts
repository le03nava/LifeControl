import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';

/**
 * Lazy routes for the Sales feature.
 *
 * Access requires client roles from `life-control-client`:
 * - `lc-sales` client role
 * - `lc-admin` client role
 *
 * Both roles are resolved via `resource_access.life-control-client.roles`
 * using the shared `keycloakRoleGuard`.
 */

/** Sales routes with role-based access control using keycloakRoleGuard. */
export const salesRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: ['lc-admin', 'lc-sales'], clientId: 'life-control-client' },
    children: [
      { path: '', loadComponent: () => import('./pages/sales-admin/sales-admin.component') },
      {
        path: 'orders',
        loadComponent: () =>
          import('./sales-orders/pages/sales-order-list/sales-order-list').then(
            (m) => m.SalesOrderList,
          ),
      },
      {
        path: 'orders/new',
        loadComponent: () =>
          import('./sales-orders/pages/sales-order-edit/sales-order-edit').then(
            (m) => m.SalesOrderEdit,
          ),
      },
      {
        path: 'orders/:id',
        loadComponent: () =>
          import('./sales-orders/pages/sales-order-edit/sales-order-edit').then(
            (m) => m.SalesOrderEdit,
          ),
      },
    ],
  },
];
