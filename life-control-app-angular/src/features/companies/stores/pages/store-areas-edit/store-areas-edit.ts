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
import { StoreAreaForm } from '../../components/store-area-form/store-area-form';
import { StoreAreaService } from '../../data/store-area.service';
import {
  CreateStoreAreaRequest,
  StoreArea,
  UpdateStoreAreaRequest,
} from '../../models/store-area.models';

/**
 * Full company → country → region → zone → store chain required by the nested
 * store-area endpoints.
 */
interface StoreAreaChain {
  companyId: string;
  companyCountryId: string;
  regionId: string;
  zoneId: string;
  storeId: string;
}

@Component({
  selector: 'app-store-areas-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, ErrorBanner, StoreAreaForm],
  templateUrl: './store-areas-edit.html',
  styleUrl: './store-areas-edit.scss',
})
export class StoreAreasEdit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly storeAreaService = inject(StoreAreaService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /** The rendered form, read for its pristine/dirty state and for the save-in-flight lock. */
  private readonly areaForm = viewChild(StoreAreaForm);

  /**
   * `lc-company-store-read` never reaches this route (the write-role guard blocks it), but the
   * form still hides its submit control instead of rendering a control that cannot be used.
   */
  readonly canWrite = hasAnyClientRole(STORE_WRITE_ROLES);

  /** True while a create/update request is in flight; disables the submit control. */
  readonly saving = signal(false);

  // ─── Route data ────────────────────────────────────────
  readonly areaId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly isEditMode = computed(() => !!this.areaId());

  // ─── Data signals ──────────────────────────────────────
  readonly area = signal<StoreArea | null>(null);
  /**
   * Authoritative chain for the nested URLs. In edit mode it is built ONLY from
   * the flat lookup response — never from `history.state` or query params.
   */
  readonly chain = signal<StoreAreaChain | null>(null);

  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);

  /**
   * Optimistic-lock version of the area being edited, seeded from the flat lookup (and, as a paint
   * optimization, from `history.state`). Unlike `stores`, this page has a flat GET, so a 412
   * re-runs the lookup and recovers a fresh entity and a fresh version in place.
   */
  private readonly version = signal<number | null>(null);

  constructor() {
    const id = this.areaId();

    if (id) {
      // Paint optimization: seed from history.state while the flat lookup resolves.
      const areaFromState = (globalThis.history?.state as { area?: StoreArea })?.area;
      if (areaFromState) {
        this.area.set(areaFromState);
        this.version.set(areaFromState.version);
      }

      this.loadArea(id);
      return;
    }

    // ─── Create mode: the chain comes from the query params ───
    const companyId = this.route.snapshot.queryParamMap.get('companyId');
    const countryId = this.route.snapshot.queryParamMap.get('countryId');
    const regionId = this.route.snapshot.queryParamMap.get('regionId');
    const zoneId = this.route.snapshot.queryParamMap.get('zoneId');
    const storeId = this.route.snapshot.queryParamMap.get('storeId');

    if (!companyId || !countryId || !regionId || !zoneId || !storeId) {
      this.router.navigate(['/companies/store-areas']);
      return;
    }

    this.chain.set({
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      storeId,
    });
  }

  // ─── Event handlers ────────────────────────────────────

  onSave(request: CreateStoreAreaRequest | UpdateStoreAreaRequest): void {
    const chain = this.chain();
    if (!chain || this.saving()) return;

    this.generalError.set(null);
    this.saving.set(true);

    const id = this.areaId();
    const version = this.version();
    const request$ = id
      ? this.storeAreaService.updateArea(
          chain.companyId,
          chain.companyCountryId,
          chain.regionId,
          chain.zoneId,
          chain.storeId,
          id,
          // Spread the version only when there is one: the merge belongs at the page boundary,
          // never in the form or the data service, and a create must serialize no `version` key.
          { ...request, ...(version !== null ? { version } : {}) },
        )
      : this.storeAreaService.createArea(
          chain.companyId,
          chain.companyCountryId,
          chain.regionId,
          chain.zoneId,
          chain.storeId,
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
            id ? 'Área actualizada correctamente.' : 'Área creada correctamente.',
          );
          // The route's `canDeactivate` guard must not block the navigation that follows a save.
          this.areaForm()?.formGroup.markAsPristine();
          this.router.navigate(['/companies/store-areas'], {
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
    return this.areaForm()?.formGroup.dirty ?? false;
  }

  onCancel(): void {
    const chain = this.chain();
    if (!chain) {
      this.router.navigate(['/companies/store-areas']);
      return;
    }
    this.router.navigate(['/companies/store-areas'], {
      queryParams: {
        companyId: chain.companyId,
        countryId: chain.companyCountryId,
        regionId: chain.regionId,
        zoneId: chain.zoneId,
        storeId: chain.storeId,
      },
    });
  }

  private toQueryParams(area: StoreArea): Record<string, string> {
    return {
      companyId: area.companyId,
      countryId: area.companyCountryId,
      regionId: area.regionId,
      zoneId: area.zoneId,
      storeId: area.companyStoreId,
    };
  }

  /**
   * Runs the authoritative flat lookup. Called once on init and again after a 412, so the page
   * recovers a fresh entity and a fresh version instead of staying stuck on a stale precondition.
   */
  private loadArea(id: string): void {
    this.storeAreaService
      .getAreaById(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (area) => {
          this.area.set(area);
          this.version.set(area.version);
          this.chain.set({
            companyId: area.companyId,
            companyCountryId: area.companyCountryId,
            regionId: area.regionId,
            zoneId: area.zoneId,
            storeId: area.companyStoreId,
          });
        },
        error: () => this.router.navigate(['/companies/store-areas']),
      });
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
      return;
    }
    const id = this.areaId();
    // A 412 on the update path is the version precondition failing: someone else saved this area
    // first. Unlike `stores`, this page has a flat GET, so it re-runs its load and recovers a fresh
    // entity and a fresh version in place rather than telling the operator to go back to the list.
    // The reload re-seeds the form with the server's current values, so the form is marked pristine
    // and the guard no longer asks to discard changes that now match the server. A 409 is a
    // duplicate area code, not a lost update: it falls through to the server's own message below and
    // never reloads, so the operator can fix the typo without losing the draft.
    if (err.status === 412 && id) {
      this.serverErrors.set({});
      this.generalError.set(
        'Otra sesión modificó esta área mientras la editabas. Se recargaron los valores actuales: revisalos y volvé a guardar.',
      );
      this.areaForm()?.formGroup.markAsPristine();
      this.loadArea(id);
      return;
    }
    this.serverErrors.set({});
    this.generalError.set(apiError?.message ?? 'Error inesperado. Intente de nuevo más tarde.');
  }
}
