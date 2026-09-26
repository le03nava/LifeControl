import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs/operators';
import { PageHeader, ErrorBanner } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { hasAnyClientRole, STORE_WRITE_ROLES } from '@core/security/roles';
import { ApiError } from '@shared/models';
import { StoreLocationForm } from '../../components/store-location-form/store-location-form';
import { StoreLocationService } from '../../data/store-location.service';
import {
  CreateStoreLocationRequest,
  StoreLocation,
  UpdateStoreLocationRequest,
} from '../../models/store-location.models';

/**
 * Full company → country → region → zone → store → area → store zone chain
 * required by the nested store-location endpoints.
 */
interface StoreLocationChain {
  companyId: string;
  companyCountryId: string;
  regionId: string;
  zoneId: string;
  storeId: string;
  areaId: string;
  storeZoneId: string;
}

@Component({
  selector: 'app-store-locations-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, ErrorBanner, StoreLocationForm],
  templateUrl: './store-locations-edit.html',
  styleUrl: './store-locations-edit.scss',
})
export class StoreLocationsEdit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly storeLocationService = inject(StoreLocationService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /** The rendered form, read for its pristine/dirty state and for the save-in-flight lock. */
  private readonly locationForm = viewChild(StoreLocationForm);

  /**
   * `lc-company-store-read` never reaches this route (the write-role guard blocks it), but the
   * form still hides its submit control instead of rendering a control that cannot be used.
   */
  readonly canWrite = hasAnyClientRole(STORE_WRITE_ROLES);

  /** True while a create/update request is in flight; disables the submit control. */
  readonly saving = signal(false);

  // ─── Route data ────────────────────────────────────────
  /** Id of the store location being edited (the route param), not the company zone. */
  readonly storeLocationId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly isEditMode = computed(() => !!this.storeLocationId());

  // ─── Data signals ──────────────────────────────────────
  readonly storeLocation = signal<StoreLocation | null>(null);
  /**
   * Authoritative chain for the nested URLs. In edit mode it is built ONLY from
   * the flat lookup response — never from `history.state` or query params.
   */
  readonly chain = signal<StoreLocationChain | null>(null);

  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);

  /**
   * Optimistic-lock version of the store location being edited, seeded from the flat lookup (and,
   * as a paint optimization, from `history.state`). This page has a flat GET, so a 412 re-runs the
   * lookup and recovers a fresh entity and a fresh version in place.
   */
  private readonly version = signal<number | null>(null);

  constructor() {
    const id = this.storeLocationId();

    if (id) {
      // Paint optimization: seed from history.state while the flat lookup resolves.
      const storeLocationFromState = (
        globalThis.history?.state as { storeLocation?: StoreLocation }
      )?.storeLocation;
      if (storeLocationFromState) {
        this.storeLocation.set(storeLocationFromState);
        this.version.set(storeLocationFromState.version);
      }

      this.loadLocation(id);
      return;
    }

    // ─── Create mode: the chain comes from the seven query params ───
    const companyId = this.route.snapshot.queryParamMap.get('companyId');
    const countryId = this.route.snapshot.queryParamMap.get('countryId');
    const regionId = this.route.snapshot.queryParamMap.get('regionId');
    // The `zoneId` query param is the company zone (third cascade level), not the
    // store zone being created — hence the local name.
    const companyZoneId = this.route.snapshot.queryParamMap.get('zoneId');
    const storeId = this.route.snapshot.queryParamMap.get('storeId');
    const areaId = this.route.snapshot.queryParamMap.get('areaId');
    const storeZoneId = this.route.snapshot.queryParamMap.get('storeZoneId');

    if (
      !companyId ||
      !countryId ||
      !regionId ||
      !companyZoneId ||
      !storeId ||
      !areaId ||
      !storeZoneId
    ) {
      this.router.navigate(['/companies/store-locations']);
      return;
    }

    this.chain.set({
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId: companyZoneId,
      storeId,
      areaId,
      storeZoneId,
    });
  }

  // ─── Event handlers ────────────────────────────────────

  onSave(request: CreateStoreLocationRequest | UpdateStoreLocationRequest): void {
    const chain = this.chain();
    if (!chain || this.saving()) return;

    this.generalError.set(null);
    this.saving.set(true);

    const id = this.storeLocationId();
    const version = this.version();
    const request$ = id
      ? this.storeLocationService.updateLocation(
          chain.companyId,
          chain.companyCountryId,
          chain.regionId,
          chain.zoneId,
          chain.storeId,
          chain.areaId,
          chain.storeZoneId,
          id,
          // Spread the version only when there is one: the merge belongs at the page boundary,
          // never in the form or the data service, and a create must serialize no `version` key.
          { ...request, ...(version !== null ? { version } : {}) },
        )
      : this.storeLocationService.createLocation(
          chain.companyId,
          chain.companyCountryId,
          chain.regionId,
          chain.zoneId,
          chain.storeId,
          chain.areaId,
          chain.storeZoneId,
          request,
        );

    request$
      .pipe(
        finalize(() => this.saving.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (saved) => {
          this.notifications.showSuccess(
            id ? 'Ubicación actualizada correctamente.' : 'Ubicación creada correctamente.',
          );
          // The route's `canDeactivate` guard must not block the navigation that follows a save.
          this.locationForm()?.formGroup.markAsPristine();
          this.router.navigate(['/companies/store-locations'], {
            queryParams: this.toQueryParams(saved),
          });
        },
        error: (err: HttpErrorResponse) => this.handleError(err),
      });
  }

  /**
   * Exposed to `unsavedChangesGuard`, which blocks the route change while the form is dirty.
   */
  hasUnsavedChanges(): boolean {
    return this.locationForm()?.formGroup.dirty ?? false;
  }

  onCancel(): void {
    const chain = this.chain();
    if (!chain) {
      this.router.navigate(['/companies/store-locations']);
      return;
    }
    this.router.navigate(['/companies/store-locations'], {
      queryParams: {
        companyId: chain.companyId,
        countryId: chain.companyCountryId,
        regionId: chain.regionId,
        zoneId: chain.zoneId,
        storeId: chain.storeId,
        areaId: chain.areaId,
        storeZoneId: chain.storeZoneId,
      },
    });
  }

  private toQueryParams(storeLocation: StoreLocation): Record<string, string> {
    return {
      companyId: storeLocation.companyId,
      countryId: storeLocation.companyCountryId,
      regionId: storeLocation.regionId,
      zoneId: storeLocation.zoneId,
      storeId: storeLocation.companyStoreId,
      areaId: storeLocation.storeAreaId,
      storeZoneId: storeLocation.storeZoneId,
    };
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
      return;
    }
    const id = this.storeLocationId();
    // A 412 on the update path is the version precondition failing: someone else saved this
    // location first. This page has a flat GET, so it re-runs its load and recovers a fresh entity
    // and a fresh version in place rather than telling the operator to go back to the list. The
    // reload re-seeds the form with the server's current values, so the form is marked pristine and
    // the guard no longer asks to discard changes that now match the server. A 409 is a duplicate
    // location code, not a lost update: it falls through to the server's own message below and
    // never reloads, so the operator can fix the typo without losing the draft.
    if (err.status === 412 && id) {
      this.serverErrors.set({});
      this.generalError.set(
        'Otra sesión modificó esta ubicación mientras la editabas. Se recargaron los valores actuales: revisalos y volvé a guardar.',
      );
      this.locationForm()?.formGroup.markAsPristine();
      this.loadLocation(id);
      return;
    }
    this.serverErrors.set({});
    this.generalError.set(apiError?.message ?? 'Error inesperado. Intente de nuevo más tarde.');
  }

  /**
   * Runs the authoritative flat lookup. Called once on init and again after a 412, so the page
   * recovers a fresh entity and a fresh version instead of staying stuck on a stale precondition.
   */
  private loadLocation(id: string): void {
    this.storeLocationService
      .getLocationById(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (storeLocation) => {
          this.storeLocation.set(storeLocation);
          this.version.set(storeLocation.version);
          this.chain.set({
            companyId: storeLocation.companyId,
            companyCountryId: storeLocation.companyCountryId,
            regionId: storeLocation.regionId,
            zoneId: storeLocation.zoneId,
            storeId: storeLocation.companyStoreId,
            areaId: storeLocation.storeAreaId,
            storeZoneId: storeLocation.storeZoneId,
          });
        },
        error: () => this.router.navigate(['/companies/store-locations']),
      });
  }
}
