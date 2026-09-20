import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { httpErrorMessage, unwrapHttpError } from '@shared/data';
import { NotificationService } from '@shared/data/notification';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { StoreInventorySettingsService } from '@features/inventory/data/store-inventory-settings.service';
import { StoreLocationLookupService } from '@features/inventory/data/store-location-lookup.service';
import type { StoreChain } from '@features/inventory/models/store-location-summary.models';
import type { ApiError } from '@shared/models';
import { StatusChip } from '../../../purchase-orders/components/status-chip/status-chip';
import { isOrderReceivable } from '../../../purchase-orders/data/status-config';
import { PurchaseOrderService } from '../../../purchase-orders/data/purchase-order.service';
import type { PurchaseOrder } from '../../../purchase-orders/models/purchase-order.models';
import type { UnsavedChangesAware } from '../../../purchase-orders/guards/unsaved-changes.guard';
import { GoodsReceiptService } from '../../data/goods-receipt.service';
import type { GoodsReceiptRequest } from '../../models/receipt.models';
import {
  buildReceivableLines,
  selectedDrafts,
  toDraftLines,
  toRequestLines,
  validateDrafts,
  type ReceiptLineDraft,
} from '../../utils/receipt-form.utils';

/**
 * Page that registers a goods receipt against a purchase order.
 *
 * Two states, both driven by `selectedOrderId`:
 *
 * - **State A (picker)**: no order selected. A debounced search plus a paged
 *   table of purchase orders; only the receivable ones can be picked.
 * - **State B (form)**: an order selected (or deep-linked through
 *   `?purchaseOrderId=`): the editable lines, the receiving location and the
 *   comments, then the submit.
 */
@Component({
  selector: 'app-receipt-create',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    PageHeader,
    ErrorBanner,
    DatePipe,
    StatusChip,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
    MatSelectModule,
    MatTableModule,
    MatTooltipModule,
  ],
  templateUrl: './receipt-create.html',
  styleUrl: './receipt-create.scss',
})
export class ReceiptCreate implements UnsavedChangesAware {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly purchaseOrderService = inject(PurchaseOrderService);
  private readonly goodsReceiptService = inject(GoodsReceiptService);
  private readonly settingsService = inject(StoreInventorySettingsService);
  private readonly locationsService = inject(StoreLocationLookupService);
  private readonly notificationService = inject(NotificationService);

  /** Tooltip that explains why a non-receivable order cannot be picked. */
  readonly receiveTooltip = 'Solo se puede recibir una orden Aceptada o En Tránsito';

  protected readonly httpErrorMessage = httpErrorMessage;
  /** Exposed to the template so each row can gate its own receive action. */
  protected readonly isOrderReceivable = isOrderReceivable;

