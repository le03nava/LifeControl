import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { VariantStoreContext } from '../../data/variant-store-context.service';
import { ProductVariantSearchResult } from '../../models/product-variant.models';

/** The endpoint needs at least a couple of characters to answer something usable. */
const MIN_QUERY_LENGTH = 2;

/**
 * Store-scoped variant search: find a variant in the operator's store and open its
 * per-store stock and prices.
 *
 * This is the discoverable entry point the `lc-sales` role was missing. Every other
 * route under `/products` is admin-only (the products ABM), and the product-scoped
 * variant screens hang off a product id that only an admin can browse to, so before
 * this page a sales principal could reach the per-store editor only by typing a deep
 * link. It reads `GET /api/product-variants/search`, which is authorised for exactly
 * `lc-admin` + `lc-sales` and exists for this purpose.
 *
 * The store arrives resolved through {@link VariantStoreContext} (`?storeId=` -> the
 * operator's configured store -> `null`, fail closed), the same rule as the other
 * variant screens.
 *
 * **The company is asked for here, and only for the editor link.** The endpoint this page reads is
 * store-addressed (`/api/product-variants/search?storeId=`), so the search itself has no company; but
 * a row opens the product-scoped editor, whose path and every request below it are scoped to the
 * company that owns the product. Asking for it on this page — the same inline choice
 * `ProductCategoryList` makes, kept in `?companyId=` — is what keeps the sales role's only entry point
 * at a stable URL instead of routing it through `/products`, whose destination is the admin-only hub.
 *
 * **Search runs on submit, not on every keystroke.** The sales variant selector
 * debounces because it is an autocomplete; this is a page of results, and the primary
 * flow is scanning or typing an identifier and pressing Enter. Skipping the debounce
 * also keeps the surface deterministic instead of time-dependent.
 */
@Component({
  selector: 'app-product-variant-stock-search',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatTableModule,
    MatIconModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatPaginatorModule,
    CurrencyPipe,
    ErrorBanner,
    PageHeader,
    MatSelectModule,
  ],
  templateUrl: './product-variant-stock-search.html',
  styleUrl: './product-variant-stock-search.scss',
  // Per screen, not at the root: this page owns its own store resolution.
  providers: [VariantStoreContext],
})
export class ProductVariantStockSearch {
  private readonly variantService = inject(ProductVariantService);
  private readonly companyService = inject(CompanyService);
  private readonly storeContext = inject(VariantStoreContext);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  /**
   * The companies the editor link can be scoped to.
   *
   * Read from the claim-authorized selector, the same list every other company choice in this feature
   * offers, so the page cannot name a company the scoped screens would refuse.
   */
  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /**
   * The company the product being opened belongs to, seeded from `?companyId=` and kept there.
   *
   * Deliberately not derived from the row: the search projection does not carry a company, because the
   * search itself does not need one. `null` is a real state — the operator has not said yet — and it
   * only disables the edit action, never the search.
   */
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  readonly storeId = this.storeContext.storeId;
  readonly storePending = this.storeContext.pending;
  readonly storeError = this.storeContext.storeError;
  readonly storeUnconfigured = this.storeContext.unconfigured;
  readonly storeSource = this.storeContext.source;

  /** What is in the box right now, submitted or not. */
  readonly query = signal('');
  /** The query the results belong to, so the table never claims the wrong search. */
  private readonly submittedQuery = signal('');

  readonly pageIndex = signal(0);
  readonly pageSize = signal(20);

  protected readonly httpErrorMessage = httpErrorMessage;

  readonly displayedColumns: string[] = [
    'barCode',
    'productName',
    'variantName',
    'listPrice',
    'costPrice',
    'stock',
    'actions',
  ];

  readonly resultsResource = rxResource({
    params: () => {
      // No read until the store resolution settles, and none before a query: the
      // endpoint requires both `q` and `storeId`, and one character would return most
      // of the catalogue.
      if (this.storeContext.pending()) {
        return undefined;
      }
      const storeId = this.storeId();
      const query = this.submittedQuery();
      if (!storeId || query.length < MIN_QUERY_LENGTH) {
        return undefined;
      }
      return { q: query, storeId, page: this.pageIndex(), size: this.pageSize() };
    },
    stream: ({ params }) =>
      this.variantService.searchVariants(params.q, params.storeId, params.page, params.size),
  });

  readonly results = computed(() =>
    this.resultsResource.hasValue() ? this.resultsResource.value() : undefined,
  );

  readonly loading = computed(
    () => this.storeContext.pending() || this.resultsResource.isLoading(),
  );
  readonly error = this.resultsResource.error;
  readonly hasMultiplePages = computed(() => (this.results()?.totalPages ?? 0) > 1);

  /** Whether a search was actually submitted, i.e. whether an empty table means "none". */
  readonly searched = computed(() => this.submittedQuery().length >= MIN_QUERY_LENGTH);

  constructor() {
    this.storeContext.resolve(this.route.snapshot.queryParamMap.get('storeId'));
  }

  onQueryChange(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  /**
   * Runs the search in the box.
   *
   * Re-submitting the query already on screen re-runs it instead of doing nothing, so
   * the button always does what it says.
   */
  onSearch(): void {
    const query = this.query().trim();
    this.pageIndex.set(0);

    if (query === this.submittedQuery()) {
      this.resultsResource.reload();
      return;
    }
    this.submittedQuery.set(query);
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  editVariant(row: ProductVariantSearchResult): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) {
      return;
    }
    this.router.navigate(
      ['/products/c', companyId, 'edit', row.productId, 'variants', 'edit', row.id],
      { queryParams: this.storeQueryParams() },
    );
  }

  /**
   * Keeps the chosen company in the URL (replace, so a change does not pollute history), exactly as
   * `ProductCategoryList.onCompanyChange` and the product form's company field do.
   */
  onCompanyChange(companyId: string | null): void {
    const next = companyId || null;
    this.selectedCompanyId.set(next);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { companyId: next },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** Re-runs a store resolution that failed: a failed read is not "no store set". */
  retryStore(): void {
    this.storeContext.reload();
  }

  /** Re-runs a search that failed. */
  onRetry(): void {
    this.resultsResource.reload();
  }

  /** The profile page owns the store preference and is reachable by every role. */
  goToProfile(): void {
    this.router.navigate(['/profile']);
  }

  /**
   * Query params that carry the store to the editor, only when the link named it. A
   * store that came from the profile is re-resolved there.
   */
  private storeQueryParams(): { storeId: string } | undefined {
    const storeId = this.storeId();
    return storeId && this.storeSource() === 'query' ? { storeId } : undefined;
  }
}
