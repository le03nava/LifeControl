import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { map } from 'rxjs/operators';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../data/company-country.service';
import { CountryService } from '@features/countries/data';
import { CountrySelector } from '../../components/country-selector/country-selector';
import { CompanyCountryRequest } from '../../models/country.models';

@Component({
  selector: 'app-countries-page',
  standalone: true,
  imports: [MatSelectModule, MatFormFieldModule, CountrySelector],
  templateUrl: './countries-page.html',
  styleUrl: './countries-page.scss',
})
export class CountriesPage {
  private companyService = inject(CompanyService);
  companyCountryService = inject(CompanyCountryService);
  countryService = inject(CountryService);

  companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  selectedCompanyId = signal<string | null>(null);

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    if (companyId) {
      this.companyCountryService.getCountries(companyId).subscribe();
      this.countryService.getCountries().subscribe();
    }
  }

  onAddCountry(request: CompanyCountryRequest): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.companyCountryService.addCountry(companyId, request).subscribe();
  }

  onRemoveCountry(companyCountryId: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.companyCountryService.removeCountry(companyId, companyCountryId).subscribe();
  }
}
