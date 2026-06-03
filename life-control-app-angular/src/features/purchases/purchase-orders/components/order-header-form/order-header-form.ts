import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { map } from 'rxjs/operators';
import {
  FormGroup,
  ReactiveFormsModule,
} from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { ConfigService } from '@app/services/config.service';
import { SupplierService } from '@features/products/suppliers/data/supplier.service';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { PO_STATUS_COLORS, PO_STATUS_LABELS } from '../../data/status-config';
import type { PurchaseOrderHeaderControl } from '../../models/purchase-order-control.models';
import type { PurchaseOrder } from '../../models/purchase-order.models';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';

interface PaymentMethod {
  id: string;
  name: string;
}

interface DropdownOption {
  id: string;
  name: string;
}

/**
 * Reusable purchase order header form component.
 *
 * Extracted from `purchase-order-edit.ts` to separate concerns.
 * Manages supplier dropdown, store cascade (company→country→region→zone→store),
 * payment method dropdown, comments textarea, and read-only status/order number display.
 *
 * Covers spec Requirements 4-5 header form aspects.
 */
@Component({
  selector: 'app-order-header-form',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    DatePipe,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
  ],
  templateUrl: './order-header-form.html',
  styleUrl: './order-header-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderHeaderForm implements OnInit {
  private destroyRef = inject(DestroyRef);
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private supplierService = inject(SupplierService);
  private companyService = inject(CompanyService);
  private companyCountryService = inject(CompanyCountryService);
  private companyRegionService = inject(CompanyRegionService);
  private companyZoneService = inject(CompanyZoneService);
  private companyStoreService = inject(CompanyStoreService);

  /** The header form group from the parent component. */
  readonly headerForm = input.required<FormGroup<PurchaseOrderHeaderControl>>();

  /** Whether the form is in edit mode (load existing data). */
  readonly isEditMode = input<boolean>(false);

  /** The loaded purchase order (edit mode only). Used for status/order number display. */
  readonly loadedOrder = input<PurchaseOrder | null>(null);

  /** Server-side validation errors keyed by field name. */
  readonly serverErrors = input<Record<string, string>>({});

  /**
   * In edit mode, the store is already assigned. Provide the store's
   * `{ id, name }` so the dropdown shows the current store. The full
   * cascade (company→country→region→zone) is NOT resolved automatically
   * because the backend response does not include the hierarchy path.
   * When the user starts selecting cascade options, this pre-populated
   * store is replaced.
   */
  readonly initialStore = input<{ id: string; name: string } | null>(null);

  // ─── Status display ────────────────────────────────────
  readonly statusColor = PO_STATUS_COLORS;
  readonly statusLabel = PO_STATUS_LABELS;

  readonly currentStatusName = computed(
    () => this.loadedOrder()?.statusName ?? null,
  );

  // ─── FK dropdowns ──────────────────────────────────────
  readonly suppliers = signal<DropdownOption[]>([]);

  // Store cascade
  readonly companies = signal<DropdownOption[]>([]);
  readonly selectedCompanyId = signal<string | null>(null);
  readonly countries = signal<DropdownOption[]>([]);
  readonly selectedCountryId = signal<string | null>(null);
  readonly regions = signal<DropdownOption[]>([]);
  readonly selectedRegionId = signal<string | null>(null);
  readonly zones = signal<DropdownOption[]>([]);
  readonly selectedZoneId = signal<string | null>(null);
  readonly stores = signal<DropdownOption[]>([]);

  // Payment methods
  readonly paymentMethods = signal<PaymentMethod[]>([]);

  ngOnInit(): void {
    this.loadDropdowns();
    this.applyInitialStore();
  }

  private applyInitialStore(): void {
    const store = this.initialStore();
    if (store) {
      this.stores.set([store]);
      // Ensure the form has the store ID set (in case patchValue hasn't run yet)
      const currentStoreId = this.headerForm().controls.companyStoreId.value;
      if (!currentStoreId) {
        this.headerForm().controls.companyStoreId.setValue(store.id, {
          emitEvent: false,
        });
      }
    }
  }

  // ══════════════════════════════════════════════════════════
  // DATA LOADING
  // ══════════════════════════════════════════════════════════

  private loadDropdowns(): void {
    // Load all suppliers
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

    // Load all companies for cascade
    this.companyService
      .getCompanies(0, 1000)
      .pipe(
        map((p) =>
          p.content.map((c) => ({ id: c.id, name: c.companyName })),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => this.companies.set(list),
      });

    // Load payment methods
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
  // CASCADE: Company → Country → Region → Zone → Store
  // ══════════════════════════════════════════════════════════

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId || null);
    this.countries.set([]);
    this.selectedCountryId.set(null);
    this.regions.set([]);
    this.selectedRegionId.set(null);
    this.zones.set([]);
    this.selectedZoneId.set(null);
    this.stores.set([]);

    if (companyId) {
      this.companyCountryService
        .getCountries(companyId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (ccList) =>
            this.countries.set(
              ccList.map((cc) => ({
                id: cc.id,
                name: cc.countryName,
              })),
            ),
        });
    }
  }

  onCountryChange(countryId: string): void {
    this.selectedCountryId.set(countryId || null);
    this.regions.set([]);
    this.selectedRegionId.set(null);
    this.zones.set([]);
    this.selectedZoneId.set(null);
    this.stores.set([]);

    const companyId = this.selectedCompanyId();
    if (companyId && countryId) {
      this.companyRegionService
        .getRegions(companyId, countryId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (regionList) =>
            this.regions.set(
              regionList.map((r) => ({
                id: r.id,
                name: r.regionName,
              })),
            ),
        });
    }
  }

  onRegionChange(regionId: string): void {
    this.selectedRegionId.set(regionId || null);
    this.zones.set([]);
    this.selectedZoneId.set(null);
    this.stores.set([]);

    const companyId = this.selectedCompanyId();
    const countryId = this.selectedCountryId();
    if (companyId && countryId && regionId) {
      this.companyZoneService
        .getZones(companyId, countryId, regionId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (zoneList) =>
            this.zones.set(
              zoneList.map((z) => ({
                id: z.id,
                name: z.zoneName,
              })),
            ),
        });
    }
  }

  onZoneChange(zoneId: string): void {
    this.selectedZoneId.set(zoneId || null);
    this.stores.set([]);

    const companyId = this.selectedCompanyId();
    const countryId = this.selectedCountryId();
    const regionId = this.selectedRegionId();
    if (companyId && countryId && regionId && zoneId) {
      this.companyStoreService
        .getStores(companyId, countryId, regionId, zoneId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (storeList) =>
            this.stores.set(
              storeList
                .filter((s) => s.enabled)
                .map((s) => ({ id: s.id, name: s.storeName })),
            ),
        });
    }
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
