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
import {
  NonNullableFormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
// CurrencyPipe no longer needed in parent — used by child components
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { ProductService } from '@features/products/data/product.service';
import { ApiError } from '@shared/models';
import { PageHeader } from '@shared/ui';
import { StatusSelector } from '../../components/status-selector/status-selector';
import {
  DetailTable,
  type DetailTableRow,
} from '../../components/detail-table/detail-table';
import { OrderHeaderForm } from '../../components/order-header-form/order-header-form';
import type {
  PurchaseOrder,
  PurchaseOrderDetail,
  PurchaseOrderRequest,
  PurchaseOrderDetailRequest,
} from '../../models/purchase-order.models';
import type { PurchaseOrderHeaderControl } from '../../models/purchase-order-control.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ErrorBanner } from '@shared/ui';

@Component({
  selector: 'app-purchase-order-edit',
  standalone: true,
  imports: [
    RouterLink,
    ReactiveFormsModule,
    ErrorBanner,
    PageHeader,
    StatusSelector,
    DetailTable,
    OrderHeaderForm,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './purchase-order-edit.html',
  styleUrl: './purchase-order-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PurchaseOrderEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private fb = inject(NonNullableFormBuilder);
  private purchaseOrderService = inject(PurchaseOrderService);
  private productService = inject(ProductService);
  private destroyRef = inject(DestroyRef);

  // ─── Route data ────────────────────────────────────────
  readonly orderId = signal<string | null>(
    this.route.snapshot.paramMap.get('id'),
  );
  readonly isEditMode = computed(() => this.orderId() !== null);

  // ─── Header form ───────────────────────────────────────
  readonly headerForm = signal<FormGroup<PurchaseOrderHeaderControl>>(
    this.createForm(),
  );
  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);
  readonly saving = signal(false);

  // ─── Loaded order data (edit mode) ─────────────────────
  readonly loadedOrder = signal<PurchaseOrder | null>(null);
  readonly isDraft = computed(
    () => this.loadedOrder()?.statusName === 'Draft',
  );

  /** Store info passed down to `OrderHeaderForm` for edit mode. */
  readonly initialStore = computed(() => {
    const order = this.loadedOrder();
    if (!order) return null;
    return { id: order.companyStoreId, name: order.companyStoreName };
  });

  // ─── Line items ────────────────────────────────────────
  readonly lineItems = signal<DetailTableRow[]>([]);

  /** Products filtered by the selected supplier, passed to DetailTable. */
  readonly supplierProducts = signal<{ id: string; name: string; sku: string }[]>([]);

  ngOnInit(): void {
    // Watch supplier changes to load associated products
    this.headerForm().controls.supplierId.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((supplierId) => {
        if (supplierId) {
          this.productService.getProductsBySupplier(supplierId).subscribe({
            next: (products) =>
              this.supplierProducts.set(
                products.map((p) => ({
                  id: p.productId,
                  name: p.productName,
                  sku: p.sku,
                })),
              ),
          });
        } else {
          this.supplierProducts.set([]);
        }
      });

    const id = this.orderId();
    if (id) {
      this.loadOrder(id);
    }
  }

  // ══════════════════════════════════════════════════════════
  // DATA LOADING
  // ══════════════════════════════════════════════════════════

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
    this.headerForm().patchValue({
      supplierId: order.supplierId,
      companyStoreId: order.companyStoreId,
      paymentMethodId: order.paymentMethodId,
      comments: order.comments,
    });
  }

  private populateLineItems(details: PurchaseOrderDetail[]): void {
    const rows: DetailTableRow[] = details.map((d) => ({
      id: d.id,
      productId: d.productId,
      productName: d.productName,
      quantity: d.quantity,
      unitPrice: d.unitPrice,
    }));
    this.lineItems.set(rows);
  }

  // ══════════════════════════════════════════════════════════
  // CHILD EVENT HANDLERS
  // ══════════════════════════════════════════════════════════

  /** Called by `<app-detail-table>` when items are added or removed. */
  onItemsChanged(updated: DetailTableRow[]): void {
    this.lineItems.set(updated);
  }

  /** Called by `<app-status-selector>` after a successful PATCH. */
  onStatusChanged(statusId: string): void {
    const id = this.orderId();
    if (id) {
      // Reload the order to get the updated status from the backend
      this.loadOrder(id);
    }
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
    const details: PurchaseOrderDetailRequest[] = this.lineItems().map(
      (item) => ({
        productId: item.productId,
        quantity: item.quantity,
        unitPrice: item.unitPrice,
      }),
    );

    const request: PurchaseOrderRequest = {
      supplierId: formValue.supplierId,
      companyStoreId: formValue.companyStoreId,
      paymentMethodId: formValue.paymentMethodId,
      comments: formValue.comments ?? undefined,
      details: details.length > 0 ? details : undefined,
    };

    if (this.isEditMode()) {
      const id = this.orderId()!;
      this.purchaseOrderService.update(id, request).subscribe({
        next: () => {
          this.saving.set(false);
          this.router.navigate(['/purchases/orders']);
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.handleServerError(err);
        },
      });
    } else {
      this.purchaseOrderService.create(request).subscribe({
        next: (created) => {
          this.saving.set(false);
          this.router.navigate(['/purchases/orders', created.id]);
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
      this.generalError.set(
        'Error inesperado. Intente de nuevo más tarde.',
      );
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
    });
  }

  fieldError(field: keyof PurchaseOrderHeaderControl): string | null {
    const control = this.headerForm().controls[field];
    if (control && control.invalid && control.touched) {
      if (control.hasError('required')) {
        return 'Este campo es requerido.';
      }
    }
    return null;
  }

  serverFieldError(field: string): string | null {
    return this.serverErrors()[field] ?? null;
  }

  onCancel(): void {
    this.router.navigate(['/purchases/orders']);
  }
}
