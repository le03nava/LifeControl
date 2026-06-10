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
import type { ProductVariantOption } from '../../models/sales-order.models';

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
 * columns. Includes a ProductVariantSelector for adding rows and per-row
 * quantity/price/discount editing with guards based on `isDraft`.
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

  /** Emits the full updated items array after any add, edit, or remove. */
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

  /** Called by ProductVariantSelector when a variant is selected. */
  onVariantSelected(variant: ProductVariantOption): void {
    if (!this.isDraft()) return;

    const newRow: ItemTableRow = {
      productVariantId: variant.id,
      productVariantName: variant.variantName,
      quantity: 1,
      listPrice: variant.listPrice,
      discountApplied: 0,
    };

    this.itemsChanged.emit([...this.items(), newRow]);
  }

  removeItem(index: number): void {
    if (!this.isDraft()) return;
    const updated = this.items().filter((_, i) => i !== index);
    this.itemsChanged.emit(updated);
  }

  onQuantityChange(index: number, value: number): void {
    if (!this.isDraft()) return;
    const updated = this.items().map((item, i) =>
      i === index ? { ...item, quantity: value || 1 } : item,
    );
    this.itemsChanged.emit(updated);
  }

  onListPriceChange(index: number, value: number): void {
    if (!this.isDraft()) return;
    const updated = this.items().map((item, i) =>
      i === index ? { ...item, listPrice: value || 0 } : item,
    );
    this.itemsChanged.emit(updated);
  }

  onDiscountChange(index: number, value: number): void {
    if (!this.isDraft()) return;
    const updated = this.items().map((item, i) =>
      i === index ? { ...item, discountApplied: value || 0 } : item,
    );
    this.itemsChanged.emit(updated);
  }

  /** Subtotal per row: (quantity × listPrice) − discountApplied. */
  rowSubtotal(row: ItemTableRow): number {
    return row.quantity * row.listPrice - row.discountApplied;
  }
}
