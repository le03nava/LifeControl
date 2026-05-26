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
            import('./companies/pages/companies-admin/companies-admin.component').then(
              (m) => m.CompaniesAdminComponent,
            ),
        },
        {
          path: 'list',
          loadComponent: () => import('./companies/pages/company-list/company-list').then((m) => m.CompanyList),
        },
        {
          path: 'edit/:id',
          loadComponent: () => import('./companies/pages/company-edit/company-edit').then((m) => m.CompanyEdit),
        },
        {
          path: 'create',
          loadComponent: () => import('./companies/pages/company-edit/company-edit').then((m) => m.CompanyEdit),
        },
        {
          path: 'countries',
          loadComponent: () =>
            import('./countries/pages/countries-page/countries-page').then((m) => m.CountriesPage),
        },
      ],
  },
];
