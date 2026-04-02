import { Routes } from '@angular/router';

export const companyRoutes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/company-list/company-list').then((m) => m.CompanyList),
  },

  {
    path: 'edit/:id',
    loadComponent: () => import('./pages/company-edit/company-edit').then((m) => m.CompanyEdit),
  },
  {
    path: 'create',
    loadComponent: () => import('./pages/company-edit/company-edit').then((m) => m.CompanyEdit),
  },
];
