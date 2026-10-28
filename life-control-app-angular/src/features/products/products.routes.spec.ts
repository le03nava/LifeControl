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

/**
 * The URL a redirect route produces for the given params.
 *
 * Every redirect below is a `RedirectFunction` (its target is built from the params), so the assertion
 * has to run it — asserting that a function exists says nothing about where it goes.
 */
function redirectUrl(
  path: string,
  params: Record<string, string>,
  queryParams: Record<string, string> = {},
): string {
  const route = childAt(path);
  expect(route).toBeDefined();
  expect(route?.loadComponent).toBeUndefined();
  const redirect = route?.redirectTo as RedirectFunction;
  expect(typeof redirect).toBe('function');
  const produced = TestBed.runInInjectionContext(() => redirect(redirectData(params, queryParams)));
  return (produced as UrlTree).toString();
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
    const create = childAt('create');
    const edit = childAt('edit/:id');

    expect(create).toBeDefined();
    expect(edit).toBeDefined();
    // `toEqual([guard])`, never `toContain(guard)`: `expect(undefined)
    // .toContain(fn)` passes vacuously when the property is absent.
    expect(create?.canDeactivate).toEqual([unsavedChangesGuard]);
    expect(edit?.canDeactivate).toEqual([unsavedChangesGuard]);
  });

  it('should guard the variant-list host route against unsaved changes', () => {
    const variants = childAt('edit/:id/variants');

    expect(variants).toBeDefined();
    // The host is the activated component on this route, and it forwards the
    // container's inline per-store panel flag; without this registration a dirty
    // panel's stock and prices would be discarded by a stray navigation.
    // `toEqual([guard])`, never `toContain(guard)`: `expect(undefined)
    // .toContain(fn)` passes vacuously when the property is absent.
    expect(variants?.canDeactivate).toEqual([unsavedChangesGuard]);
  });

  it('should leave the unrelated product routes unguarded', () => {
    const list = childAt('list');
    const suppliers = childAt('edit/:id/suppliers');

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

  describe('the products front door (S1)', () => {
    it('should render the products hub at /products, not a company question', async () => {
      // The requirement, stated by the maintainer: pressing Products shows the area's options. A page
      // that asks for a company first is the shape being removed, so the picker must not come back here
      // (and `product-company-picker` is deleted, so this spec also fails if it is resurrected).
      const root = childAt('');

      expect(root).toBeDefined();
      expect(root?.redirectTo).toBeUndefined();
      expect(await loadName(root!)).toBe('ProductsAdminComponent');
    });

    it('should address the product screens without a company segment', async () => {
      const list = childAt('list');
      const create = childAt('create');
      const edit = childAt('edit/:id');

      expect(await loadName(list!)).toBe('ProductList');
      expect(await loadName(create!)).toBe('ProductEdit');
      expect(await loadName(edit!)).toBe('ProductEdit');
    });

    it('should keep every screen path company-free', () => {
      // The company is a query parameter (`?companyId=`) from here on, chosen inside the screen. A
      // route that grew a `:companyId` segment again would put the question back in front of the area,
      // which is the single thing S1 asks not to do.
      //
      // Redirects are the deliberate exception and the reason this loop filters them out: the whole
      // split of "which routes are company-free" is about the screens, while `c/:companyId/**` exists
      // only to carry a legacy URL's company into a company-free one (asserted in the next describe).
      for (const route of children().filter((route) => !isRedirect(route))) {
        expect(route.path ?? '').not.toContain('companyId');
      }
    });
  });

  describe('the deliberate company-free screens', () => {
    it('should keep categories, the store-scoped search and suppliers outside any company context', () => {
      // Pinned by exact path: each of these was left out of the company context on purpose. Moving one
      // inside would remove a picker the page owns (`categories`), break a store-addressed read
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
  });

  describe('legacy company-segment URLs (S1)', () => {
    /**
     * Every URL the previous shape published, with the company preserved.
     *
     * These are bookmarks, dashboard cards and — until T2 lands — the app's own links. A 404 would be a
     * worse answer than the same screen with the company that was already in the URL.
     */
    const legacyToNew: readonly {
      path: string;
      params: Record<string, string>;
      expected: string;
    }[] = [
      { path: 'c/:companyId', params: { companyId: 'company-1' }, expected: '/products' },
      {
        path: 'c/:companyId/list',
        params: { companyId: 'company-1' },
        expected: '/products/list?companyId=company-1',
      },
      {
        path: 'c/:companyId/create',
        params: { companyId: 'company-1' },
        expected: '/products/create?companyId=company-1',
      },
      {
        path: 'c/:companyId/edit/:id',
        params: { companyId: 'company-1', id: 'prod-1' },
        expected: '/products/edit/prod-1?companyId=company-1',
      },
      {
        path: 'c/:companyId/edit/:id/variants',
        params: { companyId: 'company-1', id: 'prod-1' },
        expected: '/products/edit/prod-1/variants?companyId=company-1',
      },
      {
        path: 'c/:companyId/edit/:id/variants/edit/:variantId',
        params: { companyId: 'company-1', id: 'prod-1', variantId: 'var-1' },
        expected: '/products/edit/prod-1/variants/edit/var-1?companyId=company-1',
      },
      {
        path: 'c/:companyId/edit/:id/suppliers',
        params: { companyId: 'company-1', id: 'prod-1' },
        expected: '/products/edit/prod-1?companyId=company-1&tab=proveedores',
      },
    ];

    it('should redirect each one to its company-free screen carrying the company', () => {
      for (const { path, params, expected } of legacyToNew) {
        expect(redirectUrl(path, params)).toBe(expected);
      }
    });

    it('should preserve storeId on every one of them', () => {
      // `storeId` is the store context the variant panel prices and stocks; dropping it on the redirect
      // would silently send the operator to a different store's panel. The separator is derived because
      // the hub's target carries no query at all, so appending with a hardcoded `&` would build
      // `/products&storeId=…`, which is not a URL.
      for (const { path, params, expected } of legacyToNew) {
        const separator = expected.includes('?') ? '&' : '?';
        const url = redirectUrl(path, params, { storeId: 'store-9' });
        expect(url).toBe(`${expected}${separator}storeId=store-9`);
      }
    });

    it('should carry no inert canActivate/data on any legacy redirect', () => {
      for (const { path } of legacyToNew) {
        const route = childAt(path);

        // Asserted first, or the property assertions below pass vacuously on an undefined lookup.
        expect(route).toBeDefined();
        // Deliberate: a redirect never activates, so a guard would never run and Angular throws
        // `RuntimeError 4014` when `redirectTo` is combined with `canActivate`. The target screen keeps
        // its own gate, so effective access is unchanged.
        expect(route!.canActivate).toBeUndefined();
        expect(route!.data).toBeUndefined();
      }
    });
  });

  describe('the supplier tab redirects (D13/D19)', () => {
    it('should send the product-scoped supplier list into the workspace Proveedores tab', () => {
      // A product's *links* to a supplier hang off the product, which belongs to a company, so this
      // keeps landing on the product's workspace tab rather than on the global supplier catalog.
      expect(redirectUrl('edit/:id/suppliers', { id: 'prod-1' }, { companyId: 'company-1' })).toBe(
        '/products/edit/prod-1?companyId=company-1&tab=proveedores',
      );
    });

    it('should send both legacy supplier create/edit URLs into the same tab', () => {
      for (const path of ['edit/:id/suppliers/create', 'edit/:id/suppliers/edit/:supplierId']) {
        expect(
          redirectUrl(path, { id: 'prod-1', supplierId: 'ps-1' }, { companyId: 'company-1' }),
        ).toBe('/products/edit/prod-1?companyId=company-1&tab=proveedores');
      }
    });

    it('should preserve storeId when redirecting into the tab', () => {
      const url = redirectUrl(
        'edit/:id/suppliers/create',
        { id: 'prod-1' },
        { storeId: 'store-9' },
      );
      expect(url).toBe('/products/edit/prod-1?tab=proveedores&storeId=store-9');
    });

    it('should carry no inert canActivate/data on the supplier redirects', () => {
      for (const path of [
        'edit/:id/suppliers',
        'edit/:id/suppliers/create',
        'edit/:id/suppliers/edit/:supplierId',
      ]) {
        const route = childAt(path);
        expect(route).toBeDefined();
        expect(route!.canActivate).toBeUndefined();
        expect(route!.data).toBeUndefined();
      }
    });
  });

  describe('the variant create redirect (D19)', () => {
    it('should redirect edit/:id/variants/create into the Variantes workspace', () => {
      expect(
        redirectUrl('edit/:id/variants/create', { id: 'prod-1' }, { companyId: 'company-1' }),
      ).toBe('/products/edit/prod-1/variants?companyId=company-1');
    });

    it('should preserve storeId on the variant create redirect', () => {
      expect(
        redirectUrl('edit/:id/variants/create', { id: 'prod-1' }, { storeId: 'store-9' }),
      ).toBe('/products/edit/prod-1/variants?storeId=store-9');
    });

    it('should carry no inert canActivate/data on it', () => {
      const create = childAt('edit/:id/variants/create');
      expect(create).toBeDefined();
      expect(create!.canActivate).toBeUndefined();
      expect(create!.data).toBeUndefined();
    });
  });

  describe('the variant screens (D17/D18)', () => {
    it('should keep edit/:id/variants resolving through the thin host, never a redirect', async () => {
      const variants = childAt('edit/:id/variants');

      expect(variants).toBeDefined();
      // Redirecting here would deny `lc-sales` the variant list, whose only entry point is
      // product-variant-stock-search.ts: the route is `VARIANT_ROLES` while its would-be target
      // `edit/:id` is admin-only.
      expect(variants?.redirectTo).toBeUndefined();
      expect(variants?.canActivate).toEqual([keycloakRoleGuard]);
      expect(variants?.data).toEqual({
        roles: ['lc-admin', 'lc-sales'],
        clientId: 'life-control-client',
      });
      expect(await loadName(variants!)).toBe('ProductVariantListHost');
    });

    it('should keep the variant editor resolving with its guard and variant roles (D18)', async () => {
      const edit = childAt('edit/:id/variants/edit/:variantId');

      expect(edit).toBeDefined();
      // D18: the store-scoped editor keeps resolving; it is the sales principal's only route to the
      // per-store stock/prices panel.
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
