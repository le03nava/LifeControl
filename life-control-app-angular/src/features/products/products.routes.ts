import { Routes } from '@angular/router';

export const productRoutes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/product-list/product-list').then((m) => m.ProductList),
  },

  {
    path: 'edit/:id',
    loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
  },
  {
    path: 'create',
    loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
  },
];
