import { inject, Injectable, signal } from '@angular/core';
import { CompanyService } from '@features/companies/data/company.service';
import { Company, Page } from '@features/companies/models/company.models';

@Injectable({ providedIn: 'root' })
export class CompanyContextService {
  private companyService = inject(CompanyService);

  private _companies = signal<Company[]>([]);
  private _currentCompany = signal<Company | null>(null);
  private _loading = signal(false);

  readonly companies = this._companies.asReadonly();
  readonly currentCompany = this._currentCompany.asReadonly();
  readonly loading = this._loading.asReadonly();

  loadCompanies(): void {
    this._loading.set(true);
    this.companyService.getCompanies(0, 1000).subscribe({
      next: (page: Page<Company>) => {
        this._companies.set(page.content);
        this._loading.set(false);

        // Auto-select first company if none selected
        if (page.content.length > 0 && !this._currentCompany()) {
          this._currentCompany.set(page.content[0]);
        }
      },
      error: () => {
        this._loading.set(false);
      },
    });
  }

  setCurrentCompany(company: Company): void {
    this._currentCompany.set(company);
  }
}
