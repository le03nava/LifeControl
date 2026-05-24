import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatSelectModule } from '@angular/material/select';
import { MatOptionModule } from '@angular/material/core';
import { CompanyContextService } from '@shared/data/company-context.service';

@Component({
  selector: 'app-company-selector',
  standalone: true,
  imports: [CommonModule, MatSelectModule, MatOptionModule],
  templateUrl: './company-selector.html',
  styleUrl: './company-selector.scss',
})
export class CompanySelector {
  private companyContext = inject(CompanyContextService);

  readonly companies = this.companyContext.companies;
  readonly loading = this.companyContext.loading;
  readonly currentCompany = this.companyContext.currentCompany;

  onCompanyChange(companyKey: string): void {
    const company = this.companies().find(c => c.companyKey === companyKey);
    if (company) {
      this.companyContext.setCurrentCompany(company);
    }
  }
}
