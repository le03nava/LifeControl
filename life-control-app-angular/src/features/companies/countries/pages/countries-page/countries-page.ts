import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
  OnInit,
} from '@angular/core';
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
import { LocationPreference } from '@features/user/profile/data/location-preference.service';

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
  private readonly locationPreference = inject(LocationPreference);

  companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /**
   * The company the route itself names, captured once. When the route names one it wins and the
   * location preference is not consulted at all: pairing the route's company with the preference's
   * country would preselect a country that company does not have.
   */
  private readonly routeCompanyId = this.route.snapshot.queryParamMap.get('companyId');

  selectedCompanyId = signal<string | null>(null);

  /** Guard so the location pre-selection runs exactly once. */
  private companyPreselected = false;

  constructor() {
    // Only a route that names nothing at all falls back to the preference.
    if (!this.routeCompanyId) {
      this.locationPreference.resolve();
    }

    // Pre-select the company from the location preference once its chain resolves: the chain arrives
    // after construction, so the route's own seed in `ngOnInit` is not enough.
    effect(() => {
      if (this.routeCompanyId || this.companyPreselected) return;
      const companyId = this.locationPreference.companyId();
      if (!companyId) return;
      this.companyPreselected = true;
      this.selectCompany(companyId);
    });
  }

  // ─── Lifecycle ───────────────────────────────────────────────

  ngOnInit(): void {
    if (this.routeCompanyId) {
      this.selectCompany(this.routeCompanyId);
    }
  }

  /**
   * Selects a company and issues the read the list hangs off. The read is not the template's job:
   * `CountriesCard` renders whatever `assignedCountries()` holds, so nothing loads it implicitly.
   */
  private selectCompany(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.companyCountryService
      .getCountries(companyId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe();
  }

  // ─── Event handlers ──────────────────────────────────────────

  onCompanyChange(companyId: string): void {
    if (companyId) {
      this.selectCompany(companyId);
    } else {
      // A cleared control is not a company: nothing is read and the list is left as it is.
      this.selectedCompanyId.set(companyId);
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
    const cc = this.companyCountryService.assignedCountries().find((c) => c.id === ccId);
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
