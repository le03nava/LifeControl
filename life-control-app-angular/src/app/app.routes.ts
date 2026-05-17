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
    path: 'login',
    loadComponent: () => import('@features/auth/login').then((m) => m.Login),
  },

  {
    path: 'products',
    loadChildren: () => import('@features/products/products.routes').then((m) => m.productRoutes),
  },
  {
    path: 'companies',
    loadChildren: () => import('@features/companies/companies.routes').then((m) => m.companyRoutes),
  },
  {
    path: 'users-admin',
    loadChildren: () => import('@features/users-admin/users-admin.routes').then((m) => m.usersAdminRoutes),
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
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
