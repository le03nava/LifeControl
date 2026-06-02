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
          path: 'countries/create',
          loadComponent: () =>
            import('./countries/pages/countries-edit/countries-edit').then((m) => m.CountriesEdit),
        },
        {
          path: 'countries/edit/:id',
          loadComponent: () =>
            import('./countries/pages/countries-edit/countries-edit').then((m) => m.CountriesEdit),
        },
        {
          path: 'countries',
          loadComponent: () =>
            import('./countries/pages/countries-page/countries-page').then((m) => m.CountriesPage),
        },
        {
          path: 'regions/create',
          loadComponent: () =>
            import('./regions/pages/regions-edit/regions-edit').then((m) => m.RegionsEdit),
        },
        {
          path: 'regions/edit/:id',
          loadComponent: () =>
            import('./regions/pages/regions-edit/regions-edit').then((m) => m.RegionsEdit),
        },
        {
          path: 'regions',
          loadComponent: () =>
            import('./regions/pages/regions-page/regions-page').then((m) => m.RegionsPage),
        },
        {
          path: 'zones/create',
          loadComponent: () =>
            import('./zones/pages/zones-edit/zones-edit').then((m) => m.ZonesEdit),
        },
        {
          path: 'zones/edit/:id',
          loadComponent: () =>
            import('./zones/pages/zones-edit/zones-edit').then((m) => m.ZonesEdit),
        },
        {
          path: 'zones',
          loadComponent: () =>
            import('./zones/pages/zones-page/zones-page').then((m) => m.ZonesPage),
        },
        {
          path: 'stores/create',
          loadComponent: () =>
            import('./stores/pages/stores-edit/stores-edit').then((m) => m.StoresEdit),
        },
        {
          path: 'stores/edit/:id',
          loadComponent: () =>
            import('./stores/pages/stores-edit/stores-edit').then((m) => m.StoresEdit),
        },
        {
          path: 'stores',
          loadComponent: () =>
            import('./stores/pages/stores-page/stores-page').then((m) => m.StoresPage),
        },
      ],
  },
];
