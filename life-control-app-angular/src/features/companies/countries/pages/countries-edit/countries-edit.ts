import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../data/company-country.service';
import { CountryService } from '@features/countries/data/country.service';
import { CountriesForm } from '../../components/countries-form/countries-form';
import { CompanyCountry, CountrySaveEvent } from '../../models/country.models';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '@shared/models';
import { map } from 'rxjs/operators';
import { toSignal } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-countries-edit',
  standalone: true,
  imports: [CountriesForm],
  templateUrl: './countries-edit.html',
  styleUrl: './countries-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CountriesEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private companyService = inject(CompanyService);
  private companyCountryService = inject(CompanyCountryService);
  private countryService = inject(CountryService);

  // ─── Route data ────────────────────────────────────────
  countryId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  isEditMode = signal(false);

  // ─── Data signals ──────────────────────────────────────
  companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map(p => p.content)),
    { initialValue: [] },
  );
  companyCountries = this.companyCountryService.assignedCountries;
  catalogCountries = this.countryService.countries;
  serverErrors = signal<Record<string, string>>({});

  // ─── Edit mode data ────────────────────────────────────
  ccToEdit = signal<CompanyCountry | null>(null);

  // ─── Create mode initial values (from query params) ───
  initialCompanyId = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.countryId();
    if (id) {
      this.isEditMode.set(true);
      // Leer el CompanyCountry del state desde history.state (vía globalThis para
      // ser SSR-safe — en servidor globalThis.history es undefined).
      // NO usar router.getCurrentNavigation() — devuelve null cuando
      // ngOnInit se ejecuta después de que la navegación ya se completó.
      const ccFromState = (globalThis.history?.state as { cc?: CompanyCountry })?.cc;
      if (ccFromState) {
        this.ccToEdit.set(ccFromState);
      } else {
        // Fallback: redirect to countries list if no state
        this.router.navigate(['/companies/countries']);
      }
    }

    // ─── Create mode: pre-select company from query params ──
    const companyId = this.route.snapshot.queryParamMap.get('companyId');

    if (!id && companyId) {
      this.initialCompanyId.set(companyId);
      this.companyCountryService.getCountries(companyId).subscribe();
    }
  }

  onSaveCountry(event: CountrySaveEvent): void {
    if (this.isEditMode()) {
      const ccId = event.countryId;
      if (!ccId) return;
      this.companyCountryService
        .updateCountry(event.companyId, ccId, event.request)
        .subscribe({
          next: () =>
            this.router.navigate(['/companies/countries'], {
              queryParams: { companyId: event.companyId },
            }),
          error: (err: HttpErrorResponse) => this.handleError(err),
        });
    } else {
      this.companyCountryService
        .addCountry(event.companyId, event.request)
        .subscribe({
          next: () =>
            this.router.navigate(['/companies/countries'], {
              queryParams: { companyId: event.companyId },
            }),
          error: (err: HttpErrorResponse) => this.handleError(err),
        });
    }
  }

  onCancelForm(): void {
    const cc = this.ccToEdit();
    const qp: Record<string, string> = {};

    if (cc) {
      // Edit mode — sacar companyId del cc en state
      qp['companyId'] = cc.companyId;
    } else {
      // Create mode — preservar los valores originales de query params
      const companyId = this.initialCompanyId();
      if (companyId) qp['companyId'] = companyId;
    }

    if (Object.keys(qp).length > 0) {
      this.router.navigate(['/companies/countries'], { queryParams: qp });
    } else {
      this.router.navigate(['/companies/countries']);
    }
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors) {
      this.serverErrors.set(apiError.errors);
    }
  }
}
