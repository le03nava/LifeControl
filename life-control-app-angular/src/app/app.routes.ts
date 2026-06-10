import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
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
    loadChildren: () => import('@features/purchases/purchases.routes').then((m) => m.purchasesRoutes),
  },
  {
    path: 'sales',
    loadChildren: () => import('@features/sales/sales.routes').then((m) => m.salesRoutes),
  },
  {
    path: 'users-admin',
    loadChildren: () => import('@features/users-admin/users-admin.routes').then((m) => m.usersAdminRoutes),
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
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
