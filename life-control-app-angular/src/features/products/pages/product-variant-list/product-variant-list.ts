import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { ProductService } from '../../data/product.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { VariantStoreContext } from '../../data/variant-store-context.service';
import { Page } from '../../models/product.models';
import { ProductVariant } from '../../models/product-variant.models';
import { DisableVariantDialogComponent } from '../../ui/disable-variant-dialog/disable-variant-dialog';

/** Page emitted while the route carries no product id, so the template never sees `null`. */
const EMPTY_PAGE: Page<ProductVariant> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  size: 0,
  number: 0,
  first: true,
  last: true,
  empty: true,
};

/** Columns of the global read: the store-scoped fields are `null` on every row. */
const GLOBAL_COLUMNS = ['barCode', 'variantName', 'enabled', 'actions'];

/**
 * Columns of the store-scoped read. The prices and the stock are joined in for
 * every row, so they earn a column here; `enabled` is dropped instead, because
 * that branch filters `enabled = true` server-side and the column would state the
 * same thing on every row.
 */
const STORE_COLUMNS = ['barCode', 'variantName', 'listPrice', 'costPrice', 'stock', 'actions'];

/**
 * A product's variant list, in one of two views.
 *
 * **Global definitions** (no store in play) — the backend leaves `companyStoreId`,
 * `listPrice`, `costPrice` and `stock` `null` on every row, so the table shows the
 * identity columns and the `enabled` state, and `includeDisabled` opts into the
 * soft-deleted definitions.
 *
 * **Store-scoped** — the resolved store is passed to the same endpoint and the
 * backend joins the per-store row into each result, so prices and stock are worth
 * a column. That branch always filters `enabled = true` regardless of
 * `includeDisabled`, so the "Mostrar deshabilitadas" toggle is only offered on the
 * global view.
 *
 * Both views have to stay reachable, which is why the store scope is a control
 * and not just a resolution: the store-scoped read cannot list a soft-deleted
 * definition nor one with no row for that store, so the global view is the only
 * place those can be seen and re-enabled. The scope starts from
 * {@link VariantStoreContext} and then follows the operator.
 *
 * `DELETE` is a soft delete that flips `enabled` to `false`; `PATCH .../enable`
 * brings the variant back. The user-facing actions are therefore "Deshabilitar"
 * and "Habilitar", never a hard delete.
 */
@Component({
  selector: 'app-product-variant-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatTableModule,
    MatIconModule,
    MatButtonModule,
    MatChipsModule,
    MatPaginatorModule,
    MatSlideToggleModule,
    MatCardModule,
    CurrencyPipe,
    PageHeader,
  ],
  templateUrl: './product-variant-list.html',
  styleUrl: './product-variant-list.scss',
  // Per screen, not at the root: this page owns its own store resolution.
  providers: [VariantStoreContext],
})
export class ProductVariantList {
  private readonly productVariantService = inject(ProductVariantService);
  private readonly productService = inject(ProductService);
  private readonly storeContext = inject(VariantStoreContext);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly productId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  /**
   * Opt-in for the disabled global definitions.
   *
   * The backend excludes soft-deleted variants by default, so without this the
   * "Habilitar" affordance could never render. Inapplicable on the store-scoped
   * view, whose branch ignores it and always filters `enabled = true`.
   */
  readonly includeDisabled = signal(false);

  /** The store the resolution produced, regardless of the view in play. */
  readonly storeId = this.storeContext.storeId;
  readonly storePending = this.storeContext.pending;
  readonly storeError = this.storeContext.storeError;
  readonly storeUnconfigured = this.storeContext.unconfigured;
  readonly storeSource = this.storeContext.source;

  /**
   * The operator's store-scope choice, or `null` while it still follows the
   * resolution. A signal rather than an effect on {@link storeId} so that turning
   * the scope off stays off instead of being re-applied by the next resolution.
   */
  private readonly storeScopeChoice = signal<boolean | null>(null);

  /** Whether the list is narrowed to one store right now. */
  readonly storeScopeEnabled = computed(() => this.storeScopeChoice() ?? this.storeId() !== null);

  /** The store to send, or `null` for the global definitions. */
  readonly activeStoreId = computed(() => (this.storeScopeEnabled() ? this.storeId() : null));

  /** Whether the store-scoped view is the one in play. */
  readonly storeScoped = computed(() => this.activeStoreId() !== null);

  readonly displayedColumns = computed(() => (this.storeScoped() ? STORE_COLUMNS : GLOBAL_COLUMNS));

