import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { CLIENT_ID, DEPARTMENT_WRITE_ROLES } from '@core/security/roles';

/**
 * Feature routes for `/hr`.
 *
 * The parent carries the guard and **no `roles`**: `keycloakRoleGuard` returns
 * `true` as soon as the required-role list is empty, **after** its
 * authentication check, which is exactly the "any authenticated caller"
 * contract `DepartmentController` reads declare (`isAuthenticated()`). The read
 * children inherit it and carry no `roles` of their own.
 *
 * The write children re-declare both the guard and their own `data`, because
 * `keycloakRoleGuard` only reads `route.data` when it is itself listed in
 * `canActivate`; a `data` block without its own guard would be inert.
 *
 * Only the departments children exist in this slice: `positions` (`9-2`) and the
 * employee registry (`9-3`) arrive with later work, and a route with no menu
 * destination is deliberately absent rather than stubbed.
 */
export const hrRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    children: [
      {
        path: 'departments',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./pages/department-list/department-list').then((m) => m.DepartmentList),
          },
          {
            path: 'create',
            canActivate: [keycloakRoleGuard],
            data: { roles: DEPARTMENT_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./pages/department-edit/department-edit').then((m) => m.DepartmentEdit),
          },
          {
            path: 'edit/:id',
            canActivate: [keycloakRoleGuard],
            data: { roles: DEPARTMENT_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./pages/department-edit/department-edit').then((m) => m.DepartmentEdit),
          },
        ],
      },
    ],
  },
];
