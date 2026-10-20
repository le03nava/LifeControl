import type { Route } from '@angular/router';
import { companyRoutes } from './companies.routes';

/**
 * Pins S3 of `odd/tasks/hierarchy-read-roles.md`.
 *
 * `lc-company-read` was made grantable so a read-only position can reach the
 * company, and the decision that came with it is that the role reaches the
 * **API** and never the **screen**: the two `GET /api/companies` mappings admit
 * it, and the Companies administration screen stays closed. `header.spec.ts`
 * pins the menu half; this file pins the route half, so re-adding the role to
 * any `data.roles` of this feature fails here instead of silently handing a
 * read-only position the administration routes.
 */
function walk(routes: Route[]): Route[] {
  return routes.flatMap((route) => [route, ...walk(route.children ?? [])]);
}

/** The role gate declared on a route, or an empty list when it declares none. */
function rolesOf(route: Route): string[] {
  return (route.data?.['roles'] as string[] | undefined) ?? [];
}

describe('companyRoutes', () => {
  const everyRoute = walk(companyRoutes);

  it('should not open any Companies route to lc-company-read', () => {
    const offenders = everyRoute
      .filter((route) => rolesOf(route).includes('lc-company-read'))
      .map((route) => route.path);

    expect(offenders).toEqual([]);
  });

  it('should keep the Companies CRUD subtree on the write roles only', () => {
    // The gate lives on the wrapper that owns the three CRUD children
    // (`companies.routes.ts:31-32`); the children themselves declare no
    // `data.roles` of their own, so this walks up to the wrapper instead of
    // matching every route literally named `create` in the whole feature.
    const crudWrapper = everyRoute.find((route) =>
      route.children?.some((child) => child.path === 'list'),
    );

    expect(crudWrapper).toBeDefined();
    expect(rolesOf(crudWrapper!)).toEqual(['lc-admin', 'lc-company']);
  });
});
