import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { PageHeader, ErrorBanner } from '@shared/ui';
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
  private readonly destroyRef = inject(DestroyRef);

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

  constructor() {
    const id = this.areaId();

    if (id) {
      // Paint optimization: seed from history.state while the flat lookup resolves.
      const areaFromState = (globalThis.history?.state as { area?: StoreArea })?.area;
      if (areaFromState) {
        this.area.set(areaFromState);
      }

      this.storeAreaService
        .getAreaById(id)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (area) => {
            this.area.set(area);
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
    if (!chain) return;

    this.generalError.set(null);

    const id = this.areaId();
    const request$ = id
      ? this.storeAreaService.updateArea(
          chain.companyId,
          chain.companyCountryId,
          chain.regionId,
          chain.zoneId,
          chain.storeId,
          id,
          request,
        )
      : this.storeAreaService.createArea(
          chain.companyId,
          chain.companyCountryId,
          chain.regionId,
          chain.zoneId,
          chain.storeId,
          request,
        );

    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) =>
        this.router.navigate(['/companies/store-areas'], {
          queryParams: this.toQueryParams(saved),
        }),
      error: (err: HttpErrorResponse) => this.handleError(err),
    });
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

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
      return;
    }
    this.serverErrors.set({});
    this.generalError.set(apiError?.message ?? 'Error inesperado. Intente de nuevo más tarde.');
  }
}
