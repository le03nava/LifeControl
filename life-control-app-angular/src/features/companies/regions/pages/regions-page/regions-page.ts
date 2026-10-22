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
import { CompanyRegionService } from '../../data/company-region.service';
import { RegionsCard } from '../../components/regions-card/regions-card';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../models/region.models';
import { LocationPreference } from '@features/user/profile/data/location-preference.service';

@Component({
  selector: 'app-regions-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatSlideToggleModule,
    PageHeader,
    RegionsCard,
  ],
  templateUrl: './regions-page.html',
  styleUrl: './regions-page.scss',
})
export class RegionsPage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly companyService = inject(CompanyService);
  private readonly companyCountryService = inject(CompanyCountryService);
  private readonly companyRegionService = inject(CompanyRegionService);
  private readonly locationPreference = inject(LocationPreference);

  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  // ─── Selection state (pre-seeded from query params at construction) ───
  /**
   * The ids the route itself names, captured once. `S7`: when the route names any level it wins and
   * the location preference is not consulted at all — pairing one company's id from the URL with
   * another company's country from the preference would preselect a country the company does not have.
   */
  private readonly routeCompanyId = this.route.snapshot.queryParamMap.get('companyId');
  private readonly routeCountryId = this.route.snapshot.queryParamMap.get('countryId');
  private readonly routeCarriesIds = !!this.routeCompanyId || !!this.routeCountryId;

  readonly selectedCompanyId = signal<string | null>(this.routeCompanyId);
  readonly selectedCountry = signal<CompanyCountry | null>(null);

  /** The country to preselect: the route's own id, or the preference's, captured once. */
  private readonly initialCountryId = signal<string | null>(this.routeCountryId);

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
    params: () => {
      const country = this.selectedCountry();
      if (!country) return undefined;
      return {
        companyId: country.companyId,
        countryId: country.id,
        includeDisabled: this.showDisabled(),
      };
    },
    stream: ({ params }) =>
      this.companyRegionService
        .getRegions(params.companyId, params.countryId, params.includeDisabled)
        .pipe(catchError(() => of([] as CompanyRegion[]))),
    defaultValue: [] as CompanyRegion[],
  });

  // Guarded resource reads: never touch `.value()` while a resource is in an error state.
  readonly countries = computed(() =>
    this.countriesResource.hasValue() ? this.countriesResource.value() : [],
  );
  readonly regions = computed(() =>
    this.regionsResource.hasValue() ? this.regionsResource.value() : [],
  );

  /** Friendly error message owned by the service (set on load failure). */
  readonly regionsError = computed(() => this.companyRegionService.error());

  /**
   * `showDisabled` lives in the resource params, not in the stream: Angular runs the stream
   * function untracked (`_resource-chunk.mjs`, `loadEffect`), so a signal read there would never
   * re-trigger the request. The API applies `includeDisabled` server-side; this filter keeps a
   * disabled row off the list while the toggle is off even if the response ignores the flag.
   */
  readonly filteredRegions = computed(() => {
    const all = this.regions();
    if (this.showDisabled()) return all;
    return all.filter((r) => r.enabled);
  });

  /** compareWith for mat-select: both sides are CompanyCountry objects */
  protected compareCompanyCountry = (
    a: CompanyCountry | null,
    b: CompanyCountry | null,
  ): boolean => {
    return a?.id === b?.id;
  };

  /** Guard so the location pre-selection runs exactly once. */
  private companyPreselected = false;

  /** Guard so the country pre-selection runs exactly once. */
  private countryPreselected = false;

  constructor() {
    // `S7`: only a route that names nothing at all falls back to the preference.
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

    // Pre-select the country once its company's countries resolve: the route's id first (`S7`), then
    // the preference's. A country the company does not have is simply not found, and nothing is
    // preselected rather than guessed (`S8`).
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
  }

  // ─── Event handlers ──────────────────────────────────────────

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.selectedCountry.set(null);
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
  }

  onCreateRegion(): void {
    const cc = this.selectedCountry();
    if (!cc) return;
    this.router.navigate(['/companies/regions/create'], {
      queryParams: { companyId: cc.companyId, countryId: cc.id },
    });
  }

  onEditRegion(region: CompanyRegion): void {
    this.router.navigate(['/companies/regions/edit', region.id], {
      state: { region },
    });
  }

  /** Bridge: the card emits a region ID; look up the full region and delegate. */
  onCardEditRegion(regionId: string): void {
    const region = this.regions().find((r) => r.id === regionId);
    if (region) {
      this.onEditRegion(region);
    }
  }

  onRemoveRegion(regionId: string): void {
    const cc = this.selectedCountry();
    if (!cc) return;
    this.companyRegionService
      .removeRegion(cc.companyId, cc.id, regionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.regionsResource.reload());
  }

  onEnableRegion(regionId: string): void {
    const cc = this.selectedCountry();
    if (!cc) return;
    this.companyRegionService
      .enableRegion(cc.companyId, cc.id, regionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.regionsResource.reload());
  }

  /**
   * When a user slides the toggle:
   * - ON → OFF → soft-delete (disable)
   * - OFF → ON → re-enable
   */
  onToggleRegion(region: CompanyRegion): void {
    if (region.enabled) {
      this.onRemoveRegion(region.id);
    } else {
      this.onEnableRegion(region.id);
    }
  }
}
