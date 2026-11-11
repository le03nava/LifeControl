import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, EMPTY, Subject, switchMap } from 'rxjs';
import { NonNullableFormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { ProductService } from '@features/products/data/product.service';
import { ApiError } from '@shared/models';
import { PageHeader } from '@shared/ui';
import { StatusSelector } from '../../components/status-selector/status-selector';
import { StatusChip } from '../../components/status-chip/status-chip';
import {
  DetailTable,
  type DetailLineDraft,
  type DetailLinePatch,
  type DetailTableRow,
} from '../../components/detail-table/detail-table';
import { CompanyInfoSection } from '../../components/company-info-section/company-info-section';
import { SupplierInfoSection } from '../../components/supplier-info-section/supplier-info-section';
import { isOrderReceivable } from '../../data/status-config';
import type {
  PurchaseOrder,
  PurchaseOrderDetail,
  PurchaseOrderRequest,
} from '../../models/purchase-order.models';
import type {
  PurchaseOrderHeaderControl,
  PurchaseOrderCompanyControl,
} from '../../models/purchase-order-control.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { NotificationService } from '@shared/data/notification';
import { ErrorBanner } from '@shared/ui';
import { MoneyPipe } from '@shared/pipes/money.pipe';

/**
 * Key the add attempt uses in {@link PurchaseOrderEdit.linePending} and {@link PurchaseOrderEdit.lineErrors}.
 *
 * A line the operator just added has no id until the server answers for it, and a rejected `POST` leaves no row at all,
 * so the attempt itself is the only thing its message can be attached to. No detail id can collide with it: those are
 * UUIDs.
 */
export const NEW_LINE_KEY = 'new';

@Component({
  selector: 'app-purchase-order-edit',
  standalone: true,
  imports: [
    RouterLink,
    ReactiveFormsModule,
    ErrorBanner,
    PageHeader,
    StatusSelector,
    StatusChip,
    DetailTable,
    CompanyInfoSection,
    SupplierInfoSection,
    MoneyPipe,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
  ],
  templateUrl: './purchase-order-edit.html',
  styleUrl: './purchase-order-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PurchaseOrderEdit implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly purchaseOrderService = inject(PurchaseOrderService);
  private readonly productService = inject(ProductService);
  private readonly notificationService = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  // ─── Route data ────────────────────────────────────────
  readonly orderId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly isEditMode = computed(() => this.orderId() !== null);

  // ─── Header form ───────────────────────────────────────
  readonly headerForm = signal<FormGroup<PurchaseOrderHeaderControl>>(this.createForm());
  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);
  readonly saving = signal(false);

  /**
   * The company the order belongs to: the header's cascade root, as the cascade resolves it.
   *
   * It is the company every product read of this screen is scoped to — the supplier search, the
   * supplier's product list and, through {@link DetailTable}, the variant picker — because a product and
   * a supplier belong to one company and those endpoints take it in the path. There is no second place
   * this is chosen: the cascade group is the order's own company, and `null` is the honest answer while
   * an order being created has none yet.
   *
   * Fed by `(companyResolved)`, never by `valueChanges`: the cascade patches its controls with
   * `emitEvent: false`, so the stream would stay silent on the profile prefill, the order
   * reconstruction and the operator's own selection alike. Same two-channel contract as the store
   * below (see {@link onStoreResolved}).
   */
  readonly orderCompanyId = signal<string | null>(null);

  // ─── Loaded order data (edit mode) ─────────────────────
  readonly loadedOrder = signal<PurchaseOrder | null>(null);

  /** Exposed to the template so the receipt action can gate on the order status. */
  // The receipt page re-validates the order status server side anyway.
  protected readonly isOrderReceivable = isOrderReceivable;

  /**
   * New orders start as drafts (the backend defaults them to Draft), so the
   * line-items editor is enabled while no order is loaded yet.
   */
  readonly isDraft = computed(() => {
    const order = this.loadedOrder();
    return !order || order.statusName === 'Draft';
  });

  // ─── Header context ────────────────────────────────────
  readonly headerTitle = computed(() => {
    const order = this.loadedOrder();
    if (order) {
      return `Orden ${order.orderNumber}`;
    }
    return this.isEditMode() ? 'Editar Orden de Compra' : 'Nueva Orden de Compra';
  });

  readonly headerSubtitle = computed(() => {
    const order = this.loadedOrder();
    if (order) {
      return `${order.supplierName} · ${order.companyStoreName}`;
    }
    return this.isEditMode()
      ? 'Modificá los datos de la orden'
      : 'Completá los datos para crear una nueva orden';
  });

  // ─── Line items ────────────────────────────────────────
  readonly lineItems = signal<DetailTableRow[]>([]);

  /** Every line item must have a positive quantity and unit price. */
  readonly lineItemsValid = computed(() =>
    this.lineItems().every((item) => item.quantity > 0 && item.unitPrice > 0),
  );

  /** Products filtered by the selected supplier, passed to DetailTable. */
  readonly supplierProducts = signal<{ id: string; name: string; sku: string }[]>([]);

  /**
   * Store that scopes the line-item variant picker. Two channels feed it:
   *
   * - `(storeResolved)` from `<app-company-info-section>`, which mirrors every
   *   programmatic patch the cascade service makes (profile prefill on create,
   *   order reconstruction on edit, and the company/country/region/zone resets).
   * - the `companyStoreId.valueChanges` subscription below plus the explicit
   *   set in `populateForm`, which cover a user picking a store directly.
   *
   * Both are required: `valueChanges` never sees `emitEvent: false` patches, and
   * the output only fires when the cascade service itself applies a store.
   */
  readonly variantStoreId = signal('');

  /**
   * Lines being written, by detail id, plus {@link NEW_LINE_KEY} for an add in flight.
   *
   * Each line persists on its own (S4), so a slow request must be visible on that row and nowhere else: a single
   * page-wide flag could not tell the operator which line is still moving.
   */
  readonly linePending = signal<Record<string, boolean>>({});

  /**
   * The message a rejected line carries, by detail id, plus {@link NEW_LINE_KEY} for a rejected add.
   *
   * Kept apart from {@link generalError} on purpose: the banner is reserved for the header, so a rejected line never
   * reads as a rejected document (S5).
   */
  readonly lineErrors = signal<Record<string, string>>({});

  // ─── Unsaved-changes tracking ──────────────────────────
  private readonly supplierId$ = new Subject<string>();
  private readonly formDirty = signal(false);
  private readonly lineItemsBaseline = signal('[]');

  private readonly lineItemsKey = computed(() =>
    JSON.stringify(
      this.lineItems().map((item) => ({
        productId: item.productId,
        productVariantId: item.productVariantId,
        quantity: item.quantity,
        unitPrice: item.unitPrice,
      })),
    ),
  );

  /** Exposed to `unsavedChangesGuard` so navigation can warn before losing edits. */
  readonly hasUnsavedChanges = computed(
    () => this.formDirty() || this.lineItemsKey() !== this.lineItemsBaseline(),
  );

  ngOnInit(): void {
    // A user edit flips the dirty flag. Programmatic patches use
    // `emitEvent: false`, so they never mark the form dirty.
    this.headerForm()
      .valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.formDirty.set(true));

    // Supplier changes drive the product list. The Subject + `switchMap`
    // cancels the previous request, so switching suppliers quickly can never
    // resolve with a stale product list.
    this.supplierId$
      .pipe(
        switchMap((supplierId) => {
          // The company is the order's, read at request time (the cascade group may have been filled by
          // the picker or by the order load). No company is not "any company": it is an order that is not
          // attributable yet, so no request is issued and the list stays empty rather than showing a
          // company-blind catalogue.
          const companyId = this.orderCompanyId();
          if (!supplierId || !companyId) {
            this.supplierProducts.set([]);
            return EMPTY;
          }
          return this.productService.getProductsBySupplier(companyId, supplierId).pipe(
            catchError(() => {
              this.supplierProducts.set([]);
              return EMPTY;
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((products) =>
        this.supplierProducts.set(
          products.map((p) => ({
            id: p.productId,
            name: p.productName,
            sku: p.sku,
          })),
        ),
      );

    // Bridge the supplier form control into the Subject.
    this.headerForm()
      .controls.supplierId.valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((supplierId) => this.supplierId$.next(supplierId));

    // Channel 1: user-driven store selections emit an event, so `valueChanges`
    // catches them here (channel 2 is the child's `(storeResolved)` output,
    // which covers the silent programmatic patches).
    this.headerForm()
      .controls.companyStoreId.valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((storeId) => this.variantStoreId.set(storeId ?? ''));

    const id = this.orderId();
    if (id) {
      this.loadOrder(id);
    }
  }

  // ══════════════════════════════════════════════════════════
  // DATA LOADING
  // ══════════════════════════════════════════════════════════

  /**
   * Called by `<app-company-info-section>` whenever the cascade resolves a store
   * through a silent programmatic patch. `valueChanges` cannot see those, so
   * this is what keeps the variant picker on the real store for the create-mode
   * profile prefill and after every cascade reset.
   */
  onStoreResolved(storeId: string): void {
    // Before the cascade resolves anything it reports '', which is not a reset.
    // A genuine reset clears `companyStoreId` as well, so only a truly empty
    // control accepts the empty emission — otherwise a still-loading cascade
    // would wipe the store the page set from the loaded order.
    if (!storeId && this.headerForm().controls.companyStoreId.value) {
      return;
    }
    this.variantStoreId.set(storeId);
  }

  /**
   * Called by `<app-company-info-section>` whenever the cascade resolves a company.
   *
   * The company control is patched silently for the same reason the store is (programmatic patches
   * must not mark the form dirty), so this is the only channel that carries it. Without it the
   * supplier search, the supplier's products and the variant picker would all stay company-less.
   */
  onCompanyResolved(companyId: string | null): void {
    this.orderCompanyId.set(companyId);
  }

  private loadOrder(id: string): void {
    this.purchaseOrderService
      .getPurchaseOrder(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (order) => this.applyLoadedOrder(order),
        error: (err: HttpErrorResponse) => {
          this.generalError.set(
            err.status === 404
              ? 'Orden de compra no encontrada.'
              : 'Error al cargar la orden de compra.',
          );
        },
      });
  }

  /**
   * Makes the loaded document the screen's state.
   *
   * Shared by the edit-mode load and by a successful create: after the order exists the same page is its edit surface,
   * so both paths have to leave the form, the rows and the money parts in the same shape.
   */
  private applyLoadedOrder(order: PurchaseOrder): void {
    this.loadedOrder.set(order);
    this.populateForm(order);
    this.populateLineItems(order.details);
  }

  private populateForm(order: PurchaseOrder): void {
    this.headerForm().patchValue(
      {
        supplierId: order.supplierId,
        companyStoreId: order.companyStoreId,
        paymentMethodId: order.paymentMethodId,
        comments: order.comments,
      },
      { emitEvent: false },
    );
    // valueChanges is suppressed above, so load the products explicitly.
    this.supplierId$.next(order.supplierId);
    this.variantStoreId.set(order.companyStoreId);
    this.formDirty.set(false);
  }

  private populateLineItems(details: PurchaseOrderDetail[]): void {
    this.applyLineItems(details.map(toDetailTableRow));
    // `lineItemsKey` only projects the editable pricing fields, so the receipt
    // fields above can never make a freshly loaded order look dirty.
    this.lineItemsBaseline.set(this.lineItemsKey());
  }

  /**
   * Restates the document's money after a line moved.
   *
   * `purchase_order_money()` derives `subtotal`, `tax_total` and `total_amount` in the database from the rows that just
   * changed, and the line's own answer carries none of them — so this screen cannot compute them and must not try. It asks
   * the document again and takes the three numbers the server states. A failed read says so instead of leaving totals that
   * describe the previous set of lines.
   */
  private refreshDocumentMoney(orderId: string): void {
    this.purchaseOrderService
      .getPurchaseOrder(orderId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (order) => {
          const current = this.loadedOrder();
          if (!current || current.id !== order.id) {
            return;
          }
          this.loadedOrder.set({
            ...current,
            currency: order.currency,
            subtotal: order.subtotal,
            taxTotal: order.taxTotal,
            totalAmount: order.totalAmount,
          });
        },
        error: () => {
          this.generalError.set('Los totales de la orden no se pudieron actualizar.');
        },
      });
  }

  // ══════════════════════════════════════════════════════════
  // CHILD EVENT HANDLERS
  // ══════════════════════════════════════════════════════════

  /**
   * Creates a line through `POST /{id}/details` and appends the row the server answers for it.
   *
   * Nothing is staged here: a rejected `POST` leaves no row, and an accepted one carries the id and the money the server
   * derived, so the list reconciles from the answer instead of from the payload that asked for it (S4).
   */
  addLine(line: DetailLineDraft): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }
    this.markLinePending(NEW_LINE_KEY, true);
    this.setLineError(NEW_LINE_KEY, null);
    this.purchaseOrderService
      .addDetail(orderId, line)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (detail) => {
          this.markLinePending(NEW_LINE_KEY, false);
          this.applyLineItems([...this.lineItems(), toDetailTableRow(detail)]);
          this.refreshDocumentMoney(orderId);
        },
        error: (err: HttpErrorResponse) => {
          this.markLinePending(NEW_LINE_KEY, false);
          this.setLineError(NEW_LINE_KEY, lineErrorMessage(err, 'No se pudo agregar la línea.'));
        },
      });
  }

  /**
   * Saves one edited line through `PUT /{id}/details/{detailId}`.
   *
   * The row shows the edit while the request is in flight and is put back to the values it had before it when the server
   * rejects it, so a refused edit can never be read as a saved one (S5). The derived total and tax of the edited row go
   * with the edit: the amounts the server stated describe the base that just changed, and this screen renders the
   * server's amounts or nothing. The **rate** stays — it is the line's snapshot and editing a quantity does not
   * re-resolve it.
   */
  updateLine(id: string, changes: DetailLinePatch): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }
    const current = this.lineItems().find((item) => item.id === id);
    if (!current) {
      return;
    }
    // A legacy line has no variant, and the API addresses a line by its variant: there is no payload to send.
    if (!current.productVariantId) {
      this.setLineError(id, 'La línea no tiene variante: elimínala y agrégala de nuevo.');
      return;
    }
    const previous = { ...current };
    this.applyLineItems(
      this.lineItems().map((item) =>
        item.id === id ? { ...item, ...changes, total: null, taxAmount: null } : item,
      ),
    );
    this.markLinePending(id, true);
    this.setLineError(id, null);
    this.purchaseOrderService
      .updateDetail(orderId, id, {
        productId: current.productId,
        productVariantId: current.productVariantId,
        quantity: changes.quantity ?? current.quantity,
        unitPrice: changes.unitPrice ?? current.unitPrice,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (detail) => {
          this.markLinePending(id, false);
          this.applyLineItems(
            this.lineItems().map((item) => (item.id === id ? toDetailTableRow(detail) : item)),
          );
          this.refreshDocumentMoney(orderId);
        },
        error: (err: HttpErrorResponse) => {
          this.markLinePending(id, false);
          this.applyLineItems(this.lineItems().map((item) => (item.id === id ? previous : item)));
          this.setLineError(id, lineErrorMessage(err, 'No se pudo actualizar la línea.'));
        },
      });
  }

  /**
   * Removes one line through `DELETE /{id}/details/{detailId}`.
   *
   * The row goes at once and comes back with its own message if the server rejects the delete, so a row the server
   * still holds is never silently hidden.
   */
  removeLine(id: string): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }
    const removed = this.lineItems().find((item) => item.id === id);
    if (!removed) {
      return;
    }
    this.applyLineItems(this.lineItems().filter((item) => item.id !== id));
    this.markLinePending(id, true);
    this.setLineError(id, null);
    this.purchaseOrderService
      .deleteDetail(orderId, id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.markLinePending(id, false);
          this.refreshDocumentMoney(orderId);
        },
        error: (err: HttpErrorResponse) => {
          this.markLinePending(id, false);
          // Only the row this request took goes back: anything another line did meanwhile is still the server's state.
          this.applyLineItems([...this.lineItems(), removed]);
          this.setLineError(id, lineErrorMessage(err, 'No se pudo eliminar la línea.'));
        },
      });
  }

  private markLinePending(key: string, pending: boolean): void {
    const next = { ...this.linePending() };
    if (pending) {
      next[key] = true;
    } else {
      delete next[key];
    }
    this.linePending.set(next);
  }

  private setLineError(key: string, message: string | null): void {
    const next = { ...this.lineErrors() };
    if (message) {
      next[key] = message;
    } else {
      delete next[key];
    }
    this.lineErrors.set(next);
  }

  /**
   * Replaces the rows, in creation order.
   *
   * Every path that changes them goes through here — the load and each of the three mutations — because the API returns
   * them with no `ORDER BY` and the schema has no ordinal: ordering at one call site and not the other is exactly how
   * the rows would drift apart (S9).
   */
  private applyLineItems(rows: DetailTableRow[]): void {
    this.lineItems.set(orderLineItems(rows));
  }

  /**
   * Called by `<app-status-selector>` after a successful PATCH with the new
   * status name. Only the status is updated in place — reloading the whole
   * order would silently discard unsaved header edits and line items.
   */
  onStatusChanged(statusName: string): void {
    const order = this.loadedOrder();
    if (!order) {
      return;
    }
    this.loadedOrder.set({ ...order, statusName });
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

    // The header is all this screen saves: every line was written on its own against the detail sub-resource as it was
    // added or edited, so neither call carries `details` and no payload here can restate a line (S3, S4).
    const request: PurchaseOrderRequest = {
      supplierId: formValue.supplierId,
      companyStoreId: formValue.companyStoreId,
      paymentMethodId: formValue.paymentMethodId,
      comments: formValue.comments ?? undefined,
    };

    if (this.isEditMode()) {
      const id = this.orderId()!;
      this.purchaseOrderService
        .update(id, request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () => {
            this.saving.set(false);
            this.formDirty.set(false);
            this.lineItemsBaseline.set(this.lineItemsKey());
            this.notificationService.showSuccess('Orden actualizada correctamente.');
            this.router.navigate(['/purchases/orders']);
          },
          error: (err: HttpErrorResponse) => {
            this.saving.set(false);
            this.handleServerError(err);
          },
        });
    } else {
      this.purchaseOrderService
        .create(request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (created) => {
            this.saving.set(false);
            this.formDirty.set(false);
            this.notificationService.showSuccess('Orden creada correctamente.');
            // The created order is this screen's next state: the same page becomes its edit surface, with the detail
            // area appearing to add products. The navigation replaces the history entry, so Back does not return to a
            // `/create` URL that would produce a second order (S2).
            this.orderId.set(created.id);
            this.applyLoadedOrder(created);
            this.router.navigate(['/purchases/orders', created.id], { replaceUrl: true });
          },
          error: (err: HttpErrorResponse) => {
            this.saving.set(false);
            this.handleServerError(err);
          },
        });
    }
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
    } else if (apiError?.message) {
      this.serverErrors.set({});
      this.generalError.set(apiError.message);
    } else {
      this.serverErrors.set({});
      this.generalError.set('Error inesperado. Intente de nuevo más tarde.');
    }
  }

  // ══════════════════════════════════════════════════════════
  // FORM HELPERS
  // ══════════════════════════════════════════════════════════

  private createForm(): FormGroup<PurchaseOrderHeaderControl> {
    return this.fb.group({
      supplierId: this.fb.control('', Validators.required),
      companyStoreId: this.fb.control('', Validators.required),
      paymentMethodId: this.fb.control('', Validators.required),
      comments: this.fb.control<string | null>(null),
      company: this.fb.group<PurchaseOrderCompanyControl>({
        companyId: this.fb.control<string | null>(null),
        companyCountryId: this.fb.control<string | null>(null),
        regionId: this.fb.control<string | null>(null),
        zoneId: this.fb.control<string | null>(null),
      }),
    });
  }

  onCancel(): void {
    this.router.navigate(['/purchases/orders']);
  }
}

