import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { CLIENT_ID, LC_ADMIN, TAX_RATE_ROLES } from '@core/security/roles';
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
    // Los clientes: la primera pantalla que tiene el receptor en el producto (`#940`). Es de lectura
    // solamente por ahora; las páginas de alta y edición llegan con el perfil fiscal, en la unidad hermana.
    // El gate del par `lc-admin`/`lc-sales` vive en la ruta hija y en su padre, porque son los dos únicos
    // roles que `CustomerController` autoriza.
    path: 'customers',
    loadChildren: () =>
      import('@features/customers/customers.routes').then((m) => m.customerRoutes),
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
    // `Configuración` is one menu entry and one landing with a card per catalog (`S1`). The gate sits on the
    // parent, so the landing, the tax-rate screen and every card a later slice adds are refused for the same
    // pair without a second declaration that could disagree with this one.
    path: 'configuration',
    loadChildren: () =>
      import('@features/configuration/configuration.routes').then((m) => m.configurationRoutes),
    canActivate: [keycloakRoleGuard],
    data: { roles: TAX_RATE_ROLES, clientId: CLIENT_ID },
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
