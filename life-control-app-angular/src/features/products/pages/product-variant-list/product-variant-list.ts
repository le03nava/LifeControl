import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { httpErrorMessage } from '@shared/data';
import { ProductVariantService } from '../../data/product-variant.service';
import { ProductVariantDialog } from '../../components/product-variant-dialog/product-variant-dialog';
import { VariantStoreContext } from '../../data/variant-store-context.service';
import { ProductVariant } from '../../models/product-variant.models';
import { DisableVariantDialogComponent } from '../../ui/disable-variant-dialog/disable-variant-dialog';

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
 *
 * Rendered as the **Variantes** tab of the product workspace: the shell owns the
 * page header and passes `productId` as an input, and reports the loaded count
 * upward through `countChange`. This component still owns the store resolution
 * because `?storeId=` lives on the workspace route the shell renders.
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
  ],
  templateUrl: './product-variant-list.html',
  styleUrl: './product-variant-list.scss',
  // Per screen, not at the root: this tab owns its own store resolution.
  providers: [VariantStoreContext],
})
export class ProductVariantList {
  /** The product whose variants are shown. Owned by the workspace shell. */
  readonly productId = input.required<string>();

  /**
   * The variant count of the current view, emitted on every successful read.
   *
   * The current view's `totalElements`, not the loaded page: a store-scoped view
   * counts that store's rows, and a paginated view must not report one page.
   */
  readonly countChange = output<number>();

  private readonly productVariantService = inject(ProductVariantService);
  private readonly storeContext = inject(VariantStoreContext);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

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
    stream: ({ params }) =>
      this.productVariantService.getVariants(
        params.productId,
        params.storeId ?? undefined,
        params.page,
        params.size,
        // The store-scoped branch ignores the opt-in server-side, so it is never sent with a store.
        params.storeId ? false : params.includeDisabled,
      ),
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

    // Reports the count of whichever view resolves, including after a reload.
    effect(() => {
      const page = this.variants();
      if (page) {
        this.countChange.emit(page.totalElements);
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

  /**
   * Opens the create dialog.
   *
   * A variant created while the store-scoped view is active will not appear in that
   * view: the store branch only lists variants that already have a row for that
   * store, and a brand-new definition has none yet.
   */
  addVariant(): void {
    this.openVariantDialog();
  }

  /**
   * Edits the variant, branching on the view (D18).
   *
   * With a store in play it keeps navigating to the store-scoped editor: that page
   * hosts the per-store stock/prices panel and is the sales principal's only route
   * to it. Without a store the dialog edits the global definition.
   */
  editVariant(row: ProductVariant): void {
    if (this.storeScoped()) {
      this.router.navigate(['/products/edit', this.productId(), 'variants', 'edit', row.id], {
        queryParams: this.storeQueryParams(),
      });
      return;
    }

    this.openVariantDialog(row);
  }

  /**
   * Opens the create/edit dialog and reloads the list only when it closed with a
   * saved entity (D22: `null` on cancel or dismissal). The dialog owns the write and
   * stays open with its own error banner on failure, so no error handling here.
   */
  private openVariantDialog(variant?: ProductVariant): void {
    this.dialog
      .open(ProductVariantDialog, {
        data: { productId: this.productId(), variant },
        width: '560px',
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((saved) => {
        if (saved) {
          this.variantsResource.reload();
        }
      });
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
        if (result) {
          // Soft delete: the backend flips `enabled` to false, it does not remove the row.
          this.productVariantService
            .deleteVariant(this.productId(), variant.id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({ next: () => this.variantsResource.reload() });
        }
      });
  }

  enableVariant(variantId: string): void {
    this.productVariantService
      .enableVariant(this.productId(), variantId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: () => this.variantsResource.reload() });
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
