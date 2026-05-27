import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../data/company-region.service';
import { RegionsForm } from '../../components/regions-form/regions-form';
import { CompanyRegion, CompanyRegionRequest, RegionSaveEvent } from '../../models/region.models';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '@shared/models';
import { map } from 'rxjs/operators';
import { toSignal } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-regions-edit',
  standalone: true,
  imports: [RegionsForm],
  templateUrl: './regions-edit.html',
  styleUrl: './regions-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegionsEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private companyService = inject(CompanyService);
  private companyCountryService = inject(CompanyCountryService);
  private companyRegionService = inject(CompanyRegionService);

  // ─── Route data ────────────────────────────────────────
  regionId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  isEditMode = signal(false);

  // ─── Data signals ──────────────────────────────────────
  companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map(p => p.content)),
    { initialValue: [] },
  );
  companyCountries = this.companyCountryService.assignedCountries;
  serverErrors = signal<Record<string, string>>({});

  // ─── Edit mode data ────────────────────────────────────
  regionToEdit = signal<CompanyRegion | null>(null);

  ngOnInit(): void {
    const id = this.regionId();
    if (id) {
      this.isEditMode.set(true);
      // Try router state first
      const navigation = this.router.getCurrentNavigation();
      const state = navigation?.extras.state as { region?: CompanyRegion } | null;
      if (state?.region) {
        this.regionToEdit.set(state.region);
      } else {
        // Fallback: redirect to regions list if no state
        this.router.navigate(['/companies/regions']);
      }
    }
  }

  onSelectedCompanyChange(companyId: string): void {
    if (companyId) {
      this.companyCountryService.getCountries(companyId).subscribe();
    }
  }

  onSelectedCountryChange(_cc: any): void {
    // country selected — no need to load regions here
  }

  onSaveRegion(event: RegionSaveEvent): void {
    if (this.isEditMode()) {
      const regionId = this.regionId();
      if (!regionId) return;
      this.companyRegionService
        .updateRegion(event.companyId, event.countryId, regionId, event.request)
        .subscribe({
          next: () => this.router.navigate(['/companies/regions']),
          error: (err: HttpErrorResponse) => this.handleError(err),
        });
    } else {
      this.companyRegionService
        .addRegion(event.companyId, event.countryId, event.request)
        .subscribe({
          next: () => this.router.navigate(['/companies/regions']),
          error: (err: HttpErrorResponse) => this.handleError(err),
        });
    }
  }

  onCancelForm(): void {
    this.router.navigate(['/companies/regions']);
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors) {
      this.serverErrors.set(apiError.errors);
    }
  }
}
