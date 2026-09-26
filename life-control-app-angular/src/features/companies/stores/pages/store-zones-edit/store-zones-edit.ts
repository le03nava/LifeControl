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
import { StoreZoneForm } from '../../components/store-zone-form/store-zone-form';
import { StoreZoneService } from '../../data/store-zone.service';
import {
  CreateStoreZoneRequest,
  StoreZone,
  UpdateStoreZoneRequest,
} from '../../models/store-zone.models';

/**
 * Full company → country → region → zone → store → area chain required by the
 * nested store-zone endpoints.
 */
interface StoreZoneChain {
  companyId: string;
  companyCountryId: string;
  regionId: string;
  zoneId: string;
  storeId: string;
  areaId: string;
}

@Component({
  selector: 'app-store-zones-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, ErrorBanner, StoreZoneForm],
  templateUrl: './store-zones-edit.html',
  styleUrl: './store-zones-edit.scss',
})
export class StoreZonesEdit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly storeZoneService = inject(StoreZoneService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /** The rendered form, read for its pristine/dirty state and for the save-in-flight lock. */
  private readonly zoneForm = viewChild(StoreZoneForm);

  /**
   * `lc-company-store-read` never reaches this route (the write-role guard blocks it), but the
   * form still hides its submit control instead of rendering a control that cannot be used.
   */
  readonly canWrite = hasAnyClientRole(STORE_WRITE_ROLES);

  /** True while a create/update request is in flight; disables the submit control. */
  readonly saving = signal(false);

  // ─── Route data ────────────────────────────────────────
  /** Id of the store zone being edited (the route param), not the company zone. */
  readonly storeZoneId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly isEditMode = computed(() => !!this.storeZoneId());

  // ─── Data signals ──────────────────────────────────────
  readonly storeZone = signal<StoreZone | null>(null);
  /**
   * Authoritative chain for the nested URLs. In edit mode it is built ONLY from
   * the flat lookup response — never from `history.state` or query params.
   */
  readonly chain = signal<StoreZoneChain | null>(null);

  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);

  /**
   * Optimistic-lock version of the store zone being edited, seeded from the flat lookup (and, as
   * a paint optimization, from `history.state`). This page has a flat GET, so a 412 re-runs the
   * lookup and recovers a fresh entity and a fresh version in place.
   */
  private readonly version = signal<number | null>(null);

  constructor() {
    const id = this.storeZoneId();

    if (id) {
      // Paint optimization: seed from history.state while the flat lookup resolves.
      const storeZoneFromState = (globalThis.history?.state as { storeZone?: StoreZone })
        ?.storeZone;
      if (storeZoneFromState) {
        this.storeZone.set(storeZoneFromState);
        this.version.set(storeZoneFromState.version);
      }

      this.loadZone(id);
      return;
    }

    // ─── Create mode: the chain comes from the six query params ───
    const companyId = this.route.snapshot.queryParamMap.get('companyId');
    const countryId = this.route.snapshot.queryParamMap.get('countryId');
    const regionId = this.route.snapshot.queryParamMap.get('regionId');
    // The `zoneId` query param is the company zone (third cascade level), not the
    // store zone being created — hence the local name.
    const companyZoneId = this.route.snapshot.queryParamMap.get('zoneId');
    const storeId = this.route.snapshot.queryParamMap.get('storeId');
    const areaId = this.route.snapshot.queryParamMap.get('areaId');

    if (!companyId || !countryId || !regionId || !companyZoneId || !storeId || !areaId) {
      this.router.navigate(['/companies/store-zones']);
      return;
    }

    this.chain.set({
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId: companyZoneId,
      storeId,
      areaId,
    });
  }

  // ─── Event handlers ────────────────────────────────────

  onSave(request: CreateStoreZoneRequest | UpdateStoreZoneRequest): void {
    const chain = this.chain();
    if (!chain || this.saving()) return;

    this.generalError.set(null);
    this.saving.set(true);

    const id = this.storeZoneId();
    const version = this.version();
    const request$ = id
      ? this.storeZoneService.updateZone(
          chain.companyId,
          chain.companyCountryId,
          chain.regionId,
          chain.zoneId,
          chain.storeId,
          chain.areaId,
          id,
          // Spread the version only when there is one: the merge belongs at the page boundary,
          // never in the form or the data service, and a create must serialize no `version` key.
          { ...request, ...(version !== null ? { version } : {}) },
        )
      : this.storeZoneService.createZone(
          chain.companyId,
          chain.companyCountryId,
          chain.regionId,
          chain.zoneId,
          chain.storeId,
          chain.areaId,
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
            id ? 'Zona actualizada correctamente.' : 'Zona creada correctamente.',
          );
          // The route's `canDeactivate` guard must not block the navigation that follows a save.
          this.zoneForm()?.formGroup.markAsPristine();
          this.router.navigate(['/companies/store-zones'], {
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
    return this.zoneForm()?.formGroup.dirty ?? false;
  }

  onCancel(): void {
    const chain = this.chain();
    if (!chain) {
      this.router.navigate(['/companies/store-zones']);
      return;
    }
    this.router.navigate(['/companies/store-zones'], {
      queryParams: {
        companyId: chain.companyId,
        countryId: chain.companyCountryId,
        regionId: chain.regionId,
        zoneId: chain.zoneId,
        storeId: chain.storeId,
        areaId: chain.areaId,
      },
    });
  }

  private toQueryParams(storeZone: StoreZone): Record<string, string> {
    return {
      companyId: storeZone.companyId,
      countryId: storeZone.companyCountryId,
      regionId: storeZone.regionId,
      zoneId: storeZone.zoneId,
      storeId: storeZone.companyStoreId,
      areaId: storeZone.storeAreaId,
    };
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
      return;
    }
    const id = this.storeZoneId();
    // A 412 on the update path is the version precondition failing: someone else saved this zone
    // first. This page has a flat GET, so it re-runs its load and recovers a fresh entity and a
    // fresh version in place rather than telling the operator to go back to the list. The reload
    // re-seeds the form with the server's current values, so the form is marked pristine and the
    // guard no longer asks to discard changes that now match the server. A 409 is a duplicate zone
    // code, not a lost update: it falls through to the server's own message below and never
    // reloads, so the operator can fix the typo without losing the draft.
    if (err.status === 412 && id) {
      this.serverErrors.set({});
      this.generalError.set(
        'Otra sesión modificó esta zona mientras la editabas. Se recargaron los valores actuales: revisalos y volvé a guardar.',
      );
      this.zoneForm()?.formGroup.markAsPristine();
      this.loadZone(id);
      return;
    }
    this.serverErrors.set({});
    this.generalError.set(apiError?.message ?? 'Error inesperado. Intente de nuevo más tarde.');
  }

  /**
   * Runs the authoritative flat lookup. Called once on init and again after a 412, so the page
   * recovers a fresh entity and a fresh version instead of staying stuck on a stale precondition.
   */
  private loadZone(id: string): void {
    this.storeZoneService
      .getZoneById(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (storeZone) => {
          this.storeZone.set(storeZone);
          this.version.set(storeZone.version);
          this.chain.set({
            companyId: storeZone.companyId,
            companyCountryId: storeZone.companyCountryId,
            regionId: storeZone.regionId,
            zoneId: storeZone.zoneId,
            storeId: storeZone.companyStoreId,
            areaId: storeZone.storeAreaId,
          });
        },
        error: () => this.router.navigate(['/companies/store-zones']),
      });
  }
}
