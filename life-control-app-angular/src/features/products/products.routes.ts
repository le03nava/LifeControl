import { inject } from '@angular/core';
import { Router, Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { unsavedChangesGuard } from '@core/guards/unsaved-changes.guard';
import { LC_ADMIN, VARIANT_ROLES } from '@core/security/roles';

const CLIENT_ID = 'life-control-client';
const PRODUCT_ADMIN_ROLES = [LC_ADMIN];

/**
 * The products area.
 *
 * A product belongs to one company — `/api/companies/{companyId}/products` — so the company is a
 * **route segment** and not a query param or a piece of page state: every screen below
 * `c/:companyId` reads it from the URL, which is what makes the links shareable and the scope
 * impossible to forget. `/products` therefore carries no company and is the **company picker**; a
 * choice there navigates to `c/:companyId`, the hub.
 *
 * The legacy URLs the app itself used to link to (`/products/list`, `/products/create`,
 * `/products/edit/:id`, the variant and supplier children of that edit screen) are kept as
 * **redirects to `/products`** rather than removed: bookmarks and the dashboard cards exist, and a 404
 * is a worse answer than the picker that re-establishes the company.
 */
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
        // `/products`: the company picker. A page, not a redirect: the company is the first thing the
        // area needs and the only thing it cannot derive, and a redirect to a company-blind screen
        // would be a loop. Admin-only because everything it leads to is.
        path: '',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/product-company-picker/product-company-picker').then(
            (m) => m.ProductCompanyPicker,
          ),
      },
      {
        // The hub for one company: the cards of the products dashboard, now scoped.
        path: 'c/:companyId',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/products-admin/products-admin.component').then(
            (m) => m.ProductsAdminComponent,
          ),
      },
      {
        path: 'c/:companyId/list',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () => import('./pages/product-list/product-list').then((m) => m.ProductList),
      },
      {
        path: 'c/:companyId/create',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        // A half-filled new product must not be discarded by a stray navigation.
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
      },
      {
        path: 'c/:companyId/edit/:id',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
      },
      {
        // D19, carried into the company segment: the legacy create URL stays registered so no existing
        // URL 404s, and redirects into the workspace `Variantes` tab. It targets the
        // `edit/:id/variants` host, not `edit/:id`: the create route is `VARIANT_ROLES` (`lc-admin` +
        // `lc-sales`) while `edit/:id` is admin-only, so redirecting there would deny `lc-sales` the
        // variant list (the D17 argument applied to this route). A redirect route never activates, so
        // it carries no `canActivate`: Angular throws `RuntimeError 4014` when `redirectTo` is
        // combined with it. `data` is omitted too because it would be inert on a route that never
        // activates and would read as a gate.
        path: 'c/:companyId/edit/:id/variants/create',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          const storeId = queryParams['storeId'];
          return router.createUrlTree(
            ['/products/c', params['companyId'], 'edit', params['id'], 'variants'],
            { queryParams: storeId ? { storeId } : {} },
          );
        },
      },
      {
        path: 'c/:companyId/edit/:id/variants/edit/:variantId',
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
        path: 'c/:companyId/edit/:id/variants',
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
        // D19: the legacy create/edit supplier URLs stay registered so no existing URL 404s, and
        // redirect into the workspace `Proveedores` tab. Both are admin-only on both sides (the same
        // argument as D13), so no principal loses access. A redirect route never activates, so it
        // carries no `canActivate`: Angular throws `RuntimeError 4014` when `redirectTo` is combined
        // with it. `data` is omitted too because it would be inert on a route that never activates
        // and would read as a gate. The target `edit/:id` keeps its own admin gate.
        path: 'c/:companyId/edit/:id/suppliers/create',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/c', params['companyId'], 'edit', params['id']], {
            queryParams: supplierTabQueryParams(queryParams),
          });
        },
      },
      {
        // D19: same redirect as `suppliers/create` above. The function runs in an
        // injection context and returns a `UrlTree`, the only return shape that can
        // carry `tab=` and preserve `storeId`.
        path: 'c/:companyId/edit/:id/suppliers/edit/:supplierId',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/c', params['companyId'], 'edit', params['id']], {
            queryParams: supplierTabQueryParams(queryParams),
          });
        },
      },
      {
        // D13: the product-scoped supplier **links** are company-scoped — the link hangs off the
        // product, which belongs to a company — so this keeps redirecting into the workspace tab.
        // The supplier **catalog** itself is global and stays at `/products/suppliers`; see the
        // comment on that route.
        path: 'c/:companyId/edit/:id/suppliers',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/c', params['companyId'], 'edit', params['id']], {
            queryParams: supplierTabQueryParams(queryParams),
          });
        },
      },
      {
        // The screen S3 asked for, and the target the dashboard `Categories` card names.
        // **Deliberately outside the company segment.** The tree is company-scoped on the wire
        // (`/api/companies/{companyId}/product-categories`), but this is a company-independent
        // browser: like `DepartmentList`, it chooses its company inside the page (its own documented
        // decision), so the same URL serves every company and a review that "fixed" the missing
        // segment here would take the picker away from it.
        path: 'categories',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/product-category-list/product-category-list').then(
            (m) => m.ProductCategoryList,
          ),
      },
      {
        // Store-scoped variant search: the one variant screen that stands on its own
        // instead of hanging off a product id, because a sales principal has no
        // product-picking UI — the `/products` ABM is admin-only, which is the whole
        // reason this child exists. Reads `GET /api/product-variants/search`, which is
        // authorised for the same role pair.
        //
        // **Also deliberately outside the company segment.** That endpoint is store-addressed
        // (`?q=&storeId=`), so this page has no company of its own: the company only becomes
        // necessary when a row is opened in the product-scoped editor, and the page asks for it
        // there, exactly like `categories` does. The alternative — routing a sales principal
        // through `/products`, whose destination is the admin-only hub — would keep the sales role's
        // only entry point behind a screen it cannot use.
        path: 'variants',
        canActivate: [keycloakRoleGuard],
        data: { roles: VARIANT_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/product-variant-stock-search/product-variant-stock-search').then(
            (m) => m.ProductVariantStockSearch,
          ),
      },
      {
        // **Deliberately outside the company segment.** Suppliers are global by decision
        // (`/api/suppliers`, a later unit of its own): only a product's *links* to a supplier belong to
        // a company, and those live under `c/:companyId/edit/:id/suppliers` above.
        path: 'suppliers',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./suppliers/pages/supplier-list/supplier-list').then((m) => m.SupplierList),
      },
      {
        // The global supplier catalog's own create/edit screens: global resource, no company segment,
        // for the reason stated on `suppliers` above.
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
      // ─── Legacy bookmarks ────────────────────────────────────────────────────────────
      // Every URL the app itself linked to before the company became a path segment lands on the
      // picker: there is no company in these paths, so there is nothing to redirect *into*, and the
      // picker is the screen whose whole job is to ask. They stay registered so a bookmark and a
      // stale dashboard card resolve instead of hitting `**` (NotFound).
      { path: 'list', redirectTo: '/products' },
      { path: 'create', redirectTo: '/products' },
      { path: 'edit/:id', redirectTo: '/products' },
      { path: 'edit/:id/variants', redirectTo: '/products' },
      { path: 'edit/:id/variants/create', redirectTo: '/products' },
      { path: 'edit/:id/variants/edit/:variantId', redirectTo: '/products' },
      { path: 'edit/:id/suppliers', redirectTo: '/products' },
      { path: 'edit/:id/suppliers/create', redirectTo: '/products' },
      { path: 'edit/:id/suppliers/edit/:supplierId', redirectTo: '/products' },
    ],
  },
];

/**
 * The `Proveedores` tab's query params, carrying `storeId` when the legacy URL named it.
 *
 * Shared by the three supplier redirects above so the two params cannot drift apart.
 */
function supplierTabQueryParams(queryParams: Record<string, string>): Record<string, string> {
  const storeId = queryParams['storeId'];
  return storeId ? { tab: 'proveedores', storeId } : { tab: 'proveedores' };
}
