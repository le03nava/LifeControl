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
import { CurrencyPipe } from '@angular/common';
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
import { DetailTable, type DetailTableRow } from '../../components/detail-table/detail-table';
import { CompanyInfoSection } from '../../components/company-info-section/company-info-section';
import { SupplierInfoSection } from '../../components/supplier-info-section/supplier-info-section';
import { isOrderReceivable } from '../../data/status-config';
import type {
  PurchaseOrder,
  PurchaseOrderDetail,
  PurchaseOrderRequest,
  PurchaseOrderDetailRequest,
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

@Component({
  selector: 'app-purchase-order-edit',
  standalone: true,
  imports: [
    RouterLink,
    CurrencyPipe,
    ReactiveFormsModule,
    ErrorBanner,
    PageHeader,
    StatusSelector,
    StatusChip,
    DetailTable,
    CompanyInfoSection,
    SupplierInfoSection,
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

  /** Sum of all line-item subtotals (quantity × unit price). */
  readonly orderTotal = computed(() =>
    this.lineItems().reduce((sum, item) => sum + item.quantity * item.unitPrice, 0),
  );

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
   * Legacy rows loaded without a variant cannot produce a valid payload: the API
   * rejects a detail without `productVariantId`.
   */
  readonly hasLinesWithoutVariant = computed(() =>
    this.lineItems().some((item) => !item.productVariantId),
  );

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
          if (!supplierId) {
            this.supplierProducts.set([]);
            return EMPTY;
          }
          return this.productService.getProductsBySupplier(supplierId).pipe(
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

  private loadOrder(id: string): void {
    this.purchaseOrderService
      .getPurchaseOrder(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (order) => {
          this.loadedOrder.set(order);
          this.populateForm(order);
          this.populateLineItems(order.details);
        },
        error: (err: HttpErrorResponse) => {
          this.generalError.set(
            err.status === 404
              ? 'Orden de compra no encontrada.'
              : 'Error al cargar la orden de compra.',
          );
        },
      });
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
    const rows: DetailTableRow[] = details.map((d) => ({
      id: d.id,
      productId: d.productId,
      productName: d.productName,
      productVariantId: d.productVariantId,
      productVariantName: d.productVariantName,
      quantity: d.quantity,
      unitPrice: d.unitPrice,
      receivedQuantity: d.receivedQuantity,
      statusName: d.statusName,
    }));
    this.lineItems.set(rows);
    // `lineItemsKey` only projects the editable pricing fields, so the receipt
    // fields above can never make a freshly loaded order look dirty.
    this.lineItemsBaseline.set(this.lineItemsKey());
  }

  // ══════════════════════════════════════════════════════════
  // CHILD EVENT HANDLERS
  // ══════════════════════════════════════════════════════════

  /** Called by `<app-detail-table>` when items are added or removed. */
  onItemsChanged(updated: DetailTableRow[]): void {
    this.lineItems.set(updated);
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

    // A legacy line loaded without a variant has no valid payload. Abort before
    // touching the request instead of letting the API answer with a raw 400; the
    // detail table renders the actionable warning next to the offending lines and
    // the error banner names which lines block the save.
    const items = this.lineItems();
    const details: PurchaseOrderDetailRequest[] = [];
    const missingVariantLines: string[] = [];
    items.forEach((item, index) => {
      if (!item.productVariantId) {
        missingVariantLines.push(`${index + 1}: ${item.productName}`);
        return;
      }
      details.push({
        productId: item.productId,
        productVariantId: item.productVariantId,
        quantity: item.quantity,
        unitPrice: item.unitPrice,
      });
    });

    if (missingVariantLines.length > 0) {
      // Outside Draft the delete/add controls are disabled, so asking the user
      // to remove and re-add the lines would be an impossible instruction.
      const repairInstruction = this.isDraft()
        ? 'Eliminá esas líneas y agregalas de nuevo eligiendo una variante.'
        : 'Esas líneas no se pueden reparar en el estado actual de la orden.';
      this.serverErrors.set({});
      this.generalError.set(
        `No se puede guardar: hay líneas sin variante (${missingVariantLines.join(
          ', ',
        )}). ${repairInstruction}`,
      );
      return;
    }

    this.serverErrors.set({});
    this.generalError.set(null);
    this.saving.set(true);

    const formValue = form.getRawValue();

    const request: PurchaseOrderRequest = {
      supplierId: formValue.supplierId,
      companyStoreId: formValue.companyStoreId,
      paymentMethodId: formValue.paymentMethodId,
      comments: formValue.comments ?? undefined,
      details: details.length > 0 ? details : undefined,
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
          next: () => {
            this.saving.set(false);
            this.formDirty.set(false);
            this.lineItemsBaseline.set(this.lineItemsKey());
            this.notificationService.showSuccess('Orden creada correctamente.');
            this.router.navigate(['/purchases/orders']);
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
