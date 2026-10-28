import { inject } from '@angular/core';
import { Router, Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { unsavedChangesGuard } from '@core/guards/unsaved-changes.guard';
import { LC_ADMIN, VARIANT_ROLES } from '@core/security/roles';

const CLIENT_ID = 'life-control-client';
const PRODUCT_ADMIN_ROLES = [LC_ADMIN];

/**
 * The company context of a URL, as query parameters.
 *
 * The company is **chosen inside a screen** — the All Products filter, the create form's `Empresa`
 * field — and then travels as `?companyId=`, exactly as `ProductCategoryList` and the store-scoped
 * variant search already do. It is not a path segment: a segment puts the question in front of the
 * whole area, which is the shape the maintainer asked to remove (record `products-company-filter`,
 * S1/S3).
 *
 * Built in one fixed order (`companyId`, `tab`, `storeId`) so the same context always produces the
 * same URL string, whichever screen built it.
 */
function companyQuery(
  source: Record<string, string>,
  extra: { tab?: string } = {},
): Record<string, string> {
  const query: Record<string, string> = {};
  const companyId = source['companyId'];
  if (companyId) query['companyId'] = companyId;
  if (extra.tab) query['tab'] = extra.tab;
  const storeId = source['storeId'];
  if (storeId) query['storeId'] = storeId;
  return query;
}

/**
 * The products area.
 *
 * `Products` in the menu lands on the **hub** (`/products`): the area's own options — All Products,
 * New Product, Categories, Suppliers — the moment the menu is pressed. A product belongs to one
 * company, but the company is not something the area asks for up front: it is a filter inside All
 * Products, and the screens that need it read it from the URL (`?companyId=`) or ask for it in their
 * own form.
 *
 * The company-segment URLs the previous shape published (`/products/c/:companyId/...`) stay
 * registered as **redirects that carry the company**, so a bookmark, a dashboard card or a stale link
 * keeps its company instead of 404ing or losing it.
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
        // `/products`: the area's hub, and what the menu item opens. Cards only — no company question
        // stands between the menu and the products options (S1).
        path: '',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/products-admin/products-admin.component').then(
            (m) => m.ProductsAdminComponent,
          ),
      },
      {
        // All Products. Its own `Empresa` filter decides which catalog it shows; no company in the
        // path, and no request at all until one is chosen.
        path: 'list',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () => import('./pages/product-list/product-list').then((m) => m.ProductList),
      },
      {
        // The create form asks for the company in its own `Empresa` field, preselected from
        // `?companyId=` when the operator arrived from the list.
        path: 'create',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        // A half-filled new product must not be discarded by a stray navigation.
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
      },
      {
        // The product's own workspace. `?companyId=` is required: a product id alone cannot be
        // addressed on `/api/companies/{companyId}/products`, and inventing a company is exactly the
        // second source of truth this screen does not have.
        path: 'edit/:id',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () => import('./pages/product-edit/product-edit').then((m) => m.ProductEdit),
      },
      {
        // D19, kept from the company-segment shape: the legacy create URL stays registered so no
        // existing URL 404s, and redirects into the workspace `Variantes` tab, carrying the company
        // and the store the URL named. It targets `edit/:id/variants`, not `edit/:id`: the create route
        // is `VARIANT_ROLES` (`lc-admin` + `lc-sales`) while `edit/:id` is admin-only, so redirecting
        // there would deny `lc-sales` the variant list (the D17 argument applied to this route). A
        // redirect route never activates, so it carries no `canActivate`: Angular throws
        // `RuntimeError 4014` when `redirectTo` is combined with it.
        path: 'edit/:id/variants/create',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id'], 'variants'], {
            queryParams: companyQuery(queryParams),
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
        // D19: the legacy create/edit supplier URLs stay registered so no existing URL 404s, and
        // redirect into the workspace `Proveedores` tab, carrying the company and store. Both are
        // admin-only on both sides (the same argument as D13), so no principal loses access. A
        // redirect route never activates, so it carries no `canActivate`; `data` is omitted too
        // because it would be inert and would read as a gate.
        path: 'edit/:id/suppliers/create',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: companyQuery(queryParams, { tab: 'proveedores' }),
          });
        },
      },
      {
        // D19: same redirect as `suppliers/create` above.
        path: 'edit/:id/suppliers/edit/:supplierId',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: companyQuery(queryParams, { tab: 'proveedores' }),
          });
        },
      },
      {
        // D13: the product-scoped supplier **links** are company-scoped — the link hangs off the
        // product, which belongs to a company — so this keeps redirecting into the workspace tab.
        // The supplier **catalog** itself is global and stays at `/products/suppliers`; see the
        // comment on that route.
        path: 'edit/:id/suppliers',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: companyQuery(queryParams, { tab: 'proveedores' }),
          });
        },
      },
      {
        // The screen S3 asked for, and the target the dashboard `Categories` card names.
        // **Deliberately company-free.** The tree is company-scoped on the wire
        // (`/api/companies/{companyId}/product-categories`), but this is a company-independent
        // browser: like `DepartmentList`, it chooses its company inside the page (its own documented
        // decision), so the same URL serves every company.
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
        // product-picking UI. Reads `GET /api/product-variants/search`, which is
        // authorised for the same role pair.
        //
        // **Also deliberately company-free.** That endpoint is store-addressed
        // (`?q=&storeId=`), so this page has no company of its own: the company becomes
        // necessary only when a row is opened in the product-scoped editor, and the page
        // asks for it there.
        path: 'variants',
        canActivate: [keycloakRoleGuard],
        data: { roles: VARIANT_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/product-variant-stock-search/product-variant-stock-search').then(
            (m) => m.ProductVariantStockSearch,
          ),
      },
      {
        // **Deliberately company-free.** Suppliers are global by decision
        // (`/api/suppliers`, a later unit of its own): only a product's *links* to a supplier belong to
        // a company, and those live under `edit/:id/suppliers` above.
        path: 'suppliers',
        canActivate: [keycloakRoleGuard],
        data: { roles: PRODUCT_ADMIN_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./suppliers/pages/supplier-list/supplier-list').then((m) => m.SupplierList),
      },
      {
        // The global supplier catalog's own create/edit screens: global resource, no company context.
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
      // ─── Legacy company-segment URLs ─────────────────────────────────────────────────
      // The previous shape put the company in the path. Every one of those URLs is a bookmark, a
      // dashboard card or an older link, so each redirects to its company-free screen **carrying the
      // company as a query parameter** instead of dropping it or 404ing. These are the only routes
      // whose target depends on a `:companyId` param, and that is precisely why they are redirects.
      {
        path: 'c/:companyId',
        redirectTo: ({ queryParams }) => {
          const router = inject(Router);
          // The hub carries no company — its cards ask where they need to — so only the store
          // context survives the hop.
          return router.createUrlTree(['/products'], {
            queryParams: companyQuery(queryParams),
          });
        },
      },
      {
        path: 'c/:companyId/list',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/list'], {
            queryParams: companyQuery({ ...queryParams, companyId: params['companyId'] }),
          });
        },
      },
      {
        path: 'c/:companyId/create',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/create'], {
            queryParams: companyQuery({ ...queryParams, companyId: params['companyId'] }),
          });
        },
      },
      {
        path: 'c/:companyId/edit/:id',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: companyQuery({ ...queryParams, companyId: params['companyId'] }),
          });
        },
      },
      {
        path: 'c/:companyId/edit/:id/variants',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id'], 'variants'], {
            queryParams: companyQuery({ ...queryParams, companyId: params['companyId'] }),
          });
        },
      },
      {
        path: 'c/:companyId/edit/:id/variants/create',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id'], 'variants'], {
            queryParams: companyQuery({ ...queryParams, companyId: params['companyId'] }),
          });
        },
      },
      {
        path: 'c/:companyId/edit/:id/variants/edit/:variantId',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(
            ['/products/edit', params['id'], 'variants', 'edit', params['variantId']],
            { queryParams: companyQuery({ ...queryParams, companyId: params['companyId'] }) },
          );
        },
      },
      {
        path: 'c/:companyId/edit/:id/suppliers',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: companyQuery(
              { ...queryParams, companyId: params['companyId'] },
              { tab: 'proveedores' },
            ),
          });
        },
      },
      {
        path: 'c/:companyId/edit/:id/suppliers/create',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: companyQuery(
              { ...queryParams, companyId: params['companyId'] },
              { tab: 'proveedores' },
            ),
          });
        },
      },
      {
        path: 'c/:companyId/edit/:id/suppliers/edit/:supplierId',
        redirectTo: ({ params, queryParams }) => {
          const router = inject(Router);
          return router.createUrlTree(['/products/edit', params['id']], {
            queryParams: companyQuery(
              { ...queryParams, companyId: params['companyId'] },
              { tab: 'proveedores' },
            ),
          });
        },
      },
    ],
  },
];
