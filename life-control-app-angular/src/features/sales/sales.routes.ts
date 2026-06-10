import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';

/**
 * Lazy routes for the Sales feature.
 *
 * All routes under /sales require the `lc-sales` **client role**
 * (checked via `resource_access.life-control-api.roles`).
 */
export const salesRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: ['lc-sales'], clientId: 'life-control-api' },
    children: [
      { path: '', redirectTo: 'orders', pathMatch: 'full' },
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
