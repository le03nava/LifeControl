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

  readonly companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  // ─── Selection state (pre-seeded from query params at construction) ───
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );
  readonly selectedCountry = signal<CompanyCountry | null>(null);

  /** `countryId` query param captured once at construction for pre-selection. */
  private readonly initialCountryId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('countryId'),
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

  /** Friendly error message owned by the service (set on load failure). */
  readonly regionsError = computed(() => this.companyRegionService.error());

  readonly filteredRegions = computed(() => {
    const all = this.regionsResource.value();
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

  /** Guard so the query-param pre-selection runs exactly once. */
  private countryPreselected = false;

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
    const region = this.regionsResource.value().find((r) => r.id === regionId);
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
