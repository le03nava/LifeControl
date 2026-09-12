import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal, OnInit } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { PageHeader } from '@shared/ui';
import { map } from 'rxjs/operators';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../data/company-country.service';
import { CountriesCard } from '../../components/countries-card/countries-card';

@Component({
  selector: 'app-countries-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    PageHeader,
    CountriesCard,
  ],
  templateUrl: './countries-page.html',
  styleUrl: './countries-page.scss',
})
export class CountriesPage implements OnInit {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly companyService = inject(CompanyService);
  companyCountryService = inject(CompanyCountryService);

  companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  selectedCompanyId = signal<string | null>(null);

  // ─── Lifecycle ───────────────────────────────────────────────

  ngOnInit(): void {
    const companyId = this.route.snapshot.queryParamMap.get('companyId');
    if (companyId) {
      this.selectedCompanyId.set(companyId);
      this.companyCountryService.getCountries(companyId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe();
    }
  }

  // ─── Event handlers ──────────────────────────────────────────

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    if (companyId) {
      this.companyCountryService.getCountries(companyId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe();
    }
  }

  onCreateCountry(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.router.navigate(['/companies/countries/create'], {
      queryParams: { companyId },
    });
  }

  /** Bridge: the card emits a country ID; look up the full CompanyCountry and delegate. */
  onCardEditCountry(ccId: string): void {
    const cc = this.companyCountryService
      .assignedCountries()
      .find((c) => c.id === ccId);
    if (cc) {
      this.router.navigate(['/companies/countries/edit', cc.id], {
        state: { cc },
      });
    }
  }

  onDeleteCountry(ccId: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.companyCountryService
      .removeCountry(companyId, ccId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe();
  }
}
