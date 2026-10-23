import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { CLIENT_ID, LC_ADMIN } from '@core/security/roles';
import { Home } from '@features/home/home';

export const routes: Routes = [
  {
    path: '',
    component: Home,
    title: 'Home',
  },

  {
    path: 'companies',
    loadChildren: () => import('@features/companies/companies.routes').then((m) => m.companyRoutes),
  },
  {
    path: 'products',
    loadChildren: () => import('@features/products/products.routes').then((m) => m.productRoutes),
  },
  {
    path: 'purchases',
    loadChildren: () =>
      import('@features/purchases/purchases.routes').then((m) => m.purchasesRoutes),
  },
  {
    path: 'sales',
    loadChildren: () => import('@features/sales/sales.routes').then((m) => m.salesRoutes),
  },
  {
    path: 'scheduling',
    loadChildren: () =>
      import('@features/scheduling/scheduling.routes').then((m) => m.schedulingRoutes),
  },
  {
    path: 'hr',
    loadChildren: () => import('@features/hr/hr.routes').then((m) => m.hrRoutes),
  },
  {
    path: 'users-admin',
    loadChildren: () =>
      import('@features/users-admin/users-admin.routes').then((m) => m.usersAdminRoutes),
    canActivate: [keycloakRoleGuard],
    data: { roles: ['lc-admin'], clientId: 'life-control-client' },
  },
  {
    // The audit trail's read surface, admin-only like the endpoint itself
    // (`GET /api/activity-logs` is `hasRole('lc-admin')`). The gate is here, on the
    // parent, in the **client-role** shape: `keycloakRoleGuard` resolves `roles`
    // from `resource_access[clientId]`, so a direct URL is refused for every other
    // role. The realm-role `{ role: 'admin' }` form would refuse every real
    // administrator instead.
    path: 'reports',
    loadChildren: () => import('@features/reports/reports.routes').then((m) => m.reportsRoutes),
    canActivate: [keycloakRoleGuard],
    data: { roles: [LC_ADMIN], clientId: CLIENT_ID },
  },
  {
    path: 'profile',
    loadComponent: () =>
      import('@features/user/profile/user-profile.component').then((m) => m.UserProfileComponent),
    canActivate: [keycloakRoleGuard],
    title: 'User Profile',
  },
  {
    path: 'unauthorized',
    loadComponent: () => import('@shared/ui/unauthorized').then((m) => m.Unauthorized),
  },
  {
    path: '**',
    loadComponent: () => import('@shared/ui/not-found').then((m) => m.NotFound),
  },
];