  // ─── State A: order picker ─────────────────────────────
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);
  readonly searchQuery = signal('');
  private readonly _debouncedSearch = signal('');

  // ─── Selection ─────────────────────────────────────────
  /**
   * Order being received. Seeded from the `purchaseOrderId` query param the
   * purchase-order detail page deep-links with.
   */
  readonly selectedOrderId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('purchaseOrderId'),
  );
  readonly isPickerState = computed(() => this.selectedOrderId() === null);

  // ─── Form state ────────────────────────────────────────
  readonly drafts = signal<ReceiptLineDraft[]>([]);
  /** Location picked by the operator; `null` means "use the store's configuration". */
  readonly locationOverride = signal<string | null>(null);
  readonly comments = signal('');
  readonly submitting = signal(false);
  readonly generalError = signal<string | null>(null);
  /** Raw backend `ApiError.message`, kept next to the mapped copy. */
  readonly serverDetail = signal<string | null>(null);
  /** Flips on any user-facing edit; the programmatic draft re-seed never sets it. */
  private readonly touched = signal(false);

  // ─── Resources ─────────────────────────────────────────
  /**
   * Paged purchase orders for the picker. The list is only requested while no
   * order is selected, so the form state never pays for a read it does not use.
   */
  readonly ordersResource = rxResource({
    params: () => {
      if (!this.isPickerState()) {
        return undefined;
      }
      return {
        page: this.pageIndex(),
        size: this.pageSize(),
        search: this._debouncedSearch(),
      };
    },
    stream: ({ params }) =>
      this.purchaseOrderService.getPurchaseOrders(
        params.page,
        params.size,
        params.search || undefined,
      ),
  });

  readonly orders = computed(() =>
    this.ordersResource.hasValue() ? this.ordersResource.value() : undefined,
  );
  readonly ordersLoading = this.ordersResource.isLoading;
  readonly ordersError = this.ordersResource.error;
  /** Whether the picker paginator is worth rendering (more than one page). */
  readonly hasMultiplePages = computed(() => (this.orders()?.totalPages ?? 0) > 1);

  /** The selected order. `undefined` params keep the resource idle with no selection. */
  readonly orderResource = rxResource({
    params: () => {
      const orderId = this.selectedOrderId();
      return orderId ? { orderId } : undefined;
    },
    stream: ({ params }) => this.purchaseOrderService.getPurchaseOrder(params.orderId),
  });

  /**
   * Reading `value()` on a resource in an error state throws, so both computed
   * views project the value through `hasValue()`.
   */
  readonly order = computed<PurchaseOrder | null>(() =>
    this.orderResource.hasValue() ? this.orderResource.value() : null,
  );
  readonly orderLoading = this.orderResource.isLoading;
  readonly orderError = this.orderResource.error;

  /**
   * The 5-level store chain the inventory endpoints are nested under.
   *
   * `PurchaseOrder` types the four cascade ids as `string | null`, so this
   * fails closed: unless all five ids are real strings there is no chain, no
   * settings read and no location read, and the page shows a blocking error.
   */
  readonly chain = computed<StoreChain | null>(() => {
    const order = this.order();
    if (!order) {
      return null;
    }

    const { companyId, companyCountryId, regionId, zoneId, companyStoreId } = order;
    if (!companyId || !companyCountryId || !regionId || !zoneId || !companyStoreId) {
      return null;
    }

    return { companyId, companyCountryId, regionId, zoneId, storeId: companyStoreId };
  });

  /** Store inventory settings; the service maps "not configured" to `null`. */
  readonly settingsResource = rxResource({
    params: () => {
      const chain = this.chain();
      return chain ? { chain } : undefined;
    },
    stream: ({ params }) => this.settingsService.getSettings(params.chain),
  });

  readonly settings = computed(() =>
    this.settingsResource.hasValue() ? this.settingsResource.value() : null,
  );
  /** True once the settings read resolved; a `null` value then means "not configured". */
  readonly settingsLoaded = computed(() => this.settingsResource.hasValue());

  /** Enabled locations of the store, used for the optional override. */
  readonly locationsResource = rxResource({
    params: () => {
      const chain = this.chain();
      return chain ? { chain } : undefined;
    },
    stream: ({ params }) => this.locationsService.getStoreLocations(params.chain),
  });

  readonly locations = computed(() =>
    this.locationsResource.hasValue() ? this.locationsResource.value() : [],
  );

  // ─── Derived form state ────────────────────────────────
  /** Lines of the loaded order that can still receive goods. */
  readonly receivableLines = computed(() => {
    const order = this.order();
    return order ? buildReceivableLines(order.details) : [];
  });
  readonly hasReceivableLines = computed(() => this.receivableLines().length > 0);

  readonly pendingLines = computed(
    () => this.drafts().filter((draft) => draft.quantity > 0).length,
  );
  readonly formErrors = computed(() => validateDrafts(this.drafts()));

  /** The store has no receiving location configured, so the override is required. */
  readonly requiresLocation = computed(() => this.settingsLoaded() && this.settings() === null);
  readonly locationMissing = computed(
    () => this.requiresLocation() && this.locationOverride() === null,
  );
  readonly canSubmit = computed(
    () => !this.submitting() && this.formErrors().valid && !this.locationMissing(),
  );

  /** Name of the store's configured receiving location, when it can be resolved. */
  readonly defaultLocationName = computed<string | null>(() => {
    const settings = this.settings();
    if (!settings) {
      return null;
    }
    const location = this.locations().find((item) => item.id === settings.receivingLocationId);
    return location?.locationName ?? null;
  });

  /** `mat-select` comparator. The bound value is the location id, not the entity. */
  readonly compareLocationIds = (a: string | null, b: string | null): boolean => a === b;

  // ─── Blocking states ───────────────────────────────────
  readonly orderNotReceivable = computed(() => {
    const order = this.order();
    return order !== null && !isOrderReceivable(order.statusName);
  });
  readonly storeUnresolved = computed(() => this.order() !== null && this.chain() === null);

  // ─── Page chrome ───────────────────────────────────────
  readonly headerTitle = computed(() =>
    this.isPickerState() ? 'Nuevo recibo' : 'Registrar recepción',
  );
  readonly headerSubtitle = computed<string | undefined>(() => {
    const order = this.order();
    if (order) {
      return `Orden ${order.orderNumber} · ${order.supplierName}`;
    }
    return this.isPickerState() ? 'Elegí la orden de compra a recibir' : undefined;
  });

  readonly displayedOrderColumns: string[] = [
    'orderNumber',
    'supplierName',
    'companyStoreName',
    'createdAt',
    'status',
    'actions',
  ];

  readonly displayedLineColumns: string[] = [
    'product',
    'variant',
    'ordered',
    'alreadyReceived',
    'pending',
    'quantity',
  ];

  constructor() {
    // Debounce effect: searchQuery → 300ms → _debouncedSearch
    effect((onCleanup) => {
      const query = this.searchQuery();
      const timer = setTimeout(() => {
        this._debouncedSearch.set(query);
      }, 300);
      onCleanup(() => clearTimeout(timer));
    });

    // Reset page to 0 when debounced search changes
    effect(() => {
      this._debouncedSearch();
      if (this.pageIndex() !== 0) {
        this.pageIndex.set(0);
      }
    });

    // Seed the editable drafts from the loaded order. This effect re-runs
    // whenever the order resource emits (a new selection or a reload), which is
    // safe because the order is fetched once per selected order and the re-seed
    // is programmatic: it never marks the form as touched by the user.
    effect(() => {
      this.drafts.set(toDraftLines(this.receivableLines()));
    });
  }

  // ══════════════════════════════════════════════════════════
  // STATE A: ORDER PICKER
  // ══════════════════════════════════════════════════════════

  onSearchInput(value: string): void {
    this.searchQuery.set(value);
  }

  clearSearch(): void {
    this.searchQuery.set('');
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  onRetryOrders(): void {
    this.ordersResource.reload();
  }

  /** Only a receivable order can start a reception. */
  onReceiveOrder(order: PurchaseOrder): void {
    if (!isOrderReceivable(order.statusName)) {
      return;
    }
    this.selectedOrderId.set(order.id);
  }

  // ══════════════════════════════════════════════════════════
  // STATE B: FORM
  // ══════════════════════════════════════════════════════════

  /** Returns to the picker and drops every edit of the previous order. */
  chooseAnotherOrder(): void {
    this.selectedOrderId.set(null);
    this.drafts.set([]);
    this.locationOverride.set(null);
    this.comments.set('');
    this.submitting.set(false);
    this.generalError.set(null);
    this.serverDetail.set(null);
    this.touched.set(false);
    this.searchQuery.set('');
    // Drop the deep-link query param too, so a reload does not silently return
    // to the order the operator just left. Safe re-entry: `touched` was reset
    // above, so the unsaved-changes guard sees nothing to confirm.
    void this.router.navigate(['/purchases/receipts/create']);
  }

  onQuantityInput(index: number, value: string): void {
    // An empty field is "skip this line", the same as an explicit zero.
    const parsed = value.trim() === '' ? 0 : Number(value);

    this.touched.set(true);
    this.drafts.update((drafts) =>
      drafts.map((draft, i) =>
        i === index ? { ...draft, quantity: Number.isFinite(parsed) ? parsed : 0 } : draft,
      ),
    );
  }

  onLocationChange(locationId: string | null): void {
    this.touched.set(true);
    this.locationOverride.set(locationId);
  }

  onCommentsInput(value: string): void {
    this.touched.set(true);
    this.comments.set(value);
  }

  /** Per-row error message, or `null` when the row is acceptable. */
  rowError(draft: ReceiptLineDraft): string | null {
    return this.formErrors().errors[draft.detailId] ?? null;
  }

  /** Exposed to `unsavedChangesGuard` so navigation can warn before losing edits. */
  hasUnsavedChanges(): boolean {
    return this.touched();
  }

  onSubmit(): void {
    const order = this.order();
    if (!order || this.submitting() || this.locationMissing() || !this.formErrors().valid) {
      return;
    }

    this.submitting.set(true);
    this.generalError.set(null);
    this.serverDetail.set(null);

    const request: GoodsReceiptRequest = {
      purchaseOrderId: order.id,
      receivingLocationId: this.locationOverride(),
      comments: this.comments().trim() || null,
      lines: toRequestLines(selectedDrafts(this.drafts())),
    };

    this.goodsReceiptService
      .createReceipt(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (receipt) => {
          this.submitting.set(false);
          this.touched.set(false);
          this.notificationService.showSuccess(`Recepción registrada: ${receipt.receiptNumber}`);
          this.router.navigate(['/purchases/receipts', receipt.id]);
        },
        error: (error: unknown) => {
          this.submitting.set(false);
          this.handleServerError(error);
        },
      });
  }

  goToReceiptList(): void {
    this.router.navigate(['/purchases/receipts']);
  }

  /**
   * Maps the create-receipt failure to operator-facing copy. The backend
   * answers 403 only as an `AccessDeniedException` and 409 only as an
   * `InvalidStatusTransitionException`; both are normal outcomes here, so the
   * raw message is kept beside the mapped copy instead of being dropped.
   */
  private handleServerError(error: unknown): void {
    const httpError = unwrapHttpError(error);
    const apiError = httpError?.error as ApiError | undefined;
    const detail = apiError?.message ?? null;

    this.serverDetail.set(detail);

    const status = httpError?.status;
    if (status === 400) {
      this.generalError.set('Revisá las cantidades: el servidor rechazó la recepción.');
    } else if (status === 403) {
      this.generalError.set('No tenés permisos para recibir en esta tienda.');
    } else if (status === 404 && detail?.includes('Store inventory settings not found')) {
      this.generalError.set(
        'Esta tienda no tiene una ubicación de recepción configurada. Configurala o elegí una ubicación.',
      );
    } else if (status === 404) {
      this.generalError.set('La orden de compra ya no existe.');
    } else if (status === 409) {
      this.generalError.set('La orden no está en un estado que permita recibir.');
    } else {
      this.generalError.set(httpErrorMessage(error));
    }
  }
}
