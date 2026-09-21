import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { CLIENT_ID, LC_ADMIN, LC_RECEIVING } from '@core/security/roles';
import { unsavedChangesGuard } from './purchase-orders/guards/unsaved-changes.guard';

export const purchasesRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: [LC_ADMIN, LC_RECEIVING], clientId: CLIENT_ID },
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
        // The orders area stays admin-only: the relaxed parent lets a
        // receiving-only user in, so each child re-declares both the guard and
        // its own `data`. A child `data` block without a child guard is inert,
        // because `keycloakRoleGuard` only reads `route.data` when it is itself
        // listed in `canActivate`.
        canActivate: [keycloakRoleGuard],
        data: { roles: [LC_ADMIN], clientId: CLIENT_ID },
        loadComponent: () =>
          import('./purchase-orders/pages/purchase-order-list/purchase-order-list').then(
            (m) => m.PurchaseOrderList,
          ),
      },
      {
        path: 'orders/create',
        canActivate: [keycloakRoleGuard],
        canDeactivate: [unsavedChangesGuard],
        data: { roles: [LC_ADMIN], clientId: CLIENT_ID },
        loadComponent: () =>
          import('./purchase-orders/pages/purchase-order-edit/purchase-order-edit').then(
            (m) => m.PurchaseOrderEdit,
          ),
      },
      {
        path: 'orders/:id',
        canActivate: [keycloakRoleGuard],
        canDeactivate: [unsavedChangesGuard],
        data: { roles: [LC_ADMIN], clientId: CLIENT_ID },
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
      {
        // The literal `create` path must stay declared before `receipts/:id`,
        // otherwise the parameterised route captures it as an id.
        path: 'receipts/create',
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./receipts/pages/receipt-create/receipt-create').then((m) => m.ReceiptCreate),
      },
      {
        path: 'receipts/:id',
        loadComponent: () =>
          import('./receipts/pages/receipt-detail/receipt-detail').then((m) => m.ReceiptDetail),
      },
    ],
  },
];
