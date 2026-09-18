import {
  computed,
  DestroyRef,
  effect,
  Injector,
  inject,
  Injectable,
  OnInit,
  signal,
  Signal,
  WritableSignal,
} from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { ApiError } from '@shared/models';
import { map } from 'rxjs/operators';
import { CompanyService } from '../../companies/data/company.service';
import { CompanyCountryService } from '../../countries/data/company-country.service';
import { CompanyRegionService } from '../../regions/data/company-region.service';
import { CompanyZoneService } from '../../zones/data/company-zone.service';
import { CompanyStoreService } from '../data/company-store.service';
import { StoreAreaService } from '../data/store-area.service';
import { CompanyStore } from '../models/store.models';
import { StoreArea } from '../models/store-area.models';
import { StoreZone } from '../models/store-zone.models';
import { CompanyCountry } from '../../countries/models/country.models';
import { CompanyRegion } from '../../regions/models/region.models';
import { CompanyZone } from '../../zones/models/zone.models';

/** Type-erased view of a {@link CascadeStep}: the ladder mixes levels of different entities. */
export interface CascadeStepHandle {
  /** Query param that seeds this level. */
  readonly queryParam: string;
  /** Clears this level; called by the change handler of the level above it. */
  reset(): void;
  /** Seeds this level from the query params, at most once and only once options resolve. */
  preselect(paramId: string | null): void;
}

/**
 * One step of the store cascade: the selection it owns plus the options that resolve it.
 * Owns the two behaviours the three listing pages repeated per level: query-param
 * pre-selection and reset.
 */
export class CascadeStep<T extends { id: string }> implements CascadeStepHandle {
  private preselected = false;

  constructor(
    readonly queryParam: string,
    private readonly selection: WritableSignal<T | null>,
    private readonly options: Signal<T[]>,
  ) {}

  reset(): void {
    this.selection.set(null);
  }

  preselect(paramId: string | null): void {
    if (!paramId || this.preselected) return;
    const match = this.options().find((option) => option.id === paramId);
    if (!match) return;
    this.preselected = true;
    this.selection.set(match);
  }
}

/** `mat-select` comparator shared by every cascade entity: all of them are identified by `id`. */
export function compareById<T extends { id: string }>(): (a: T | null, b: T | null) => boolean {
  return (a, b) => a?.id === b?.id;
}

/**
 * Shared shell for the store cascade pages (company → country → region → company zone → store,
 * plus whatever levels the concrete page adds).
 *
 * It owns the resource chain of the levels every page shares, their guarded reads and error
 * copy, the `compareWith` comparators, query-param pre-selection and reset semantics. Each
 * concrete page keeps only its leaf concern: the final list resource and its write handlers.
 *
 * A base class — not a `<cascade-selectors>` component — because the existing page specs drive
 * the cascade through `fixture.componentInstance` (`selectedStore`, `onSelectCountry`, guarded
 * reads). Inheritance keeps that surface intact, so the specs stay the safety net for this
 * refactor; a child component would have invalidated their access path and, under View
 * Encapsulation, the `.filters-section` / `*-selector` rules each page styles itself.
 */
