import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { RedirectFunction, Route, UrlTree } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { unsavedChangesGuard } from '@core/guards/unsaved-changes.guard';
import { LC_ADMIN, LC_SALES } from '@core/security/roles';
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

/** A child by its exact path. */
function childAt(path: string): Route | undefined {
  return children().find((route) => route.path === path);
}

/** The runtime class name behind a `loadComponent`, with Angular's `_` alias prefix stripped. */
async function loadName(route: Route): Promise<string> {
  expect(typeof route.loadComponent).toBe('function');
  const loaded = await route.loadComponent!();
  const component = (loaded as { default?: unknown }).default ?? loaded;
  // Angular's compiler emits a `_`-prefixed runtime class name for decorated
  // components; strip it so the assertion pins the page, not the compiler's alias.
  return ((component as { name?: string }).name ?? '').replace(/^_/, '');
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
    const search = childAt('variants');

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
    const create = childAt('c/:companyId/create');
    const edit = childAt('c/:companyId/edit/:id');

    expect(create).toBeDefined();
    expect(edit).toBeDefined();
    // `toEqual([guard])`, never `toContain(guard)`: `expect(undefined)
    // .toContain(fn)` passes vacuously when the property is absent.
    expect(create?.canDeactivate).toEqual([unsavedChangesGuard]);
    expect(edit?.canDeactivate).toEqual([unsavedChangesGuard]);
  });

  it('should guard the variant-list host route against unsaved changes', () => {
    const variants = childAt('c/:companyId/edit/:id/variants');

    expect(variants).toBeDefined();
    // The host is the activated component on this route, and it now forwards the
    // container's inline per-store panel flag; without this registration a dirty
    // panel's stock and prices would be discarded by a stray navigation.
    // `toEqual([guard])`, never `toContain(guard)`: `expect(undefined)
    // .toContain(fn)` passes vacuously when the property is absent.
    expect(variants?.canDeactivate).toEqual([unsavedChangesGuard]);
  });

  it('should leave the unrelated product routes unguarded', () => {
    const list = childAt('c/:companyId/list');
    const suppliers = childAt('c/:companyId/edit/:id/suppliers');

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

  describe('the company segment (T6)', () => {
    it('should render the company picker at /products and the list under the company', async () => {
      // The two rules a reviewer cannot read off the service: `/products` is the picker (a page, not a
      // redirect), and the product list is addressed through the company it belongs to.
      const root = childAt('');
      const list = childAt('c/:companyId/list');

      expect(root).toBeDefined();
      expect(root?.redirectTo).toBeUndefined();
      expect(await loadName(root!)).toBe('ProductCompanyPicker');

      expect(list).toBeDefined();
      expect(list?.redirectTo).toBeUndefined();
      expect(await loadName(list!)).toBe('ProductList');
    });

    it('should render the company hub at c/:companyId', async () => {
      const hub = childAt('c/:companyId');

      expect(hub).toBeDefined();
      expect(await loadName(hub!)).toBe('ProductsAdminComponent');
    });

    it('should keep the deliberate company-free screens outside the segment', () => {
      // Pinned by exact path: each of these was left out of `c/:companyId` on purpose, and moving one
      // inside would either remove a picker the page owns (`categories`), break a store-addressed read
      // (`variants`), or scope a resource that is global by decision (`suppliers`).
      for (const path of [
        'categories',
        'variants',
        'suppliers',
        'suppliers/create',
        'suppliers/edit/:id',
      ]) {
        const route = childAt(path);

        expect(route).toBeDefined();
        expect(route?.redirectTo).toBeUndefined();
      }
    });

    it('should redirect every legacy company-free URL to the picker', () => {
      // Bookmarks and the dashboard cards exist. None of these paths carries a company, so there is
      // nothing to redirect *into*: they land on the screen whose whole job is to ask for one.
      const legacyPaths = [
        'list',
        'create',
        'edit/:id',
        'edit/:id/variants',
        'edit/:id/variants/create',
        'edit/:id/variants/edit/:variantId',
        'edit/:id/suppliers',
        'edit/:id/suppliers/create',
        'edit/:id/suppliers/edit/:supplierId',
      ];

      for (const path of legacyPaths) {
        const route = childAt(path);

        expect(route).toBeDefined();
        // A string target, not a function: there is no company in these params to build a scoped URL
        // from, so the target is fixed.
        expect(route?.redirectTo).toBe('/products');
        // A redirect route never activates, so a `loadComponent` here would be dead config.
        expect(route?.loadComponent).toBeUndefined();
        // Deliberate: a redirect never activates, so a guard would never run and Angular throws
        // `RuntimeError 4014` when `redirectTo` is combined with `canActivate`.
        expect(route?.canActivate).toBeUndefined();
        expect(route?.data).toBeUndefined();
      }
    });
  });

  describe('product-scoped list routes (T9, D13/D14/D17)', () => {
    it('should redirect the product-scoped supplier list into the workspace tab', () => {
      const suppliers = childAt('c/:companyId/edit/:id/suppliers');

      expect(suppliers).toBeDefined();
      // A function, not a string: the target is built from the route params.
      expect(typeof suppliers?.redirectTo).toBe('function');
      // A redirect route never activates, so a `loadComponent` here would be dead config.
      expect(suppliers?.loadComponent).toBeUndefined();

      const redirect = suppliers?.redirectTo as RedirectFunction;
      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ companyId: 'company-1', id: 'prod-1' })),
      );

      // Assert the produced value, not merely that a function is present.
      expect((produced as UrlTree).toString()).toBe(
        '/products/c/company-1/edit/prod-1?tab=proveedores',
      );
    });

    it('should preserve storeId when redirecting the product-scoped supplier list', () => {
      const suppliers = childAt('c/:companyId/edit/:id/suppliers');
      const redirect = suppliers?.redirectTo as RedirectFunction;

      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ companyId: 'company-1', id: 'prod-1' }, { storeId: 'store-9' })),
      );

      expect((produced as UrlTree).toString()).toBe(
        '/products/c/company-1/edit/prod-1?tab=proveedores&storeId=store-9',
      );
    });

    it('should carry no inert canActivate/data on the redirect route', () => {
      const suppliers = childAt('c/:companyId/edit/:id/suppliers');

      // Deliberate: a redirect never activates, so the guards would never run. The
      // target `edit/:id` keeps its own admin gate, so access is unchanged.
      expect(suppliers?.canActivate).toBeUndefined();
      expect(suppliers?.data).toBeUndefined();
    });

    it('should keep c/:companyId/edit/:id/variants resolving through the thin host, never a redirect (D17)', async () => {
      const variants = childAt('c/:companyId/edit/:id/variants');

      expect(variants).toBeDefined();
      // Redirecting here would deny `lc-sales` the variant list, whose only entry
      // point is product-variant-stock-search.ts: the route is `VARIANT_ROLES`
      // while its would-be target `edit/:id` is admin-only.
      expect(variants?.redirectTo).toBeUndefined();
      expect(variants?.canActivate).toEqual([keycloakRoleGuard]);
      expect(variants?.data).toEqual({
        roles: ['lc-admin', 'lc-sales'],
        clientId: 'life-control-client',
      });
      expect(await loadName(variants!)).toBe('ProductVariantListHost');
    });
  });

  describe('legacy supplier create/edit redirects (D19)', () => {
    const legacySupplierPaths = [
      'c/:companyId/edit/:id/suppliers/create',
      'c/:companyId/edit/:id/suppliers/edit/:supplierId',
    ];

    it('should redirect c/:companyId/edit/:id/suppliers/create into the workspace Proveedores tab', () => {
      const create = childAt('c/:companyId/edit/:id/suppliers/create');

      expect(create).toBeDefined();
      expect(typeof create?.redirectTo).toBe('function');
      // A redirect route never activates, so a `loadComponent` here would be dead config.
      expect(create?.loadComponent).toBeUndefined();

      const redirect = create?.redirectTo as RedirectFunction;
      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ companyId: 'company-1', id: 'prod-1' })),
      );

      expect((produced as UrlTree).toString()).toBe(
        '/products/c/company-1/edit/prod-1?tab=proveedores',
      );
    });

    it('should redirect c/:companyId/edit/:id/suppliers/edit/:supplierId into the workspace Proveedores tab', () => {
      const edit = childAt('c/:companyId/edit/:id/suppliers/edit/:supplierId');

      expect(edit).toBeDefined();
      expect(typeof edit?.redirectTo).toBe('function');
      expect(edit?.loadComponent).toBeUndefined();

      const redirect = edit?.redirectTo as RedirectFunction;
      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ companyId: 'company-1', id: 'prod-1', supplierId: 'ps-1' })),
      );

      expect((produced as UrlTree).toString()).toBe(
        '/products/c/company-1/edit/prod-1?tab=proveedores',
      );
    });

    it('should preserve storeId on both legacy supplier redirects', () => {
      for (const path of legacySupplierPaths) {
        const route = childAt(path);
        expect(route).toBeDefined();
        const redirect = route!.redirectTo as RedirectFunction;

        const produced = TestBed.runInInjectionContext(() =>
          redirect(
            redirectData(
              { companyId: 'company-1', id: 'prod-1', supplierId: 'ps-1' },
              { storeId: 'store-9' },
            ),
          ),
        );

        expect((produced as UrlTree).toString()).toBe(
          '/products/c/company-1/edit/prod-1?tab=proveedores&storeId=store-9',
        );
      }
    });

    it('should carry no inert canActivate/data on either legacy redirect route', () => {
      for (const path of legacySupplierPaths) {
        const route = childAt(path);
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

  describe('legacy variant create redirect (D19)', () => {
    it('should redirect c/:companyId/edit/:id/variants/create into the Variantes workspace', () => {
      const create = childAt('c/:companyId/edit/:id/variants/create');

      expect(create).toBeDefined();
      // A function, not a string: the target is built from the route params.
      expect(typeof create?.redirectTo).toBe('function');
      // A redirect route never activates, so a `loadComponent` here would be dead config.
      expect(create?.loadComponent).toBeUndefined();

      const redirect = create?.redirectTo as RedirectFunction;
      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ companyId: 'company-1', id: 'prod-1' })),
      );

      // Assert the produced value, not merely that a function is present.
      expect((produced as UrlTree).toString()).toBe('/products/c/company-1/edit/prod-1/variants');
    });

    it('should preserve storeId when redirecting the legacy variant create URL', () => {
      const create = childAt('c/:companyId/edit/:id/variants/create');

      // Asserted first, or the property assertions below pass vacuously on an
      // undefined lookup.
      expect(create).toBeDefined();

      const redirect = create?.redirectTo as RedirectFunction;

      const produced = TestBed.runInInjectionContext(() =>
        redirect(redirectData({ companyId: 'company-1', id: 'prod-1' }, { storeId: 'store-9' })),
      );

      expect((produced as UrlTree).toString()).toBe(
        '/products/c/company-1/edit/prod-1/variants?storeId=store-9',
      );
    });

    it('should carry no inert canActivate/data on the legacy variant create redirect', () => {
      const create = childAt('c/:companyId/edit/:id/variants/create');

      // Asserted first, or the property assertions below pass vacuously on an
      // undefined lookup.
      expect(create).toBeDefined();

      // Deliberate: a redirect never activates, so a guard would never run and
      // Angular throws `RuntimeError 4014` when `redirectTo` is combined with
      // `canActivate`. `data` is omitted because it would be inert on a route that
      // never activates and would read as a gate. The target `edit/:id/variants`
      // keeps its own `VARIANT_ROLES` gate.
      expect(create!.canActivate).toBeUndefined();
      expect(create!.data).toBeUndefined();
    });

    it('should keep c/:companyId/edit/:id/variants/edit/:variantId resolving with its guard and variant roles (D18)', async () => {
      const edit = childAt('c/:companyId/edit/:id/variants/edit/:variantId');

      expect(edit).toBeDefined();
      // D18: the store-scoped editor keeps resolving; it is the sales principal's
      // only route to the per-store stock/prices panel.
      expect(edit?.redirectTo).toBeUndefined();
      expect(edit?.canActivate).toEqual([keycloakRoleGuard]);
      expect(edit?.data).toEqual({
        roles: ['lc-admin', 'lc-sales'],
        clientId: 'life-control-client',
      });
      expect(await loadName(edit!)).toBe('ProductVariantEdit');
    });
  });

  describe('product categories screen (S3)', () => {
    it('should expose the categories screen at categories, admin-only', async () => {
      // Pinned by path: it is the target the products dashboard card links to, so a
      // rename that leaves the gate intact would still break the entry point.
      const categories = childAt('categories');

      expect(categories).toBeDefined();
      expect(categories?.redirectTo).toBeUndefined();
      expect(categories?.canActivate).toEqual([keycloakRoleGuard]);
      expect(categories?.data).toEqual({ roles: ['lc-admin'], clientId: 'life-control-client' });
      expect(await loadName(categories!)).toBe('ProductCategoryList');
    });

    it('should never admit the sales role to the categories screen', () => {
      const categories = childAt('categories');

      // `toEqual` on the literal, not `not.toContain`: a missing `data` must fail
      // here instead of passing vacuously.
      expect(categories?.data?.['roles']).toEqual([LC_ADMIN]);
      expect(categories?.data?.['roles']).not.toContain(LC_SALES);
    });
  });
});
