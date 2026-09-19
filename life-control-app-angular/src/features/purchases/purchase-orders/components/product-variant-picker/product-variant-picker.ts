import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  model,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CurrencyPipe } from '@angular/common';
import { ProductService } from '@features/products/data/product.service';
import type { ProductVariant } from '@features/products/models/product-variant.models';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';

/**
 * Page size requested for one product's variants in a single store. A product
 * realistically has few variants per store; variants beyond this page are
 * surfaced as a quiet hint instead of being silently dropped.
 */
const VARIANT_PAGE_SIZE = 50;

/**
 * Variant picker for one purchase order line.
 *
 * Loads `GET /api/products/{productId}/variants?storeId=<uuid>` once both the
 * product and the store are known, and renders `variantName` next to `sku` and
 * `costPrice` so two variants of the same product stay distinguishable.
 *
 * The selected variant id is a two-way `model` so the parent can reset it after
 * adding a line; `variantSelected` carries the whole variant, because the parent
 * needs `costPrice` (unit-price pre-fill) and `variantName` (row label).
 *
 * This is deliberately not the sales-side `product-variant-selector`: that one
 * is a table-level barcode/text scan box with no product filter and no row
 * context.
 */
@Component({
  selector: 'app-product-variant-picker',
  standalone: true,
  imports: [CurrencyPipe, MatFormFieldModule, MatSelectModule],
  templateUrl: './product-variant-picker.html',
  styleUrl: './product-variant-picker.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductVariantPicker {
  private readonly productService = inject(ProductService);
  private readonly destroyRef = inject(DestroyRef);

  /** Product whose variants are offered. */
  readonly productId = input<string | null>(null);

  /** Store that scopes the variants (the order's store). */
  readonly storeId = input<string | null>(null);

  /** Blocks interaction (for example when the order is no longer a draft). */
  readonly disabled = input(false);

  /** Selected variant id. Two-way so the parent can reset it. */
  readonly variantId = model<string | null>(null);

  /** Emits the whole variant the user picked. */
  readonly variantSelected = output<ProductVariant>();

  private readonly _variants = signal<ProductVariant[]>([]);
  private readonly _loading = signal(false);
  private readonly _loadFailed = signal(false);
  private readonly _totalElements = signal(0);

  readonly variants = this._variants.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly loadFailed = this._loadFailed.asReadonly();

  /** True while no product or no store is available: nothing can be requested. */
  readonly missingContext = computed(() => !this.productId() || !this.storeId());

  readonly controlDisabled = computed(() => this.disabled() || this.missingContext());

  /** Loaded successfully and the product genuinely has no variant in this store. */
  readonly isEmpty = computed(
    () =>
      !this.missingContext() &&
      !this.loading() &&
      !this.loadFailed() &&
      this._variants().length === 0,
  );

  /** The store has more variants than the requested page. */
  readonly hasMore = computed(() => this._totalElements() > this._variants().length);

  constructor() {
    // Reload whenever the product or the store changes. A scope change
    // invalidates everything loaded for the previous product/store, so the
    // options, the page total, the failure flag and the two-way selection are
    // cleared up front (before the new request resolves). Otherwise a variant
    // from the old scope would stay selectable and could be added to a line the
    // backend then rejects with a 404.
    effect(() => {
      const productId = this.productId();
      const storeId = this.storeId();

      this._variants.set([]);
      this._totalElements.set(0);
      this._loadFailed.set(false);
      this.variantId.set(null);

      if (!productId || !storeId) {
        this._loading.set(false);
        return;
      }

      this._loading.set(true);

      this.productService
        .getProductVariants(productId, storeId, 0, VARIANT_PAGE_SIZE)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (page) => {
            this._variants.set(page.content);
            this._totalElements.set(page.totalElements);
            this._loading.set(false);
          },
          error: () => {
            this._variants.set([]);
            this._totalElements.set(0);
            this._loading.set(false);
            this._loadFailed.set(true);
          },
        });
    });
  }

  /** Handles the select change: keeps the model and the parent in sync. */
  onSelectionChange(id: string | null): void {
    this.variantId.set(id);

    const variant = this._variants().find((candidate) => candidate.id === id);
    if (variant) {
      this.variantSelected.emit(variant);
    }
  }
}
