import { DestroyRef, inject, Injectable, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { FormGroup } from '@angular/forms';
import { map } from 'rxjs/operators';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { ProfileService } from '@features/user/profile/data/profile.service';
import type { Company } from '@features/companies/companies/models/company.models';
import type { PurchaseOrderHeaderControl } from '../models/purchase-order-control.models';
import type { PurchaseOrder } from '../models/purchase-order.models';
import type { SelectOption } from '../models/select-option.models';

/** Read-only company summary rendered under the cascade. */
export interface CompanyDetail {
  rfc: string;
  address: string;
  phone: string;
  email: string;
}

interface CascadeTargets {
  companyId: string | null;
  companyCountryId: string | null;
  regionId: string | null;
  zoneId: string | null;
  companyStoreId: string | null;
}

/** Number of options fetched per server-side company search. */
const COMPANY_PAGE_SIZE = 20;

/**
 * Owns the Company → Country → Region → Zone → Store cascade for the purchase
 * order editor.
 *
 * Selected IDs live in the nested `company` group of the header form (except
 * `companyStoreId`, which stays at the header level because it is the only
 * value sent to the backend). This service drives the dependent loads, the
 * server-side company search, and the cascade reconstruction from a loaded
 * order (edit mode) or the authenticated user's profile (create mode).
 */
@Injectable()
export class CompanyCascadeService {
  private readonly destroyRef = inject(DestroyRef);
  private readonly companyService = inject(CompanyService);
  private readonly companyCountryService = inject(CompanyCountryService);
  private readonly companyRegionService = inject(CompanyRegionService);
  private readonly companyZoneService = inject(CompanyZoneService);
  private readonly companyStoreService = inject(CompanyStoreService);
  private readonly profileService = inject(ProfileService);

  // ─── Company ───────────────────────────────────────────
  readonly companies = signal<SelectOption[]>([]);
  readonly companiesLoading = signal(false);
  readonly companyDetail = signal<CompanyDetail | null>(null);
  readonly companyDetailLoading = signal(false);

  // ─── Dependent levels ──────────────────────────────────
  readonly countries = signal<SelectOption[]>([]);
  readonly regions = signal<SelectOption[]>([]);
  readonly zones = signal<SelectOption[]>([]);
  readonly stores = signal<SelectOption[]>([]);

  /**
   * Store the cascade last applied to the header's `companyStoreId` control.
   *
   * Every programmatic patch of that control uses `emitEvent: false` (the
   * documented contract: silent patches must not mark the form dirty), so
   * `companyStoreId.valueChanges` never sees them. Consumers that must follow
   * the resolved store — like the variant picker scope — read this signal
   * instead. All patches go through {@link patchStore} so it cannot drift from
   * the control.
   */
  readonly storeId = signal('');

  private form: FormGroup<PurchaseOrderHeaderControl> | null = null;
  private targets: CascadeTargets | null = null;
  private detailCompanyId: string | null = null;

  // ══════════════════════════════════════════════════════════
  // BINDING
  // ══════════════════════════════════════════════════════════

  /** Binds the header form and loads the initial company page. Idempotent. */
  bind(form: FormGroup<PurchaseOrderHeaderControl>): void {
    if (this.form === form) {
      return;
    }
    this.form = form;
    untracked(() => {
      this.searchCompanies('');
      this.advanceCascade();
    });
  }

  // ══════════════════════════════════════════════════════════
  // SERVER-SIDE SEARCH
  // ══════════════════════════════════════════════════════════

  /** Search companies by term (server-side, paginated). */
  searchCompanies(term: string): void {
    this.companiesLoading.set(true);
    this.companyService
      .getCompanies(0, COMPANY_PAGE_SIZE, term || undefined)
      .pipe(
        map((page) =>
          page.content.map((company) => ({ id: company.id, name: company.companyName })),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => {
          this.companies.set(list);
          this.companiesLoading.set(false);
        },
        error: () => this.companiesLoading.set(false),
      });
  }

  // ══════════════════════════════════════════════════════════
  // RECONSTRUCTION
  // ══════════════════════════════════════════════════════════

  /** Rebuilds the cascade from a loaded order (edit mode). */
  reconstructFromOrder(order: PurchaseOrder): void {
    if (!order.companyId) {
      return;
    }
    this.reconstruct({
      companyId: order.companyId,
      companyCountryId: order.companyCountryId,
      regionId: order.regionId,
      zoneId: order.zoneId,
      companyStoreId: order.companyStoreId,
    });
  }

  /** Rebuilds the cascade from the authenticated user's profile (create mode). */
  reconstructFromProfile(): void {
    this.profileService
      .getProfile()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (profile) => {
          if (!profile.companyId) {
            return;
          }
          this.reconstruct({
            companyId: profile.companyId,
            companyCountryId: profile.companyCountryId,
            regionId: profile.companyRegionId,
            zoneId: profile.companyZoneId,
            companyStoreId: profile.companyStoreId,
          });
        },
        // Silently fail — the cascade stays empty.
      });
  }

  private reconstruct(targets: CascadeTargets): void {
    this.targets = targets;
    this.advanceCascade();
  }

  /**
   * Advances the cascade one level at a time, selecting each level with
   * `emitEvent: false` (programmatic patches never mark the form dirty) until
   * the target store is applied to the header form.
   */
  private advanceCascade(): void {
    const targets = this.targets;
    if (!this.form || !targets?.companyId) {
      return;
    }

    const controls = this.form.controls.company.controls;

    // Step 1: company
    if (!controls.companyId.value) {
      if (!this.companies().some((c) => c.id === targets.companyId)) {
        // Seed the option list so the autocomplete can render the selection.
        this.loadCompanyDetails(targets.companyId);
        return;
      }
      controls.companyId.setValue(targets.companyId, { emitEvent: false });
      if (this.detailCompanyId !== targets.companyId) {
        this.loadCompanyDetails(targets.companyId);
      }
      this.loadCountries(targets.companyId);
      return;
    }

    // Step 2: country
    if (
      targets.companyCountryId &&
      !controls.companyCountryId.value &&
      this.countries().some((c) => c.id === targets.companyCountryId)
    ) {
      controls.companyCountryId.setValue(targets.companyCountryId, { emitEvent: false });
      this.loadRegions(targets.companyId, targets.companyCountryId);
      return;
    }

    // Step 3: region
    if (
      targets.regionId &&
      targets.companyCountryId &&
      !controls.regionId.value &&
      this.regions().some((r) => r.id === targets.regionId)
    ) {
      controls.regionId.setValue(targets.regionId, { emitEvent: false });
      this.loadZones(targets.companyId, targets.companyCountryId, targets.regionId);
      return;
    }

    // Step 4: zone
    if (
      targets.zoneId &&
      targets.companyCountryId &&
      targets.regionId &&
      !controls.zoneId.value &&
      this.zones().some((z) => z.id === targets.zoneId)
    ) {
      controls.zoneId.setValue(targets.zoneId, { emitEvent: false });
      this.loadStores(
        targets.companyId,
        targets.companyCountryId,
        targets.regionId,
        targets.zoneId,
      );
      return;
    }

    // Step 5: store (header-level control sent to the backend)
    if (
      targets.companyStoreId &&
      targets.zoneId &&
      controls.zoneId.value === targets.zoneId &&
      this.stores().some((s) => s.id === targets.companyStoreId)
    ) {
      this.patchStore(targets.companyStoreId);
    }
  }

  /**
   * Single owner of every write to the header's `companyStoreId` control.
   * Patches silently and mirrors the value into {@link storeId}.
   */
  private patchStore(storeId: string): void {
    if (!this.form) {
      return;
    }
    this.form.controls.companyStoreId.setValue(storeId, { emitEvent: false });
    this.storeId.set(storeId);
  }

  // ══════════════════════════════════════════════════════════
  // DEPENDENT LOADS
  // ══════════════════════════════════════════════════════════

  private loadCompanyDetails(companyId: string): void {
    this.companyDetailLoading.set(true);
    this.companyService
      .getCompanyById(companyId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (company) => {
          this.detailCompanyId = companyId;
          this.companyDetail.set(this.formatCompanyDetail(company));
          this.companyDetailLoading.set(false);
          this.companies.update((list) =>
            list.some((c) => c.id === company.id)
              ? list
              : [{ id: company.id, name: company.companyName }, ...list],
          );
          this.advanceCascade();
        },
        error: () => this.companyDetailLoading.set(false),
      });
  }

  private loadCountries(companyId: string): void {
    this.companyCountryService
      .getCountries(companyId)
      .pipe(
        map((list) => list.map((c) => ({ id: c.id, name: c.countryName }))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => {
          this.countries.set(list);
          this.advanceCascade();
        },
      });
  }

  private loadRegions(companyId: string, companyCountryId: string): void {
    this.companyRegionService
      .getRegions(companyId, companyCountryId)
      .pipe(
        map((list) => list.map((r) => ({ id: r.id, name: r.regionName }))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => {
          this.regions.set(list);
          this.advanceCascade();
        },
      });
  }

  private loadZones(companyId: string, companyCountryId: string, regionId: string): void {
    this.companyZoneService
      .getZones(companyId, companyCountryId, regionId)
      .pipe(
        map((list) => list.map((z) => ({ id: z.id, name: z.zoneName }))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => {
          this.zones.set(list);
          this.advanceCascade();
        },
      });
  }

  private loadStores(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
  ): void {
    this.companyStoreService
      .getStores(companyId, companyCountryId, regionId, zoneId)
      .pipe(
        map((list) => list.filter((s) => s.enabled).map((s) => ({ id: s.id, name: s.storeName }))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => {
          this.stores.set(list);
          this.advanceCascade();
        },
      });
  }

  private formatCompanyDetail(company: Company): CompanyDetail {
    const address = company.address;
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
      rfc: company.rfc,
      address: addressParts.length > 0 ? addressParts.join(', ') : '—',
      phone: company.phone,
      email: company.email,
    };
  }

  // ══════════════════════════════════════════════════════════
  // USER CHANGE HANDLERS
  // ══════════════════════════════════════════════════════════

  /** Handles a company selection and resets every dependent level. */
  onCompanyChange(companyId: string | null): void {
    if (!this.form) {
      return;
    }
    const controls = this.form.controls.company.controls;
    controls.companyId.setValue(companyId || null, { emitEvent: false });
    controls.companyCountryId.setValue(null, { emitEvent: false });
    controls.regionId.setValue(null, { emitEvent: false });
    controls.zoneId.setValue(null, { emitEvent: false });
    this.patchStore('');

    this.targets = null;
    this.companyDetail.set(null);
    this.detailCompanyId = null;
    this.countries.set([]);
    this.regions.set([]);
    this.zones.set([]);
    this.stores.set([]);

    if (companyId) {
      this.loadCompanyDetails(companyId);
      this.loadCountries(companyId);
    }
  }

  /** Handles a country selection, resetting region/zone/store. */
  onCountryChange(companyCountryId: string | null): void {
    if (!this.form) {
      return;
    }
    const controls = this.form.controls.company.controls;
    controls.companyCountryId.setValue(companyCountryId || null, { emitEvent: false });
    controls.regionId.setValue(null, { emitEvent: false });
    controls.zoneId.setValue(null, { emitEvent: false });
    this.patchStore('');

    this.targets = null;
    this.regions.set([]);
    this.zones.set([]);
    this.stores.set([]);

    const companyId = controls.companyId.value;
    if (companyId && companyCountryId) {
      this.loadRegions(companyId, companyCountryId);
    }
  }

  /** Handles a region selection, resetting zone/store. */
  onRegionChange(regionId: string | null): void {
    if (!this.form) {
      return;
    }
    const controls = this.form.controls.company.controls;
    controls.regionId.setValue(regionId || null, { emitEvent: false });
    controls.zoneId.setValue(null, { emitEvent: false });
    this.patchStore('');

    this.targets = null;
    this.zones.set([]);
    this.stores.set([]);

    const companyId = controls.companyId.value;
    const companyCountryId = controls.companyCountryId.value;
    if (companyId && companyCountryId && regionId) {
      this.loadZones(companyId, companyCountryId, regionId);
    }
  }

  /** Handles a zone selection, resetting and loading stores. */
  onZoneChange(zoneId: string | null): void {
    if (!this.form) {
      return;
    }
    const controls = this.form.controls.company.controls;
    controls.zoneId.setValue(zoneId || null, { emitEvent: false });
    this.patchStore('');

    this.targets = null;
    this.stores.set([]);

    const companyId = controls.companyId.value;
    const companyCountryId = controls.companyCountryId.value;
    const regionId = controls.regionId.value;
    if (companyId && companyCountryId && regionId && zoneId) {
      this.loadStores(companyId, companyCountryId, regionId, zoneId);
    }
  }
}
