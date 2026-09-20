import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { unsavedChangesGuard } from './purchase-orders/guards/unsaved-changes.guard';

export const purchasesRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: ['lc-admin'], clientId: 'life-control-client' },
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
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./purchase-orders/pages/purchase-order-edit/purchase-order-edit').then(
            (m) => m.PurchaseOrderEdit,
          ),
      },
      {
        path: 'orders/:id',
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./purchase-orders/pages/purchase-order-edit/purchase-order-edit').then(
            (m) => m.PurchaseOrderEdit,
          ),
      },
      {
        path: 'receipts',
        loadComponent: () =>
          import('./receipts/pages/receipt-list/receipt-list').then((m) => m.ReceiptList),
      },
    ],
  },
];
