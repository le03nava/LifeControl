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
    path: 'dashboard',
    loadComponent: () => import('@features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'expressions',
    loadComponent: () => import('@features/auth/login').then((m) => m.Login),

    canActivate: [keycloakRoleGuard],
    data: { role: 'view-books' },
  },
  {
    path: 'users',
    loadChildren: () =>
      import('@features/user-management/user-management.routes').then((m) => m.userRoutes),
  },
  {
    path: 'profile',
    loadComponent: () => import('@features/profile/profile').then((m) => m.Profile),
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
    path: '**',
    loadComponent: () => import('@shared/ui/not-found').then((m) => m.NotFound),
  },
];
