import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
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
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { ProfileService } from '@features/user/profile/data/profile.service';
import type { PurchaseOrderHeaderControl } from '../../models/purchase-order-control.models';
import type { PurchaseOrder } from '../../models/purchase-order.models';
import type { Company } from '@features/companies/companies/models/company.models';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';

interface DropdownOption {
  id: string;
  name: string;
}

interface CompanyDetail {
  rfc: string;
  address: string;
  phone: string;
  email: string;
}

/**
 * Company info section for the purchase order edit form.
 *
 * Displays the company cascade (Company → Country → Region → Zone → Store)
 * with a read-only company details card. On create mode, the cascade is
 * pre-populated from the authenticated user's profile. On edit mode, the
 * cascade is reconstructed from the loaded order's cascade IDs.
 *
 * Covers spec Requirements F1–F3, F5.
 */
@Component({
  selector: 'app-company-info-section',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatSelectModule,
  ],
  templateUrl: './company-info-section.html',
  styleUrl: './company-info-section.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyInfoSection implements OnInit {
  private destroyRef = inject(DestroyRef);
  private http = inject(HttpClient);
  private configService = inject(ConfigService);
  private companyService = inject(CompanyService);
  private companyCountryService = inject(CompanyCountryService);
  private companyRegionService = inject(CompanyRegionService);
  private companyZoneService = inject(CompanyZoneService);
  private companyStoreService = inject(CompanyStoreService);
  private profileService = inject(ProfileService);

  /** The header form group from the parent component. */
  readonly headerForm = input.required<FormGroup<PurchaseOrderHeaderControl>>();

  /** Whether the form is in edit mode. */
  readonly isEditMode = input<boolean>(false);

  /** The loaded purchase order (edit mode only). */
  readonly loadedOrder = input<PurchaseOrder | null>(null);

  /** Server-side validation errors keyed by field name. */
  readonly serverErrors = input<Record<string, string>>({});

  // ─── Company cascade ─────────────────────────────────────

  readonly companies = signal<DropdownOption[]>([]);
  readonly selectedCompanyId = signal<string | null>(null);
  readonly companyDetail = signal<CompanyDetail | null>(null);
  readonly companyDetailLoading = signal(false);

  readonly countries = signal<DropdownOption[]>([]);
  readonly selectedCountryId = signal<string | null>(null);

  readonly regions = signal<DropdownOption[]>([]);
  readonly selectedRegionId = signal<string | null>(null);

  readonly zones = signal<DropdownOption[]>([]);
  readonly selectedZoneId = signal<string | null>(null);

  readonly stores = signal<DropdownOption[]>([]);

  // ─── Cascade reconstruction targets ──────────────────────

  private reconstructTargets: {
    companyId: string | null;
    companyCountryId: string | null;
    regionId: string | null;
    zoneId: string | null;
    companyStoreId: string | null;
  } | null = null;

  ngOnInit(): void {
    this.loadCompanies();

    if (!this.isEditMode()) {
      this.loadProfileCascade();
    }
  }

  /**
   * Reacts to loadedOrder changes in edit mode, setting up cascade
   * reconstruction targets when the order becomes available.
   * This handles both initial render and late input resolution.
   */
  private readonly initFromOrder = effect(() => {
    const order = this.loadedOrder();
    if (this.isEditMode() && order?.companyId) {
      this.reconstructTargets = {
        companyId: order.companyId,
        companyCountryId: order.companyCountryId,
        regionId: order.regionId,
        zoneId: order.zoneId,
        companyStoreId: order.companyStoreId,
      };
      this.advanceCascade();
    }
  });

  /**
   * Loads the user profile to pre-select the company cascade on create mode.
   * Silently fails — cascade stays empty if profile can't be loaded.
   */
  private loadProfileCascade(): void {
    this.profileService
      .getProfile()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (profile) => {
          if (profile.companyId) {
            this.reconstructTargets = {
              companyId: profile.companyId,
              companyCountryId: profile.companyCountryId,
              regionId: profile.companyRegionId,
              zoneId: profile.companyZoneId,
              companyStoreId: profile.companyStoreId,
            };
            // Companies may already be loaded — retry cascade
            this.advanceCascade();
          }
        },
        // Silently fail
      });
  }

  // ══════════════════════════════════════════════════════════
  // DATA LOADING
  // ══════════════════════════════════════════════════════════

  private loadCompanies(): void {
    this.companyService
      .getCompanies(0, 1000)
      .pipe(
        map((p) =>
          p.content.map((c) => ({ id: c.id, name: c.companyName })),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => {
          this.companies.set(list);
          this.advanceCascade();
        },
      });
  }

  // ══════════════════════════════════════════════════════════
  // COMPANY DETAILS
  // ══════════════════════════════════════════════════════════

  private loadCompanyDetails(companyId: string): void {
    this.companyDetailLoading.set(true);
    this.companyService
      .getCompanyById(companyId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (company) => {
          this.companyDetail.set(this.formatCompanyDetail(company));
          this.companyDetailLoading.set(false);
        },
        error: () => this.companyDetailLoading.set(false),
      });
  }

  private formatCompanyDetail(company: Company): CompanyDetail {
    const address = company.address;
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
      rfc: company.rfc,
      address: addressParts.length > 0 ? addressParts.join(', ') : '—',
      phone: company.phone,
      email: company.email,
    };
  }

  // ══════════════════════════════════════════════════════════
  // CASCADE RECONSTRUCTION
  // ══════════════════════════════════════════════════════════

  /**
   * Advances the cascade one level at a time after each level's data loads.
   * Called after companies, countries, regions, or zones are loaded.
   */
  private advanceCascade(): void {
    const targets = this.reconstructTargets;
    if (!targets || !targets.companyId) return;

    const companyId = targets.companyId;

    // Step 1: select company (only if companies are loaded and target exists)
    if (!this.selectedCompanyId() && this.companies().some((c) => c.id === companyId)) {
      this.selectedCompanyId.set(companyId);
      this.loadCompanyDetails(companyId);

      // Load countries
      this.companyCountryService
        .getCountries(companyId)
        .pipe(
          map((list) =>
            list.map((c) => ({ id: c.id, name: c.countryName })),
          ),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (countries) => {
            this.countries.set(countries);
            this.advanceCascade();
          },
        });
      return;
    }

    // Step 2: select country
    if (
      this.selectedCompanyId() &&
      !this.selectedCountryId() &&
      targets.companyCountryId &&
      this.countries().some((c) => c.id === targets.companyCountryId)
    ) {
      this.selectedCountryId.set(targets.companyCountryId);

      // Load regions
      this.companyRegionService
        .getRegions(companyId, targets.companyCountryId)
        .pipe(
          map((list) =>
            list.map((r) => ({ id: r.id, name: r.regionName })),
          ),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (regions) => {
            this.regions.set(regions);
            this.advanceCascade();
          },
        });
      return;
    }

    // Step 3: select region
    if (
      this.selectedCountryId() &&
      !this.selectedRegionId() &&
      targets.regionId &&
      this.regions().some((r) => r.id === targets.regionId)
    ) {
      this.selectedRegionId.set(targets.regionId);

      // Load zones
      this.companyZoneService
        .getZones(companyId, targets.companyCountryId!, targets.regionId)
        .pipe(
          map((list) =>
            list.map((z) => ({ id: z.id, name: z.zoneName })),
          ),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (zones) => {
            this.zones.set(zones);
            this.advanceCascade();
          },
        });
      return;
    }

    // Step 4: select zone
    if (
      this.selectedRegionId() &&
      !this.selectedZoneId() &&
      targets.zoneId &&
      this.zones().some((z) => z.id === targets.zoneId)
    ) {
      this.selectedZoneId.set(targets.zoneId);

      // Load stores
      this.companyStoreService
        .getStores(
          companyId,
          targets.companyCountryId!,
          targets.regionId!,
          targets.zoneId,
        )
        .pipe(
          map((list) =>
            list
              .filter((s) => s.enabled)
              .map((s) => ({ id: s.id, name: s.storeName })),
          ),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (stores) => {
            this.stores.set(stores);
            this.advanceCascade();
          },
        });
      return;
    }

    // Step 5: select store (patch form value)
    if (
      this.selectedZoneId() &&
      targets.companyStoreId &&
      this.stores().some((s) => s.id === targets.companyStoreId)
    ) {
      this.headerForm()
        .controls.companyStoreId.setValue(targets.companyStoreId, {
          emitEvent: false,
        });
    }
  }

  // ══════════════════════════════════════════════════════════
  // CASCADE CHANGE HANDLERS
  // ══════════════════════════════════════════════════════════

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId || null);
    this.companyDetail.set(null);
    this.countries.set([]);
    this.selectedCountryId.set(null);
    this.regions.set([]);
    this.selectedRegionId.set(null);
    this.zones.set([]);
    this.selectedZoneId.set(null);
    this.stores.set([]);

    // Clear store form control
    this.headerForm().controls.companyStoreId.setValue('', {
      emitEvent: false,
    });

    if (companyId) {
      this.loadCompanyDetails(companyId);

      this.companyCountryService
        .getCountries(companyId)
        .pipe(
          map((list) =>
            list.map((c) => ({ id: c.id, name: c.countryName })),
          ),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (countries) => this.countries.set(countries),
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

    this.headerForm().controls.companyStoreId.setValue('', {
      emitEvent: false,
    });

    const companyId = this.selectedCompanyId();
    if (companyId && countryId) {
      this.companyRegionService
        .getRegions(companyId, countryId)
        .pipe(
          map((list) =>
            list.map((r) => ({ id: r.id, name: r.regionName })),
          ),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (regions) => this.regions.set(regions),
        });
    }
  }

  onRegionChange(regionId: string): void {
    this.selectedRegionId.set(regionId || null);
    this.zones.set([]);
    this.selectedZoneId.set(null);
    this.stores.set([]);

    this.headerForm().controls.companyStoreId.setValue('', {
      emitEvent: false,
    });

    const companyId = this.selectedCompanyId();
    const countryId = this.selectedCountryId();
    if (companyId && countryId && regionId) {
      this.companyZoneService
        .getZones(companyId, countryId, regionId)
        .pipe(
          map((list) =>
            list.map((z) => ({ id: z.id, name: z.zoneName })),
          ),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (zones) => this.zones.set(zones),
        });
    }
  }

  onZoneChange(zoneId: string): void {
    this.selectedZoneId.set(zoneId || null);
    this.stores.set([]);

    this.headerForm().controls.companyStoreId.setValue('', {
      emitEvent: false,
    });

    const companyId = this.selectedCompanyId();
    const countryId = this.selectedCountryId();
    const regionId = this.selectedRegionId();
    if (companyId && countryId && regionId && zoneId) {
      this.companyStoreService
        .getStores(companyId, countryId, regionId, zoneId)
        .pipe(
          map((list) =>
            list
              .filter((s) => s.enabled)
              .map((s) => ({ id: s.id, name: s.storeName })),
          ),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (stores) => this.stores.set(stores),
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
