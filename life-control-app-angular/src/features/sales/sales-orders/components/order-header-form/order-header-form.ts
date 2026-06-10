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
import { HttpClient } from '@angular/common/http';
import {
  FormGroup,
  ReactiveFormsModule,
} from '@angular/forms';
import { ConfigService } from '@app/services/config.service';
import { SO_STATUS_COLORS, SO_STATUS_LABELS } from '../../data/status-config';
import type { SalesOrderHeaderControl } from '../../models/sales-order-control.models';
import type { SalesOrder, OpenShiftOption } from '../../models/sales-order.models';
import { DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';

interface DropdownOption {
  id: string;
  name: string;
}

/**
 * Reusable sales order header form component.
 *
 * Manages customer autocomplete (CustomerSelector child), store cascade
 * (company→country→region→zone→store), shift selector, comments textarea,
 * and read-only status/order number display in edit mode.
 *
 * Covers spec Requirements: create/edit header form aspects.
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

  /** The header form group from the parent component. */
  readonly headerForm = input.required<FormGroup<SalesOrderHeaderControl>>();

  /** Whether the form is in edit mode (load existing data). */
  readonly isEditMode = input<boolean>(false);

  /** Whether the order is in Draft status (enables field editing). */
  readonly isDraft = input<boolean>(false);

  /** The loaded sales order (edit mode only). Used for status/order number display. */
  readonly loadedOrder = input<SalesOrder | null>(null);

  /** Server-side validation errors keyed by field name. */
  readonly serverErrors = input<Record<string, string>>({});

  /**
   * In edit mode, the store is already assigned. Provide the store's
   * `{ id, name }` so the dropdown shows the current store.
   */
  readonly initialStore = input<{ id: string; name: string } | null>(null);

  // ─── Status display ────────────────────────────────────
  readonly statusColor = SO_STATUS_COLORS;
  readonly statusLabel = SO_STATUS_LABELS;

  readonly currentStatusName = computed(
    () => this.loadedOrder()?.statusName ?? null,
  );

  // ─── Store cascade ─────────────────────────────────────
  readonly companies = signal<DropdownOption[]>([]);
  readonly selectedCompanyId = signal<string | null>(null);
  readonly countries = signal<DropdownOption[]>([]);
  readonly selectedCountryId = signal<string | null>(null);
  readonly regions = signal<DropdownOption[]>([]);
  readonly selectedRegionId = signal<string | null>(null);
  readonly zones = signal<DropdownOption[]>([]);
  readonly selectedZoneId = signal<string | null>(null);
  readonly stores = signal<DropdownOption[]>([]);

  // ─── Shift selector ────────────────────────────────────
  readonly openShifts = signal<OpenShiftOption[]>([]);

  ngOnInit(): void {
    this.loadDropdowns();
    this.applyInitialStore();
  }

  private applyInitialStore(): void {
    const store = this.initialStore();
    if (store) {
      this.stores.set([store]);
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
    // Load companies for cascade
    this.http
      .get<{ content: { id: string; companyName: string }[] }>(
        `${this.configService.apiUrl}/companies`,
        { params: { page: '0', size: '1000' } },
      )
      .pipe(
        map((p) =>
          p.content.map((c) => ({ id: c.id, name: c.companyName })),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => this.companies.set(list),
      });

    // Load open shifts
    this.http
      .get<OpenShiftOption[]>(`${this.configService.apiUrl}/shifts/open`)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => this.openShifts.set(list),
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
      this.http
        .get<{ id: string; countryName: string }[]>(
          `${this.configService.apiUrl}/companies/${companyId}/countries`,
        )
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
      this.http
        .get<{ id: string; regionName: string }[]>(
          `${this.configService.apiUrl}/companies/${companyId}/countries/${countryId}/regions`,
        )
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
      this.http
        .get<{ id: string; zoneName: string }[]>(
          `${this.configService.apiUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones`,
        )
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
      this.http
        .get<{ id: string; storeName: string; enabled: boolean }[]>(
          `${this.configService.apiUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores`,
        )
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

  fieldError(field: keyof SalesOrderHeaderControl): string | null {
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
