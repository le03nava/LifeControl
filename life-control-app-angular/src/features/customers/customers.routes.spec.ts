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
  const [listChild] = parent.children ?? [];

  it('gates the parent with the two roles the API states', () => {
    expect(parent.canActivate).toContain(keycloakRoleGuard);
    expect(parent.data?.['roles']).toEqual(CUSTOMER_ROLES);
    expect(parent.data?.['roles']).toEqual([LC_ADMIN, LC_SALES]);
  });

  it('gates the list child as well, so a direct URL is not a way around the menu', () => {
    expect(listChild).toBeDefined();
    expect(listChild.canActivate).toContain(keycloakRoleGuard);
    expect(listChild.data?.['roles']).toEqual(CUSTOMER_ROLES);
  });

  it('names the client the guard reads the roles from', () => {
    // The guard resolves `resource_access[clientId].roles`; without this the entry would gate nothing.
    expect(parent.data?.['clientId']).toBe(CLIENT_ID);
    expect(listChild.data?.['clientId']).toBe(CLIENT_ID);
  });

  it('serves the list at /customers, not /customers/list', () => {
    expect(parent.path).toBe('');
    expect(listChild.path).toBe('');
    expect(listChild.loadComponent).toBeDefined();
  });

  it('declares no create or edit route in this unit', () => {
    // S5: this unit adds no write path. A route that lands early would point at nothing.
    expect((parent.children ?? []).map((route) => route.path)).toEqual(['']);
  });
});