  /** The owning product, only for the page header. */
  readonly productResource = rxResource({
    params: () => ({ productId: this.productId() }),
    stream: ({ params }) => {
      if (!params.productId) {
        return of(null);
      }
      return this.productService.getProductById(params.productId);
    },
  });
  readonly product = computed(() =>
    this.productResource.hasValue() ? this.productResource.value() : undefined,
  );

  readonly variantsResource = rxResource({
    params: () => {
      // No read until the store resolution settles: issuing the global read first and
      // the store-scoped read after it would be two reads for one view. The cost is
      // one extra flush before the list loads, which the specs pay with `settle()`.
      if (this.storeContext.pending()) {
        return undefined;
      }
      return {
        productId: this.productId(),
        storeId: this.activeStoreId(),
        page: this.pageIndex(),
        size: this.pageSize(),
        includeDisabled: this.includeDisabled(),
      };
    },
    stream: ({ params }) => {
      if (!params.productId) {
        return of(EMPTY_PAGE);
      }
      return this.productVariantService.getVariants(
        params.productId,
        params.storeId ?? undefined,
        params.page,
        params.size,
        // The store-scoped branch ignores the opt-in server-side, so it is never sent with a store.
        params.storeId ? false : params.includeDisabled,
      );
    },
  });

  readonly variants = computed(() =>
    this.variantsResource.hasValue() ? this.variantsResource.value() : undefined,
  );

  /** Neither read nor resolution settled yet: both halves gate the view. */
  readonly loading = computed(
    () => this.storeContext.pending() || this.variantsResource.isLoading(),
  );
  readonly error = this.variantsResource.error;
  /** Whether the paginator is worth rendering (more than one page of results). */
  readonly hasMultiplePages = computed(() => (this.variants()?.totalPages ?? 0) > 1);

  protected readonly httpErrorMessage = httpErrorMessage;

  constructor() {
    // Resolved once per visit: a link may name the store, otherwise the operator's
    // configured store decides, and `null` leaves the global definitions in view.
    this.storeContext.resolve(this.route.snapshot.queryParamMap.get('storeId'));

    effect(() => {
      if (!this.productId()) {
        this.router.navigate(['/products/list']);
      }
    });
  }

  /**
   * Query params that carry the store to the sibling screens.
   *
   * Only when the URL named it: a store that came from the profile is re-resolved
   * by the next screen, and writing it into the link would claim a store the
   * operator never chose.
   */
  private storeQueryParams(): { storeId: string } | undefined {
    const storeId = this.storeId();
    return storeId && this.storeSource() === 'query' ? { storeId } : undefined;
  }

  addVariant(): void {
    const id = this.productId();
    if (id) {
      this.router.navigate(['/products/edit', id, 'variants', 'create'], {
        queryParams: this.storeQueryParams(),
      });
    }
  }

  editVariant(variantId: string): void {
    const id = this.productId();
    if (id) {
      this.router.navigate(['/products/edit', id, 'variants', 'edit', variantId], {
        queryParams: this.storeQueryParams(),
      });
    }
  }

  /** The profile page owns the store preference and is reachable by every role. */
  goToProfile(): void {
    this.router.navigate(['/profile']);
  }

  /** Re-runs a store resolution that failed: a failed read is not "no store set". */
  retryStore(): void {
    this.storeContext.reload();
  }

  confirmDisable(variant: ProductVariant): void {
    const dialogRef = this.dialog.open(DisableVariantDialogComponent, {
      data: { variant },
    });

    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result: boolean) => {
        const id = this.productId();
        if (result && id) {
          // Soft delete: the backend flips `enabled` to false, it does not remove the row.
          this.productVariantService
            .deleteVariant(id, variant.id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({ next: () => this.variantsResource.reload() });
        }
      });
  }

  enableVariant(variantId: string): void {
    const id = this.productId();
    if (id) {
      this.productVariantService
        .enableVariant(id, variantId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: () => this.variantsResource.reload() });
    }
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  /**
   * Narrows the list to the resolved store, or widens it back to the global
   * definitions.
   *
   * Resetting the page keeps the paginator honest: both views are different result
   * sets, so staying on the old page could land on an empty page.
   */
  onStoreScopeChange(enabled: boolean): void {
    this.storeScopeChoice.set(enabled);
    this.pageIndex.set(0);
  }

  /**
   * Flips the "show disabled" filter.
   *
   * Resetting the page keeps the paginator honest: flipping the filter changes
   * the result set, so staying on the old page could land on an empty page. The
   * signal feeds the resource params, which reloads the list.
   */
  onIncludeDisabledChange(includeDisabled: boolean): void {
    this.includeDisabled.set(includeDisabled);
    this.pageIndex.set(0);
  }

  onRetry(): void {
    this.variantsResource.reload();
  }
}
