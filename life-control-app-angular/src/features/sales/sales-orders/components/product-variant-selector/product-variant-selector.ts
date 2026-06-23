import {
  ChangeDetectionStrategy,
  Component,
  ViewChild,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
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
import {
  MatAutocompleteModule,
  MatAutocompleteTrigger,
} from '@angular/material/autocomplete';
import type {
  ProductVariantOption,
  Page,
} from '../../models/sales-order.models';

/**
 * Single-step variant search via barcode scan or text input.
 *
 * Calls GET /api/product-variants/search?q=&storeId= with a 300 ms debounce.
 * Results are scoped to the current order's store. The user scans a barcode or
 * types a partial query, and the autocomplete shows matching variants enriched
 * with the product name.
 *
 * Emits the selected {@link ProductVariantOption} via `variantSelected`.
 */
@Component({
  selector: 'app-product-variant-selector',
  standalone: true,
  imports: [
    CurrencyPipe,
    MatFormFieldModule,
    MatInputModule,
    MatAutocompleteModule,
  ],
  templateUrl: './product-variant-selector.html',
  styleUrl: './product-variant-selector.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductVariantSelector {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private destroyRef = inject(DestroyRef);

  /** Store ID to scope the search to the current order's store. */
  readonly storeId = input<string | null>(null);

  /** Scan mode toggle. When true, Enter triggers an immediate barcode lookup. */
  readonly scanMode = input(true);

  /** Reference to the autocomplete trigger for programmatic panel opening. */
  @ViewChild(MatAutocompleteTrigger)
  private autocompleteTrigger?: MatAutocompleteTrigger;

  /** Emits when the user picks a variant. */
  readonly variantSelected = output<ProductVariantOption>();

  /** Current search query typed / scanned by the user. */
  readonly searchQuery = signal('');

  // ── Internal state ───────────────────────────────────────

  /** Debounced query used to trigger the actual HTTP call. */
  private readonly _debouncedSearch = signal('');

  /** Search results from the API. */
  private readonly _variants = signal<ProductVariantOption[]>([]);

  /** Expose as readonly for the template. */
  readonly filteredVariants = computed(() => this._variants());

  constructor() {
    // Debounce: wait 300 ms after the user stops typing before searching.
    // Skipped in scan mode — Enter triggers the HTTP call directly.
    effect((onCleanup) => {
      if (this.scanMode()) return;
      const query = this.searchQuery();
      const timer = setTimeout(() => this._debouncedSearch.set(query), 300);
      onCleanup(() => clearTimeout(timer));
    });

    // Search: fire the API call when debounced query or store ID changes.
    effect(() => {
      const query = this._debouncedSearch();
      const storeId = this.storeId();

      if (!query || query.trim().length < 2 || !storeId) {
        this._variants.set([]);
        return;
      }

      const params = { q: query.trim(), storeId, page: '0', size: '20' };
      this.http
        .get<Page<ProductVariantOption>>(
          `${this.configService.apiUrl}/product-variants/search`,
          { params },
        )
        .pipe(
          map((page) => page.content),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (variants) => this._variants.set(variants),
          error: () => this._variants.set([]),
        });
    });
  }

  /** Called on every keystroke / scan in the autocomplete input. */
  onSearchChange(value: string): void {
    this.searchQuery.set(value);
  }

  /** Called when the user selects a variant from the autocomplete dropdown. */
  onVariantSelect(variant: ProductVariantOption): void {
    this.variantSelected.emit(variant);
    // Clear the input and results so the user can scan the next barcode.
    this.searchQuery.set('');
    this._variants.set([]);
  }

  /**
   * Enter key handler for scan mode.
   * Fires a direct GET request (no debounce) and either auto-adds
   * on a unique match with stock, or opens the autocomplete panel
   * for manual selection when results are ambiguous.
   */
  onSearchEnter(event: Event): void {
    event.preventDefault();
    if (!this.scanMode()) return;

    const query = this.searchQuery().trim();
    const storeId = this.storeId();
    if (!query || !storeId) return;

    const params = { q: query, storeId, page: '0', size: '20' };
    this.http
      .get<Page<ProductVariantOption>>(
        `${this.configService.apiUrl}/product-variants/search`,
        { params },
      )
      .pipe(
        map((page) => page.content),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (variants) => {
          if (variants.length === 1 && variants[0].stock > 0) {
            // Unique match with stock → auto-add
            this.variantSelected.emit(variants[0]);
            this.searchQuery.set('');
            this._variants.set([]);
          } else {
            // 0, >1, or zero stock → show in autocomplete for manual selection
            this._variants.set(variants);
            this.autocompleteTrigger?.openPanel();
          }
        },
        error: () => {
          this._variants.set([]);
          this.autocompleteTrigger?.openPanel();
        },
      });
  }
}
