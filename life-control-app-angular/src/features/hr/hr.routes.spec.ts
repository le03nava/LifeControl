/// <reference types="vitest/globals" />
import { Route, Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import {
  CLIENT_ID,
  DEPARTMENT_WRITE_ROLES,
  EMPLOYEE_WRITE_ROLES,
  POSITION_WRITE_ROLES,
  SENIORITY_LEVEL_WRITE_ROLES,
} from '@core/security/roles';
import { hrRoutes } from './hr.routes';
import { EmployeeDetail } from './pages/employee-detail/employee-detail';
import { EmployeeEdit } from './pages/employee-edit/employee-edit';
import { EmployeeList } from './pages/employee-list/employee-list';
import { PositionSettings } from './pages/position-settings/position-settings';

/**
 * Pins the guard split T22 establishes by walking the **real** `hrRoutes` tree:
 * the write children re-declare `keycloakRoleGuard` with the department write
 * roles, while the parent and the read child carry no `roles`, so the guard
 * admits any authenticated caller (its `requiredRoles.length === 0` branch).
 */
describe('hr.routes', () => {
  function routeAt(routes: Routes | undefined, path: string): Route {
    const found = routes?.find((route) => route.path === path);
    if (!found) {
      throw new Error(`Expected a route with path "${path}" in the hr route tree`);
    }
    return found;
  }

  function hrBranch(): {
    parent: Route;
    departments: Route;
    read: Route;
    create: Route;
    edit: Route;
  } {
    const parent = routeAt(hrRoutes, '');
    const departments = routeAt(parent.children, 'departments');
    return {
      parent,
      departments,
      read: routeAt(departments.children, ''),
      create: routeAt(departments.children, 'create'),
      edit: routeAt(departments.children, 'edit/:id'),
    };
  }

  it('should register the positions read child with no roles and a lazy component', () => {
    const parent = routeAt(hrRoutes, '');
    const positions = routeAt(parent.children, 'positions');
    const read = routeAt(positions.children, '');

    // Reads are `isAuthenticated()` on the API, which the guard expresses as an
    // absent `roles` list.
    expect(positions.data?.['roles']).toBeUndefined();
    expect(read.data?.['roles']).toBeUndefined();
    expect(typeof read.loadComponent).toBe('function');
  });

  it('should carry the position write roles and clientId on the positions write children', () => {
    const parent = routeAt(hrRoutes, '');
    const positions = routeAt(parent.children, 'positions');

    for (const path of ['create', 'edit/:id']) {
      const child = routeAt(positions.children, path);
      // Mirrors PositionController's `hasAnyRole('lc-admin','lc-position')` pair;
      // the department write set is a different catalog and must not appear here.
      expect(child.data?.['roles']).toEqual(POSITION_WRITE_ROLES);
      expect(child.data?.['roles']).not.toEqual(DEPARTMENT_WRITE_ROLES);
      expect(child.data?.['clientId']).toBe(CLIENT_ID);
      expect(child.canActivate).toContain(keycloakRoleGuard);
      expect(typeof child.loadComponent).toBe('function');
    }
  });

  it('should register the seniority-levels read child with no roles and a lazy component', () => {
    const parent = routeAt(hrRoutes, '');
    const levels = routeAt(parent.children, 'seniority-levels');
    const read = routeAt(levels.children, '');

    // Global catalog, reached from Puestos and deliberately absent from the
    // menu (D5); its reads are `isAuthenticated()` on the API.
    expect(levels.data?.['roles']).toBeUndefined();
    expect(read.data?.['roles']).toBeUndefined();
    expect(typeof read.loadComponent).toBe('function');
  });

  it('should register the position settings child as a read route with no roles and a lazy component', () => {
    const parent = routeAt(hrRoutes, '');
    const positions = routeAt(parent.children, 'positions');
    const settings = routeAt(positions.children, ':id/settings');

    // The settings screen is a **read route** (T22): the API's GET is
    // `isAuthenticated()`, so the guard must admit any authenticated caller, and
    // the screen is what gates its own save on `canWrite`.
    expect(settings.data?.['roles']).toBeUndefined();
    expect(settings.canActivate).toBeUndefined();
    expect(typeof settings.loadComponent).toBe('function');
  });

  it('should resolve the lazy import of the position settings child to PositionSettings', async () => {
    const parent = routeAt(hrRoutes, '');
    const positions = routeAt(parent.children, 'positions');
    const settings = routeAt(positions.children, ':id/settings');

    const loaded = await settings.loadComponent!();
    expect(loaded).toBe(PositionSettings);
  });

  it('should declare the position settings child after every literal segment, so none is shadowed', () => {
    const parent = routeAt(hrRoutes, '');
    const positions = routeAt(parent.children, 'positions');

    // `edit/:id` is literal-first and `:id/settings` is two segments long: the
    // order is what keeps `/positions/edit/9` from being read as an id.
    expect(positions.children?.map((route) => route.path)).toEqual([
      '',
      'create',
      'edit/:id',
      ':id/settings',
    ]);
  });

  it('should carry the seniority-level write roles and clientId on its write children', () => {
    const parent = routeAt(hrRoutes, '');
    const levels = routeAt(parent.children, 'seniority-levels');

    for (const path of ['create', 'edit/:id']) {
      const child = routeAt(levels.children, path);
      // Mirrors SeniorityLevelController's `hasAnyRole('lc-admin','lc-seniority-level')`.
      expect(child.data?.['roles']).toEqual(SENIORITY_LEVEL_WRITE_ROLES);
      expect(child.data?.['clientId']).toBe(CLIENT_ID);
      expect(child.canActivate).toContain(keycloakRoleGuard);
      expect(typeof child.loadComponent).toBe('function');
    }
  });

  it('should carry the department write roles and clientId on the create and edit children', () => {
    const { create, edit } = hrBranch();

    for (const writeChild of [create, edit]) {
      expect(writeChild.data?.['roles']).toEqual(DEPARTMENT_WRITE_ROLES);
      expect(writeChild.data?.['clientId']).toBe(CLIENT_ID);
    }
  });

  it('should require no roles on the parent or the read child', () => {
    const { parent, departments, read } = hrBranch();

    for (const readOnlyRoute of [parent, departments, read]) {
      expect(readOnlyRoute.data?.['roles']).toBeUndefined();
    }
  });

  it('should guard both write children with keycloakRoleGuard', () => {
    const { create, edit } = hrBranch();

    for (const writeChild of [create, edit]) {
      expect(writeChild.canActivate).toContain(keycloakRoleGuard);
    }
  });

  describe('employees', () => {
    /** The `employees` sibling of `departments`, looked up under the same parent. */
    function employeesBranch(): Route {
      return routeAt(routeAt(hrRoutes, '').children, 'employees');
    }

    it('should be a sibling of the departments route under the guarded hr parent', () => {
      const parent = routeAt(hrRoutes, '');
      expect(parent.children?.map((route) => route.path)).toContain('employees');
      expect(parent.children?.map((route) => route.path)).toContain('departments');
    });

    it('should require no roles on the employees list child, so any authenticated caller reads it', () => {
      const read = routeAt(employeesBranch().children, '');
      expect(read.data?.['roles']).toBeUndefined();
    });

    it('should resolve the lazy import of the employees list child to EmployeeList', async () => {
      const read = routeAt(employeesBranch().children, '');
      expect(typeof read.loadComponent).toBe('function');

      const loaded = await read.loadComponent!();
      expect(loaded).toBe(EmployeeList);
    });

    it('should register the list, create, edit and detail children in that order', () => {
      expect(employeesBranch().children?.map((route) => route.path)).toEqual([
        '',
        'create',
        'edit/:id',
        ':id',
      ]);
    });

    it('should declare the detail child after every literal segment, so none is shadowed', () => {
      const paths = employeesBranch().children?.map((route) => route.path) ?? [];

      expect(paths.indexOf(':id')).toBeGreaterThan(paths.indexOf('create'));
      expect(paths.indexOf(':id')).toBeGreaterThan(paths.indexOf('edit/:id'));
    });

    it('should require no roles on the detail child, so any authenticated caller reads it', () => {
      const detail = routeAt(employeesBranch().children, ':id');
      expect(detail.data?.['roles']).toBeUndefined();
      expect(detail.canActivate).toBeUndefined();
    });

    it('should resolve the lazy import of the detail child to EmployeeDetail', async () => {
      const detail = routeAt(employeesBranch().children, ':id');
      expect(typeof detail.loadComponent).toBe('function');

      const loaded = await detail.loadComponent!();
      expect(loaded).toBe(EmployeeDetail);
    });

    it('should carry the employee write roles and clientId on the create and edit children', () => {
      const children = employeesBranch().children;
      for (const path of ['create', 'edit/:id']) {
        const writeChild = routeAt(children, path);
        expect(writeChild.data?.['roles']).toEqual(EMPLOYEE_WRITE_ROLES);
        expect(writeChild.data?.['clientId']).toBe(CLIENT_ID);
      }
    });

    it('should guard both employee write children with keycloakRoleGuard', () => {
      const children = employeesBranch().children;
      for (const path of ['create', 'edit/:id']) {
        expect(routeAt(children, path).canActivate).toContain(keycloakRoleGuard);
      }
    });

    it('should resolve the lazy imports of the employee write children to EmployeeEdit', async () => {
      const children = employeesBranch().children;
      for (const path of ['create', 'edit/:id']) {
        const writeChild = routeAt(children, path);
        expect(typeof writeChild.loadComponent).toBe('function');

        const loaded = await writeChild.loadComponent!();
        expect(loaded).toBe(EmployeeEdit);
      }
    });
  });
});
