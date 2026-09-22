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
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs/operators';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ErrorBanner } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { NotificationService } from '@shared/data/notification';
import { ProductVariantService } from '../../data/product-variant.service';
import { VariantStoreContext } from '../../data/variant-store-context.service';
import {
  ProductVariantSearchResult,
  ProductVariantStoreStockRequest,
} from '../../models/product-variant.models';

/**
 * Typed control map of the per-store stock form.
 *
 * All three controls are nullable and may start empty on purpose: an empty field is
 * sent as an **absent** key, which is the only thing the backend reads as "keep the
 * stored value". Zero is a real value — an empty stock is a legitimate stock — and
 * travels as `0`, so "unset" and "zero" never collapse into each other.
 */
export interface ProductVariantStoreStockControl {
  listPrice: FormControl<number | null>;
  costPrice: FormControl<number | null>;
  stock: FormControl<number | null>;
}

/**
 * Per-store stock and prices of ONE variant definition.
 *
 * The store arrives as a resolved id through {@link VariantStoreContext}
 * (`?storeId=` -> profile -> `null`, fail closed), never as a picker, and the five
 * level chain is not needed: the backend derives and verifies it from the store id.
 *
 * The row is **read by searching the variant's own barcode inside the store**. There
 * is no `GET` for a single store row, and the store-scoped list is an inner join that
 * cannot report a variant with no row yet; the search's barcode predicate is an
 * equality on a globally unique value, so it answers exactly "the row, or none" with
 * no pagination ambiguity. No backend contract change was needed for that.
 *
 * `null` row is a normal state, not an error: the first save creates it through the
 * backend's insert-if-absent.
 *
 * Presentational in the same sense as `ProductVariantForm` — it owns its own HTTP and
 * state but no routing beyond the two calls to action — and it reports its dirty flag
 * so the page's unsaved-changes guard can see edits made here.
 */
@Component({
  selector: 'app-product-variant-store-stock',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, ErrorBanner],
  templateUrl: './product-variant-store-stock.html',
  styleUrl: './product-variant-store-stock.scss',
  // Per component, not at the root: this panel owns its own store resolution.
  providers: [VariantStoreContext],
})
export class ProductVariantStoreStock {
  /** The variant whose per-store row is edited. */
  variantId = input.required<string>();

  /**
   * The variant's **stored** barcode.
   *
   * The row read keys on it, so the page passes the last value loaded from the API
   * rather than the live form value: an edited barcode is not saved yet, and reading
   * the row of a barcode the server does not have would report "no row".
   */
  barCode = input.required<string>();

  /** Mirrors the panel's dirty state so the page's guard can account for it. */
  dirtyChange = output<boolean>();

  private readonly variantService = inject(ProductVariantService);
  private readonly storeContext = inject(VariantStoreContext);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /** The store the resolution produced, or `null` when there is none. */
  readonly storeId = this.storeContext.storeId;
  readonly storePending = this.storeContext.pending;
  readonly storeError = this.storeContext.storeError;
  readonly storeUnconfigured = this.storeContext.unconfigured;
  readonly storeSource = this.storeContext.source;

  readonly storeForm = signal<FormGroup<ProductVariantStoreStockControl>>(this.createForm());

  /** Mirrors the form's dirty flag as a signal: control state is not reactive. */
  private readonly dirty = signal(false);
  readonly storeFormDirty = this.dirty.asReadonly();

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  private readonly storeRowResource = rxResource({
    params: () => {
      // No read until the store resolution settles, so the panel issues one request
      // for one row instead of one without a store and another with it.
      if (this.storeContext.pending()) {
        return undefined;
      }
      const storeId = this.storeId();
      if (!storeId) {
        return undefined;
      }
      return { barCode: this.barCode(), storeId };
    },
    stream: ({ params }) =>
      this.variantService.searchVariants(params.barCode, params.storeId, 0, 1),
  });

  /**
   * The variant's row in the resolved store, `null` when the store has none, or
   * `undefined` while it is still unknown. A guarded resource read.
   */
  readonly storeRow = computed<ProductVariantSearchResult | null | undefined>(() => {
    if (!this.storeRowResource.hasValue()) {
      return undefined;
    }
    return this.storeRowResource.value().content[0] ?? null;
  });

