import { Routes, type CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import Keycloak from 'keycloak-js';

/**
 * Lazy routes for the Sales feature.
 *
 * Access requires either:
 * - `lc-sales` client role from `life-control-client`
 * - `lc-admin` client role from `life-control-client`
 *
 * Both roles are checked via `resource_access.life-control-client.roles`.
 */

/** Sales feature guard: checks both `lc-sales` and `lc-admin` from `life-control-client`. */
const salesGuard: CanActivateFn = (_route, _state) => {
  const keycloak = inject(Keycloak);
  const token = keycloak.tokenParsed;
  const hasLcSales = token?.resource_access?.['life-control-client']?.roles?.includes('lc-sales');
  const hasLcAdmin = token?.resource_access?.['life-control-client']?.roles?.includes('lc-admin');
  return (hasLcSales || hasLcAdmin) === true;
};

export const salesRoutes: Routes = [
  {
    path: '',
    canActivate: [salesGuard],
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
