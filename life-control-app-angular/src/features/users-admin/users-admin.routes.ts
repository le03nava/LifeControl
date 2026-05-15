import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';

export const usersAdminRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./pages/admin-dashboard/admin-dashboard').then((m) => m.AdminDashboard),
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
  },
  {
    path: 'roles',
    loadComponent: () =>
      import('./pages/role-list/role-list').then((m) => m.RoleList),
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
  },
  {
    path: 'roles/create',
    loadComponent: () =>
      import('./pages/role-form/role-form').then((m) => m.RoleForm),
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
  },
  {
    path: 'roles/edit/:name',
    loadComponent: () =>
      import('./pages/role-form/role-form').then((m) => m.RoleForm),
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
  },
  {
    path: 'users',
    loadComponent: () =>
      import('./pages/user-list/user-list').then((m) => m.UserList),
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
  },
  {
    path: 'users/:id',
    loadComponent: () =>
      import('./pages/user-detail/user-detail').then((m) => m.UserDetail),
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
  },
];
