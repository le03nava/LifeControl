import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ProductService } from '../../data/product.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { ProductVariant } from '../../models/product-variant.models';
import { ProductVariantDialog } from '../../components/product-variant-dialog/product-variant-dialog';
import { ProductVariantStoreStock } from '../../components/product-variant-store-stock/product-variant-store-stock';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import type { UnsavedChangesAware } from '@core/guards/unsaved-changes.guard';

/**
 * Store-scoped editor for the per-store stock and prices of one product variant.
 *
 * The page keeps the `app-product-variant-store-stock` panel — the sales
 * principal's only route to it — and delegates the **global definition** edit to
 * {@link ProductVariantDialog} (D21). It is edit-only: the create URL redirects into
 * the workspace (D19).
 *
 * The route carries `unsavedChangesGuard`, so the page implements
 * {@link UnsavedChangesAware} from the panel's dirty flag, which is the only state
 * this page can lose.
 */
@Component({
  selector: 'app-product-variant-edit',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, ErrorBanner, ProductVariantStoreStock, PageHeader],
  templateUrl: './product-variant-edit.html',
  styleUrl: './product-variant-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductVariantEdit implements OnInit, UnsavedChangesAware {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly productService = inject(ProductService);
  private readonly productVariantService = inject(ProductVariantService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly productId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  /** The variant being edited. Mandatory in practice: the create URL redirects away. */
  readonly variantId = signal<string | null>(this.route.snapshot.paramMap.get('variantId'));

  /** The variant loaded from the API, or `null` until the read settles. */
  readonly loadedVariant = signal<ProductVariant | null>(null);

  /**
   * The **stored** barcode of the variant being edited, or `null` until it is loaded.
   *
   * The per-store panel keys its row read on it instead of the dialog's live value:
   * a definition edit is not saved until the dialog closes with a saved entity, and
   * searching for a barcode the server does not have would answer "this store has no
   * row".
   */
  readonly loadedBarCode = signal<string | null>(null);

  /** Whether the per-store panel holds unsaved edits; the only state this page owns. */
  readonly storeStockDirty = signal(false);

  /** The owning product, loaded only to name it in the header. */
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

  protected readonly httpErrorMessage = httpErrorMessage;

  readonly headerSubtitle = computed(() => {
    const sku = this.product()?.sku;
    const scope = 'Stock y precios de esta tienda';
    return sku ? `SKU: ${sku} — ${scope}` : scope;
  });

  readonly generalError = signal<string | null>(null);

  constructor() {
    // A product id is mandatory: without it there is no product to attach the
    // definition to, so fail closed instead of rendering a broken page.
    effect(() => {
      if (!this.productId()) {
        this.router.navigate(['/products/list']);
      }
    });
  }

  ngOnInit(): void {
    const variantId = this.variantId();
    const productId = this.productId();
    if (variantId && productId) {
      this.loadVariant(productId, variantId);
    }
  }

  /**
   * Opens the global-definition dialog for the loaded variant and refreshes what
   * this page derives from it (D21): the stored barcode keys the panel's row read,
   * and the loaded variant feeds the next open.
   */
  openDefinitionDialog(): void {
    const variant = this.loadedVariant();
    const productId = this.productId();
    if (!variant || !productId) {
      return;
    }

    this.dialog
      .open(ProductVariantDialog, {
        data: { productId, variant },
        width: '560px',
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((saved) => {
        if (saved) {
          this.loadedVariant.set(saved);
          this.loadedBarCode.set(saved.barCode);
        }
      });
  }

  /** Mirrors the per-store panel's dirty state into the page's guard. */
  onStoreStockDirtyChange(dirty: boolean): void {
    this.storeStockDirty.set(dirty);
  }

  /** Exposed to `unsavedChangesGuard`: only the per-store panel holds page state. */
  hasUnsavedChanges(): boolean {
    return this.storeStockDirty();
  }

  private loadVariant(productId: string, variantId: string): void {
    this.productVariantService
      .getVariantById(productId, variantId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (variant) => {
          this.loadedVariant.set(variant);
          this.loadedBarCode.set(variant.barCode);
        },
        error: (err: HttpErrorResponse) => this.handleLoadError(err),
      });
  }

  private handleLoadError(err: HttpErrorResponse): void {
    this.generalError.set(httpErrorMessage(err));
  }
}