/** Maps the line the API answered into the row the table renders. */
function toDetailTableRow(detail: PurchaseOrderDetail): DetailTableRow {
  return {
    id: detail.id,
    productId: detail.productId,
    productName: detail.productName,
    productVariantId: detail.productVariantId,
    productVariantName: detail.productVariantName,
    quantity: detail.quantity,
    unitPrice: detail.unitPrice,
    // The line total the server derived for the row it answered for. The table renders it; nothing multiplies.
    total: detail.total,
    // The tax the same answer carried: the rate it snapshotted and the amount derived from the stored base. Both are
    // read here and never computed — a `base × rate` or a `total - base` would be a second answer to a number the
    // server owns.
    taxRate: detail.taxRate,
    taxAmount: detail.taxAmount,
    receivedQuantity: detail.receivedQuantity,
    statusName: detail.statusName,
    createdAt: detail.createdAt,
  };
}

/**
 * Lines render in creation order: by `createdAt` then `id`.
 *
 * The API returns them with no `ORDER BY` and the schema has no ordinal column, so this is the only stable order there
 * is (S9). A row the server has not dated sorts first, which is where a line it has not answered for belongs; `id`
 * still breaks the tie between two such rows, and between two lines written in the same millisecond.
 */
function orderLineItems(rows: DetailTableRow[]): DetailTableRow[] {
  return [...rows].sort((a, b) => {
    const byCreatedAt = lineCreatedAt(a) - lineCreatedAt(b);
    return byCreatedAt !== 0 ? byCreatedAt : (a.id ?? '').localeCompare(b.id ?? '');
  });
}

function lineCreatedAt(row: DetailTableRow): number {
  const parsed = row.createdAt ? Date.parse(row.createdAt) : Number.NaN;
  return Number.isNaN(parsed) ? 0 : parsed;
}

/** The message the server gave for a rejected line, or the caller's own when it gave none. */
function lineErrorMessage(err: HttpErrorResponse, fallback: string): string {
  const apiError = err.error as ApiError | undefined;
  return apiError?.message ?? fallback;
}
