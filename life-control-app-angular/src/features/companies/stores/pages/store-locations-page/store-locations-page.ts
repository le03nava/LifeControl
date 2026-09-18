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
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { PageHeader, ConfirmDialog } from '@shared/ui';
import { ApiError } from '@shared/models';
import { of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { StoreAreaService } from '../../data/store-area.service';
import { StoreZoneService } from '../../data/store-zone.service';
import { StoreLocationService } from '../../data/store-location.service';
import { CompanyStore } from '../../models/store.models';
import { StoreArea } from '../../models/store-area.models';
import { StoreZone } from '../../models/store-zone.models';
import { StoreLocation } from '../../models/store-location.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyZone } from '../../../zones/models/zone.models';

@Component({
  selector: 'app-store-locations-page',
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
  templateUrl: './store-locations-page.html',
  styleUrl: './store-locations-page.scss',
})
export class StoreLocationsPage {
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
  private readonly storeZoneService = inject(StoreZoneService);
  private readonly storeLocationService = inject(StoreLocationService);

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
  /** Company zone (third cascade level) — distinct from the store zones listed below. */
  readonly selectedCompanyZone = signal<CompanyZone | null>(null);
  readonly selectedStore = signal<CompanyStore | null>(null);
  readonly selectedArea = signal<StoreArea | null>(null);
  readonly selectedStoreZone = signal<StoreZone | null>(null);

  /** Query params captured once at construction for pre-selection. */
  private readonly initialCountryId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('countryId'),
  );
  private readonly initialRegionId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('regionId'),
  );
  private readonly initialCompanyZoneId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('zoneId'),
  );
  private readonly initialStoreId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('storeId'),
  );
  private readonly initialAreaId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('areaId'),
  );
  private readonly initialStoreZoneId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('storeZoneId'),
  );

  // ─── Filter state ────────────────────────────────────────────
  readonly showDisabled = signal(false);
  /** Bumped after a disable/enable write to re-fetch the store locations list. */
  readonly reload = signal(0);
  /** Write failure surfaced to the user (e.g. HTTP 409 when an ancestor is disabled). */
  readonly actionError = signal<string | null>(null);

  // ─── Reactive data flow: each level is keyed on the selection above it ───
  readonly countriesResource = rxResource({
    params: () => this.selectedCompanyId() || undefined,
    stream: ({ params: companyId }) => this.companyCountryService.getCountries(companyId),
    defaultValue: [] as CompanyCountry[],
  });

  readonly regionsResource = rxResource({
    params: () => this.selectedCountry() ?? undefined,
    stream: ({ params: country }) =>
      this.companyRegionService.getRegions(country.companyId, country.id),
    defaultValue: [] as CompanyRegion[],
  });

  readonly companyZonesResource = rxResource({
    params: () => {
      const country = this.selectedCountry();
      const region = this.selectedRegion();
      if (!country || !region) return undefined;
      return { companyId: country.companyId, countryId: country.id, regionId: region.id };
    },
    stream: ({ params }) =>
      this.companyZoneService.getZones(params.companyId, params.countryId, params.regionId),
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
      const companyZone = this.selectedCompanyZone();
      if (!country || !region || !companyZone) return undefined;
      return {
        companyId: country.companyId,
        companyCountryId: country.id,
        regionId: region.id,
        zoneId: companyZone.id,
      };
    },
    stream: ({ params }) =>
      this.companyStoreService.getStores(
        params.companyId,
        params.companyCountryId,
        params.regionId,
        params.zoneId,
        true,
      ),
    defaultValue: [] as CompanyStore[],
  });

  /**
   * Always requests disabled areas too: the page can be entered from a disabled
   * area's card, and the pre-selected area must resolve even when disabled.
   */
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
      };
    },
    stream: ({ params }) =>
      this.storeAreaService.getAreas(
        params.companyId,
        params.companyCountryId,
        params.regionId,
        params.zoneId,
        params.storeId,
        true,
      ),
    defaultValue: [] as StoreArea[],
  });

  /**
   * Filter level, not the final list: always requests disabled store zones too so a
   * pre-selected disabled store zone can resolve. Unlike the mirror page — where
   * this same service method is the final list and respects the toggle — here the
   * `includeDisabled` argument is hardcoded and the params deliberately exclude
   * `showDisabled` and `reload`. Only `storeLocationsResource` below respects the
   * "mostrar deshabilitadas" toggle.
   */
  readonly storeZonesResource = rxResource({
    params: () => {
      const area = this.selectedArea();
      if (!area) return undefined;
      return {
        companyId: area.companyId,
        companyCountryId: area.companyCountryId,
        regionId: area.regionId,
        zoneId: area.zoneId,
        storeId: area.companyStoreId,
        areaId: area.id,
      };
    },
    stream: ({ params }) =>
      this.storeZoneService.getStoreZones(
        params.companyId,
        params.companyCountryId,
        params.regionId,
        params.zoneId,
        params.storeId,
        params.areaId,
        true,
      ),
    defaultValue: [] as StoreZone[],
  });

  /** Final list of the cascade: the only level that respects the toggle. */
  readonly storeLocationsResource = rxResource({
    params: () => {
      const storeZone = this.selectedStoreZone();
      if (!storeZone) return undefined;
      return {
        companyId: storeZone.companyId,
        companyCountryId: storeZone.companyCountryId,
        regionId: storeZone.regionId,
        zoneId: storeZone.zoneId,
        storeId: storeZone.companyStoreId,
        areaId: storeZone.storeAreaId,
        storeZoneId: storeZone.id,
        showDisabled: this.showDisabled(),
        reload: this.reload(),
      };
    },
    stream: ({ params }) =>
      this.storeLocationService
        .getStoreLocations(
          params.companyId,
          params.companyCountryId,
          params.regionId,
          params.zoneId,
          params.storeId,
          params.areaId,
          params.storeZoneId,
          params.showDisabled,
        )
        .pipe(catchError(() => of([] as StoreLocation[]))),
    defaultValue: [] as StoreLocation[],
  });

  // Guarded resource reads: never touch `.value()` while a resource is in an error state.
  readonly countries = computed(() =>
    this.countriesResource.hasValue() ? this.countriesResource.value() : [],
  );
  readonly regions = computed(() =>
    this.regionsResource.hasValue() ? this.regionsResource.value() : [],
  );
  readonly companyZones = computed(() =>
    this.companyZonesResource.hasValue() ? this.companyZonesResource.value() : [],
  );
  readonly stores = computed(() =>
    this.storesResource.hasValue() ? this.storesResource.value() : [],
  );
  readonly areas = computed(() =>
    this.areasResource.hasValue() ? this.areasResource.value() : [],
  );
  readonly storeZones = computed(() =>
    this.storeZonesResource.hasValue() ? this.storeZonesResource.value() : [],
  );
  readonly storeLocations = computed(() =>
    this.storeLocationsResource.hasValue() ? this.storeLocationsResource.value() : [],
  );

  /** Friendly error message owned by the service (set on load failure). */
  readonly storeLocationsError = computed(() => this.storeLocationService.error());

  /**
   * Cascade load failures: a failed non-leaf level leaves its dependent selectors
   * empty (guarded reads above) and surfaces the failure instead of a silent empty.
   */
  readonly countriesError = computed(() =>
    this.cascadeMessage(this.countriesResource.error(), 'No se pudieron cargar los países.'),
  );
  readonly regionsError = computed(() =>
    this.cascadeMessage(this.regionsResource.error(), 'No se pudieron cargar las regiones.'),
  );
  readonly companyZonesError = computed(() =>
    this.cascadeMessage(this.companyZonesResource.error(), 'No se pudieron cargar las zonas.'),
  );
  readonly storesError = computed(() =>
    this.cascadeMessage(this.storesResource.error(), 'No se pudieron cargar las tiendas.'),
  );
  readonly areasError = computed(() =>
    this.cascadeMessage(this.areasResource.error(), 'No se pudieron cargar las áreas.'),
  );
  readonly storeZonesError = computed(() =>
    this.cascadeMessage(
      this.storeZonesResource.error(),
      'No se pudieron cargar las zonas de tienda.',
    ),
  );

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

  /** compareWith for mat-select: both sides are StoreArea objects */
  protected compareStoreArea = (a: StoreArea | null, b: StoreArea | null): boolean => {
    return a?.id === b?.id;
  };

  /** compareWith for mat-select: both sides are StoreZone objects */
  protected compareStoreZone = (a: StoreZone | null, b: StoreZone | null): boolean => {
    return a?.id === b?.id;
  };

  /** Guards so the query-param pre-selection runs exactly once per level. */
  private countryPreselected = false;
  private regionPreselected = false;
  private companyZonePreselected = false;
  private storePreselected = false;
  private areaPreselected = false;
  private storeZonePreselected = false;

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

    // Pre-select the company zone from the query param once its zones resolve.
    effect(() => {
      const zoneId = this.initialCompanyZoneId();
      if (!zoneId || this.companyZonePreselected) return;
      const companyZone = this.companyZones().find((z) => z.id === zoneId);
      if (companyZone) {
        this.companyZonePreselected = true;
        this.selectedCompanyZone.set(companyZone);
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

    // Pre-select the area from the query param once its areas resolve.
    effect(() => {
      const areaId = this.initialAreaId();
      if (!areaId || this.areaPreselected) return;
      const area = this.areas().find((a) => a.id === areaId);
      if (area) {
        this.areaPreselected = true;
        this.selectedArea.set(area);
      }
    });

    // Pre-select the store zone from the query param once its store zones resolve.
    effect(() => {
      const storeZoneId = this.initialStoreZoneId();
      if (!storeZoneId || this.storeZonePreselected) return;
      const storeZone = this.storeZones().find((sz) => sz.id === storeZoneId);
      if (storeZone) {
        this.storeZonePreselected = true;
        this.selectedStoreZone.set(storeZone);
      }
    });
  }

  // ─── Event handlers ──────────────────────────────────────────

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.selectedCountry.set(null);
    this.selectedRegion.set(null);
    this.selectedCompanyZone.set(null);
    this.selectedStore.set(null);
    this.selectedArea.set(null);
    this.selectedStoreZone.set(null);
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
    this.selectedRegion.set(null);
    this.selectedCompanyZone.set(null);
    this.selectedStore.set(null);
    this.selectedArea.set(null);
    this.selectedStoreZone.set(null);
  }

  onSelectRegion(region: CompanyRegion): void {
    this.selectedRegion.set(region);
    this.selectedCompanyZone.set(null);
    this.selectedStore.set(null);
    this.selectedArea.set(null);
    this.selectedStoreZone.set(null);
  }

  onSelectCompanyZone(companyZone: CompanyZone): void {
    this.selectedCompanyZone.set(companyZone);
    this.selectedStore.set(null);
    this.selectedArea.set(null);
    this.selectedStoreZone.set(null);
  }

  onSelectStore(store: CompanyStore): void {
    this.selectedStore.set(store);
    this.selectedArea.set(null);
    this.selectedStoreZone.set(null);
  }

  onSelectArea(area: StoreArea): void {
    this.selectedArea.set(area);
    this.selectedStoreZone.set(null);
  }

  onSelectStoreZone(storeZone: StoreZone): void {
    this.selectedStoreZone.set(storeZone);
  }

  onCreateStoreLocation(): void {
    const storeZone = this.selectedStoreZone();
    const area = this.selectedArea();
    const store = this.selectedStore();
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    const companyZone = this.selectedCompanyZone();
    if (!storeZone || !area || !store || !cc || !region || !companyZone) return;
    this.router.navigate(['/companies/store-locations/create'], {
      queryParams: {
        companyId: cc.companyId,
        countryId: cc.id,
        regionId: region.id,
        zoneId: companyZone.id,
        storeId: store.id,
        areaId: area.id,
        storeZoneId: storeZone.id,
      },
    });
  }

  onEditStoreLocation(storeLocation: StoreLocation): void {
    this.router.navigate(['/companies/store-locations/edit', storeLocation.id], {
      state: { storeLocation },
    });
  }

  onToggleStoreLocation(storeLocation: StoreLocation): void {
    const storeZone = this.selectedStoreZone();
    if (!storeZone) return;

    this.actionError.set(null);

    if (storeLocation.enabled) {
      const dialogRef = this.dialog.open(ConfirmDialog, {
        data: {
          title: 'Deshabilitar ubicación',
          message: `¿Confirmás que querés deshabilitar la ubicación "${storeLocation.locationName}"? La información se conserva y podés reactivarla más adelante.`,
          confirmLabel: 'Deshabilitar',
          destructive: true,
        },
      });
      dialogRef
        .afterClosed()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((confirmed: boolean) => {
          if (!confirmed) return;
          this.storeLocationService
            .removeLocation(
              storeZone.companyId,
              storeZone.companyCountryId,
              storeZone.regionId,
              storeZone.zoneId,
              storeZone.companyStoreId,
              storeZone.storeAreaId,
              storeZone.id,
              storeLocation.id,
            )
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
              next: () => this.reload.update((n) => n + 1),
              error: (err: HttpErrorResponse) => this.setActionError(err),
            });
        });
      return;
    }

    this.storeLocationService
      .enableLocation(
        storeZone.companyId,
        storeZone.companyCountryId,
        storeZone.regionId,
        storeZone.zoneId,
        storeZone.companyStoreId,
        storeZone.storeAreaId,
        storeZone.id,
        storeLocation.id,
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.reload.update((n) => n + 1),
        error: (err: HttpErrorResponse) => this.setActionError(err),
      });
  }

  /**
   * Surfaces write failures instead of swallowing them: the backend answers 409
   * when an ancestor (store, area or store zone) is disabled, which is an expected
   * flow here.
   */
  private setActionError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    this.actionError.set(apiError?.message ?? 'No se pudo actualizar la ubicación de la tienda.');
  }

  /**
   * Maps a cascade resource failure to user-facing copy. `rxResource` wraps
   * non-`Error` throwables (an `HttpErrorResponse`) in a wrapped error, so the
   * API envelope lives on `cause`.
   */
  private cascadeMessage(err: Error | undefined, fallback: string): string | null {
    if (!err) return null;
    const httpError = (err.cause as HttpErrorResponse | undefined) ?? (err as HttpErrorResponse);
    return (httpError.error as ApiError | undefined)?.message ?? fallback;
  }
}
