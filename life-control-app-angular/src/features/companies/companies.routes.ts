import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';

export const companyRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: ['life-control-admin', 'life-control-country'] },
      children: [
        {
          path: '',
          loadComponent: () =>
            import('./pages/companies-admin/companies-admin.component').then(
              (m) => m.CompaniesAdminComponent,
            ),
        },
        {
          path: 'list',
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
