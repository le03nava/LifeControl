import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
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
import { StoreZoneService } from '../../data/store-zone.service';
import { StoreZone } from '../../models/store-zone.models';
import { StoreAreaLevelCascadePage } from '../store-cascade-page';

@Component({
  selector: 'app-store-zones-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Page-scoped so the service's `error` signal cannot leak in from another route (2d).
  providers: [StoreZoneService],
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
export class StoreZonesPage extends StoreAreaLevelCascadePage {
  private readonly storeZoneService = inject(StoreZoneService);
  private readonly actionErrorFallback = 'No se pudo actualizar la zona de la tienda.';

  /** Final list of the cascade: the only level that respects the toggle. */
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

  readonly storeZones = computed(() =>
    this.storeZonesResource.hasValue() ? this.storeZonesResource.value() : [],
  );

  /** Friendly error message owned by the service (set on load failure). */
  readonly storeZonesError = computed(() => this.storeZoneService.error());

  // ─── Write handlers (leaf concern) ───────────────────────────

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
              error: (err: HttpErrorResponse) => this.setActionError(err, this.actionErrorFallback),
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
        error: (err: HttpErrorResponse) => this.setActionError(err, this.actionErrorFallback),
      });
  }
}
