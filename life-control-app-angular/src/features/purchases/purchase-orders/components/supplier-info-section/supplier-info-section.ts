import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  OnInit,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, debounceTime } from 'rxjs';
import { map } from 'rxjs/operators';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { SupplierService } from '@features/products/suppliers/data/supplier.service';
import { PaymentMethodService } from '../../data/payment-method.service';
import { requiredFieldError, serverError } from '../../utils/form-error.utils';
import type { PurchaseOrderHeaderControl } from '../../models/purchase-order-control.models';
import type { PurchaseOrder } from '../../models/purchase-order.models';
import type { SelectOption } from '../../models/select-option.models';
import type { Supplier } from '@features/products/suppliers/models/supplier.models';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatSelectModule } from '@angular/material/select';

interface SupplierDetail {
  rfc: string;
  address: string;
  phone: string;
  email: string;
}

/** Number of suppliers fetched per server-side search. */
const SUPPLIER_PAGE_SIZE = 20;

/**
 * Supplier info section for the purchase order edit form.
 *
 * Renders a supplier autocomplete with server-side search, a read-only supplier
 * details card, a payment method selector, and a comments textarea.
 */
@Component({
  selector: 'app-supplier-info-section',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatAutocompleteModule,
    MatSelectModule,
  ],
  templateUrl: './supplier-info-section.html',
  styleUrl: './supplier-info-section.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupplierInfoSection implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly supplierService = inject(SupplierService);
  private readonly paymentMethodService = inject(PaymentMethodService);

  /** The header form group from the parent component. */
  readonly headerForm = input.required<FormGroup<PurchaseOrderHeaderControl>>();

  /**
   * The company that owns the order, and therefore the only suppliers this picker may offer.
   *
   * It is the header's cascade root (`orderCompanyId` on the screen), which the screen already scopes its
   * product reads to: a supplier belongs to one company (issue #672), so there is no company-blind
   * catalog to search and `null` is the honest answer while a new order still has none.
   */
  readonly companyId = input<string | null>(null);

  /** Whether the form is in edit mode. */
  readonly isEditMode = input<boolean>(false);

  /** The loaded purchase order (edit mode only). */
  readonly loadedOrder = input<PurchaseOrder | null>(null);

  /** Server-side validation errors keyed by field name. */
  readonly serverErrors = input<Record<string, string>>({});

  // ─── FK dropdowns ──────────────────────────────────────
  readonly suppliers = signal<SelectOption[]>([]);
  readonly supplierDetail = signal<SupplierDetail | null>(null);
  readonly supplierDetailLoading = signal(false);
  readonly paymentMethods = signal<SelectOption[]>([]);

  private readonly supplierSearch$ = new Subject<string>();

  /** The last term the operator typed, so a company change re-issues the same search. */
  private lastSupplierTerm = '';

  constructor() {
    // Reconstruct supplier details from the loaded order (edit mode). The parent
    // patches the form with `emitEvent: false`, so `onSupplierChange` never fires.
    // The company is read here too: the purchase order scopes the supplier read, and on edit the
    // company arrives with the same loaded order the supplier id does.
    effect(() => {
      const order = this.loadedOrder();
      const isEditMode = this.isEditMode();
      const companyId = this.companyId();
      untracked(() => {
        if (isEditMode && order?.supplierId && companyId) {
          this.loadSupplierDetails(order.supplierId);
        }
      });
    });

    // The picker follows the company: choosing a company (or changing it) re-issues the current search,
    // so the options are always one company's. With no company there is nothing to offer.
    effect(() => {
      const companyId = this.companyId();
      untracked(() => {
        if (companyId) {
          this.searchSuppliers(this.lastSupplierTerm);
        } else {
          this.suppliers.set([]);
        }
      });
    });

    this.supplierSearch$
      .pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef))
      .subscribe((term) => this.searchSuppliers(term.trim()));
  }

  ngOnInit(): void {
    // The search itself is issued by the company effect above: with no company there is no catalog to
    // ask for.
    this.loadPaymentMethods();
  }

  // ══════════════════════════════════════════════════════════
  // DATA LOADING
  // ══════════════════════════════════════════════════════════

  private searchSuppliers(term: string): void {
    const companyId = this.companyId();
    if (!companyId) {
      // No supplier belongs to no company: nothing to offer, and nothing to ask for.
      this.suppliers.set([]);
      return;
    }

    this.lastSupplierTerm = term;
    this.supplierService
      .getSuppliers(companyId, 0, SUPPLIER_PAGE_SIZE, term || undefined)
      .pipe(
        // A row the API reports as another company's is dropped even if it arrives in the page: the
        // order may only name a supplier of its own company.
        map((page) => page.content.filter((s) => !s.companyId || s.companyId === companyId)),
        map((suppliers) =>
          suppliers.map((s) => ({
            id: s.id,
            name: s.supplierName,
          })),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => this.suppliers.set(list),
      });
  }

  private loadPaymentMethods(): void {
    this.paymentMethodService
      .getPaymentMethods()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => this.paymentMethods.set(list),
      });
  }

  // ══════════════════════════════════════════════════════════
  // SUPPLIER DETAILS
  // ══════════════════════════════════════════════════════════

  /** Autocomplete display formatter: maps a supplier UUID to its name. */
  displaySupplier = (id: string | null): string =>
    this.suppliers().find((supplier) => supplier.id === id)?.name ?? id ?? '';

  onSupplierSearch(event: Event): void {
    this.supplierSearch$.next((event.target as HTMLInputElement).value);
  }

  onSupplierChange(supplierId: string | null): void {
    this.headerForm().controls.supplierId.setValue(supplierId ?? '');
    this.supplierDetail.set(null);

    if (supplierId) {
      this.loadSupplierDetails(supplierId);
    }
  }

  private loadSupplierDetails(supplierId: string): void {
    const companyId = this.companyId();
    if (!companyId) {
      // `GET /api/companies/{companyId}/suppliers/{id}` is the only read: without the company there is
      // no detail to show, and inventing one would display another company's supplier.
      this.supplierDetail.set(null);
      this.supplierDetailLoading.set(false);
      return;
    }

    this.supplierDetailLoading.set(true);
    this.supplierService
      .getSupplierById(companyId, supplierId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (supplier) => {
          this.supplierDetail.set(this.formatSupplierDetail(supplier));
          this.supplierDetailLoading.set(false);
          this.suppliers.update((list) =>
            list.some((s) => s.id === supplier.id)
              ? list
              : [{ id: supplier.id, name: supplier.supplierName }, ...list],
          );
        },
        error: () => this.supplierDetailLoading.set(false),
      });
  }

  private formatSupplierDetail(supplier: Supplier): SupplierDetail {
    const address = supplier.address;
    const addressParts: string[] = [];

    if (address) {
      const street = [address.street, address.streetNumber].filter(Boolean).join(' ') || '';
      if (street) addressParts.push(street);
      if (address.neighborhood) addressParts.push(address.neighborhood);
      const zipCity = [address.zipCode, address.city].filter(Boolean).join(' ');
      if (zipCity) addressParts.push(zipCity);
      if (address.state) addressParts.push(address.state);
    }

    return {
      rfc: supplier.rfc,
      address: addressParts.length > 0 ? addressParts.join(', ') : '—',
      phone: supplier.phoneNumber,
      email: supplier.email,
    };
  }

  // ══════════════════════════════════════════════════════════
  // FORM HELPERS
  // ══════════════════════════════════════════════════════════

  fieldError(field: keyof PurchaseOrderHeaderControl): string | null {
    return requiredFieldError(this.headerForm().controls[field]);
  }

  serverFieldError(field: string): string | null {
    return serverError(this.serverErrors(), field);
  }
}
