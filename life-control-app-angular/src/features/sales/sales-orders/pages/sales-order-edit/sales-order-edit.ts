import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { HttpErrorResponse, HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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
import { PageHeader } from '@shared/ui';
import { OrderHeaderForm } from '../../components/order-header-form/order-header-form';
import { SalesOrderItemTable, type ItemTableRow } from '../../components/sales-order-item-table/sales-order-item-table';
import { StatusTransition } from '../../components/status-transition/status-transition';
import { ProductVariantSelector } from '../../components/product-variant-selector/product-variant-selector';
import type { SalesOrder, SalesOrderRequest } from '../../models/sales-order.models';
import type { ProductVariantOption } from '../../models/sales-order.models';
import type { SalesOrderHeaderControl } from '../../models/sales-order-control.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-sales-order-edit',
  standalone: true,
  imports: [
    RouterLink,
    ReactiveFormsModule,
    PageHeader,
    OrderHeaderForm,
    SalesOrderItemTable,
    StatusTransition,
    ProductVariantSelector,
    MatButtonModule,
    MatIconModule,
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

  /** Store name to display in the header (from profile in create mode, from loaded order in edit mode). */
  readonly displayStoreName = computed(() => {
    const order = this.loadedOrder();
    if (order?.companyStoreName) return order.companyStoreName;
    return this.userStoreName();
  });

  // ─── Line items ────────────────────────────────────────
  readonly lineItems = signal<ItemTableRow[]>([]);

  ngOnInit(): void {
    const id = this.orderId();
    if (id) {
      this.loadOrder(id);
    } else {
      this.loadUserStore();
    }
  }

  // ══════════════════════════════════════════════════════════
  // DATA LOADING
  // ══════════════════════════════════════════════════════════

  /** Load the user's preferred store from their profile (create mode only). */
  private loadUserStore(): void {
    this.profileService
      .getProfile()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (profile) => {
          if (profile.companyStoreId) {
            this.headerForm().patchValue({
              companyStoreId: profile.companyStoreId,
            });
            // Fetch store name via separate HTTP call using cascade IDs from profile
            this.fetchStoreName(
              profile.companyId,
              profile.companyCountryId,
              profile.companyRegionId,
              profile.companyZoneId,
              profile.companyStoreId,
            );
          }
        },
        error: () => {
          // Non-critical: form will be valid without a pre-set store
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
    const newRow: ItemTableRow = {
      productVariantId: variant.id,
      productVariantName: variant.variantName,
      quantity: 1,
      listPrice: variant.listPrice,
      discountApplied: 0,
    };
    this.lineItems.update((rows) => [newRow, ...rows]);
  }

  /** Called by `<app-sales-order-item-table>` when items are added or removed. */
  onItemsChanged(updated: ItemTableRow[]): void {
    this.lineItems.set(updated);
  }

  /** Called by `<app-status-transition>` after a successful PATCH. */
  onStatusChanged(statusId: string): void {
    const id = this.orderId();
    if (id) {
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

    const request: SalesOrderRequest = {
      customerId: formValue.customerId || undefined,
      companyStoreId: formValue.companyStoreId,
      shiftId: formValue.shiftId || undefined,
      items: this.lineItems().map((row) => ({
        ...(row.id && { id: row.id }),
        productVariantId: row.productVariantId,
        quantity: row.quantity,
        listPrice: row.listPrice,
        discountApplied: row.discountApplied,
      })),
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

  onCancel(): void {
    this.router.navigate(['/sales/orders']);
  }
}
