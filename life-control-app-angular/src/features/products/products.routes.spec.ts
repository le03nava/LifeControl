import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { RedirectFunction, Route, UrlTree } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { unsavedChangesGuard } from '@core/guards/unsaved-changes.guard';
import { LC_SALES } from '@core/security/roles';
import { productRoutes } from './products.routes';

/** The single `/products` parent route. */
const parent = productRoutes[0];

/** Every child declared under `/products`, derived from the config. */
function children(): Route[] {
  return parent.children ?? [];
}

/**
 * A redirect route never activates, so it carries no `canActivate`/`data` to
 * assert. The two derivations below must skip it explicitly, or their per-child
 * guard assertions fail on a route that is not a guarded screen at all (D14).
 */
function isRedirect(route: Route): boolean {
  return route.redirectTo !== undefined;
}

/** The variant screens: the deliberate exception to the admin-only children. */
function variantChildren(): Route[] {
  return children().filter((route) => route.path?.includes('variants') && !isRedirect(route));
}

/** Every child that is not a variant screen and stays admin-only. */
function nonVariantChildren(): Route[] {
  return children().filter((route) => !route.path?.includes('variants') && !isRedirect(route));
}

/** The `redirectData` slice the router hands a `RedirectFunction`; only the two used fields matter here. */
function redirectData(
  params: Record<string, string>,
  queryParams: Record<string, string> = {},
): Parameters<RedirectFunction>[0] {
  return { params, queryParams } as Parameters<RedirectFunction>[0];
}

