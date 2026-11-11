import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';
import { PercentPipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { observeMobileViewport } from '@shared/responsive/mobile-viewport';
import { MoneyPipe } from '@shared/pipes/money.pipe';
import { ProductVariantPicker } from '../product-variant-picker/product-variant-picker';
import { StatusChip } from '../status-chip/status-chip';
import type { ProductVariant } from '@features/products/models/product-variant.models';

/**
 * Column ids of the editable draft view, in render order.
 *
 * `taxAmount` and `total` are the two money parts the response states for a line. There is no `subtotal` column on
 * purpose: the wire carries the line's total and its tax, never its base, so this table prints those two numbers and
 * never subtracts one from the other to produce a third.
 */
const BASE_COLUMNS = [
  'productName',
  'variantName',
  'quantity',
  'unitPrice',
  'taxAmount',
  'total',
  'actions',
];

/** Column ids the opt-in reception progress view inserts after `quantity`. */
const RECEIPT_PROGRESS_COLUMNS = ['received', 'lineStatus'];

/**
 * Simplified row type for the detail table.
 * Mirrors the fields needed for create/edit line items.
 */
export interface DetailTableRow {
  /** Backend detail ID for already-saved items. `undefined` for new unsaved rows. */
  id?: string;
  productId: string;
  productName: string;
  /** Variant the line is for. `null` only for legacy rows saved before the variant contract. */
  productVariantId: string | null;
  /** Display label for the variant. `null` on the same legacy rows. */
  productVariantName: string | null;
  quantity: number;
  unitPrice: number;
  /**
   * The line total as the server derived it, or `null`/`undefined` when the server has not answered for this row.
   *
   * A row the operator just added has no server total, and one whose quantity or price was edited no longer matches
   * the one the server stated: both render nothing rather than a number this table computed.
   */
  total?: number | null;
  /**
   * The rate the server snapshotted for this line, as a fraction (`0.16` is 16%).
   *
   * Absent on a row the server has not answered for. It survives an inline edit on purpose: the rate is a snapshot of
   * the line, so changing its quantity or its price does not re-resolve it.
   */
  taxRate?: number | null;
  /**
   * The tax the server derived from that rate and the line's base, or `null`/`undefined` when the server has not
   * answered for this row.
   *
   * Never a product of the row's own fields: the amount is the server's number or nothing, exactly like {@link total}.
   */
  taxAmount?: number | null;
  /**
   * Quantity already received against the line. Only the reception progress
   * view reads it, so the draft editor leaves it `undefined`.
   */
  receivedQuantity?: number;
  /** Line (detail) status name; `null` when the row carries no status yet. */
  statusName?: string | null;
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
    MoneyPipe,
    PercentPipe,
    MatTableModule,
    MatFormFieldModule,
    MatSelectModule,
    MatInputModule,
    MatAutocompleteModule,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatTooltipModule,
    ProductVariantPicker,
    StatusChip,
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

  /**
   * Store that scopes the variant picker. Required: a line without a store
   * cannot resolve a variant.
   */
  readonly storeId = input.required<string>();

  /**
   * Company that owns the order, forwarded to the variant picker.
   *
   * Defaults to `null` on purpose: this table is presentational and owns no order state, so a host that
   * has no company yet (an unsaved order) simply binds nothing, and the picker fails closed instead of
   * reading a catalogue it cannot scope.
   */
  readonly companyId = input<string | null>(null);

  /**
   * The document's currency, stated by the order itself.
   *
   * Defaulted to the empty string only so that a host without a document (a new order) claims nothing: the pipe
   * then renders the amount with «(no currency)» instead of a currency nobody chose — see {@link MoneyPipe}.
   */
  readonly currency = input<string>('');

  /**
   * Opt-in reception progress view: adds the received-quantity and line-status
   * columns. Defaults to `false`, so the draft editor renders exactly as before.
   */
  readonly showReceiptProgress = input<boolean>(false);

  /** Emits the full updated items array after any add or remove. */
  readonly itemsChanged = output<DetailTableRow[]>();

  /**
   * Narrow viewports render one card per line item instead of the overflowing
   * `mat-table`.
   */
  // One card per line item, below the shared mobile max width.
  readonly isMobile = observeMobileViewport();

  // ─── Add-row form state ────────────────────────────────
  readonly newProductId = signal('');
  readonly newQuantity = signal(1);
  readonly newUnitPrice = signal(0);
  readonly searchQuery = signal('');

  /** Variant id picked in the add-row form (two-way with the picker). */
  readonly newVariantId = signal<string | null>(null);

  /** Whole variant picked in the add-row form; carries `costPrice` and `variantName`. */
  readonly selectedVariant = signal<ProductVariant | null>(null);

  /**
   * Whether the user typed a unit price for the current add-row selection.
   * While it is false, picking a variant pre-fills the price with its cost.
   * Reset whenever the product or the variant changes, and after a line is added.
   */
  private readonly priceManuallyEdited = signal(false);

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
  /**
   * Rendered columns. The reception progress columns are inserted between
   * `quantity` and `unitPrice` only while `showReceiptProgress` is on.
   */
  readonly displayedColumns = computed(() => {
    if (!this.showReceiptProgress()) {
      return BASE_COLUMNS;
    }

    const columns = [...BASE_COLUMNS];
    columns.splice(columns.indexOf('quantity') + 1, 0, ...RECEIPT_PROGRESS_COLUMNS);
    return columns;
  });

  /**
   * The picked variant must belong to the current product and the current
   * store. The picker reloads on a scope change, but a stale variant from the
   * previous scope can still be held here until the reset lands, so the guard
   * re-checks the variant's own ownership fields before any add.
   */
  private readonly isSelectedVariantInScope = computed(() => {
    const variant = this.selectedVariant();
    return (
      variant !== null &&
      variant.productId === this.newProductId() &&
      variant.companyStoreId === this.storeId()
    );
  });

  readonly canAddItem = computed(
    () =>
      this.isDraft() &&
      this.newProductId() !== '' &&
      this.newVariantId() !== null &&
      this.isSelectedVariantInScope() &&
      this.newQuantity() > 0 &&
      this.newUnitPrice() > 0,
  );

  constructor() {
    // A store change invalidates any variant picked in the add-row form: the
    // variant belongs to the previous store, so it can no longer be added even
    // if the picker has not reported the reset yet.
    effect(() => {
      this.storeId();
      this.newVariantId.set(null);
      this.selectedVariant.set(null);
    });
  }

  /** Whether any existing row has a non-positive quantity or unit price. */
  readonly hasInvalidRows = computed(() => this.items().some((item) => this.isRowInvalid(item)));

  /**
   * Whether any existing row has no variant. Such a row cannot produce a valid
   * payload since the API contract made the variant mandatory.
   */
  readonly hasMissingVariants = computed(() => this.items().some((item) => !item.productVariantId));

  isRowInvalid(row: DetailTableRow): boolean {
    return row.quantity <= 0 || row.unitPrice <= 0;
  }

  // ─── Mutations ─────────────────────────────────────────

  addItem(): void {
    if (
      !this.isDraft() ||
      !this.newProductId() ||
      this.newUnitPrice() <= 0 ||
      !this.isSelectedVariantInScope()
    ) {
      return;
    }

    const product = this.availableProducts().find((p) => p.id === this.newProductId());
    const variant = this.selectedVariant();
    if (!product || !variant) {
      return;
    }

    const newRow: DetailTableRow = {
      productId: this.newProductId(),
      productName: product.name,
      productVariantId: variant.id,
      productVariantName: variant.variantName,
      quantity: this.newQuantity(),
      unitPrice: this.newUnitPrice(),
    };

    this.itemsChanged.emit([...this.items(), newRow]);

    // Reset form
    this.newProductId.set('');
    this.newQuantity.set(1);
    this.newUnitPrice.set(0);
    this.newVariantId.set(null);
    this.selectedVariant.set(null);
    this.searchQuery.set('');
    this.priceManuallyEdited.set(false);
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
    // A different product means a different set of variants and a fresh price.
    this.newVariantId.set(null);
    this.selectedVariant.set(null);
    this.newUnitPrice.set(0);
    this.priceManuallyEdited.set(false);
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
    // From now on the user owns the price: picking another variant must not
    // overwrite what they typed.
    this.priceManuallyEdited.set(true);
  }

  /** Keeps the add-row form in sync with the picker's two-way model. */
  onVariantIdChange(variantId: string | null): void {
    this.newVariantId.set(variantId);
  }

  /**
   * Pre-fills the unit price with the variant's cost, but only while the user
   * has not typed a price for the current add-row selection. The flag is only
   * cleared by a product change or by adding the line, so a price the user typed
   * is never overwritten by a later variant change.
   */
  onVariantSelected(variant: ProductVariant): void {
    this.selectedVariant.set(variant);

    if (!this.priceManuallyEdited()) {
      // `costPrice` is null when no store scopes the variant; this add-row keeps 0
      // as its "no price yet" sentinel, which is what the reset paths use too.
      this.newUnitPrice.set(variant.costPrice ?? 0);
    }
  }

  /** Inline-edit the quantity of an already-saved row. */
  onRowQuantityChange(index: number, value: number): void {
    if (!this.isDraft()) {
      return;
    }
    this.patchRow(index, {
      quantity: Number.isFinite(value) ? value : 0,
      total: null,
      taxAmount: null,
    });
  }

  /** Inline-edit the unit price of an already-saved row. */
  onRowUnitPriceChange(index: number, value: number): void {
    if (!this.isDraft()) {
      return;
    }
    this.patchRow(index, {
      unitPrice: Number.isFinite(value) ? value : 0,
      total: null,
      taxAmount: null,
    });
  }

  /**
   * Inline edits drop the line total and the tax amount the server derived for this row.
   *
   * The numbers the server derived no longer describe a row whose quantity or price just changed, and this table may
   * not invent replacements: it renders the server's amounts or nothing. Clearing them here is what keeps a stale
   * amount from being printed as if the server had stated it for the edited row.
   *
   * The **rate** is kept, and the difference is the rule rather than an oversight: the rate is the line's snapshot,
   * resolved once when the line was written, so editing its quantity does not re-resolve it (S3).
   */
  private patchRow(index: number, patch: Partial<DetailTableRow>): void {
    this.itemsChanged.emit(
      this.items().map((item, i) => (i === index ? { ...item, ...patch } : item)),
    );
  }
}