  /** The row read failure, or `null`. Distinct from "this store has no row yet". */
  readonly readError = computed<string | null>(() => {
    const error = this.storeRowResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  constructor() {
    this.storeContext.resolve(this.route.snapshot.queryParamMap.get('storeId'));

    // Only operator edits reach this: the seed below patches with `emitEvent: false`.
    this.storeForm()
      .valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (!this.dirty()) {
          this.dirty.set(true);
          this.dirtyChange.emit(true);
        }
      });

    // Seeds the fields from the loaded row. `emitEvent: false` matters twice: a
    // programmatic load is not an operator edit, so it must not mark the form dirty nor
    // arm the guard above; and the `dirty` check keeps a reload from overwriting an
    // edit that is already in progress.
    effect(() => {
      const row = this.storeRow();
      if (row === undefined || this.dirty()) {
        return;
      }
      this.storeForm().patchValue(
        {
          listPrice: row?.listPrice ?? null,
          costPrice: row?.costPrice ?? null,
          stock: row?.stock ?? null,
        },
        { emitEvent: false },
      );
    });
  }

  private createForm(): FormGroup<ProductVariantStoreStockControl> {
    return new FormGroup<ProductVariantStoreStockControl>({
      listPrice: new FormControl<number | null>(null, [Validators.min(0)]),
      costPrice: new FormControl<number | null>(null, [Validators.min(0)]),
      stock: new FormControl<number | null>(null, [Validators.min(0)]),
    });
  }

  onSave(): void {
    const storeId = this.storeId();
    const form = this.storeForm();
    if (!storeId) {
      return;
    }
    // One source of truth for "there is something to write": the same signal the save
    // button and the page's unsaved-changes guard read. `form.pristine` is not used
    // here because a programmatic patch leaves it true while `valueChanges` — and
    // therefore the signal — reports a change, so the two would disagree and the page
    // could hold a pending edit the save refused to write.
    if (!this.dirty()) {
      return;
    }
    if (form.invalid) {
      form.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    this.saveError.set(null);

    this.variantService
      .upsertStoreStock(this.variantId(), storeId, this.buildRequest(form))
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: () => {
          // A successful write is not a pending edit: the route guard must not fire,
          // and clearing the flag also lets the reload below reseed the fields.
          this.markPristine();
          this.notifications.showSuccess('Stock y precios de la tienda guardados.');
          this.storeRowResource.reload();
        },
        error: (err: HttpErrorResponse) => this.saveError.set(httpErrorMessage(err)),
      });
  }

  /** Re-runs a store resolution that failed: a failed read is not "no store set". */
  retryStore(): void {
    this.storeContext.reload();
  }

  /** Re-runs a row read that failed. */
  retryRead(): void {
    this.storeRowResource.reload();
  }

  /** The profile page owns the store preference and is reachable by every role. */
  goToProfile(): void {
    this.router.navigate(['/profile']);
  }

  /**
   * Builds the upsert body out of the fields the operator filled in.
   *
   * An empty field becomes an **absent** key, never `null` and never `0`: the backend
   * keeps the stored value when a field is absent (`if (request.x() != null)`), so
   * sending nothing is the only way to say "leave it alone". An all-empty body is a
   * valid request — the backend inserts the row and changes nothing.
   */
  private buildRequest(
    form: FormGroup<ProductVariantStoreStockControl>,
  ): ProductVariantStoreStockRequest {
    const { listPrice, costPrice, stock } = form.getRawValue();
    const request: ProductVariantStoreStockRequest = {};
    if (listPrice !== null) {
      request.listPrice = listPrice;
    }
    if (costPrice !== null) {
      request.costPrice = costPrice;
    }
    if (stock !== null) {
      request.stock = stock;
    }
    return request;
  }

  /** Clears the dirty state after a successful write, keeping both readers in step. */
  private markPristine(): void {
    this.storeForm().markAsPristine();
    if (this.dirty()) {
      this.dirty.set(false);
      this.dirtyChange.emit(false);
    }
  }
}