// `@Injectable()` (no `providedIn`) is required because the base injects its collaborators; the
// concrete pages are the ones Angular instantiates.
@Injectable()
export abstract class StoreCascadePage implements OnInit {
  protected readonly router = inject(Router);
  protected readonly destroyRef = inject(DestroyRef);
  protected readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);
  private readonly companyService = inject(CompanyService);
  private readonly companyCountryService = inject(CompanyCountryService);
  private readonly companyRegionService = inject(CompanyRegionService);
  private readonly companyZoneService = inject(CompanyZoneService);
  private readonly companyStoreService = inject(CompanyStoreService);

  /** Query params frozen at construction, exactly like each page captured them before. */
  private readonly queryParamMap = inject(ActivatedRoute).snapshot.queryParamMap;

  readonly companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  // ─── Selection state (the company is seeded from the query params at construction) ───
  readonly selectedCompanyId = signal<string | null>(this.queryParamMap.get('companyId'));
  readonly selectedCountry = signal<CompanyCountry | null>(null);
  readonly selectedRegion = signal<CompanyRegion | null>(null);
  /** Company zone (third cascade level) — distinct from the store zones of the zones page. */
  readonly selectedCompanyZone = signal<CompanyZone | null>(null);
  readonly selectedStore = signal<CompanyStore | null>(null);

  // ─── Filter state shared by every listing page ───
  readonly showDisabled = signal(false);
  /** Bumped after a disable/enable write to re-fetch the leaf list. */
  readonly reload = signal(0);
  /** Write failure surfaced to the user (HTTP 409 when an ancestor is disabled). */
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
   * Always requests disabled stores too: the pages can be entered from a disabled store's
   * card, and the pre-selected store must resolve even when disabled.
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

  /**
   * Cascade load failures: a failed non-leaf level leaves its dependent selectors empty
   * (guarded reads above) and surfaces the failure instead of a silent empty.
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

  /** `mat-select` comparators, one per cascade entity type. */
  protected readonly compareCompanyCountry = compareById<CompanyCountry>();
  protected readonly compareCompanyRegion = compareById<CompanyRegion>();
  protected readonly compareCompanyZone = compareById<CompanyZone>();
  protected readonly compareCompanyStore = compareById<CompanyStore>();
  protected readonly compareStoreArea = compareById<StoreArea>();
  protected readonly compareStoreZone = compareById<StoreZone>();

  // ─── Cascade ladder ──────────────────────────────────────────
  private readonly countryStep = new CascadeStep('countryId', this.selectedCountry, this.countries);
  private readonly regionStep = new CascadeStep('regionId', this.selectedRegion, this.regions);
  private readonly companyZoneStep = new CascadeStep(
    'zoneId',
    this.selectedCompanyZone,
    this.companyZones,
  );
  private readonly storeStep = new CascadeStep('storeId', this.selectedStore, this.stores);

  /**
   * Ordered ladder below the company selector. Concrete pages append their own levels.
   * Read lazily (never during construction) so subclass field initializers have run.
   */
  protected cascadeSteps(): readonly CascadeStepHandle[] {
    return [this.countryStep, this.regionStep, this.companyZoneStep, this.storeStep];
  }

  /**
   * Pre-selection is wired from `ngOnInit` rather than the constructor so that
   * `cascadeSteps()` already sees the levels the concrete page declares.
   *
   * The lint suppression is a false positive: the rule assumes an `@Injectable()` class is never
   * instantiated as a directive. This one is only ever extended by the listing components, and
   * Angular does invoke the inherited hook on them — the query-param pre-selection specs of all
   * three pages fail if it does not run.
   */
  // eslint-disable-next-line @angular-eslint/contextual-lifecycle -- inherited component hook
  ngOnInit(): void {
    effect(
      () => {
        for (const step of this.cascadeSteps()) {
          step.preselect(this.queryParamMap.get(step.queryParam));
        }
      },
      { injector: this.injector },
    );
  }

  // ─── Event handlers (reset semantics live in the ladder) ─────

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    for (const step of this.cascadeSteps()) {
      step.reset();
    }
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
    this.resetAbove(this.countryStep);
  }

  onSelectRegion(region: CompanyRegion): void {
    this.selectedRegion.set(region);
    this.resetAbove(this.regionStep);
  }

  onSelectCompanyZone(companyZone: CompanyZone): void {
    this.selectedCompanyZone.set(companyZone);
    this.resetAbove(this.companyZoneStep);
  }

  onSelectStore(store: CompanyStore): void {
    this.selectedStore.set(store);
    this.resetAbove(this.storeStep);
  }

  /** Clears every level below `step` in the ladder. */
  protected resetAbove(step: CascadeStepHandle): void {
    const steps = this.cascadeSteps();
    const index = steps.indexOf(step);
    for (let i = index + 1; i < steps.length; i++) {
      steps[i].reset();
    }
  }

  /**
   * Surfaces write failures instead of swallowing them: the backend answers 409 when an
   * ancestor is disabled, which is an expected flow on these pages.
   */
  protected setActionError(err: HttpErrorResponse, fallback: string): void {
    const apiError = err.error as ApiError | undefined;
    this.actionError.set(apiError?.message ?? fallback);
  }

  /**
   * Maps a resource failure to user-facing copy. `rxResource` wraps non-`Error` throwables
   * (an `HttpErrorResponse`) in a wrapped error, so the API envelope lives on `cause`.
   */
  protected cascadeMessage(err: Error | undefined, fallback: string): string | null {
    if (!err) return null;
    const httpError = (err.cause as HttpErrorResponse | undefined) ?? (err as HttpErrorResponse);
    return (httpError.error as ApiError | undefined)?.message ?? fallback;
  }
}

/**
 * Cascade shell for the pages whose ladder includes the area level (store zones and store
 * locations). Both request the area filter with disabled rows included, so it lives here
 * instead of being copied per page.
 */
@Injectable()
export abstract class StoreAreaLevelCascadePage extends StoreCascadePage {
  private readonly storeAreaService = inject(StoreAreaService);

  readonly selectedArea = signal<StoreArea | null>(null);

  /**
   * Filter level, never the final list: always requests disabled areas too so a pre-selected
   * disabled area can resolve.
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

  readonly areas = computed(() =>
    this.areasResource.hasValue() ? this.areasResource.value() : [],
  );

  readonly areasError = computed(() =>
    this.cascadeMessage(this.areasResource.error(), 'No se pudieron cargar las áreas.'),
  );

  private readonly areaStep = new CascadeStep('areaId', this.selectedArea, this.areas);

  protected override cascadeSteps(): readonly CascadeStepHandle[] {
    return [...super.cascadeSteps(), this.areaStep];
  }

  onSelectArea(area: StoreArea): void {
    this.selectedArea.set(area);
    this.resetAbove(this.areaStep);
  }
}
