import { inject, Injectable, signal, effect } from '@angular/core';
import { CompanyService } from '@features/companies/data/company.service';
import { Company } from '@features/companies/models/company.models';

@Injectable({ providedIn: 'root' })
export class CompanyContextService {
  private companyService = inject(CompanyService);

  private _companies = signal<Company[]>([]);
  private _currentCompany = signal<Company | null>(null);
  private _loading = signal(false);

  readonly companies = this._companies.asReadonly();
  readonly currentCompany = this._currentCompany.asReadonly();
  readonly loading = this._loading.asReadonly();

  constructor() {
    effect(() => {
      this._companies.set(this.companyService.companies());
    }, { allowSignalWrites: true });

    effect(() => {
      this._loading.set(this.companyService.loading());
    }, { allowSignalWrites: true });

    effect(() => {
      const companies = this._companies();
      const current = this._currentCompany();
      if (companies.length > 0 && !current) {
        this._currentCompany.set(companies[0]);
      }
    }, { allowSignalWrites: true });
  }

  loadCompanies(): void {
    this.companyService.getCompanies();
  }

  setCurrentCompany(company: Company): void {
    this._currentCompany.set(company);
  }
}
