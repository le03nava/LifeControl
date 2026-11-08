import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MoneyPipe } from '@shared/pipes/money.pipe';

/**
 * Simplified row type for the sales order item table.
 *
 * Mirrors the fields needed for create/edit line items **and** the parts the server states for a line that already
 * exists (`V81`'s `subtotal`, `V82`'s `taxAmount`, `V85`'s `finalPrice`). The four money fields are optional on
 * purpose: an unsaved row has no server parts, and the table says so instead of presenting its own arithmetic as
 * the server's.
 */
export interface ItemTableRow {
  /** Backend item ID for already-saved items. `undefined` for new unsaved rows. */
  id?: string;
  productVariantId: string;
  productVariantName: string;
  quantity: number;
  listPrice: number;
  discountApplied: number;
  /** `quantity × listPrice` as the server derived it; absent on a row the server has not answered for. */
  subtotal?: number | null;
  /** The tax the server computed for the line; absent on a row the server has not answered for. */
  taxAmount?: number | null;
  /** The line total `subtotal - discountApplied + taxAmount` as `V85` derived it. */
  finalPrice?: number | null;
  /** The platform tax code the line states, when it states one. */
  taxCode?: string | null;
  /** The rate the server resolved for the line's document date. */
  taxRate?: number | null;
}

/** Shared empty set, so the default input value stays referentially stable. */
const NO_ROWS_SAVING: ReadonlySet<string> = new Set<string>();

/**
 * Standalone line-items table component for sales orders.
 *
 * Displays product variant name, quantity, unit price, discount, and the line's own parts — the server's
 * `subtotal`, `taxAmount` and `finalPrice` when it stated them, a named local estimate when it did not.
 * Emits granular per-row mutation events (`itemRemoved`,
 * `quantityChanged`, `listPriceChanged`, `discountChanged`) that the parent
 * component orchestrates into API calls. Editing is gated by `isModifiable`, the page's own
 * derivation of the backend rule (only a terminal status closes the order); controls are
 * disabled while the parent's `isSaving` signal is non-null.
 */
