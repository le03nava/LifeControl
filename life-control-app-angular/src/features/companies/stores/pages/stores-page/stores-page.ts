import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { PageHeader } from '@shared/ui';
import { of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { StoresCard } from '../../components/stores-card/stores-card';
import { CompanyStore } from '../../models/store.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyZone } from '../../../zones/models/zone.models';

@Component({
  selector: 'app-stores-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatSlideToggleModule,
    PageHeader,
    StoresCard,
  ],
  templateUrl: './stores-page.html',
  styleUrl: './stores-page.scss',
})
export class StoresPage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly companyService = inject(CompanyService);
  private readonly companyCountryService = inject(CompanyCountryService);
  private readonly companyRegionService = inject(CompanyRegionService);
  private readonly companyZoneService = inject(CompanyZoneService);
  private readonly companyStoreService = inject(CompanyStoreService);

  readonly companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  // ─── Selection state (pre-seeded from query params at construction) ───
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );
  readonly selectedCountry = signal<CompanyCountry | null>(null);
  readonly selectedRegion = signal<CompanyRegion | null>(null);
  readonly selectedZone = signal<CompanyZone | null>(null);

  /** Query params captured once at construction for pre-selection. */
  private readonly initialCountryId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('countryId'),
  );
  private readonly initialRegionId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('regionId'),
  );
  private readonly initialZoneId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('zoneId'),
  );

  // ─── Filter state ────────────────────────────────────────────
  readonly showDisabled = signal(false);

  // ─── Reactive data flow: each level is keyed on the selection above it ───
  readonly countriesResource = rxResource({
    params: () => this.selectedCompanyId() || undefined,
    stream: ({ params: companyId }) =>
      this.companyCountryService
        .getCountries(companyId)
        .pipe(catchError(() => of([] as CompanyCountry[]))),
    defaultValue: [] as CompanyCountry[],
  });

  readonly regionsResource = rxResource({
    params: () => this.selectedCountry() ?? undefined,
    stream: ({ params: country }) =>
      this.companyRegionService
        .getRegions(country.companyId, country.id)
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
        .getZones(params.companyId, params.countryId, params.regionId)
        .pipe(catchError(() => of([] as CompanyZone[]))),
    defaultValue: [] as CompanyZone[],
  });

  readonly storesResource = rxResource({
    params: () => {
      const country = this.selectedCountry();
      const region = this.selectedRegion();
      const zone = this.selectedZone();
      if (!country || !region || !zone) return undefined;
      return { companyId: country.companyId, countryId: country.id, regionId: region.id, zoneId: zone.id };
    },
    stream: ({ params }) =>
      this.companyStoreService
        .getStores(params.companyId, params.countryId, params.regionId, params.zoneId, this.showDisabled())
        .pipe(catchError(() => of([] as CompanyStore[]))),
    defaultValue: [] as CompanyStore[],
  });

  /** Friendly error message owned by the service (set on load failure). */
  readonly storesError = computed(() => this.companyStoreService.error());

  readonly filteredStores = computed(() => {
    const all = this.storesResource.value();
    if (this.showDisabled()) return all;
    return all.filter((s) => s.enabled);
  });

  /** compareWith for mat-select: both sides are CompanyCountry objects */
  protected compareCompanyCountry = (a: CompanyCountry | null, b: CompanyCountry | null): boolean => {
    return a?.id === b?.id;
  };

  /** compareWith for mat-select: both sides are CompanyRegion objects */
  protected compareCompanyRegion = (a: CompanyRegion | null, b: CompanyRegion | null): boolean => {
    return a?.id === b?.id;
  };

  /** compareWith for mat-select: both sides are CompanyZone objects */
  protected compareCompanyZone = (a: CompanyZone | null, b: CompanyZone | null): boolean => {
    return a?.id === b?.id;
  };

  /** Guards so the query-param pre-selection runs exactly once per level. */
  private countryPreselected = false;
  private regionPreselected = false;
  private zonePreselected = false;

  constructor() {
    // Pre-select the country from the query param once its countries resolve.
    effect(() => {
      const countryId = this.initialCountryId();
      if (!countryId || this.countryPreselected) return;
      const country = this.countriesResource.value().find((c) => c.id === countryId);
      if (country) {
        this.countryPreselected = true;
        this.selectedCountry.set(country);
      }
    });

    // Pre-select the region from the query param once its regions resolve.
    effect(() => {
      const regionId = this.initialRegionId();
      if (!regionId || this.regionPreselected) return;
      const region = this.regionsResource.value().find((r) => r.id === regionId);
      if (region) {
        this.regionPreselected = true;
        this.selectedRegion.set(region);
      }
    });

    // Pre-select the zone from the query param once its zones resolve.
    effect(() => {
      const zoneId = this.initialZoneId();
      if (!zoneId || this.zonePreselected) return;
      const zone = this.zonesResource.value().find((z) => z.id === zoneId);
      if (zone) {
        this.zonePreselected = true;
        this.selectedZone.set(zone);
      }
    });
  }

  // ─── Event handlers ──────────────────────────────────────────

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.selectedCountry.set(null);
    this.selectedRegion.set(null);
    this.selectedZone.set(null);
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
    this.selectedRegion.set(null);
    this.selectedZone.set(null);
  }

  onSelectRegion(region: CompanyRegion): void {
    this.selectedRegion.set(region);
    this.selectedZone.set(null);
  }

  onSelectZone(zone: CompanyZone): void {
    this.selectedZone.set(zone);
  }

  onCreateStore(): void {
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    const zone = this.selectedZone();
    if (!cc || !region || !zone) return;
    this.router.navigate(['/companies/stores/create'], {
      queryParams: { companyId: cc.companyId, countryId: cc.id, regionId: region.id, zoneId: zone.id },
    });
  }

  onEditStore(store: CompanyStore): void {
    this.router.navigate(['/companies/stores/edit', store.id], {
      state: { store },
    });
  }

  /** Bridge: the card emits a store ID; look up the full store and delegate. */
  onCardEditStore(storeId: string): void {
    const store = this.storesResource.value().find((s) => s.id === storeId);
    if (store) {
      this.onEditStore(store);
    }
  }

  onToggleStore(storeId: string): void {
    const store = this.storesResource.value().find((s) => s.id === storeId);
    if (!store) return;
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    const zone = this.selectedZone();
    if (!cc || !region || !zone) return;

    if (store.enabled) {
      this.companyStoreService
        .removeStore(cc.companyId, cc.id, region.id, zone.id, storeId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => this.storesResource.reload());
    } else {
      this.companyStoreService
        .enableStore(cc.companyId, cc.id, region.id, zone.id, storeId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => this.storesResource.reload());
    }
  }
}
