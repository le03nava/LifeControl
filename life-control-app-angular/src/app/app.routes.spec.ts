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

  it('should still contain the login route', () => {
    const loginRoute = routes.find((r) => r.path === 'login');
    expect(loginRoute).toBeDefined();
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

  it('should still contain the wildcard route', () => {
    const wildcardRoute = routes.find((r) => r.path === '**');
    expect(wildcardRoute).toBeDefined();
  });
});
