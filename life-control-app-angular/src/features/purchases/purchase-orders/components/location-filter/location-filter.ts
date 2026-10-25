import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  output,
  signal,
  untracked,
} from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { catchError, map, of } from 'rxjs';
import { LocationPreference } from '@features/user/profile/data/location-preference.service';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import type { CompanySelectorOption } from '@features/companies/companies/models/company.models';
import type { CompanyCountry } from '@features/companies/countries/models/country.models';
import type { CompanyRegion } from '@features/companies/regions/models/region.models';
import type { CompanyZone } from '@features/companies/zones/models/zone.models';
import type { CompanyStore } from '@features/companies/stores/models/store.models';
import type { PurchaseOrderLocationSelection } from '../../models/purchase-order-location-filter.models';

/**
 * The location filter of the purchase order list: `Empresa → País → Región → Zona → Tienda`
 * (`S1`, `S3` of `odd/tasks/purchase-order-region-filter.md`).
 *
 * It owns the cascade and nothing else: the page decides what to do with the selection (`S2`
 * gates the list on company, country and region). The five levels are filter levels, not a leaf
 * list, so every dependent read asks for disabled rows too — otherwise a disabled region the
 * profile names would vanish from its own options.
 *
 * The preselection comes from `LocationPreference`, the same source `/companies/stores` uses, and
 * it is per level: a level is preselected only when its id is listed by that level's own options. An
 * id a level does not list is left empty and nothing is guessed from another level, which is what
 * keeps a preference chain stitched from two sources out of the selectors.
 *
 * There is no query-param preselection here, on purpose: `/purchases/orders` has no inbound link
 * that carries location ids, so the URL is not a source of truth for this filter.
 */
@Component({
  selector: 'app-purchase-order-location-filter',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatFormFieldModule, MatSelectModule],
  templateUrl: './location-filter.html',
  styleUrl: './location-filter.scss',
})
export class PurchaseOrderLocationFilter {
  private readonly companyService = inject(CompanyService);
  private readonly companyCountryService = inject(CompanyCountryService);
  private readonly companyRegionService = inject(CompanyRegionService);
  private readonly companyZoneService = inject(CompanyZoneService);
  private readonly companyStoreService = inject(CompanyStoreService);
  private readonly locationPreference = inject(LocationPreference);

  /** The resolved selection, emitted on every level change and on every preselected level. */
  readonly selection = output<PurchaseOrderLocationSelection>();

  // ─── Options ─────────────────────────────────────────────────

