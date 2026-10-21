import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
  viewChild,
  OnInit,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { StoresForm } from '../../components/stores-form/stores-form';
import { StoreInventorySettingsPanel } from '../../components/store-inventory-settings-panel/store-inventory-settings-panel';
import { ErrorBanner } from '@shared/ui';
import { CompanyStore, StoreSaveEvent } from '../../models/store.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '@shared/models';
import type { UnsavedChangesAware } from '@core/guards/unsaved-changes.guard';
import type { StoreChain } from '@features/inventory/models/store-location-summary.models';
import { map } from 'rxjs/operators';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';

/**
 * The tabs the edit screen renders, in the order they appear, keyed by the values `?tab=` carries.
 *
 * There is no configuration tab while creating a store: the inventory settings hang off a store id,
 * and a store that does not exist yet has nothing to configure.
 */
const EDIT_TABS = ['datos', 'configuracion'] as const;
type EditTab = (typeof EDIT_TABS)[number];

@Component({
  selector: 'app-stores-edit',
  standalone: true,
  imports: [NgTemplateOutlet, StoresForm, StoreInventorySettingsPanel, ErrorBanner, MatTabsModule],
  templateUrl: './stores-edit.html',
  styleUrl: './stores-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StoresEdit implements OnInit, UnsavedChangesAware {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly companyService = inject(CompanyService);
  private readonly companyCountryService = inject(CompanyCountryService);
  private readonly companyRegionService = inject(CompanyRegionService);
  private readonly companyZoneService = inject(CompanyZoneService);
  private readonly companyStoreService = inject(CompanyStoreService);
  private readonly destroyRef = inject(DestroyRef);

  // ─── Route data ────────────────────────────────────────
  storeId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  isEditMode = signal(false);

  // ─── Tabs ──────────────────────────────────────────────
  /**
   * The tab `?tab=` asks for, or `Datos` when it names none of the two the screen renders.
   *
   * Read once from the route snapshot, like the create-mode pre-selection below: a stale or unknown
   * link must land on the store, not on a blank tab. `selectedTabIndex` is derived, so the selection
   * Material paints can never disagree with the URL.
   */
  readonly activeTab = signal<EditTab>(this.readTab(this.route.snapshot.queryParamMap.get('tab')));

  readonly selectedTabIndex = computed(() =>
    Math.max(0, (EDIT_TABS as readonly string[]).indexOf(this.activeTab())),
  );

  // ─── Data signals ──────────────────────────────────────
  companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((p) => p.content)),
    {
      initialValue: [],
    },
  );
  companyCountries = this.companyCountryService.assignedCountries;

  regionList = signal<CompanyRegion[]>([]);

  serverErrors = signal<Record<string, string>>({});
  addressServerErrors = signal<Record<string, string>>({});

  /** Non-field save failure (e.g. 409 duplicate name or 404) surfaced as a banner. */
  generalError = signal<string | null>(null);

  // ─── Edit mode data ────────────────────────────────────
  storeToEdit = signal<CompanyStore | null>(null);
  /**
   * Optimistic-lock version of the store being edited, seeded from `history.state`, echoed back on
   * the update. It is deliberately never advanced here: after a 412 the retry re-sends the same
   * stale version, so the backend keeps rejecting it and the screen stays fail-closed.
   */
  private readonly version = signal<number | null>(null);

  // ─── Create mode initial values (from query params) ───
  initialCompanyId = signal<string | null>(null);
  initialCountryId = signal<string | null>(null);
  initialRegionId = signal<string | null>(null);
  initialZoneId = signal<string | null>(null);

  /**
   * The five-level chain the inventory settings panel needs, derived from the store being edited.
   *
   * `null` until the store is known, and the template renders the panel only when it is not: the
   * panel is keyed on the chain, so it never asks the backend about a store the page cannot name.
   */
  readonly inventoryChain = computed<StoreChain | null>(() => {
    const store = this.storeToEdit();
    if (!store) return null;
    return {
      companyId: store.companyId,
      companyCountryId: store.companyCountryId,
      regionId: store.regionId,
      zoneId: store.zoneId,
      storeId: store.id,
    };
  });

  /**
   * The mounted configuration panel. A `viewChild` and not a template reference because the panel
   * only exists once the page holds a store: until then it is `undefined` and there is no draft to
   * report. Material mounts the tab bodies eagerly, so this resolves on page entry, not on the first
   * visit to the tab.
   */
  private readonly inventoryPanel = viewChild(StoreInventorySettingsPanel);

  constructor() {
    // --- Server error mapping: extract address.* keys and strip prefix ---
    effect(() => {
      const allErrors = this.serverErrors();
      const addrErrors: Record<string, string> = {};
      Object.entries(allErrors).forEach(([k, v]) => {
        if (k.startsWith('address.')) addrErrors[k.slice(8)] = v;
      });
      this.addressServerErrors.set(addrErrors);
    });
  }

  ngOnInit(): void {
    const id = this.storeId();
    if (id) {
      this.isEditMode.set(true);
      // Leer la store del state desde history.state (SSR-safe)
      const storeFromState = (globalThis.history?.state as { store?: CompanyStore })?.store;
      if (storeFromState) {
        this.storeToEdit.set(storeFromState);
        this.version.set(storeFromState.version);
        // Load regions for the store's country
        this.companyRegionService
          .getRegions(storeFromState.companyId, storeFromState.companyCountryId)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe((regions) => this.regionList.set(regions));
      } else {
        // Fallback: redirect to stores list if no state
        this.router.navigate(['/companies/stores']);
      }
    }

    // ─── Create mode: pre-select company/country/region/zone from query params ──
    const companyId = this.route.snapshot.queryParamMap.get('companyId');
    const countryId = this.route.snapshot.queryParamMap.get('countryId');
    const regionId = this.route.snapshot.queryParamMap.get('regionId');
    const zoneId = this.route.snapshot.queryParamMap.get('zoneId');

    if (!id) {
      if (companyId) {
        this.initialCompanyId.set(companyId);
        this.initialCountryId.set(countryId);
        this.initialRegionId.set(regionId);
        this.initialZoneId.set(zoneId);
        this.companyCountryService
          .getCountries(companyId)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe();
        if (countryId) {
          this.companyRegionService
            .getRegions(companyId, countryId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((regions) => this.regionList.set(regions));
        }
      }
    }
  }

  onSelectedCompanyChange(companyId: string): void {
    if (companyId) {
      this.companyCountryService
        .getCountries(companyId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe();
    }
  }

  onSelectedCountryChange(cc: CompanyCountry): void {
    if (cc?.companyId && cc?.id) {
      this.companyRegionService
        .getRegions(cc.companyId, cc.id)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((regions) => this.regionList.set(regions));
    }
  }

  onSelectedRegionChange(_region: CompanyRegion): void {
    // region selected — no need to load zones here
  }

  onSaveStore(event: StoreSaveEvent): void {
    if (this.isEditMode()) {
      const storeId = this.storeId();
      if (!storeId) return;
      const version = this.version();
      this.companyStoreService
        .updateStore(
          event.companyId,
          event.countryId,
          event.regionId,
          event.zoneId,
          storeId,
          // Spread the version only when there is one: a create must serialize no `version` key at
          // all, and the merge belongs at the page boundary, never in the form or the data service.
          { ...event.request, ...(version !== null ? { version } : {}) },
        )
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () =>
            this.router.navigate(['/companies/stores'], {
              queryParams: {
                companyId: event.companyId,
                countryId: event.countryId,
                regionId: event.regionId,
                zoneId: event.zoneId,
              },
            }),
          error: (err: HttpErrorResponse) => this.handleError(err),
        });
    } else {
      this.companyStoreService
        .addStore(event.companyId, event.countryId, event.regionId, event.zoneId, event.request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () =>
            this.router.navigate(['/companies/stores'], {
              queryParams: {
                companyId: event.companyId,
                countryId: event.countryId,
                regionId: event.regionId,
                zoneId: event.zoneId,
              },
            }),
          error: (err: HttpErrorResponse) => this.handleError(err),
        });
    }
  }

  onCancelForm(): void {
    const store = this.storeToEdit();
    const qp: Record<string, string> = {};

    if (store) {
      // Edit mode — sacar companyId, countryId, regionId, zoneId del store en state
      qp['companyId'] = store.companyId;
      qp['countryId'] = store.companyCountryId;
      qp['regionId'] = store.regionId;
      qp['zoneId'] = store.zoneId;
    } else {
      // Create mode — preservar los valores originales de query params
      const companyId = this.initialCompanyId();
      const countryId = this.initialCountryId();
      const regionId = this.initialRegionId();
      const zoneId = this.initialZoneId();
      if (companyId) {
        qp['companyId'] = companyId;
        if (countryId) qp['countryId'] = countryId;
        if (regionId) qp['regionId'] = regionId;
        if (zoneId) qp['zoneId'] = zoneId;
      }
    }

    if (Object.keys(qp).length > 0) {
      this.router.navigate(['/companies/stores'], { queryParams: qp });
    } else {
      this.router.navigate(['/companies/stores']);
    }
  }

  /**
   * Keeps `?tab=` in step with the tab Material paints.
   *
   * The write merges, so the create-mode pre-selection parameters survive, and replaces the history
   * entry: a tab is a view of the store, not a navigation, so Back leaves the screen instead of
   * walking the tabs. The early return is the feedback-loop guard — `mat-tab-group` emits
   * `selectedIndexChange` in a microtask, and writing a value the URL already carries would navigate
   * into itself.
   */
  onTabChange(index: number): void {
    const requested = EDIT_TABS[index];
    if (!requested || requested === this.activeTab()) {
      return;
    }
    this.activeTab.set(requested);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab: requested },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /**
   * Exposed to `unsavedChangesGuard` on the edit route: the configuration tab owns the only draft
   * this screen can lose behind its back, so the answer is the panel's, never a guess here.
   */
  hasUnsavedChanges(): boolean {
    return this.inventoryPanel()?.hasUnsavedChanges() ?? false;
  }

  private readTab(value: string | null): EditTab {
    return (EDIT_TABS as readonly string[]).includes(value ?? '') ? (value as EditTab) : 'datos';
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
      return;
    }
    // A 412 on the update path is the version precondition failing (or a concurrent write). The
    // page must not reload: `history.state` holds the stale copy and survives a browser reload of
    // the same history entry, so the shared "recargá la página" copy would be a lie. The copy names
    // the real recovery instead. A 409, on the update path or in create mode, is a duplicate name,
    // not a lost update: it falls through to the server's own message below.
    if (err.status === 412 && this.isEditMode()) {
      this.generalError.set(
        'Otra sesión modificó esta tienda mientras la editabas. Volvé a la lista y abrí la tienda de nuevo.',
      );
      return;
    }
    // The backend 409/404 envelope has no `errors` map: surface its message instead.
    this.generalError.set(apiError?.message ?? 'No se pudo guardar la tienda.');
  }
}
