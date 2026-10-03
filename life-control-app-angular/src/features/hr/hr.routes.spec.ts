/// <reference types="vitest/globals" />
import { Route, Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { CLIENT_ID, DEPARTMENT_WRITE_ROLES, EMPLOYEE_WRITE_ROLES } from '@core/security/roles';
import { hrRoutes } from './hr.routes';
import { EmployeeEdit } from './pages/employee-edit/employee-edit';
import { EmployeeList } from './pages/employee-list/employee-list';

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

    it('should register the list, create and edit children in that order', () => {
      expect(employeesBranch().children?.map((route) => route.path)).toEqual([
        '',
        'create',
        'edit/:id',
      ]);
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
