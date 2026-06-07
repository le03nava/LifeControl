import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';

const BASE_ROLES = ['lc-admin', 'lc-company', 'lc-company-country'];
const REGION_ROLES = [...BASE_ROLES, 'lc-company-region'];
const ZONE_ROLES = [...REGION_ROLES, 'lc-company-zone'];
const STORE_ROLES = [...ZONE_ROLES, 'lc-company-store'];

const CLIENT_ID = 'life-control-client';

export const companyRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: STORE_ROLES, clientId: CLIENT_ID },
    children: [
      // Dashboard — no extra guard (parent covers all company roles)
      {
        path: '',
        loadComponent: () =>
          import('./companies/pages/companies-admin/companies-admin.component').then(
            (m) => m.CompaniesAdminComponent,
          ),
      },
      // Companies CRUD — base roles only
      {
        path: '',
        canActivate: [keycloakRoleGuard],
        data: { roles: BASE_ROLES, clientId: CLIENT_ID },
        children: [
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
        ],
      },
      // Countries — base roles
      {
        path: 'countries',
        canActivate: [keycloakRoleGuard],
        data: { roles: BASE_ROLES, clientId: CLIENT_ID },
        children: [
          {
            path: 'create',
            loadComponent: () =>
              import('./countries/pages/countries-edit/countries-edit').then((m) => m.CountriesEdit),
          },
          {
            path: 'edit/:id',
            loadComponent: () =>
              import('./countries/pages/countries-edit/countries-edit').then((m) => m.CountriesEdit),
          },
          {
            path: '',
            loadComponent: () =>
              import('./countries/pages/countries-page/countries-page').then((m) => m.CountriesPage),
          },
        ],
      },
      // Regions — base + lc-company-region
      {
        path: 'regions',
        canActivate: [keycloakRoleGuard],
        data: { roles: REGION_ROLES, clientId: CLIENT_ID },
        children: [
          {
            path: 'create',
            loadComponent: () =>
              import('./regions/pages/regions-edit/regions-edit').then((m) => m.RegionsEdit),
          },
          {
            path: 'edit/:id',
            loadComponent: () =>
              import('./regions/pages/regions-edit/regions-edit').then((m) => m.RegionsEdit),
          },
          {
            path: '',
            loadComponent: () =>
              import('./regions/pages/regions-page/regions-page').then((m) => m.RegionsPage),
          },
        ],
      },
      // Zones — base + region + lc-company-zone
      {
        path: 'zones',
        canActivate: [keycloakRoleGuard],
        data: { roles: ZONE_ROLES, clientId: CLIENT_ID },
        children: [
          {
            path: 'create',
            loadComponent: () =>
              import('./zones/pages/zones-edit/zones-edit').then((m) => m.ZonesEdit),
          },
          {
            path: 'edit/:id',
            loadComponent: () =>
              import('./zones/pages/zones-edit/zones-edit').then((m) => m.ZonesEdit),
          },
          {
            path: '',
            loadComponent: () =>
              import('./zones/pages/zones-page/zones-page').then((m) => m.ZonesPage),
          },
        ],
      },
      // Stores — all company roles
      {
        path: 'stores',
        canActivate: [keycloakRoleGuard],
        data: { roles: STORE_ROLES, clientId: CLIENT_ID },
        children: [
          {
            path: 'create',
            loadComponent: () =>
              import('./stores/pages/stores-edit/stores-edit').then((m) => m.StoresEdit),
          },
          {
            path: 'edit/:id',
            loadComponent: () =>
              import('./stores/pages/stores-edit/stores-edit').then((m) => m.StoresEdit),
          },
          {
            path: '',
            loadComponent: () =>
              import('./stores/pages/stores-page/stores-page').then((m) => m.StoresPage),
          },
        ],
      },
    ],
  },
];
