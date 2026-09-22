import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
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
  ],
  templateUrl: './product-variant-stock-search.html',
  styleUrl: './product-variant-stock-search.scss',
  // Per screen, not at the root: this page owns its own store resolution.
  providers: [VariantStoreContext],
})
export class ProductVariantStockSearch {
  private readonly variantService = inject(ProductVariantService);
  private readonly storeContext = inject(VariantStoreContext);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

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
    this.router.navigate(['/products/edit', row.productId, 'variants', 'edit', row.id], {
      queryParams: this.storeQueryParams(),
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
