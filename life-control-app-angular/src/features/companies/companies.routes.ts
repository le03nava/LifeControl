import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';

export const companyRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { role: 'life-control-admin' },
    children: [
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
    ],
  },
];
