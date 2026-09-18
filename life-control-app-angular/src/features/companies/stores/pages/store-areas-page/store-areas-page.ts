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
import { StoreAreaService } from '../../data/store-area.service';
import { StoreArea } from '../../models/store-area.models';
import { StoreCascadePage } from '../store-cascade-page';

@Component({
  selector: 'app-store-areas-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Page-scoped so the service's `error` signal cannot leak in from another route (2d).
  providers: [StoreAreaService],
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatSlideToggleModule,
    PageHeader,
  ],
  templateUrl: './store-areas-page.html',
  styleUrl: './store-areas-page.scss',
})
export class StoreAreasPage extends StoreCascadePage {
  private readonly storeAreaService = inject(StoreAreaService);
  private readonly actionErrorFallback = 'No se pudo actualizar el área de la tienda.';

  /** Final list of the cascade: the only level that respects the toggle. */
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
        showDisabled: this.showDisabled(),
        reload: this.reload(),
      };
    },
    stream: ({ params }) =>
      this.storeAreaService
        .getAreas(
          params.companyId,
          params.companyCountryId,
          params.regionId,
          params.zoneId,
          params.storeId,
          params.showDisabled,
        )
        .pipe(catchError(() => of([] as StoreArea[]))),
    defaultValue: [] as StoreArea[],
  });

  readonly areas = computed(() =>
    this.areasResource.hasValue() ? this.areasResource.value() : [],
  );

  /** Friendly error message owned by the service (set on load failure). */
  readonly areasError = computed(() => this.storeAreaService.error());

  // ─── Write handlers (leaf concern) ───────────────────────────

  onCreateArea(): void {
    const store = this.selectedStore();
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    const zone = this.selectedCompanyZone();
    if (!store || !cc || !region || !zone) return;
    this.router.navigate(['/companies/store-areas/create'], {
      queryParams: {
        companyId: cc.companyId,
        countryId: cc.id,
        regionId: region.id,
        zoneId: zone.id,
        storeId: store.id,
      },
    });
  }

  onEditArea(area: StoreArea): void {
    this.router.navigate(['/companies/store-areas/edit', area.id], {
      state: { area },
    });
  }

  onToggleArea(area: StoreArea): void {
    const store = this.selectedStore();
    if (!store) return;

    this.actionError.set(null);

    if (area.enabled) {
      const dialogRef = this.dialog.open(ConfirmDialog, {
        data: {
          title: 'Deshabilitar área',
          message: `¿Confirmás que querés deshabilitar el área "${area.areaName}"? La información se conserva y podés reactivarla más adelante.`,
          confirmLabel: 'Deshabilitar',
          destructive: true,
        },
      });
      dialogRef
        .afterClosed()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((confirmed: boolean) => {
          if (!confirmed) return;
          this.storeAreaService
            .removeArea(
              store.companyId,
              store.companyCountryId,
              store.regionId,
              store.zoneId,
              store.id,
              area.id,
            )
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
              next: () => this.reload.update((n) => n + 1),
              error: (err: HttpErrorResponse) => this.setActionError(err, this.actionErrorFallback),
            });
        });
      return;
    }

    this.storeAreaService
      .enableArea(
        store.companyId,
        store.companyCountryId,
        store.regionId,
        store.zoneId,
        store.id,
        area.id,
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.reload.update((n) => n + 1),
        error: (err: HttpErrorResponse) => this.setActionError(err, this.actionErrorFallback),
      });
  }
}
