import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { map } from 'rxjs/operators';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';
import { SupplierService } from '@features/products/suppliers/data/supplier.service';
import type { PurchaseOrderHeaderControl } from '../../models/purchase-order-control.models';
import type { Supplier } from '@features/products/suppliers/models/supplier.models';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

interface DropdownOption {
  id: string;
  name: string;
}

interface PaymentMethod {
  id: string;
  name: string;
}

interface SupplierDetail {
  rfc: string;
  address: string;
  phone: string;
  email: string;
}

/**
 * Supplier info section for the purchase order edit form.
 *
 * Displays a supplier dropdown with a read-only supplier details card,
 * a payment method selector, and a comments textarea.
 *
 * Covers spec Requirements F4–F5.
 */
@Component({
  selector: 'app-supplier-info-section',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './supplier-info-section.html',
  styleUrl: './supplier-info-section.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupplierInfoSection implements OnInit {
  private destroyRef = inject(DestroyRef);
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private supplierService = inject(SupplierService);

  /** The header form group from the parent component. */
  readonly headerForm = input.required<FormGroup<PurchaseOrderHeaderControl>>();

  /** Server-side validation errors keyed by field name. */
  readonly serverErrors = input<Record<string, string>>({});

  // ─── FK dropdowns ──────────────────────────────────────

  readonly suppliers = signal<DropdownOption[]>([]);
  readonly selectedSupplierId = signal<string | null>(null);
  readonly supplierDetail = signal<SupplierDetail | null>(null);
  readonly supplierDetailLoading = signal(false);

  readonly paymentMethods = signal<PaymentMethod[]>([]);

  ngOnInit(): void {
    this.loadSuppliers();
    this.loadPaymentMethods();
  }

  // ══════════════════════════════════════════════════════════
  // DATA LOADING
  // ══════════════════════════════════════════════════════════

  private loadSuppliers(): void {
    this.supplierService
      .getAllSuppliers(0, 1000)
      .pipe(
        map((p) =>
          p.content.map((s) => ({
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
    this.http
      .get<PaymentMethod[]>(
        `${this.configService.apiUrl}/payment-methods`,
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => this.paymentMethods.set(list),
      });
  }

  // ══════════════════════════════════════════════════════════
  // SUPPLIER DETAILS
  // ══════════════════════════════════════════════════════════

  onSupplierChange(supplierId: string): void {
    this.selectedSupplierId.set(supplierId || null);
    this.supplierDetail.set(null);

    if (supplierId) {
      this.loadSupplierDetails(supplierId);
    }
  }

  private loadSupplierDetails(supplierId: string): void {
    this.supplierDetailLoading.set(true);
    this.supplierService
      .getSupplierById(supplierId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (supplier) => {
          this.supplierDetail.set(this.formatSupplierDetail(supplier));
          this.supplierDetailLoading.set(false);
        },
        error: () => this.supplierDetailLoading.set(false),
      });
  }

  private formatSupplierDetail(supplier: Supplier): SupplierDetail {
    const address = supplier.address;
    const addressParts: string[] = [];

    if (address) {
      const street =
        [address.street, address.streetNumber]
          .filter(Boolean)
          .join(' ') || '';
      if (street) addressParts.push(street);
      if (address.neighborhood) addressParts.push(address.neighborhood);
      const zipCity = [address.zipCode, address.city]
        .filter(Boolean)
        .join(' ');
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
}
