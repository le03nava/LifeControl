import type { ActivatedRoute } from '@angular/router';

/**
 * The company segment of the current products route: `/products/c/:companyId/...`.
 *
 * Read from the route, never from a query param and never from a response body. The company is the
 * resource's owner on the wire (`/api/companies/{companyId}/products`), so it is part of the URL that
 * addresses it: a screen that derived it from anything else could disagree with the path it is
 * standing on, which is exactly the second source of truth this unit removed.
 *
 * A snapshot read is correct and not a shortcut: `:companyId` is a path segment, so it cannot change
 * without the router destroying the component that reads it.
 *
 * `null` means the URL is one of the company-free screens — `/products` (the picker), `categories`,
 * `variants` (the store-addressed search) or `suppliers` — and those never build a scoped request.
 */
export function productsCompanyId(route: ActivatedRoute): string | null {
  return route.snapshot.paramMap.get('companyId');
}