@Component({
  selector: 'app-sales-order-item-table',
  standalone: true,
  imports: [
    MoneyPipe,
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

  /**
   * The document's currency, stated by the sale itself.
   *
   * Required in practice and defaulted to the empty string only so that a caller which forgets it renders
   * «(no currency)» instead of a currency nobody chose — see {@link MoneyPipe}.
   */
  readonly currency = input<string>('');

  /**
   * The document total as the server stated it, or `null` while the order has none yet.
   *
   * The rest of the document's breakdown (`subtotal`, `discountTotal`, `taxTotal`, `roundingAdjustment`) is not
   * re-derived here: the client shows the numbers the server answered with, and only the summary falls back to a
   * local sum — labelled as such — when there is no server total to show.
   */
  readonly documentTotal = input<number | null>(null);

  /** Whether the parent order is in Draft status (mutation guard). */
  readonly isModifiable = input<boolean>(false);

  /**
   * Row ids with a write in flight.
   *
   * Scoped per row on purpose: disabling the whole table on every mutation reads
   * as a hang and blocks the operator's next scan for no gain. Keyed by id rather
   * than by index so that a row is never re-enabled while its own write is still
   * open — the local row has not absorbed that write yet, and editing it from the
   * stale copy would send the pre-edit value back to the server.
   */
  readonly savingRowIds = input<ReadonlySet<string>>(NO_ROWS_SAVING);

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
    'taxAmount',
    'finalPrice',
    'actions',
  ];

  /**
   * Whether the server stated this row's parts.
   *
   * `subtotal` is the marker and not `id`: a row can be saved and still be a local copy while its own write is in
   * flight, and the parts the table shows are whatever the last server answer carried.
   */
  hasServerParts(row: ItemTableRow): boolean {
    return row.subtotal !== null && row.subtotal !== undefined;
  }

  /** Rows whose parts the table cannot represent, because the server has not stated them. */
  readonly localEstimateCount = computed(
    () => this.items().filter((row) => !this.hasServerParts(row)).length,
  );

  /** Whether any row is showing a local estimate instead of the server's parts. */
  readonly hasLocalEstimates = computed(() => this.localEstimateCount() > 0);

  /** The line's subtotal: the server's when stated, the local estimate otherwise. */
  lineSubtotal(row: ItemTableRow): number {
    return this.hasServerParts(row) ? row.subtotal! : this.rowSubtotal(row);
  }

  /** The line's tax: the server's when stated, `null` when the server has not answered for the row. */
  lineTaxAmount(row: ItemTableRow): number | null {
    return this.hasServerParts(row) ? (row.taxAmount ?? 0) : null;
  }

  /** The line's total: `V85`'s stored value, or the local estimate while the server has none. */
  lineFinalPrice(row: ItemTableRow): number {
    return this.hasServerParts(row)
      ? (row.finalPrice ?? this.lineSubtotal(row))
      : this.rowSubtotal(row);
  }

  /** The total the summary shows: the document's own, or the local sum when the document has none yet. */
  readonly summaryTotal = computed(() => this.documentTotal() ?? this.lineItemsTotal());

  /** Whether the summary total is a local sum rather than the document's own. */
  readonly summaryIsLocal = computed(() => this.documentTotal() === null);

  /** Whether the row identified by `rowId` has a write in flight. */
  isRowSaving(rowId: string | undefined): boolean {
    return rowId !== undefined && this.savingRowIds().has(rowId);
  }

  /**
   * Track data rows by their server id.
   *
   * Without it, `mat-table` falls back to object identity. Every reconciliation
   * hands it freshly built row objects for items that did not change, so it
   * re-creates the whole body on each add: a visible blink once an order holds a
   * couple of dozen items, and an arrival highlight replayed on rows that did not
   * arrive.
   */
  trackRow(index: number, row: ItemTableRow): string | number {
    return row.id ?? index;
  }

  readonly lineItemsTotal = computed(() =>
    this.items().reduce((sum, item) => sum + this.rowSubtotal(item), 0),
  );

  // ─── Mutations ─────────────────────────────────────────

  removeItem(index: number): void {
    if (!this.isModifiable()) return;
    const updated = this.items().filter((_, i) => i !== index);
    this.itemRemoved.emit(index);
    this.itemsChanged.emit(updated);
  }

  onQuantityChange(index: number, value: number): void {
    if (!this.isModifiable()) return;
    const newValue = value || 1;
    const updated = this.items().map((item, i) =>
      i === index ? { ...item, quantity: newValue } : item,
    );
    this.quantityChanged.emit({ index, value: newValue });
    this.itemsChanged.emit(updated);
  }

  onListPriceChange(index: number, value: number): void {
    if (!this.isModifiable()) return;
    const newValue = value || 0;
    const updated = this.items().map((item, i) =>
      i === index ? { ...item, listPrice: newValue } : item,
    );
    this.listPriceChanged.emit({ index, value: newValue });
    this.itemsChanged.emit(updated);
  }

  onDiscountChange(index: number, value: number): void {
    if (!this.isModifiable()) return;
    const newValue = value || 0;
    const updated = this.items().map((item, i) =>
      i === index ? { ...item, discountApplied: newValue } : item,
    );
    this.discountChanged.emit({ index, value: newValue });
    this.itemsChanged.emit(updated);
  }

  /**
   * Subtotal per row as the client can compute it: `(quantity × listPrice) − discountApplied`.
   *
   * The local estimate, not the source of truth: a saved row shows the server's `subtotal` instead, and a row
   * that has to fall back to this one is counted by {@link hasLocalEstimates} and named on screen.
   */
  rowSubtotal(row: ItemTableRow): number {
    return row.quantity * row.listPrice - row.discountApplied;
  }
}
