import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CurrencyPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { map } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatSelectModule } from '@angular/material/select';
import type {
  ProductOption,
  ProductVariantOption,
  Page,
} from '../../models/sales-order.models';

/**
 * Two-step product → variant selector component.
 *
 * Step 1: Autocomplete search for products via `GET /api/products?search=`.
 * Step 2: After a product is selected, loads variants via
 *         `GET /api/products/{productId}/variants` and renders a dropdown.
 *
 * Emits the selected variant via `variantSelected`.
 */
@Component({
  selector: 'app-product-variant-selector',
  standalone: true,
  imports: [
    CurrencyPipe,
    MatFormFieldModule,
    MatInputModule,
    MatAutocompleteModule,
    MatSelectModule,
  ],
  templateUrl: './product-variant-selector.html',
  styleUrl: './product-variant-selector.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductVariantSelector {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private destroyRef = inject(DestroyRef);

  /** Emits when the user picks a variant. */
  readonly variantSelected = output<ProductVariantOption>();

  // ── Step 1: Product search ─────────────────────────────
  readonly productSearchQuery = signal('');

  readonly _products = signal<ProductOption[]>([]);
  readonly filteredProducts = computed(() => this._products());

  // ── Step 2: Variant dropdown ───────────────────────────
  readonly variants = signal<ProductVariantOption[]>([]);
  readonly selectedProduct = signal<ProductOption | null>(null);

  /** Triggered by typing in the product search input. */
  onProductSearch(value: string): void {
    this.productSearchQuery.set(value);

    if (!value || value.trim().length < 2) {
      this._products.set([]);
      return;
    }

    const params = { search: value.trim(), page: '0', size: '20' };
    this.http
      .get<Page<ProductOption>>(`${this.configService.apiUrl}/products`, { params })
      .pipe(
        map((page) => page.content),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (products) => this._products.set(products),
        error: () => this._products.set([]),
      });
  }

  /** Called when the user selects a product from the autocomplete. */
  onProductSelect(product: ProductOption | null): void {
    this.selectedProduct.set(product);

    if (!product) {
      this.variants.set([]);
      return;
    }

    this.productSearchQuery.set(product.name);
    this._products.set([]);

    this.http
      .get<Page<ProductVariantOption>>(
        `${this.configService.apiUrl}/products/${product.id}/variants`,
        { params: { page: '0', size: '50' } },
      )
      .pipe(
        map((page) => page.content),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (v) => this.variants.set(v),
        error: () => this.variants.set([]),
      });
  }

  /** Called when the user selects a variant from the dropdown. */
  onVariantSelect(variant: ProductVariantOption): void {
    this.variantSelected.emit(variant);
    this.variants.set([]);
    this.selectedProduct.set(null);
    this.productSearchQuery.set('');
  }
}
