import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';

export const productRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: ['life-control-admin', 'life-control-country'] },
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./pages/products-admin/products-admin.component').then(
            (m) => m.ProductsAdminComponent,
          ),
      },
      {
        path: 'list',
        loadComponent: () => import('./pages/product-list/product-list').then((m) => m.ProductList),
      },
      {
        path: 'create',
        loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
      },
      {
        path: 'edit/:id',
        loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
      },
      {
        path: 'edit/:id/suppliers',
        loadComponent: () =>
          import('./pages/product-supplier-list/product-supplier-list').then(
            (m) => m.ProductSupplierList,
          ),
      },
      {
        path: 'suppliers',
        loadComponent: () =>
          import('./suppliers/pages/supplier-list/supplier-list').then((m) => m.SupplierList),
      },
      {
        path: 'suppliers/create',
        loadComponent: () =>
          import('./suppliers/pages/supplier-edit/supplier-edit').then((m) => m.SupplierEdit),
      },
      {
        path: 'suppliers/edit/:id',
        loadComponent: () =>
          import('./suppliers/pages/supplier-edit/supplier-edit').then((m) => m.SupplierEdit),
      },
    ],
  },
];
