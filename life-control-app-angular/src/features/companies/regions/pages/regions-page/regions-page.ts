import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatChipsModule } from '@angular/material/chips';
import { map } from 'rxjs/operators';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../data/company-region.service';
import { CompanyCountry } from '../../../countries/models/country.models';
import { RegionManager } from '../../components/region-manager/region-manager';
import { CompanyRegion } from '../../models/region.models';

@Component({
  selector: 'app-regions-page',
  standalone: true,
  imports: [MatSelectModule, MatFormFieldModule, MatChipsModule, RegionManager],
  templateUrl: './regions-page.html',
  styleUrl: './regions-page.scss',
})
export class RegionsPage {
  private router = inject(Router);
  private companyService = inject(CompanyService);
  companyCountryService = inject(CompanyCountryService);
  companyRegionService = inject(CompanyRegionService);

  companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  selectedCompanyId = signal<string | null>(null);
  selectedCountry = signal<CompanyCountry | null>(null);

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.selectedCountry.set(null);
    if (companyId) {
      this.companyCountryService.getCountries(companyId).subscribe();
    }
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
    this.companyRegionService
      .getRegions(cc.companyId, cc.id)
      .subscribe();
  }

  onCreateRegion(): void {
    const cc = this.selectedCountry();
    if (!cc) return;
    this.router.navigate(['/companies/regions/create'], {
      queryParams: { companyId: cc.companyId, countryId: cc.id },
    });
  }

  onEditRegion(region: CompanyRegion): void {
    this.router.navigate(['/companies/regions/edit', region.id], {
      state: { region },
    });
  }

  onRemoveRegion(regionId: string): void {
    const cc = this.selectedCountry();
    if (!cc) return;
    this.companyRegionService.removeRegion(cc.companyId, cc.id, regionId).subscribe();
  }

  onEnableRegion(regionId: string): void {
    const cc = this.selectedCountry();
    if (!cc) return;
    this.companyRegionService.enableRegion(cc.companyId, cc.id, regionId).subscribe();
  }
}
