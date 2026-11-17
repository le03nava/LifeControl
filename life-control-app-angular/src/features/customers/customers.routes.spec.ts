import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { CLIENT_ID, LC_ADMIN, LC_SALES } from '@core/security/roles';
import { CUSTOMER_ROLES, customerRoutes } from './customers.routes';

/**
 * `S3` of `odd/tasks/customers-screen.md` says the screen is hidden in the header **and** gated in the
 * route, and the header has its own spec (`header.spec.ts`). This one pins the other half.
 *
 * It asserts the declaration rather than driving the guard, because what can silently regress is a
 * `canActivate` or a `data.roles` that a later edit drops while the screen keeps compiling and the menu
 * keeps showing the entry — the state where a user sees the link and a direct URL still answers. The
 * guard's own behaviour, and which roles it admits, belongs to its own suite.
 */
describe('customerRoutes', () => {
  const [parent] = customerRoutes;
  const children = parent.children ?? [];
  const childByPath = (path: string) => children.find((route) => route.path === path);

  it('gates the parent with the two roles the API states', () => {
    expect(parent.canActivate).toContain(keycloakRoleGuard);
    expect(parent.data?.['roles']).toEqual(CUSTOMER_ROLES);
    expect(parent.data?.['roles']).toEqual([LC_ADMIN, LC_SALES]);
  });

  it('gates the list child as well, so a direct URL is not a way around the menu', () => {
    const listChild = childByPath('');

    expect(listChild).toBeDefined();
    expect(listChild?.canActivate).toContain(keycloakRoleGuard);
    expect(listChild?.data?.['roles']).toEqual(CUSTOMER_ROLES);
  });

  it('names the client the guard reads the roles from', () => {
    // The guard resolves `resource_access[clientId].roles`; without this entry the route would gate nothing.
    expect(parent.data?.['clientId']).toBe(CLIENT_ID);
    expect(childByPath('')?.data?.['clientId']).toBe(CLIENT_ID);
  });

  it('serves the list at /customers, not /customers/list', () => {
    const listChild = childByPath('');

    expect(parent.path).toBe('');
    expect(listChild?.loadComponent).toBeDefined();
    // `pathMatch: 'full'` is what keeps the empty path from swallowing the sibling routes below it.
    expect(listChild?.pathMatch).toBe('full');
  });

  // `L2` of `odd/tasks/customers-fiscal-form.md`: this assertion used to say the opposite, and it is
  // **inverted, not deleted** — its being false is the proof this unit did its job.
  it('declares the create and edit routes this unit delivers, beside the list', () => {
    const paths = children.map((route) => route.path);

    expect(paths).toEqual(expect.arrayContaining(['', 'create', 'edit/:id']));
    expect(childByPath('create')?.loadComponent).toBeDefined();
    expect(childByPath('edit/:id')?.loadComponent).toBeDefined();
  });

  it('covers the two new screens with the same gate the list carries', () => {
    for (const path of ['create', 'edit/:id']) {
      const route = childByPath(path);

      // They are children of the gated parent, and they state the pair themselves: a screen that answers a
      // direct URL is a screen the menu's visibility cannot protect.
      expect(route?.canActivate).toContain(keycloakRoleGuard);
      expect(route?.data?.['roles']).toEqual(CUSTOMER_ROLES);
      expect(route?.data?.['clientId']).toBe(CLIENT_ID);
    }
  });
});
