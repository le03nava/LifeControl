import type { Route } from '@angular/router';
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

/** The variant screens: the deliberate exception to the admin-only children. */
function variantChildren(): Route[] {
  return children().filter((route) => route.path?.includes('variants'));
}

/** Every child that is not a variant screen and stays admin-only. */
function nonVariantChildren(): Route[] {
  return children().filter((route) => !route.path?.includes('variants'));
}

describe('productRoutes', () => {
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
});
