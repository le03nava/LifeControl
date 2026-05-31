import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../data/company-zone.service';
import { ZonesForm } from '../../components/zones-form/zones-form';
import { CompanyZone, CompanyZoneRequest, ZoneSaveEvent } from '../../models/zone.models';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '@shared/models';
import { map } from 'rxjs/operators';
import { toSignal } from '@angular/core/rxjs-interop';
import { CompanyRegion } from '../../../regions/models/region.models';

@Component({
  selector: 'app-zones-edit',
  standalone: true,
  imports: [ZonesForm],
  templateUrl: './zones-edit.html',
  styleUrl: './zones-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ZonesEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private companyService = inject(CompanyService);
  private companyCountryService = inject(CompanyCountryService);
  private companyRegionService = inject(CompanyRegionService);
  private companyZoneService = inject(CompanyZoneService);

  // ─── Route data ────────────────────────────────────────
  zoneId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
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
  zoneToEdit = signal<CompanyZone | null>(null);

  // ─── Create mode initial values (from query params) ───
  initialCompanyId = signal<string | null>(null);
  initialCountryId = signal<string | null>(null);
  initialRegionId = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.zoneId();
    if (id) {
      this.isEditMode.set(true);
      // Leer la zone del state desde history.state (SSR-safe)
      const zoneFromState = (globalThis.history?.state as { zone?: CompanyZone })?.zone;
      if (zoneFromState) {
        this.zoneToEdit.set(zoneFromState);
        // Load regions for the zone's country
        this.companyRegionService.getRegions(
          zoneFromState.companyId,
          zoneFromState.companyCountryId,
        ).subscribe(regions => this.regionList.set(regions));
      } else {
        // Fallback: redirect to zones list if no state
        this.router.navigate(['/companies/zones']);
      }
    }

    // ─── Create mode: pre-select company/country/region from query params ──
    const companyId = this.route.snapshot.queryParamMap.get('companyId');
    const countryId = this.route.snapshot.queryParamMap.get('countryId');
    const regionId = this.route.snapshot.queryParamMap.get('regionId');

    if (!id) {
      if (companyId) {
        this.initialCompanyId.set(companyId);
        this.initialCountryId.set(countryId);
        this.initialRegionId.set(regionId);
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

  onSaveZone(event: ZoneSaveEvent): void {
    if (this.isEditMode()) {
      const zoneId = this.zoneId();
      if (!zoneId) return;
      this.companyZoneService
        .updateZone(event.companyId, event.countryId, event.regionId, zoneId, event.request)
        .subscribe({
          next: () =>
            this.router.navigate(['/companies/zones'], {
              queryParams: { companyId: event.companyId, countryId: event.countryId, regionId: event.regionId },
            }),
          error: (err: HttpErrorResponse) => this.handleError(err),
        });
    } else {
      this.companyZoneService
        .addZone(event.companyId, event.countryId, event.regionId, event.request)
        .subscribe({
          next: () =>
            this.router.navigate(['/companies/zones'], {
              queryParams: { companyId: event.companyId, countryId: event.countryId, regionId: event.regionId },
            }),
          error: (err: HttpErrorResponse) => this.handleError(err),
        });
    }
  }

  onCancelForm(): void {
    const zone = this.zoneToEdit();
    const qp: Record<string, string> = {};

    if (zone) {
      // Edit mode — sacar companyId, countryId y regionId del zone en state
      qp['companyId'] = zone.companyId;
      qp['countryId'] = zone.companyCountryId;
      qp['regionId'] = zone.companyRegionId;
    } else {
      // Create mode — preservar los valores originales de query params
      const companyId = this.initialCompanyId();
      const countryId = this.initialCountryId();
      const regionId = this.initialRegionId();
      if (companyId) {
        qp['companyId'] = companyId;
        if (countryId) qp['countryId'] = countryId;
        if (regionId) qp['regionId'] = regionId;
      }
    }

    if (Object.keys(qp).length > 0) {
      this.router.navigate(['/companies/zones'], { queryParams: qp });
    } else {
      this.router.navigate(['/companies/zones']);
    }
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors) {
      this.serverErrors.set(apiError.errors);
    }
  }
}
