import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
  signal,
} from '@angular/core';
import { Country, CompanyCountry, CompanyCountryRequest } from '../../models/company.models';

@Component({
  selector: 'app-country-selector',
  standalone: true,
  imports: [],
  templateUrl: './country-selector.html',
  styleUrl: './country-selector.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CountrySelector {
  countries = input.required<Country[]>();
  assignedCountries = input.required<CompanyCountry[]>();
  loading = input(false);
  errorMessage = input<string | null>(null);

  addCountry = output<CompanyCountryRequest>();
  removeCountry = output<string>();

  selectedCountryCode = signal('');
  localAlias = signal('');

  onAdd(): void {
    const code = this.selectedCountryCode();
    if (!code) return;
    this.addCountry.emit({
      countryCode: code,
      localAlias: this.localAlias() || undefined,
    });
    this.selectedCountryCode.set('');
    this.localAlias.set('');
  }

  onRemove(companyCountryId: string): void {
    this.removeCountry.emit(companyCountryId);
  }
}
