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
 * Both catalogs are registered here: `departments` with its read child and its
 * own write children, and `employees` with its read child only. The employees
 * slice registers **no** write child in this commit: its `create` and `edit/:id`
 * children arrive with `EmployeeEdit` (`W3b-3`), gated exactly like the
 * department write children, and the list page's "Nuevo empleado" / "Editar"
 * links point at routes this commit deliberately does not register yet, instead
 * of stubbing a page or a redirect. The `positions` catalog (`9-2`) still arrives
 * with its own later slice.
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
      {
        path: 'employees',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./pages/employee-list/employee-list').then((m) => m.EmployeeList),
          },
        ],
      },
    ],
  },
];