describe('productRoutes', () => {
  beforeEach(() => {
    // `redirectTo` functions run in an injection context and this one injects the
    // `Router` to build a `UrlTree`; the real router is provided so the produced
    // value is Angular's own URL, not a stub's.
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });
  it('should gate the parent with the variant roles union and the role guard', () => {
    // Literal wire strings, not the imported constants: this is the assertion
    // that pins the role names the guard actually matches against.
    expect(parent.canActivate).toEqual([keycloakRoleGuard]);
    expect(parent.data).toEqual({
      roles: ['lc-admin', 'lc-sales'],
      clientId: 'life-control-client',
    });
  });

  it('should gate every variant child with the variant roles', () => {
    const routes = variantChildren();

    // Derived from the config, not hardcoded: the variant list must exist and
    // carry its own gate, because the parent route only decides who reaches the
    // module, not who reaches this child.
    expect(routes.length).toBeGreaterThan(0);

    for (const route of routes) {
      // `toEqual([guard])`, never `toContain(guard)`: `expect(undefined)
      // .toContain(fn)` passes vacuously, and a `data` block without its own
      // `canActivate` is inert.
      expect(route.canActivate).toEqual([keycloakRoleGuard]);
      expect(route.data).toEqual({
        roles: ['lc-admin', 'lc-sales'],
        clientId: 'life-control-client',
      });
    }
  });

  it('should gate the store-scoped variant search as a variant screen', () => {
    // Pinned by path because this child is the discoverable entry point the sales role
    // was missing; a rename that leaves the gate intact would still break that.
    const search = children().find((route) => route.path === 'variants');

    expect(search).toBeDefined();
    expect(search?.canActivate).toEqual([keycloakRoleGuard]);
    expect(search?.data).toEqual({
      roles: ['lc-admin', 'lc-sales'],
      clientId: 'life-control-client',
    });
  });

  it('should re-gate every non-variant child as admin-only', () => {
    const routes = nonVariantChildren();

    // Derived from the config, not hardcoded: a child added without its own
    // guard must fail this spec instead of silently inheriting the parent.
    expect(routes.length).toBeGreaterThan(0);

    for (const route of routes) {
      // The relaxed parent admits a sales principal, so each non-variant child
      // must carry its own admin gate; a `data` block without `canActivate` is
      // inert. Such a child would be reachable by anyone the parent gate
      // admits — which is exactly the sales leak this spec exists to prevent.
      expect(route.canActivate).toEqual([keycloakRoleGuard]);
      expect(route.data).toEqual({ roles: ['lc-admin'], clientId: 'life-control-client' });
    }
  });

  it('should guard the product create and edit routes against unsaved changes', () => {
    const create = children().find((route) => route.path === 'create');
    const edit = children().find((route) => route.path === 'edit/:id');

    expect(create).toBeDefined();
    expect(edit).toBeDefined();
    // `toEqual([guard])`, never `toContain(guard)`: `expect(undefined)
    // .toContain(fn)` passes vacuously when the property is absent.
    expect(create?.canDeactivate).toEqual([unsavedChangesGuard]);
    expect(edit?.canDeactivate).toEqual([unsavedChangesGuard]);
  });

  it('should leave the unrelated product routes unguarded', () => {
    const list = children().find((route) => route.path === 'list');
    const suppliers = children().find((route) => route.path === 'edit/:id/suppliers');

    expect(list).toBeDefined();
    expect(suppliers).toBeDefined();
    expect(list?.canDeactivate).toBeUndefined();
    expect(suppliers?.canDeactivate).toBeUndefined();
  });

  it('should never grant the sales role to a non-variant products child', () => {
    for (const route of nonVariantChildren()) {
      // `toEqual` on the literal rather than `not.toContain`: a missing `data`
      // must fail here instead of passing vacuously.
      expect(route.data?.['roles']).toEqual(['lc-admin']);
      expect(route.data?.['roles']).not.toContain(LC_SALES);
    }
  });

  describe('product-scoped list routes (T9, D13/D14/D17)', () => {
    it('should redirect the product-scoped supplier list into the workspace tab', () => {
      const suppliers = children().find((route) => route.path === 'edit/:id/suppliers');

      expect(suppliers).toBeDefined();
      // A function, not a string: the target is built from the route params.
      expect(typeof suppliers?.redirectTo).toBe('function');
      // A redirect route never activates, so a `loadComponent` here would be dead config.
      expect(suppliers?.loadComponent).toBeUndefined();

      const redirect = suppliers?.redirectTo as RedirectFunction;
      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ id: 'prod-1' })),
      );

      // Assert the produced value, not merely that a function is present.
      expect((produced as UrlTree).toString()).toBe('/products/edit/prod-1?tab=proveedores');
    });

    it('should preserve storeId when redirecting the product-scoped supplier list', () => {
      const suppliers = children().find((route) => route.path === 'edit/:id/suppliers');
      const redirect = suppliers?.redirectTo as RedirectFunction;

      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ id: 'prod-1' }, { storeId: 'store-9' })),
      );

      expect((produced as UrlTree).toString()).toBe(
        '/products/edit/prod-1?tab=proveedores&storeId=store-9',
      );
    });

    it('should carry no inert canActivate/data on the redirect route', () => {
      const suppliers = children().find((route) => route.path === 'edit/:id/suppliers');

      // Deliberate: a redirect never activates, so the guards would never run. The
      // target `edit/:id` keeps its own admin gate, so access is unchanged.
      expect(suppliers?.canActivate).toBeUndefined();
      expect(suppliers?.data).toBeUndefined();
    });

    it('should keep edit/:id/variants resolving through the thin host, never a redirect (D17)', async () => {
      const variants = children().find((route) => route.path === 'edit/:id/variants');

      expect(variants).toBeDefined();
      // Redirecting here would deny `lc-sales` the variant list and break the return
      // paths at product-variant-edit.ts:195,206 and product-variant-stock-search.ts:157.
      expect(variants?.redirectTo).toBeUndefined();
      expect(variants?.canActivate).toEqual([keycloakRoleGuard]);
      expect(variants?.data).toEqual({
        roles: ['lc-admin', 'lc-sales'],
        clientId: 'life-control-client',
      });
      expect(typeof variants?.loadComponent).toBe('function');

      const loaded = await variants!.loadComponent!();
      const component = (loaded as { default?: unknown }).default ?? loaded;
      // Angular's compiler emits a `_`-prefixed runtime class name for decorated
      // components; strip it so the assertion pins the host, not the compiler's alias.
      const name = (component as { name?: string }).name ?? '';
      expect(name.replace(/^_/, '')).toBe('ProductVariantListHost');
    });
  });

  describe('legacy supplier create/edit redirects (D19)', () => {
    const legacySupplierPaths = [
      'edit/:id/suppliers/create',
      'edit/:id/suppliers/edit/:supplierId',
    ];

    it('should redirect edit/:id/suppliers/create into the workspace Proveedores tab', () => {
      const create = children().find((route) => route.path === 'edit/:id/suppliers/create');

      expect(create).toBeDefined();
      expect(typeof create?.redirectTo).toBe('function');
      // A redirect route never activates, so a `loadComponent` here would be dead config.
      expect(create?.loadComponent).toBeUndefined();

      const redirect = create?.redirectTo as RedirectFunction;
      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ id: 'prod-1' })),
      );

      expect((produced as UrlTree).toString()).toBe('/products/edit/prod-1?tab=proveedores');
    });

    it('should redirect edit/:id/suppliers/edit/:supplierId into the workspace Proveedores tab', () => {
      const edit = children().find((route) => route.path === 'edit/:id/suppliers/edit/:supplierId');

      expect(edit).toBeDefined();
      expect(typeof edit?.redirectTo).toBe('function');
      expect(edit?.loadComponent).toBeUndefined();

      const redirect = edit?.redirectTo as RedirectFunction;
      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ id: 'prod-1', supplierId: 'ps-1' })),
      );

      expect((produced as UrlTree).toString()).toBe('/products/edit/prod-1?tab=proveedores');
    });

    it('should preserve storeId on both legacy supplier redirects', () => {
      for (const path of legacySupplierPaths) {
        const route = children().find((candidate) => candidate.path === path);
        expect(route).toBeDefined();
        const redirect = route!.redirectTo as RedirectFunction;

        const produced = TestBed.runInInjectionContext(() =>
          redirect(redirectData({ id: 'prod-1', supplierId: 'ps-1' }, { storeId: 'store-9' })),
        );

        expect((produced as UrlTree).toString()).toBe(
          '/products/edit/prod-1?tab=proveedores&storeId=store-9',
        );
      }
    });

    it('should carry no inert canActivate/data on either legacy redirect route', () => {
      for (const path of legacySupplierPaths) {
        const route = children().find((candidate) => candidate.path === path);
        // Asserted first, or the property assertions below pass vacuously on an
        // undefined lookup.
        expect(route).toBeDefined();

        // Deliberate: a redirect never activates, so a guard would never run and
        // Angular throws `RuntimeError 4014` when `redirectTo` is combined with
        // `canActivate`. `data` is omitted because it would be inert on a route that
        // never activates and would read as a gate. The target `edit/:id` keeps its
        // own admin gate, so effective access is unchanged.
        expect(route!.canActivate).toBeUndefined();
        expect(route!.data).toBeUndefined();
      }
    });
  });
});
