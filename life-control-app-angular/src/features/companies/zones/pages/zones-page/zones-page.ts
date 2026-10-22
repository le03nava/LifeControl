import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
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
import { CompanyZoneService } from '../../data/company-zone.service';
import { ZonesCard } from '../../components/zones-card/zones-card';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyZone } from '../../models/zone.models';
import { LocationPreference } from '@features/user/profile/data/location-preference.service';

@Component({
  selector: 'app-zones-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatSlideToggleModule,
    PageHeader,
    ZonesCard,
  ],
  templateUrl: './zones-page.html',
  styleUrl: './zones-page.scss',
})
export class ZonesPage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly companyService = inject(CompanyService);
  private readonly companyCountryService = inject(CompanyCountryService);
  private readonly companyRegionService = inject(CompanyRegionService);
  private readonly companyZoneService = inject(CompanyZoneService);
  private readonly locationPreference = inject(LocationPreference);

  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  // ─── Selection state (pre-seeded from query params at construction) ───
  /**
   * The ids the route itself names, captured once. When the route names any level it wins and the
   * location preference is not consulted at all: pairing one company's id from the URL with another
   * company's region from the preference would preselect a region the company does not have.
   */
  private readonly routeCompanyId = this.route.snapshot.queryParamMap.get('companyId');
  private readonly routeCountryId = this.route.snapshot.queryParamMap.get('countryId');
  private readonly routeRegionId = this.route.snapshot.queryParamMap.get('regionId');
  private readonly routeCarriesIds =
    !!this.routeCompanyId || !!this.routeCountryId || !!this.routeRegionId;

  readonly selectedCompanyId = signal<string | null>(this.routeCompanyId);
  readonly selectedCountry = signal<CompanyCountry | null>(null);
  readonly selectedRegion = signal<CompanyRegion | null>(null);

  /** Query params captured once at construction for pre-selection. */
  private readonly initialCountryId = signal<string | null>(this.routeCountryId);
  private readonly initialRegionId = signal<string | null>(this.routeRegionId);

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
      return {
        companyId: country.companyId,
        countryId: country.id,
        regionId: region.id,
        includeDisabled: this.showDisabled(),
      };
    },
    stream: ({ params }) =>
      this.companyZoneService
        .getZones(params.companyId, params.countryId, params.regionId, params.includeDisabled)
        .pipe(catchError(() => of([] as CompanyZone[]))),
    defaultValue: [] as CompanyZone[],
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

  /** Friendly error message owned by the service (set on load failure). */
  readonly zonesError = computed(() => this.companyZoneService.error());

  /**
   * `showDisabled` lives in the resource params, not in the stream: Angular runs the stream
   * function untracked (`_resource-chunk.mjs`, `loadEffect`), so a signal read there would never
   * re-trigger the request. The API applies `includeDisabled` server-side; this filter keeps a
   * disabled row off the grid while the toggle is off even if the response ignores the flag.
   */
  readonly filteredZones = computed(() => {
    const all = this.zones();
    if (this.showDisabled()) return all;
    return all.filter((z) => z.enabled);
  });

  /** compareWith for mat-select: both sides are CompanyCountry objects */
  protected compareCompanyCountry = (
    a: CompanyCountry | null,
    b: CompanyCountry | null,
  ): boolean => {
    return a?.id === b?.id;
  };

  /** compareWith for mat-select: both sides are CompanyRegion objects */
  protected compareCompanyRegion = (a: CompanyRegion | null, b: CompanyRegion | null): boolean => {
    return a?.id === b?.id;
  };

  /** Guards so the pre-selection runs exactly once per level. */
  private companyPreselected = false;
  private countryPreselected = false;
  private regionPreselected = false;

  constructor() {
    // Only a route that names nothing at all falls back to the preference.
    if (!this.routeCarriesIds) {
      this.locationPreference.resolve();
    }

    // Pre-select the company from the location preference once its chain resolves: the chain arrives
    // after construction, so the route's own seed above is not enough.
    effect(() => {
      if (this.routeCarriesIds || this.companyPreselected) return;
      const companyId = this.locationPreference.companyId();
      if (!companyId) return;
      this.companyPreselected = true;
      this.selectedCompanyId.set(companyId);
    });

    // Pre-select the country once its company's countries resolve: the route's id first, then the
    // preference's. A country the company does not have is simply not found, and nothing is
    // preselected rather than guessed.
    effect(() => {
      if (this.countryPreselected) return;
      const countryId =
        this.initialCountryId() ??
        (this.routeCarriesIds ? null : this.locationPreference.companyCountryId());
      if (!countryId) return;
      const country = this.countries().find((c) => c.id === countryId);
      if (country) {
        this.countryPreselected = true;
        this.selectedCountry.set(country);
      }
    });

    // Pre-select the region once its country's regions resolve: the route's id first, then the
    // preference's.
    effect(() => {
      if (this.regionPreselected) return;
      const regionId =
        this.initialRegionId() ??
        (this.routeCarriesIds ? null : this.locationPreference.companyRegionId());
      if (!regionId) return;
      const region = this.regions().find((r) => r.id === regionId);
      if (region) {
        this.regionPreselected = true;
        this.selectedRegion.set(region);
      }
    });
  }

  // ─── Event handlers ──────────────────────────────────────────

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.selectedCountry.set(null);
    this.selectedRegion.set(null);
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
    this.selectedRegion.set(null);
  }

  onSelectRegion(region: CompanyRegion): void {
    this.selectedRegion.set(region);
  }

  onCreateZone(): void {
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    if (!cc || !region) return;
    this.router.navigate(['/companies/zones/create'], {
      queryParams: { companyId: cc.companyId, countryId: cc.id, regionId: region.id },
    });
  }

  onEditZone(zone: CompanyZone): void {
    this.router.navigate(['/companies/zones/edit', zone.id], {
      state: { zone },
    });
  }

  /** Bridge: the card emits a zone ID; look up the full zone and delegate. */
  onCardEditZone(zoneId: string): void {
    const zone = this.zones().find((z) => z.id === zoneId);
    if (zone) {
      this.onEditZone(zone);
    }
  }

  onRemoveZone(zoneId: string): void {
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    if (!cc || !region) return;
    this.companyZoneService
      .removeZone(cc.companyId, cc.id, region.id, zoneId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.zonesResource.reload());
  }

  onEnableZone(zoneId: string): void {
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    if (!cc || !region) return;
    this.companyZoneService
      .enableZone(cc.companyId, cc.id, region.id, zoneId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.zonesResource.reload());
  }

  /** Bridge: card emits { id, enable } where enable is the desired new state. */
  onCardToggleZone(event: { id: string; enable: boolean }): void {
    if (event.enable) {
      this.onEnableZone(event.id);
    } else {
      this.onRemoveZone(event.id);
    }
  }

  /**
   * When a user slides the toggle:
   * - ON → OFF → soft-delete (disable)
   * - OFF → ON → re-enable
   */
  onToggleZone(zone: CompanyZone): void {
    if (zone.enabled) {
      this.onRemoveZone(zone.id);
    } else {
      this.onEnableZone(zone.id);
    }
  }
}
