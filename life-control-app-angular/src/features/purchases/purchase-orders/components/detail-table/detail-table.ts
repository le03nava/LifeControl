import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * Simplified row type for the detail table.
 * Mirrors the fields needed for create/edit line items.
 */
export interface DetailTableRow {
  /** Backend detail ID for already-saved items. `undefined` for new unsaved rows. */
  id?: string;
  productId: string;
  productName: string;
  quantity: number;
  unitPrice: number;
}

/**
 * Standalone line-items table component.
 *
 * Displays product, quantity, unit price, and subtotal columns.
 * Includes an "add row" form and per-row delete buttons guarded by `isDraft`.
 *
 * Covers spec Requirement 7, scenarios 7.1-7.5.
 */
@Component({
  selector: 'app-detail-table',
  standalone: true,
  imports: [
    CurrencyPipe,
    MatTableModule,
    MatFormFieldModule,
    MatSelectModule,
    MatInputModule,
    MatAutocompleteModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
  ],
  templateUrl: './detail-table.html',
  styleUrl: './detail-table.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetailTable {
  /** Current line items. */
  readonly items = input.required<DetailTableRow[]>();

  /** Whether the parent order is in Draft status (mutation guard). */
  readonly isDraft = input<boolean>(false);

  /** Available products for the add-row product autocomplete. */
  readonly availableProducts = input.required<{ id: string; name: string; sku: string }[]>();

  /** Emits the full updated items array after any add or remove. */
  readonly itemsChanged = output<DetailTableRow[]>();

  // ─── Add-row form state ────────────────────────────────
  readonly newProductId = signal('');
  readonly newQuantity = signal(1);
  readonly newUnitPrice = signal(0);
  readonly searchQuery = signal('');

  /** Products filtered by the local search query (client-side on the already supplier-filtered list). */
  readonly filteredProducts = computed(() => {
    const query = this.searchQuery().toLowerCase();
    const products = this.availableProducts();
    if (!query) return products;
    return products.filter(
      (p) => p.name.toLowerCase().includes(query) || p.sku.toLowerCase().includes(query),
    );
  });

  // ─── Computed ──────────────────────────────────────────
  readonly displayedColumns: string[] = [
    'productName',
    'quantity',
    'unitPrice',
    'subtotal',
    'actions',
  ];

  readonly lineItemsTotal = computed(() =>
    this.items().reduce((sum, item) => sum + item.quantity * item.unitPrice, 0),
  );

  readonly canAddItem = computed(
    () =>
      this.isDraft() &&
      this.newProductId() !== '' &&
      this.newQuantity() > 0 &&
      this.newUnitPrice() > 0,
  );

  /** Whether any existing row has a non-positive quantity or unit price. */
  readonly hasInvalidRows = computed(() => this.items().some((item) => this.isRowInvalid(item)));

  isRowInvalid(row: DetailTableRow): boolean {
    return row.quantity <= 0 || row.unitPrice <= 0;
  }

  // ─── Mutations ─────────────────────────────────────────

  addItem(): void {
    if (!this.isDraft() || !this.newProductId() || this.newUnitPrice() <= 0) {
      return;
    }

    const product = this.availableProducts().find((p) => p.id === this.newProductId());
    if (!product) {
      return;
    }

    const newRow: DetailTableRow = {
      productId: this.newProductId(),
      productName: product.name,
      quantity: this.newQuantity(),
      unitPrice: this.newUnitPrice(),
    };

    this.itemsChanged.emit([...this.items(), newRow]);

    // Reset form
    this.newProductId.set('');
    this.newQuantity.set(1);
    this.newUnitPrice.set(0);
  }

  removeItem(index: number): void {
    if (!this.isDraft()) {
      return;
    }
    const updated = this.items().filter((_, i) => i !== index);
    this.itemsChanged.emit(updated);
  }

  onNewProductChange(value: string): void {
    this.newProductId.set(value);
    const product = this.availableProducts().find((p) => p.id === value);
    this.searchQuery.set(product?.name ?? '');
  }

  onSearchChange(value: string): void {
    this.searchQuery.set(value);
    // Any manual edit invalidates the previous selection, otherwise "+" would
    // add a product that no longer matches what the field shows.
    this.newProductId.set('');
  }

  onNewQuantityChange(value: number): void {
    this.newQuantity.set(value || 1);
  }

  onNewUnitPriceChange(value: number): void {
    this.newUnitPrice.set(value || 0);
  }

  /** Inline-edit the quantity of an already-saved row. */
  onRowQuantityChange(index: number, value: number): void {
    if (!this.isDraft()) {
      return;
    }
    this.patchRow(index, { quantity: Number.isFinite(value) ? value : 0 });
  }

  /** Inline-edit the unit price of an already-saved row. */
  onRowUnitPriceChange(index: number, value: number): void {
    if (!this.isDraft()) {
      return;
    }
    this.patchRow(index, { unitPrice: Number.isFinite(value) ? value : 0 });
  }

  private patchRow(index: number, patch: Partial<DetailTableRow>): void {
    this.itemsChanged.emit(
      this.items().map((item, i) => (i === index ? { ...item, ...patch } : item)),
    );
  }

  /** Subtotal per row (quantity × unitPrice). */
  rowSubtotal(row: DetailTableRow): number {
    return row.quantity * row.unitPrice;
  }
}
