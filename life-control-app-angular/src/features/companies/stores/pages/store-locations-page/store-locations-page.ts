import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { PageHeader, ConfirmDialog } from '@shared/ui';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { StoreLocationService } from '../../data/store-location.service';
import { StoreZoneService } from '../../data/store-zone.service';
import { StoreLocation } from '../../models/store-location.models';
import { StoreZone } from '../../models/store-zone.models';
import { CascadeStep, CascadeStepHandle, StoreAreaLevelCascadePage } from '../store-cascade-page';

@Component({
  selector: 'app-store-locations-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Page-scoped so the service's `error` signal cannot leak in from another route (2d).
  providers: [StoreLocationService],
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
export class StoreLocationsPage extends StoreAreaLevelCascadePage {
  private readonly storeLocationService = inject(StoreLocationService);
  /** Cascade level (not page-scoped: its `error` signal is not read here). */
  private readonly storeZoneService = inject(StoreZoneService);
  private readonly actionErrorFallback = 'No se pudo actualizar la ubicación de la tienda.';

  readonly selectedStoreZone = signal<StoreZone | null>(null);

  /**
   * Filter level, not the final list: always requests disabled store zones too so a
   * pre-selected disabled store zone can resolve. Unlike the mirror page — where this same
   * service method is the final list and respects the toggle — the `includeDisabled` argument
   * is hardcoded here and the params deliberately exclude `showDisabled` and `reload`. Only
   * `storeLocationsResource` below respects the "mostrar deshabilitadas" toggle.
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

  readonly storeZones = computed(() =>
    this.storeZonesResource.hasValue() ? this.storeZonesResource.value() : [],
  );

  readonly storeZonesError = computed(() =>
    this.cascadeMessage(
      this.storeZonesResource.error(),
      'No se pudieron cargar las zonas de tienda.',
    ),
  );

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

  readonly storeLocations = computed(() =>
    this.storeLocationsResource.hasValue() ? this.storeLocationsResource.value() : [],
  );

  /** Friendly error message owned by the service (set on load failure). */
  readonly storeLocationsError = computed(() => this.storeLocationService.error());

  private readonly storeZoneStep = new CascadeStep(
    'storeZoneId',
    this.selectedStoreZone,
    this.storeZones,
  );

  protected override cascadeSteps(): readonly CascadeStepHandle[] {
    return [...super.cascadeSteps(), this.storeZoneStep];
  }

  onSelectStoreZone(storeZone: StoreZone): void {
    this.selectedStoreZone.set(storeZone);
    this.resetAbove(this.storeZoneStep);
  }

  // ─── Write handlers (leaf concern) ───────────────────────────

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
              error: (err: HttpErrorResponse) => this.setActionError(err, this.actionErrorFallback),
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
        error: (err: HttpErrorResponse) => this.setActionError(err, this.actionErrorFallback),
      });
  }
}
