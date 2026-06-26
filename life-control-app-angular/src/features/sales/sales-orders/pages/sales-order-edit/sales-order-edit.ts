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
import { HttpErrorResponse, HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, map, throwError } from 'rxjs';
import {
  NonNullableFormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { SalesOrderService } from '../../data/sales-order.service';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { ConfigService } from '@app/services/config.service';
import { ApiError } from '@shared/models';
import { SalesOrderItemTable, type ItemTableRow } from '../../components/sales-order-item-table/sales-order-item-table';
import { ProductVariantSelector } from '../../components/product-variant-selector/product-variant-selector';
import type { SalesOrder, SalesOrderRequest, SalesOrderItemRequest, PaymentMethodOption, CustomerOption, Page } from '../../models/sales-order.models';
import type { ProductVariantOption } from '../../models/sales-order.models';
import { NotificationService } from '@shared/data/notification';
import type { SalesOrderHeaderControl } from '../../models/sales-order-control.models';
import { SO_STATUS_COLORS, SO_STATUS_LABELS } from '../../data/status-config';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { ErrorBanner } from '@shared/ui';

@Component({
  selector: 'app-sales-order-edit',
  standalone: true,
  imports: [
    RouterLink,
    ReactiveFormsModule,
    ErrorBanner,
    SalesOrderItemTable,
    ProductVariantSelector,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    MatChipsModule,
  ],
  templateUrl: './sales-order-edit.html',
  styleUrl: './sales-order-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SalesOrderEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private fb = inject(NonNullableFormBuilder);
  private salesOrderService = inject(SalesOrderService);
  private profileService = inject(ProfileService);
  private http = inject(HttpClient);
  private configService = inject(ConfigService);
  private notificationService = inject(NotificationService);
  private destroyRef = inject(DestroyRef);

  // ─── Route data ────────────────────────────────────────
  readonly orderId = signal<string | null>(
    this.route.snapshot.paramMap.get('id'),
  );
  readonly isEditMode = computed(() => this.orderId() !== null);

  // ─── Header form ───────────────────────────────────────
  readonly headerForm = signal<FormGroup<SalesOrderHeaderControl>>(
    this.createForm(),
  );
  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);
  readonly saving = signal(false);
  readonly savingIndex = signal<number | null>(null);

  // ─── Store from user preferences ────────────────────────
  readonly userStoreName = signal<string | null>(null);

  // ─── Loading state for initial GET (edit mode) ───────────
  readonly loading = signal(false);

  // ─── Loaded order data (edit mode) ─────────────────────
  readonly loadedOrder = signal<SalesOrder | null>(null);
  readonly isDraft = computed(
    () => {
      const order = this.loadedOrder();
      if (!order) return true;
      return order.statusName === 'Draft';
    },
  );

  /** Store name fetched from the stores API using profile cascade IDs. */
  readonly displayStoreName = computed(() => this.userStoreName());

  /** Header title: [StoreName] - OrderNumber/No Order. */
  readonly pageTitle = computed(() => {
    const order = this.loadedOrder();
    const parts: string[] = [];

    const store = this.displayStoreName();
    if (store) parts.push(store);

    parts.push(order?.orderNumber ?? (this.creating() ? 'Creating...' : 'No Order'));

    return parts.join(' - ');
  });

  // ─── Status display ────────────────────────────────────
  readonly statusColors = SO_STATUS_COLORS;
  readonly statusLabels = SO_STATUS_LABELS;

  /** Whether the order is Pending (eligible for charge). */
  readonly isPending = computed(
    () => {
      const order = this.loadedOrder();
      if (!order) return false;
      return order.statusName === 'Pending';
    },
  );

  // ─── Default customer (create mode) ─────────────────────
  readonly defaultCustomer = signal<CustomerOption | null>(null);
  private readonly DEFAULT_CUSTOMER_ID = '00000000-0000-0000-0000-000000000001';

  // ─── Auto-creation on init (create mode) ─────────────────
  readonly creating = signal(false);
  private readonly _storeReady = signal(false);
  private readonly _customerReady = signal(false);

  // ─── Profile cascade IDs for store name lookup ────────────
  private readonly _profileCascade = signal<{
    companyId: string | null;
    companyCountryId: string | null;
    companyRegionId: string | null;
    companyZoneId: string | null;
  } | null>(null);

  // ─── Line items ────────────────────────────────────────
  readonly lineItems = signal<ItemTableRow[]>([]);

  // ─── Scan / Search mode toggle ──────────────────────────
  readonly scanMode = signal(true);

  toggleScanMode(): void {
    this.scanMode.update(v => !v);
  }

  // ─── Charge / Cobrar ─────────────────────────────────────
  readonly paymentMethods = signal<PaymentMethodOption[]>([]);
  readonly charging = signal(false);

  constructor() {
    // When both store and customer data are ready, auto-create the order
    effect(() => {
      if (this._storeReady() && this._customerReady()) {
        this.autoCreateOrder();
      }
    });

    // In edit mode: when both profile cascade and order are loaded, fetch store name
    effect(() => {
      const cascade = this._profileCascade();
      const order = this.loadedOrder();
      if (cascade && order?.companyStoreId && this.isEditMode() && !this.userStoreName()) {
        this.fetchStoreName(
          cascade.companyId,
          cascade.companyCountryId,
          cascade.companyRegionId,
          cascade.companyZoneId,
          order.companyStoreId,
        );
      }
    });
  }

  ngOnInit(): void {
    const id = this.orderId();
    if (id) {
      this.loadOrder(id);
      this.loadProfile();
      this.loadPaymentMethods();
    } else {
      this.loadProfile();
      this.loadDefaultCustomer();
      this.loadPaymentMethods();
    }
  }

  // ══════════════════════════════════════════════════════════
  // DATA LOADING
  // ══════════════════════════════════════════════════════════

  /** Load the user's profile to get cascade IDs and store info. */
  private loadProfile(): void {
    this.profileService
      .getProfile()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (profile) => {
          this._profileCascade.set({
            companyId: profile.companyId,
            companyCountryId: profile.companyCountryId,
            companyRegionId: profile.companyRegionId,
            companyZoneId: profile.companyZoneId,
          });

          if (!this.isEditMode()) {
            // Create mode: patch form and fetch store name with profile's store
            if (profile.companyStoreId) {
              this.headerForm().patchValue({
                companyStoreId: profile.companyStoreId,
              });
              this.fetchStoreName(
                profile.companyId,
                profile.companyCountryId,
                profile.companyRegionId,
                profile.companyZoneId,
                profile.companyStoreId,
              );
            }
            this._storeReady.set(true);
          }
        },
        error: () => {
          if (!this.isEditMode()) {
            this._storeReady.set(true);
          }
        },
      });
  }

  private fetchStoreName(
    companyId: string | null,
    companyCountryId: string | null,
    regionId: string | null,
    zoneId: string | null,
    storeId: string,
  ): void {
    if (!companyId || !companyCountryId || !regionId || !zoneId) {
      return;
    }
    const url = `${this.configService.apiUrl}/companies/${companyId}/countries/${companyCountryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}`;
    this.http
      .get<{ storeName: string }>(url)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (store) => this.userStoreName.set(store.storeName),
        error: () => {
          // Store name not critical — leave as null
        },
      });
  }

  /** Load default customer: try UUID lookup first, fall back to name search. */
  private loadDefaultCustomer(): void {
    this.http
      .get<CustomerOption>(
        `${this.configService.apiUrl}/customers/${this.DEFAULT_CUSTOMER_ID}`,
      )
      .pipe(
        catchError((err: HttpErrorResponse) => {
          if (err.status === 404 || err.status === 0) {
            return this.http
              .get<Page<CustomerOption>>(
                `${this.configService.apiUrl}/customers`,
                {
                  params: {
                    search: 'PUBLICO EN GENERAL',
                    page: '0',
                    size: '5',
                  },
                },
              )
              .pipe(
                map((page) => {
                  const found = page.content.find(
                    (c) => c.name === 'PUBLICO EN GENERAL',
                  );
                  if (found) return found;
                  throw new Error(
                    'Default customer not found in fallback search',
                  );
                }),
              );
          }
          return throwError(() => err);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (customer) => {
          this.headerForm().controls.customerId.setValue(customer.id);
          this.defaultCustomer.set(customer);
          this._customerReady.set(true);
        },
        error: () => {
          // Non-critical — customer field will be empty for manual selection
          this._customerReady.set(true);
        },
      });
  }

  /** Auto-create the sales order when both store and customer data are ready. */
  private autoCreateOrder(): void {
    if (this.isEditMode()) return;

    this.creating.set(true);

    const form = this.headerForm();
    const formValue = form.getRawValue();

    const request: SalesOrderRequest = {
      customerId: formValue.customerId || undefined,
      companyStoreId: formValue.companyStoreId,
      shiftId: formValue.shiftId || undefined,
    };

    this.salesOrderService
      .create(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (created) => {
          this.creating.set(false);
          this.router.navigate(['/sales/orders', created.id]);
        },
        error: (err: HttpErrorResponse) => {
          this.creating.set(false);
          const apiError = err.error as ApiError | undefined;
          if (apiError?.message) {
            this.generalError.set(apiError.message);
          } else {
            this.generalError.set(
              'Unexpected error during auto-creation. Please try again.',
            );
          }
        },
      });
  }

  /** Load available payment methods for the Cobrar charge. */
  private loadPaymentMethods(): void {
    this.http
      .get<PaymentMethodOption[]>(`${this.configService.apiUrl}/payment-methods`)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (methods) => {
          this.paymentMethods.set(methods);
        },
        error: () => {
          // Payment methods are non-critical — Cobrar buttons won't appear
        },
      });
  }

  private loadOrder(id: string): void {
    this.loading.set(true);
    this.generalError.set(null);
    this.salesOrderService
      .getSalesOrder(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (order) => {
          this.loadedOrder.set(order);
          this.loading.set(false);
          this.populateForm(order);
          this.populateLineItems(order.items);
        },
        error: (err: HttpErrorResponse) => {
          this.loading.set(false);
          this.generalError.set(
            err.status === 404
              ? 'Order not found.'
              : 'Error loading sales order.',
          );
        },
      });
  }

  private populateForm(order: SalesOrder): void {
    this.headerForm().patchValue({
      customerId: order.customerId,
      companyStoreId: order.companyStoreId,
      shiftId: order.shiftId ?? undefined,
      comments: null,
    });
  }

  private populateLineItems(items: SalesOrder['items']): void {
    const rows: ItemTableRow[] = items.map((item) => ({
      id: item.id,
      productVariantId: item.productVariantId,
      productVariantName: item.productVariantName ?? '',
      quantity: item.quantity,
      listPrice: item.listPrice,
      discountApplied: item.discountApplied,
    }));
    this.lineItems.set(rows);
  }

  // ══════════════════════════════════════════════════════════
  // CHILD EVENT HANDLERS
  // ══════════════════════════════════════════════════════════

  /** Called by `<app-product-variant-selector>` when a variant is selected. */
  onVariantSelected(variant: ProductVariantOption): void {
    const orderId = this.orderId();
    if (!orderId) return;

    this.savingIndex.set(this.lineItems().length);

    const request: SalesOrderItemRequest = {
      productVariantId: variant.id,
      quantity: 1,
      listPrice: variant.listPrice,
      discountApplied: 0,
    };

    this.salesOrderService.addItem(orderId, request).subscribe({
      next: (created) => {
        this.lineItems.update((rows) => [...rows, this.toItemTableRow(created)]);
        this.savingIndex.set(null);
        // Reload order to pick up status changes (e.g. Draft → Pending on first item)
        this.loadOrder(orderId);
      },
      error: (err: HttpErrorResponse) => {
        this.savingIndex.set(null);
        this.handleItemError(err);
      },
    });
  }

  /** Called by `<app-sales-order-item-table>` when a row's remove button is clicked. */
  onItemRemoved(index: number): void {
    const row = this.lineItems()[index];
    const orderId = this.orderId();
    if (!row.id || !orderId) return;

    this.savingIndex.set(index);

    this.salesOrderService.deleteItem(orderId, row.id).subscribe({
      next: () => {
        this.lineItems.update((items) => items.filter((_, i) => i !== index));
        this.savingIndex.set(null);
      },
      error: (err: HttpErrorResponse) => {
        this.savingIndex.set(null);
        this.handleItemError(err);
      },
    });
  }

  /** Called by `<app-sales-order-item-table>` when quantity is changed on a row. */
  onQuantityChanged(data: { index: number; value: number }): void {
    const row = this.lineItems()[data.index];
    const orderId = this.orderId();
    if (!row.id || !orderId) return;
    const previousValue = row.quantity;

    this.savingIndex.set(data.index);

    const request: SalesOrderItemRequest = {
      productVariantId: row.productVariantId,
      quantity: data.value,
      listPrice: row.listPrice,
      discountApplied: row.discountApplied,
    };

    this.salesOrderService.updateItem(orderId, row.id, request).subscribe({
      next: (updated) => {
        this.lineItems.update((items) =>
          items.map((item, i) =>
            i === data.index
              ? {
                  ...item,
                  quantity: updated.quantity,
                  listPrice: updated.listPrice,
                  discountApplied: updated.discountApplied,
                }
              : item,
          ),
        );
        this.savingIndex.set(null);
      },
      error: () => {
        this.lineItems.update((items) =>
          items.map((item, i) =>
            i === data.index ? { ...item, quantity: previousValue } : item,
          ),
        );
        this.savingIndex.set(null);
      },
    });
  }

  /** Called by `<app-sales-order-item-table>` when list price is changed on a row. */
  onListPriceChanged(data: { index: number; value: number }): void {
    const row = this.lineItems()[data.index];
    const orderId = this.orderId();
    if (!row.id || !orderId) return;
    const previousValue = row.listPrice;

    this.savingIndex.set(data.index);

    const request: SalesOrderItemRequest = {
      productVariantId: row.productVariantId,
      quantity: row.quantity,
      listPrice: data.value,
      discountApplied: row.discountApplied,
    };

    this.salesOrderService.updateItem(orderId, row.id, request).subscribe({
      next: (updated) => {
        this.lineItems.update((items) =>
          items.map((item, i) =>
            i === data.index
              ? {
                  ...item,
                  listPrice: updated.listPrice,
                  quantity: updated.quantity,
                  discountApplied: updated.discountApplied,
                }
              : item,
          ),
        );
        this.savingIndex.set(null);
      },
      error: () => {
        this.lineItems.update((items) =>
          items.map((item, i) =>
            i === data.index ? { ...item, listPrice: previousValue } : item,
          ),
        );
        this.savingIndex.set(null);
      },
    });
  }

  /** Called by `<app-sales-order-item-table>` when discount is changed on a row. */
  onDiscountChanged(data: { index: number; value: number }): void {
    const row = this.lineItems()[data.index];
    const orderId = this.orderId();
    if (!row.id || !orderId) return;
    const previousValue = row.discountApplied;

    this.savingIndex.set(data.index);

    const request: SalesOrderItemRequest = {
      productVariantId: row.productVariantId,
      quantity: row.quantity,
      listPrice: row.listPrice,
      discountApplied: data.value,
    };

    this.salesOrderService.updateItem(orderId, row.id, request).subscribe({
      next: (updated) => {
        this.lineItems.update((items) =>
          items.map((item, i) =>
            i === data.index
              ? {
                  ...item,
                  discountApplied: updated.discountApplied,
                  quantity: updated.quantity,
                  listPrice: updated.listPrice,
                }
              : item,
          ),
        );
        this.savingIndex.set(null);
      },
      error: () => {
        this.lineItems.update((items) =>
          items.map((item, i) =>
            i === data.index
              ? { ...item, discountApplied: previousValue }
              : item,
          ),
        );
        this.savingIndex.set(null);
      },
    });
  }

  /** Called by a payment-method button to charge the current Pending sales order. */
  onCharge(paymentMethodId: string): void {
    const id = this.orderId();
    if (!id) return;

    this.charging.set(true);
    this.generalError.set(null);

    this.salesOrderService
      .chargeSalesOrder(id, paymentMethodId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.charging.set(false);
          this.notificationService.showSuccess('Sales order charged successfully.');
          this.loadOrder(id);
        },
        error: (err: HttpErrorResponse) => {
          this.charging.set(false);
          const message =
            err.error?.message ||
            (err.status === 400
              ? 'Order is not in Pending status.'
              : err.status === 404
                ? 'Sales order or payment method not found.'
                : 'Error charging the sales order.');
          this.notificationService.showError(message);
        },
      });
  }

  // ══════════════════════════════════════════════════════════
  // SAVE
  // ══════════════════════════════════════════════════════════

  onSave(): void {
    const form = this.headerForm();
    if (form.invalid) {
      form.markAllAsTouched();
      return;
    }

    this.serverErrors.set({});
    this.generalError.set(null);
    this.saving.set(true);

    const formValue = form.getRawValue();

    const request: SalesOrderRequest = {
      customerId: formValue.customerId || undefined,
      companyStoreId: formValue.companyStoreId,
      shiftId: formValue.shiftId || undefined,
    };

    if (this.isEditMode()) {
      const id = this.orderId()!;
      this.salesOrderService.update(id, request).subscribe({
        next: () => {
          this.saving.set(false);
          this.router.navigate(['/sales/orders']);
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.handleServerError(err);
        },
      });
    } else {
      this.salesOrderService.create(request).subscribe({
        next: (created) => {
          this.saving.set(false);
          this.router.navigate(['/sales/orders', created.id]);
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.handleServerError(err);
        },
      });
    }
  }

  private handleServerError(err: HttpErrorResponse): void {
    if (err.status === 409) {
      this.serverErrors.set({});
      this.generalError.set(
        'The order was concurrently modified. Please reload and try again.',
      );
      return;
    }

    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
    } else if (apiError?.message) {
      this.serverErrors.set({});
      this.generalError.set(apiError.message);
    } else {
      this.serverErrors.set({});
      this.generalError.set('Unexpected error. Please try again later.');
    }
  }

  private handleItemError(err: HttpErrorResponse): void {
    const message =
      err.error?.message || (err.status === 409
        ? 'Insufficient stock for the requested quantity.'
        : 'An error occurred while updating the item.');
    this.notificationService.showError(message);
    this.generalError.set(message);
  }

  /** Convert a `SalesOrderItem` from the server into an `ItemTableRow`. */
  private toItemTableRow(item: {
    id: string;
    productVariantId: string;
    productVariantName?: string;
    quantity: number;
    listPrice: number;
    discountApplied: number;
  }): ItemTableRow {
    return {
      id: item.id,
      productVariantId: item.productVariantId,
      productVariantName: item.productVariantName ?? '',
      quantity: item.quantity,
      listPrice: item.listPrice,
      discountApplied: item.discountApplied,
    };
  }

  // ══════════════════════════════════════════════════════════
  // FORM HELPERS
  // ══════════════════════════════════════════════════════════

  private createForm(): FormGroup<SalesOrderHeaderControl> {
    return this.fb.group({
      customerId: this.fb.control('', Validators.required),
      companyStoreId: this.fb.control(''),
      shiftId: this.fb.control(''),
      comments: this.fb.control<string | null>(null),
    });
  }

}
