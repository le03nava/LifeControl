import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';

export const purchasesRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: ['life-control-admin'] },
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./pages/purchases-admin/purchases-admin.component').then(
            (m) => m.PurchasesAdminComponent,
          ),
      },
      {
        path: 'orders',
        loadComponent: () =>
          import('./purchase-orders/pages/purchase-order-list/purchase-order-list').then(
            (m) => m.PurchaseOrderList,
          ),
      },
      {
        path: 'orders/create',
        loadComponent: () =>
          import('./purchase-orders/pages/purchase-order-edit/purchase-order-edit').then(
            (m) => m.PurchaseOrderEdit,
          ),
      },
      {
        path: 'orders/:id',
        loadComponent: () =>
          import('./purchase-orders/pages/purchase-order-edit/purchase-order-edit').then(
            (m) => m.PurchaseOrderEdit,
          ),
      },
      {
        path: 'receipts',
        loadComponent: () =>
          import('./receipts/pages/receipts-placeholder/receipts-placeholder').then(
            (m) => m.ReceiptsPlaceholder,
          ),
      },
    ],
  },
];
