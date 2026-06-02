import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { StoresForm } from '../../components/stores-form/stores-form';
import { CompanyStore, StoreSaveEvent } from '../../models/store.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '@shared/models';
import { map } from 'rxjs/operators';
import { toSignal } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-stores-edit',
  standalone: true,
  imports: [StoresForm],
  templateUrl: './stores-edit.html',
  styleUrl: './stores-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StoresEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private companyService = inject(CompanyService);
  private companyCountryService = inject(CompanyCountryService);
  private companyRegionService = inject(CompanyRegionService);
  private companyZoneService = inject(CompanyZoneService);
  private companyStoreService = inject(CompanyStoreService);

  // ─── Route data ────────────────────────────────────────
  storeId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  isEditMode = signal(false);

  // ─── Data signals ──────────────────────────────────────
  companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map(p => p.content)),
    { initialValue: [] },
  );
  companyCountries = this.companyCountryService.assignedCountries;

  regionList = signal<CompanyRegion[]>([]);

  serverErrors = signal<Record<string, string>>({});

  // ─── Edit mode data ────────────────────────────────────
  storeToEdit = signal<CompanyStore | null>(null);

  // ─── Create mode initial values (from query params) ───
  initialCompanyId = signal<string | null>(null);
  initialCountryId = signal<string | null>(null);
  initialRegionId = signal<string | null>(null);
  initialZoneId = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.storeId();
    if (id) {
      this.isEditMode.set(true);
      // Leer la store del state desde history.state (SSR-safe)
      const storeFromState = (globalThis.history?.state as { store?: CompanyStore })?.store;
      if (storeFromState) {
        this.storeToEdit.set(storeFromState);
        // Load regions for the store's country
        this.companyRegionService.getRegions(
          storeFromState.companyId,
          storeFromState.companyCountryId,
        ).subscribe(regions => this.regionList.set(regions));
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
        this.companyCountryService.getCountries(companyId).subscribe();
        if (countryId) {
          this.companyRegionService.getRegions(companyId, countryId)
            .subscribe(regions => this.regionList.set(regions));
        }
      }
    }
  }

  onSelectedCompanyChange(companyId: string): void {
    if (companyId) {
      this.companyCountryService.getCountries(companyId).subscribe();
    }
  }

  onSelectedCountryChange(cc: any): void {
    if (cc?.companyId && cc?.id) {
      this.companyRegionService.getRegions(cc.companyId, cc.id)
        .subscribe(regions => this.regionList.set(regions));
    }
  }

  onSelectedRegionChange(_region: any): void {
    // region selected — no need to load zones here
  }

  onSaveStore(event: StoreSaveEvent): void {
    if (this.isEditMode()) {
      const storeId = this.storeId();
      if (!storeId) return;
      this.companyStoreService.updateStore(
        event.companyId, event.countryId, event.regionId, event.zoneId, storeId, event.request,
      ).subscribe({
        next: () =>
          this.router.navigate(['/companies/stores'], {
            queryParams: { companyId: event.companyId, countryId: event.countryId, regionId: event.regionId, zoneId: event.zoneId },
          }),
        error: (err: HttpErrorResponse) => this.handleError(err),
      });
    } else {
      this.companyStoreService.addStore(
        event.companyId, event.countryId, event.regionId, event.zoneId, event.request,
      ).subscribe({
        next: () =>
          this.router.navigate(['/companies/stores'], {
            queryParams: { companyId: event.companyId, countryId: event.countryId, regionId: event.regionId, zoneId: event.zoneId },
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

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors) {
      this.serverErrors.set(apiError.errors);
    }
  }
}
