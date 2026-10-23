import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { routes } from './app.routes';

describe('app.routes', () => {
  it('should not contain expressions route after deletion', () => {
    const expressionsRoute = routes.find((r) => r.path === 'expressions');
    expect(expressionsRoute).toBeUndefined();
  });

  it('should still contain the home route', () => {
    const homeRoute = routes.find((r) => r.path === '');
    expect(homeRoute).toBeDefined();
  });

  it('should NOT contain a login route (login is handled by Keycloak directly)', () => {
    const loginRoute = routes.find((r) => r.path === 'login');
    expect(loginRoute).toBeUndefined();
  });

  it('should still contain the companies route', () => {
    const companiesRoute = routes.find((r) => r.path === 'companies');
    expect(companiesRoute).toBeDefined();
  });

  it('should have companies route with loadChildren for lazy-loaded children', () => {
    const companiesRoute = routes.find((r) => r.path === 'companies');
    expect(companiesRoute).toBeDefined();
    // Companies uses loadChildren (lazy-loaded feature routes)
    expect(companiesRoute?.loadChildren).toBeDefined();
  });

  it('should still contain the users-admin route', () => {
    const usersAdminRoute = routes.find((r) => r.path === 'users-admin');
    expect(usersAdminRoute).toBeDefined();
  });

  it('should contain the profile route with lazy loading and auth guard', () => {
    const profileRoute = routes.find((r) => r.path === 'profile');
    expect(profileRoute).toBeDefined();
    expect(profileRoute?.loadComponent).toBeDefined();
    expect(profileRoute?.canActivate).toBeDefined();
    expect(profileRoute?.canActivate?.length).toBe(1);
  });

  it('should still contain the wildcard route', () => {
    const wildcardRoute = routes.find((r) => r.path === '**');
    expect(wildcardRoute).toBeDefined();
  });

  it('should contain the sales route with lazy loading', () => {
    const salesRoute = routes.find((r) => r.path === 'sales');
    expect(salesRoute).toBeDefined();
    expect(salesRoute?.loadChildren).toBeDefined();
  });

  it('should contain the hr route with loadChildren for the lazy-loaded feature', () => {
    const hrRoute = routes.find((r) => r.path === 'hr');
    expect(hrRoute).toBeDefined();
    expect(hrRoute?.loadChildren).toBeDefined();
  });

  it('should gate the reports route on lc-admin with the lazy feature behind it', () => {
    const reportsRoute = routes.find((r) => r.path === 'reports');

    expect(reportsRoute).toBeDefined();
    expect(reportsRoute?.loadChildren).toBeDefined();
    // The guard is the one that refuses a direct URL, and its data is the client-role
    // shape the backend's `hasRole('lc-admin')` mirrors; the realm-role `role: 'admin'`
    // form would refuse every real administrator instead of every non-administrator.
    expect(reportsRoute?.canActivate).toEqual([keycloakRoleGuard]);
    expect(reportsRoute?.data).toEqual({
      roles: ['lc-admin'],
      clientId: 'life-control-client',
    });
  });
});
