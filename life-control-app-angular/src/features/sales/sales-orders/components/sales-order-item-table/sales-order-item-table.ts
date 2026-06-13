import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * Simplified row type for the sales order item table.
 * Mirrors the fields needed for create/edit line items.
 */
export interface ItemTableRow {
  /** Backend item ID for already-saved items. `undefined` for new unsaved rows. */
  id?: string;
  productVariantId: string;
  productVariantName: string;
  quantity: number;
  listPrice: number;
  discountApplied: number;
}

/**
 * Standalone line-items table component for sales orders.
 *
 * Displays product variant name, quantity, unit price, discount, and subtotal
 * columns. Emits granular per-row mutation events (`itemRemoved`,
 * `quantityChanged`, `listPriceChanged`, `discountChanged`) that the parent
 * component orchestrates into API calls. Editing is gated by `isDraft`, and
 * controls are disabled while the parent's `isSaving` signal is non-null.
 */
@Component({
  selector: 'app-sales-order-item-table',
  standalone: true,
  imports: [
    CurrencyPipe,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
  ],
  templateUrl: './sales-order-item-table.html',
  styleUrl: './sales-order-item-table.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SalesOrderItemTable {
  /** Current line items. */
  readonly items = input.required<ItemTableRow[]>();

  /** Whether the parent order is in Draft status (mutation guard). */
  readonly isDraft = input<boolean>(false);

  /** Index of the row currently being saved by the parent, or null if idle.
   *  Controls are disabled while any save operation is in progress. */
  readonly isSaving = input<number | null>(null);

  /** Emits the index when a row's remove button is clicked. */
  readonly itemRemoved = output<number>();

  /** Emits the row index and new value when quantity is changed. */
  readonly quantityChanged = output<{ index: number; value: number }>();

  /** Emits the row index and new value when list price is changed. */
  readonly listPriceChanged = output<{ index: number; value: number }>();

  /** Emits the row index and new value when discount is changed. */
  readonly discountChanged = output<{ index: number; value: number }>();

  /**
   * @deprecated Temporary backward-compat bridge for the parent page.
   * Will be removed in PR #2 when parent wires granular outputs directly.
   * Emits the full items array after any mutation.
   */
  readonly itemsChanged = output<ItemTableRow[]>();

  // ─── Computed ──────────────────────────────────────────
  readonly displayedColumns: string[] = [
    'productVariantName',
    'quantity',
    'listPrice',
    'discountApplied',
    'subtotal',
    'actions',
  ];

  readonly lineItemsTotal = computed(() =>
    this.items().reduce(
      (sum, item) => sum + this.rowSubtotal(item),
      0,
    ),
  );

  // ─── Mutations ─────────────────────────────────────────

  removeItem(index: number): void {
    if (!this.isDraft()) return;
    const updated = this.items().filter((_, i) => i !== index);
    this.itemRemoved.emit(index);
    this.itemsChanged.emit(updated);
  }

  onQuantityChange(index: number, value: number): void {
    if (!this.isDraft()) return;
    const newValue = value || 1;
    const updated = this.items().map((item, i) =>
      i === index ? { ...item, quantity: newValue } : item,
    );
    this.quantityChanged.emit({ index, value: newValue });
    this.itemsChanged.emit(updated);
  }

  onListPriceChange(index: number, value: number): void {
    if (!this.isDraft()) return;
    const newValue = value || 0;
    const updated = this.items().map((item, i) =>
      i === index ? { ...item, listPrice: newValue } : item,
    );
    this.listPriceChanged.emit({ index, value: newValue });
    this.itemsChanged.emit(updated);
  }

  onDiscountChange(index: number, value: number): void {
    if (!this.isDraft()) return;
    const newValue = value || 0;
    const updated = this.items().map((item, i) =>
      i === index ? { ...item, discountApplied: newValue } : item,
    );
    this.discountChanged.emit({ index, value: newValue });
    this.itemsChanged.emit(updated);
  }

  /** Subtotal per row: (quantity × listPrice) − discountApplied. */
  rowSubtotal(row: ItemTableRow): number {
    return row.quantity * row.listPrice - row.discountApplied;
  }
}