  /** The company selector read: one page, no search box, so a single read covers every company. */
  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(
      map((page) => page.content),
      catchError(() => of([] as CompanySelectorOption[])),
    ),
    { initialValue: [] as CompanySelectorOption[] },
  );

  // ─── Selection state ─────────────────────────────────────────

  readonly selectedCompany = signal<CompanySelectorOption | null>(null);
  readonly selectedCountry = signal<CompanyCountry | null>(null);
  readonly selectedRegion = signal<CompanyRegion | null>(null);
  readonly selectedZone = signal<CompanyZone | null>(null);
  readonly selectedStore = signal<CompanyStore | null>(null);

  // ─── Reactive data flow: each level is keyed on the selection above it ───

  readonly countriesResource = rxResource({
    params: () => this.selectedCompany()?.id || undefined,
    stream: ({ params: companyId }) =>
      this.companyCountryService
        .getCountries(companyId)
        .pipe(catchError(() => of([] as CompanyCountry[]))),
    defaultValue: [] as CompanyCountry[],
  });

  /**
   * Filter levels, not the final list: every read below asks for disabled rows too (`true`), so a
   * region, zone or store pre-selected from the preference still resolves when it is disabled.
   */
  readonly regionsResource = rxResource({
    params: () => {
      const country = this.selectedCountry();
      if (!country) return undefined;
      return { companyId: country.companyId, countryId: country.id };
    },
    stream: ({ params }) =>
      this.companyRegionService
        .getRegions(params.companyId, params.countryId, true)
        .pipe(catchError(() => of([] as CompanyRegion[]))),
    defaultValue: [] as CompanyRegion[],
  });

  readonly zonesResource = rxResource({
    params: () => {
      const country = this.selectedCountry();
      const region = this.selectedRegion();
      if (!country || !region) return undefined;
      return { companyId: country.companyId, countryId: country.id, regionId: region.id };
    },
    stream: ({ params }) =>
      this.companyZoneService
        .getZones(params.companyId, params.countryId, params.regionId, true)
        .pipe(catchError(() => of([] as CompanyZone[]))),
    defaultValue: [] as CompanyZone[],
  });

  readonly storesResource = rxResource({
    params: () => {
      const country = this.selectedCountry();
      const region = this.selectedRegion();
      const zone = this.selectedZone();
      if (!country || !region || !zone) return undefined;
      return {
        companyId: country.companyId,
        countryId: country.id,
        regionId: region.id,
        zoneId: zone.id,
      };
    },
    stream: ({ params }) =>
      this.companyStoreService
        .getStores(params.companyId, params.countryId, params.regionId, params.zoneId, true)
        .pipe(catchError(() => of([] as CompanyStore[]))),
    defaultValue: [] as CompanyStore[],
  });

  // Guarded resource reads: never touch `.value()` while a resource is in an error state.
  readonly countries = computed(() =>
    this.countriesResource.hasValue() ? this.countriesResource.value() : [],
  );
  readonly regions = computed(() =>
    this.regionsResource.hasValue() ? this.regionsResource.value() : [],
  );
  readonly zones = computed(() =>
    this.zonesResource.hasValue() ? this.zonesResource.value() : [],
  );
  readonly stores = computed(() =>
    this.storesResource.hasValue() ? this.storesResource.value() : [],
  );

  /** compareWith for mat-select: both sides are company selector options */
  protected compareCompany = (
    a: CompanySelectorOption | null,
    b: CompanySelectorOption | null,
  ): boolean => a?.id === b?.id;

  /** compareWith for mat-select: both sides are CompanyCountry objects */
  protected compareCompanyCountry = (a: CompanyCountry | null, b: CompanyCountry | null): boolean =>
    a?.id === b?.id;

  /** compareWith for mat-select: both sides are CompanyRegion objects */
  protected compareCompanyRegion = (a: CompanyRegion | null, b: CompanyRegion | null): boolean =>
    a?.id === b?.id;

  /** compareWith for mat-select: both sides are CompanyZone objects */
  protected compareCompanyZone = (a: CompanyZone | null, b: CompanyZone | null): boolean =>
    a?.id === b?.id;

  /** compareWith for mat-select: both sides are CompanyStore objects */
  protected compareCompanyStore = (a: CompanyStore | null, b: CompanyStore | null): boolean =>
    a?.id === b?.id;

  /** Guards so the pre-selection runs exactly once per level. */
  private companyPreselected = false;
  private countryPreselected = false;
  private regionPreselected = false;
  private zonePreselected = false;
  private storePreselected = false;

  constructor() {
    // One resolution for the whole component: the chain arrives after construction, which is why
    // each level pre-selects from an effect instead of reading it here.
    this.locationPreference.resolve();

    // Pre-select the company once the selector read lists it.
    effect(() => {
      if (this.companyPreselected) return;
      const companyId = this.locationPreference.companyId();
      if (!companyId) return;
      const company = this.companies().find((c) => c.id === companyId);
      if (!company) return;
      this.companyPreselected = true;
      this.selectedCompany.set(company);
      untracked(() => this.emitSelection());
    });

    // Pre-select the country once its company's countries resolve. A country the company does not
    // have is simply not found, and nothing is preselected rather than guessed.
    effect(() => {
      if (this.countryPreselected) return;
      const countryId = this.locationPreference.companyCountryId();
      if (!countryId) return;
      const country = this.countries().find((c) => c.id === countryId);
      if (!country) return;
      this.countryPreselected = true;
      this.selectedCountry.set(country);
      untracked(() => this.emitSelection());
    });

    // Pre-select the region once its country's regions resolve.
    effect(() => {
      if (this.regionPreselected) return;
      const regionId = this.locationPreference.companyRegionId();
      if (!regionId) return;
      const region = this.regions().find((r) => r.id === regionId);
      if (!region) return;
      this.regionPreselected = true;
      this.selectedRegion.set(region);
      untracked(() => this.emitSelection());
    });

    // Pre-select the zone once its region's zones resolve.
    effect(() => {
      if (this.zonePreselected) return;
      const zoneId = this.locationPreference.companyZoneId();
      if (!zoneId) return;
      const zone = this.zones().find((z) => z.id === zoneId);
      if (!zone) return;
      this.zonePreselected = true;
      this.selectedZone.set(zone);
      untracked(() => this.emitSelection());
    });

    // Pre-select the store once its zone's stores resolve.
    effect(() => {
      if (this.storePreselected) return;
      const storeId = this.locationPreference.companyStoreId();
      if (!storeId) return;
      const store = this.stores().find((s) => s.id === storeId);
      if (!store) return;
      this.storePreselected = true;
      this.selectedStore.set(store);
      untracked(() => this.emitSelection());
    });
  }

  // ─── Event handlers: each one clears every level below it ────

  onCompanyChange(company: CompanySelectorOption | null): void {
    this.selectedCompany.set(company);
    this.selectedCountry.set(null);
    this.selectedRegion.set(null);
    this.selectedZone.set(null);
    this.selectedStore.set(null);
    this.emitSelection();
  }

  onCountryChange(country: CompanyCountry | null): void {
    this.selectedCountry.set(country);
    this.selectedRegion.set(null);
    this.selectedZone.set(null);
    this.selectedStore.set(null);
    this.emitSelection();
  }

  onRegionChange(region: CompanyRegion | null): void {
    this.selectedRegion.set(region);
    this.selectedZone.set(null);
    this.selectedStore.set(null);
    this.emitSelection();
  }

  onZoneChange(zone: CompanyZone | null): void {
    this.selectedZone.set(zone);
    this.selectedStore.set(null);
    this.emitSelection();
  }

  onStoreChange(store: CompanyStore | null): void {
    this.selectedStore.set(store);
    this.emitSelection();
  }

  /** The whole selection, level by level: what the page sends to the list query. */
  private emitSelection(): void {
    this.selection.emit({
      companyId: this.selectedCompany()?.id ?? null,
      companyCountryId: this.selectedCountry()?.id ?? null,
      regionId: this.selectedRegion()?.id ?? null,
      zoneId: this.selectedZone()?.id ?? null,
      companyStoreId: this.selectedStore()?.id ?? null,
    });
  }
}
