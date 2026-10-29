import type { ActivatedRoute } from '@angular/router';

/**
 * The company context of the current products screen, carried as `?companyId=`.
 *
 * The company is **chosen inside the screen** — the `Empresa` filter of All Products, the `Empresa`
 * field of the create form — and then kept in the URL, exactly as `ProductCategoryList` and the
 * store-scoped variant search already do. It is not a path segment: `/products/c/:companyId/...` was
 * the previous shape, and it was removed on the maintainer's request (record `products-company-filter`,
 * S1/S3) because a segment puts the question in front of the whole area instead of inside the screen
 * that actually needs the answer.
 *
 * Read from the route, never from a response body: the company is the resource's owner on the wire
 * (`/api/companies/{companyId}/products`), so it is part of the URL that addresses it. A screen that
 * derived it from anything else could disagree with the URL it is standing on, which is exactly the
 * second source of truth this unit removed.
 *
 * A snapshot read is what the screens that only *seed* from it need. A query param can change without
 * the router destroying the component — an in-page `replaceUrl` write does precisely that — so a screen
 * whose selection is editable keeps its own signal, seeded from here and written back to the URL. This
 * function is that seed, not a live value.
 *
 * `null` means the URL names no company: `/products` (the hub), `categories` or `variants` (the
 * store-addressed search), and no screen builds a scoped request from it. The supplier catalog reads it
 * the same way since a supplier belongs to one company (issue #672): `/products/suppliers` with no
 * `?companyId=` is the "choose a company" state, not a cross-company list, because there is no
 * cross-company read either.
 */
export function productsCompanyId(route: ActivatedRoute): string | null {
  return route.snapshot.queryParamMap.get('companyId');
}
