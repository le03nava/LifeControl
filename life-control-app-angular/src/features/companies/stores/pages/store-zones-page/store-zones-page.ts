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
import { CompanyStore } from '../../models/store.models';
import { StoreArea } from '../../models/store-area.models';
import { StoreZone } from '../../models/store-zone.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyZone } from '../../../zones/models/zone.models';

@Component({
  selector: 'app-store-zones-page',
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
  templateUrl: './store-zones-page.html',
  styleUrl: './store-zones-page.scss',
})
export class StoreZonesPage {
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

  // ─── Filter state ────────────────────────────────────────────
  readonly showDisabled = signal(false);
  /** Bumped after a disable/enable write to re-fetch the store zones list. */
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
        showDisabled: this.showDisabled(),
        reload: this.reload(),
      };
    },
    stream: ({ params }) =>
      this.storeZoneService
        .getStoreZones(
          params.companyId,
          params.companyCountryId,
          params.regionId,
          params.zoneId,
          params.storeId,
          params.areaId,
          params.showDisabled,
        )
        .pipe(catchError(() => of([] as StoreZone[]))),
    defaultValue: [] as StoreZone[],
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

  /** Friendly error message owned by the service (set on load failure). */
  readonly storeZonesError = computed(() => this.storeZoneService.error());

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

  /** Guards so the query-param pre-selection runs exactly once per level. */
  private countryPreselected = false;
  private regionPreselected = false;
  private companyZonePreselected = false;
  private storePreselected = false;
  private areaPreselected = false;

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
  }

  // ─── Event handlers ──────────────────────────────────────────

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.selectedCountry.set(null);
    this.selectedRegion.set(null);
    this.selectedCompanyZone.set(null);
    this.selectedStore.set(null);
    this.selectedArea.set(null);
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
    this.selectedRegion.set(null);
    this.selectedCompanyZone.set(null);
    this.selectedStore.set(null);
    this.selectedArea.set(null);
  }

  onSelectRegion(region: CompanyRegion): void {
    this.selectedRegion.set(region);
    this.selectedCompanyZone.set(null);
    this.selectedStore.set(null);
    this.selectedArea.set(null);
  }

  onSelectCompanyZone(companyZone: CompanyZone): void {
    this.selectedCompanyZone.set(companyZone);
    this.selectedStore.set(null);
    this.selectedArea.set(null);
  }

  onSelectStore(store: CompanyStore): void {
    this.selectedStore.set(store);
    this.selectedArea.set(null);
  }

  onSelectArea(area: StoreArea): void {
    this.selectedArea.set(area);
  }

  onCreateStoreZone(): void {
    const area = this.selectedArea();
    const store = this.selectedStore();
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    const companyZone = this.selectedCompanyZone();
    if (!area || !store || !cc || !region || !companyZone) return;
    this.router.navigate(['/companies/store-zones/create'], {
      queryParams: {
        companyId: cc.companyId,
        countryId: cc.id,
        regionId: region.id,
        zoneId: companyZone.id,
        storeId: store.id,
        areaId: area.id,
      },
    });
  }

  onEditStoreZone(storeZone: StoreZone): void {
    this.router.navigate(['/companies/store-zones/edit', storeZone.id], {
      state: { storeZone },
    });
  }

  onToggleStoreZone(storeZone: StoreZone): void {
    const area = this.selectedArea();
    if (!area) return;

    this.actionError.set(null);

    if (storeZone.enabled) {
      const dialogRef = this.dialog.open(ConfirmDialog, {
        data: {
          title: 'Deshabilitar zona de tienda',
          message: `¿Confirmás que querés deshabilitar la zona "${storeZone.zoneName}"? La información se conserva y podés reactivarla más adelante.`,
          confirmLabel: 'Deshabilitar',
          destructive: true,
        },
      });
      dialogRef
        .afterClosed()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((confirmed: boolean) => {
          if (!confirmed) return;
          this.storeZoneService
            .removeZone(
              area.companyId,
              area.companyCountryId,
              area.regionId,
              area.zoneId,
              area.companyStoreId,
              area.id,
              storeZone.id,
            )
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
              next: () => this.reload.update((n) => n + 1),
              error: (err: HttpErrorResponse) => this.setActionError(err),
            });
        });
      return;
    }

    this.storeZoneService
      .enableZone(
        area.companyId,
        area.companyCountryId,
        area.regionId,
        area.zoneId,
        area.companyStoreId,
        area.id,
        storeZone.id,
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.reload.update((n) => n + 1),
        error: (err: HttpErrorResponse) => this.setActionError(err),
      });
  }

  /**
   * Surfaces write failures instead of swallowing them: the backend answers 409
   * when an ancestor (store or area) is disabled, which is an expected flow here.
   */
  private setActionError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    this.actionError.set(apiError?.message ?? 'No se pudo actualizar la zona de la tienda.');
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
