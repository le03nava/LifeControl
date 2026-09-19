import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { unsavedChangesGuard } from '@core/guards/unsaved-changes.guard';
import { STORE_WRITE_ROLES } from '@core/security/roles';

const BASE_ROLES = ['lc-admin', 'lc-company', 'lc-company-country'];
const COMPANY_CRUD_ROLES = ['lc-admin', 'lc-company'];
const REGION_ROLES = [...BASE_ROLES, 'lc-company-region'];
const ZONE_ROLES = [...REGION_ROLES, 'lc-company-zone'];
const STORE_ROLES = [...STORE_WRITE_ROLES, 'lc-company-store-read'];

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
      // Companies CRUD — lc-company-country excluded
      {
        path: '',
        canActivate: [keycloakRoleGuard],
        data: { roles: COMPANY_CRUD_ROLES, clientId: CLIENT_ID },
        children: [
          {
            path: 'list',
            loadComponent: () =>
              import('./companies/pages/company-list/company-list').then((m) => m.CompanyList),
          },
          {
            path: 'edit/:id',
            loadComponent: () =>
              import('./companies/pages/company-edit/company-edit').then((m) => m.CompanyEdit),
          },
          {
            path: 'create',
            loadComponent: () =>
              import('./companies/pages/company-edit/company-edit').then((m) => m.CompanyEdit),
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
              import('./countries/pages/countries-edit/countries-edit').then(
                (m) => m.CountriesEdit,
              ),
          },
          {
            path: 'edit/:id',
            loadComponent: () =>
              import('./countries/pages/countries-edit/countries-edit').then(
                (m) => m.CountriesEdit,
              ),
          },
          {
            path: '',
            loadComponent: () =>
              import('./countries/pages/countries-page/countries-page').then(
                (m) => m.CountriesPage,
              ),
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
            canActivate: [keycloakRoleGuard],
            data: { roles: STORE_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./stores/pages/stores-edit/stores-edit').then((m) => m.StoresEdit),
          },
          {
            path: 'edit/:id',
            canActivate: [keycloakRoleGuard],
            data: { roles: STORE_WRITE_ROLES, clientId: CLIENT_ID },
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
      // Store areas — all company roles
      {
        path: 'store-areas',
        canActivate: [keycloakRoleGuard],
        data: { roles: STORE_ROLES, clientId: CLIENT_ID },
        children: [
          {
            path: 'create',
            canActivate: [keycloakRoleGuard],
            canDeactivate: [unsavedChangesGuard],
            data: { roles: STORE_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./stores/pages/store-areas-edit/store-areas-edit').then(
                (m) => m.StoreAreasEdit,
              ),
          },
          {
            path: 'edit/:id',
            canActivate: [keycloakRoleGuard],
            canDeactivate: [unsavedChangesGuard],
            data: { roles: STORE_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./stores/pages/store-areas-edit/store-areas-edit').then(
                (m) => m.StoreAreasEdit,
              ),
          },
          {
            path: '',
            loadComponent: () =>
              import('./stores/pages/store-areas-page/store-areas-page').then(
                (m) => m.StoreAreasPage,
              ),
          },
        ],
      },
      // Store zones — all company roles
      {
        path: 'store-zones',
        canActivate: [keycloakRoleGuard],
        data: { roles: STORE_ROLES, clientId: CLIENT_ID },
        children: [
          {
            path: 'create',
            canActivate: [keycloakRoleGuard],
            canDeactivate: [unsavedChangesGuard],
            data: { roles: STORE_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./stores/pages/store-zones-edit/store-zones-edit').then(
                (m) => m.StoreZonesEdit,
              ),
          },
          {
            path: 'edit/:id',
            canActivate: [keycloakRoleGuard],
            canDeactivate: [unsavedChangesGuard],
            data: { roles: STORE_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./stores/pages/store-zones-edit/store-zones-edit').then(
                (m) => m.StoreZonesEdit,
              ),
          },
          {
            path: '',
            loadComponent: () =>
              import('./stores/pages/store-zones-page/store-zones-page').then(
                (m) => m.StoreZonesPage,
              ),
          },
        ],
      },
      // Store locations — all company roles
      {
        path: 'store-locations',
        canActivate: [keycloakRoleGuard],
        data: { roles: STORE_ROLES, clientId: CLIENT_ID },
        children: [
          {
            path: 'create',
            canActivate: [keycloakRoleGuard],
            canDeactivate: [unsavedChangesGuard],
            data: { roles: STORE_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./stores/pages/store-locations-edit/store-locations-edit').then(
                (m) => m.StoreLocationsEdit,
              ),
          },
          {
            path: 'edit/:id',
            canActivate: [keycloakRoleGuard],
            canDeactivate: [unsavedChangesGuard],
            data: { roles: STORE_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./stores/pages/store-locations-edit/store-locations-edit').then(
                (m) => m.StoreLocationsEdit,
              ),
          },
          {
            path: '',
            loadComponent: () =>
              import('./stores/pages/store-locations-page/store-locations-page').then(
                (m) => m.StoreLocationsPage,
              ),
          },
        ],
      },
    ],
  },
];
