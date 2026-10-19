import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import {
  CLIENT_ID,
  DEPARTMENT_WRITE_ROLES,
  EMPLOYEE_WRITE_ROLES,
  HR_WRITE_ROLES,
  POSITION_WRITE_ROLES,
  SENIORITY_LEVEL_WRITE_ROLES,
} from '@core/security/roles';

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
 * The **first** child is the landing page of `/hr` itself, the destination of the
 * `Recursos Humanos` header entry: it lists the catalogs as cards, the way
 * `/purchases` lists its own areas. It re-declares the guard and the HR write
 * union, so the page is visible exactly where the header shows the entry, while
 * the read children below keep the parent's authenticated-only contract.
 *
 * Both catalogs are registered here: `departments` with its read child and its
 * own write children, and `employees` with its read child and its own write
 * children (`create` and `edit/:id`, which load `EmployeeEdit`). The employees
 * write children are gated exactly like their department siblings, and the list
 * page's "Nuevo empleado" / "Editar" links now point at registered routes. The
 * `positions` catalog arrives with its own slices: this one registers its read
 * child and the `create` / `edit/:id` children that load `PositionEdit`, so the
 * list's write controls all have a destination behind them.
 *
 * The employees **detail** child (`:id`, which loads `EmployeeDetail`) is a read:
 * it carries **no `roles`**, exactly like the list child, because the employee
 * read endpoints are `isAuthenticated()`. It is declared **after** the literal
 * `create` and `edit/:id` children on purpose — a path matcher prefers the first
 * matching route, so a `:id` declared before them would swallow `/create` and
 * `/edit/…`.
 */
export const hrRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    children: [
      {
        path: '',
        canActivate: [keycloakRoleGuard],
        data: { roles: HR_WRITE_ROLES, clientId: CLIENT_ID },
        loadComponent: () => import('./pages/hr-admin/hr-admin').then((m) => m.HrAdmin),
      },
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
        path: 'positions',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./pages/position-list/position-list').then((m) => m.PositionList),
          },
          {
            path: 'create',
            canActivate: [keycloakRoleGuard],
            data: { roles: POSITION_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./pages/position-edit/position-edit').then((m) => m.PositionEdit),
          },
          {
            path: 'edit/:id',
            canActivate: [keycloakRoleGuard],
            data: { roles: POSITION_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./pages/position-edit/position-edit').then((m) => m.PositionEdit),
          },
          {
            // The position's policy screen (W3, T9). It is a **read route** like
            // its siblings: the API's GET is `isAuthenticated()` (T22), so it
            // carries no `roles` here and the screen itself gates its save on
            // `canWrite`. Its entry point is the per-row `Configuración` action
            // of the list, which D16 ties to this destination.
            path: ':id/settings',
            loadComponent: () =>
              import('./pages/position-settings/position-settings').then(
                (m) => m.PositionSettings,
              ),
          },
        ],
      },
      {
        // The global seniority-level catalog is a **secondary screen reached
        // from Puestos** (D5), not a fourth HR child: this repository's global
        // catalogs carry no menu entry at all. Both its read child and its own
        // `create` / `edit/:id` children that load `SeniorityLevelEdit` are
        // registered here, so the list's write controls have a destination.
        path: 'seniority-levels',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./pages/seniority-level-list/seniority-level-list').then(
                (m) => m.SeniorityLevelList,
              ),
          },
          {
            path: 'create',
            canActivate: [keycloakRoleGuard],
            data: { roles: SENIORITY_LEVEL_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./pages/seniority-level-edit/seniority-level-edit').then(
                (m) => m.SeniorityLevelEdit,
              ),
          },
          {
            path: 'edit/:id',
            canActivate: [keycloakRoleGuard],
            data: { roles: SENIORITY_LEVEL_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./pages/seniority-level-edit/seniority-level-edit').then(
                (m) => m.SeniorityLevelEdit,
              ),
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
          {
            path: 'create',
            canActivate: [keycloakRoleGuard],
            data: { roles: EMPLOYEE_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./pages/employee-edit/employee-edit').then((m) => m.EmployeeEdit),
          },
          {
            path: 'edit/:id',
            canActivate: [keycloakRoleGuard],
            data: { roles: EMPLOYEE_WRITE_ROLES, clientId: CLIENT_ID },
            loadComponent: () =>
              import('./pages/employee-edit/employee-edit').then((m) => m.EmployeeEdit),
          },
          {
            // Declared after every literal sibling: `create` and `edit/:id` must
            // never be shadowed by this parameterized read child.
            path: ':id',
            loadComponent: () =>
              import('./pages/employee-detail/employee-detail').then((m) => m.EmployeeDetail),
          },
        ],
      },
    ],
  },
];
