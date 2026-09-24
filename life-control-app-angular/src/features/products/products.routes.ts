import { inject } from '@angular/core';
import { Router, Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { unsavedChangesGuard } from '@core/guards/unsaved-changes.guard';
import { LC_ADMIN, VARIANT_ROLES } from '@core/security/roles';

const CLIENT_ID = 'life-control-client';
const PRODUCT_ADMIN_ROLES = [LC_ADMIN];

export const productRoutes: Routes = [
  {
    path: '',
    // The parent admits the variant roles (lc-admin + lc-sales) so a sales
    // principal can reach the variant screens. The products ABM stays
    // admin-only: every child below re-declares both the guard and its own
    // data, because `keycloakRoleGuard` only reads `route.data` when it is
    // itself listed in `canActivate`.
    canActivate: [keycloakRoleGuard],
    data: { roles: VARIANT_ROLES, clientId: CLIENT_ID },
    children: [
      {
        path: '',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/products-admin/products-admin.component').then(
            (m) => m.ProductsAdminComponent,
          ),
      },
      {
        path: 'list',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () => import('./pages/product-list/product-list').then((m) => m.ProductList),
      },
      {
        path: 'create',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        // A half-filled new product must not be discarded by a stray navigation.
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
      },
      {
        path: 'edit/:id',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
      },
      {
        // D19: the legacy create URL stays registered so no existing URL 404s, and
        // redirects into the workspace `Variantes` tab. It targets the
        // `edit/:id/variants` host, not `edit/:id`: the create route is
        // `VARIANT_ROLES` (`lc-admin` + `lc-sales`) while `edit/:id` is admin-only, so
        // redirecting there would deny `lc-sales` the variant list (the D17 argument
        // applied to this route). A redirect route never activates, so it carries no
        // `canActivate`: Angular throws `RuntimeError 4014` when `redirectTo` is
        // combined with it. `data` is omitted too because it would be inert on a route
        // that never activates and would read as a gate. The target
        // `edit/:id/variants` keeps its own gate.
        path: 'edit/:id/variants/create',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          const storeId = queryParams['storeId'];
          return router.createUrlTree(['/products/edit', params['id'], 'variants'], {
            queryParams: storeId ? { storeId } : {},
          });
        },
      },
      {
        path: 'edit/:id/variants/edit/:variantId',
        canActivate: [keycloakRoleGuard],
        data: { roles: VARIANT_ROLES, clientId: CLIENT_ID },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./pages/product-variant-edit/product-variant-edit').then(
            (m) => m.ProductVariantEdit,
          ),
      },
      {
        // D17: this route does **not** redirect into the workspace. It is gated by
        // `VARIANT_ROLES` (`lc-admin` + `lc-sales`) while `edit/:id` is admin-only,
        // and it is the sales principal's only variant entry point; the variant
        // screens exist for `lc-admin` and `lc-sales` alike, mirroring the backend
        // `hasAnyRole('lc-admin','lc-sales')` on every variant endpoint. It keeps
        // resolving through the thin host, which supplies the `app-page-header` the
        // tab container no longer renders and passes `productId` down.
        path: 'edit/:id/variants',
        canActivate: [keycloakRoleGuard],
        data: { roles: VARIANT_ROLES, clientId: CLIENT_ID },
        // The host forwards the container's inline per-store panel flag, so a dirty
        // panel's stock and prices are not discarded by a stray navigation.
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./pages/product-variant-list-host/product-variant-list-host').then(
            (m) => m.ProductVariantListHost,
          ),
      },
      {
        // Store-scoped variant search: the one variant screen that stands on its own
        // instead of hanging off a product id, because a sales principal has no
        // product-picking UI — the `/products` ABM is admin-only, which is the whole
        // reason this child exists. Reads `GET /api/product-variants/search`, which is
        // authorised for the same role pair.
        path: 'variants',
        canActivate: [keycloakRoleGuard],
        data: { roles: VARIANT_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/product-variant-stock-search/product-variant-stock-search').then(
            (m) => m.ProductVariantStockSearch,
          ),
      },
      {
        // D19: the legacy create/edit URLs stay registered so no existing URL 404s,
        // and redirect into the workspace `Proveedores` tab. Both are admin-only on
        // both sides (the same argument as D13), so no principal loses access. A
        // redirect route never activates, so it carries no `canActivate`: Angular
        // throws `RuntimeError 4014` when `redirectTo` is combined with it. `data` is
        // omitted too because it would be inert on a route that never activates and
        // would read as a gate. The target `edit/:id` keeps its own admin gate.
        path: 'edit/:id/suppliers/create',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          const storeId = queryParams['storeId'];
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: storeId ? { tab: 'proveedores', storeId } : { tab: 'proveedores' },
          });
        },
      },
      {
        // D19: same redirect as `suppliers/create` above. The function runs in an
        // injection context and returns a `UrlTree`, the only return shape that can
        // carry `tab=` and preserve `storeId`.
        path: 'edit/:id/suppliers/edit/:supplierId',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          const storeId = queryParams['storeId'];
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: storeId ? { tab: 'proveedores', storeId } : { tab: 'proveedores' },
          });
        },
      },
      {
        // D13: the product-scoped supplier list is admin-only on both sides, so it
        // redirects into the workspace `Proveedores` tab and no principal loses
        // access. A redirect route never activates, so it carries no `canActivate`
        // and no `data`: those would be inert config that reads as a gate. The
        // target `edit/:id` keeps its own admin guard.
        //
        // The function runs in an injection context and returns a `UrlTree`, the
        // only return shape that can carry `tab=` and preserve `storeId`. The
        // legacy `create`/`edit` children above redirect too (D19); the sibling
        // `suppliers/create` and `suppliers/edit/:id` routes below are the global
        // supplier ABM and keep loading their pages: Angular's `defaultUrlMatcher`
        // requires full segment consumption, so neither redirect can shadow them.
        path: 'edit/:id/suppliers',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          const storeId = queryParams['storeId'];
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: storeId ? { tab: 'proveedores', storeId } : { tab: 'proveedores' },
          });
        },
      },
      {
        path: 'suppliers',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./suppliers/pages/supplier-list/supplier-list').then((m) => m.SupplierList),
      },
      {
        path: 'suppliers/create',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./suppliers/pages/supplier-edit/supplier-edit').then((m) => m.SupplierEdit),
      },
      {
        path: 'suppliers/edit/:id',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./suppliers/pages/supplier-edit/supplier-edit').then((m) => m.SupplierEdit),
      },
    ],
  },
];
