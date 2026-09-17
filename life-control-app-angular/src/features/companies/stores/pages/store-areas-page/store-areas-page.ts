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
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { PageHeader, ConfirmDialog } from '@shared/ui';
import { of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { StoreAreaService } from '../../data/store-area.service';
import { CompanyStore } from '../../models/store.models';
import { StoreArea } from '../../models/store-area.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyZone } from '../../../zones/models/zone.models';

@Component({
  selector: 'app-store-areas-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatSlideToggleModule,
    PageHeader,
  ],
  templateUrl: './store-areas-page.html',
  styleUrl: './store-areas-page.scss',
})
export class StoreAreasPage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialog = inject(MatDialog);
  private readonly companyService = inject(CompanyService);
  private readonly companyCountryService = inject(CompanyCountryService);
  private readonly companyRegionService = inject(CompanyRegionService);
  private readonly companyZoneService = inject(CompanyZoneService);
  private readonly companyStoreService = inject(CompanyStoreService);
  private readonly storeAreaService = inject(StoreAreaService);

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
  readonly selectedStore = signal<CompanyStore | null>(null);

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
  private readonly initialStoreId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('storeId'),
  );

  // ─── Filter state ────────────────────────────────────────────
  readonly showDisabled = signal(false);
  /** Bumped after a disable/enable write to re-fetch the areas list. */
  readonly reload = signal(0);

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

  /**
   * Always requests disabled stores too: the page can be entered from a disabled
   * store's card, and the pre-selected store must resolve even when disabled.
   */
  readonly storesResource = rxResource({
    params: () => {
      const country = this.selectedCountry();
      const region = this.selectedRegion();
      const zone = this.selectedZone();
      if (!country || !region || !zone) return undefined;
      return {
        companyId: country.companyId,
        companyCountryId: country.id,
        regionId: region.id,
        zoneId: zone.id,
      };
    },
    stream: ({ params }) =>
      this.companyStoreService
        .getStores(params.companyId, params.companyCountryId, params.regionId, params.zoneId, true)
        .pipe(catchError(() => of([] as CompanyStore[]))),
    defaultValue: [] as CompanyStore[],
  });

  readonly areasResource = rxResource({
    params: () => {
      const store = this.selectedStore();
      if (!store) return undefined;
      return {
        companyId: store.companyId,
        companyCountryId: store.companyCountryId,
        regionId: store.regionId,
        zoneId: store.zoneId,
        storeId: store.id,
        showDisabled: this.showDisabled(),
        reload: this.reload(),
      };
    },
    stream: ({ params }) =>
      this.storeAreaService
        .getAreas(
          params.companyId,
          params.companyCountryId,
          params.regionId,
          params.zoneId,
          params.storeId,
          params.showDisabled,
        )
        .pipe(catchError(() => of([] as StoreArea[]))),
    defaultValue: [] as StoreArea[],
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
  readonly areas = computed(() =>
    this.areasResource.hasValue() ? this.areasResource.value() : [],
  );

  /** Friendly error message owned by the service (set on load failure). */
  readonly areasError = computed(() => this.storeAreaService.error());

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

  /** compareWith for mat-select: both sides are CompanyZone objects */
  protected compareCompanyZone = (a: CompanyZone | null, b: CompanyZone | null): boolean => {
    return a?.id === b?.id;
  };

  /** compareWith for mat-select: both sides are CompanyStore objects */
  protected compareCompanyStore = (a: CompanyStore | null, b: CompanyStore | null): boolean => {
    return a?.id === b?.id;
  };

  /** Guards so the query-param pre-selection runs exactly once per level. */
  private countryPreselected = false;
  private regionPreselected = false;
  private zonePreselected = false;
  private storePreselected = false;

  constructor() {
    // Pre-select the country from the query param once its countries resolve.
    effect(() => {
      const countryId = this.initialCountryId();
      if (!countryId || this.countryPreselected) return;
      const country = this.countries().find((c) => c.id === countryId);
      if (country) {
        this.countryPreselected = true;
        this.selectedCountry.set(country);
      }
    });

    // Pre-select the region from the query param once its regions resolve.
    effect(() => {
      const regionId = this.initialRegionId();
      if (!regionId || this.regionPreselected) return;
      const region = this.regions().find((r) => r.id === regionId);
      if (region) {
        this.regionPreselected = true;
        this.selectedRegion.set(region);
      }
    });

    // Pre-select the zone from the query param once its zones resolve.
    effect(() => {
      const zoneId = this.initialZoneId();
      if (!zoneId || this.zonePreselected) return;
      const zone = this.zones().find((z) => z.id === zoneId);
      if (zone) {
        this.zonePreselected = true;
        this.selectedZone.set(zone);
      }
    });

    // Pre-select the store from the query param once its stores resolve.
    effect(() => {
      const storeId = this.initialStoreId();
      if (!storeId || this.storePreselected) return;
      const store = this.stores().find((s) => s.id === storeId);
      if (store) {
        this.storePreselected = true;
        this.selectedStore.set(store);
      }
    });
  }

  // ─── Event handlers ──────────────────────────────────────────

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.selectedCountry.set(null);
    this.selectedRegion.set(null);
    this.selectedZone.set(null);
    this.selectedStore.set(null);
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
    this.selectedRegion.set(null);
    this.selectedZone.set(null);
    this.selectedStore.set(null);
  }

  onSelectRegion(region: CompanyRegion): void {
    this.selectedRegion.set(region);
    this.selectedZone.set(null);
    this.selectedStore.set(null);
  }

  onSelectZone(zone: CompanyZone): void {
    this.selectedZone.set(zone);
    this.selectedStore.set(null);
  }

  onSelectStore(store: CompanyStore): void {
    this.selectedStore.set(store);
  }

  onCreateArea(): void {
    const store = this.selectedStore();
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    const zone = this.selectedZone();
    if (!store || !cc || !region || !zone) return;
    this.router.navigate(['/companies/store-areas/create'], {
      queryParams: {
        companyId: cc.companyId,
        countryId: cc.id,
        regionId: region.id,
        zoneId: zone.id,
        storeId: store.id,
      },
    });
  }

  onEditArea(area: StoreArea): void {
    this.router.navigate(['/companies/store-areas/edit', area.id], {
      state: { area },
    });
  }

  onToggleArea(area: StoreArea): void {
    const store = this.selectedStore();
    if (!store) return;

    if (area.enabled) {
      const dialogRef = this.dialog.open(ConfirmDialog, {
        data: {
          title: 'Deshabilitar área',
          message: `¿Confirmás que querés deshabilitar el área "${area.areaName}"? La información se conserva y podés reactivarla más adelante.`,
          confirmLabel: 'Deshabilitar',
          destructive: true,
        },
      });
      dialogRef
        .afterClosed()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((confirmed: boolean) => {
          if (!confirmed) return;
          this.storeAreaService
            .removeArea(
              store.companyId,
              store.companyCountryId,
              store.regionId,
              store.zoneId,
              store.id,
              area.id,
            )
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.reload.update((n) => n + 1));
        });
    } else {
      this.storeAreaService
        .enableArea(
          store.companyId,
          store.companyCountryId,
          store.regionId,
          store.zoneId,
          store.id,
          area.id,
        )
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => this.reload.update((n) => n + 1));
    }
  }
}
