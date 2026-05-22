import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CompanyService } from '@features/companies/data/company.service';
import { CompanyContextService } from '@shared/data/company-context.service';
import { Company, CompanyControl, CompanyCountryRequest } from '@features/companies/models/company.models';
import { CountryService } from '@features/countries/data';
import { CompanyCountryService } from '@features/companies/data/company-country.service';
import {
  NonNullableFormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { CompaniesForm } from '@features/companies/components/companies-form/companies-form';
import { CountrySelector } from '@features/companies/components';

@Component({
  selector: 'app-company-edit',
  imports: [ReactiveFormsModule, CompaniesForm, CountrySelector],
  templateUrl: './company-edit.html',
  styleUrl: './company-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private companyService = inject(CompanyService);
  private companyContextService = inject(CompanyContextService);
  private fb = inject(NonNullableFormBuilder);
  private router = inject(Router);
  countryService = inject(CountryService);
  companyCountryService = inject(CompanyCountryService);

  companyId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  companyForm = signal<FormGroup<CompanyControl>>(this.createForm());

  isEditMode = signal(false);

  ngOnInit(): void {
    const id = this.companyId();
    if (id) {
      this.isEditMode.set(true);
      this.loadCompany(id);
    } else {
      const current = this.companyContextService.currentCompany();
      if (current) {
        this.companyForm().controls.companyId.setValue(current.companyId);
      }
    }
    this.loadCountries();
  }

  private loadCompany(id: string): void {
    this.companyService.getCompanyById(id).subscribe({
      next: (company) => {
        this.companyForm.set(
          this.fb.group({
            id: this.fb.control(company.id),
            companyId: this.fb.control<number | null>(company.companyId, Validators.required),
            companyName: this.fb.control(company.companyName, Validators.required),
            tipoPersonaId: this.fb.control(company.tipoPersonaId, Validators.required),
            razonSocial: this.fb.control(company.razonSocial, Validators.required),
            rfc: this.fb.control(company.rfc, [Validators.required, Validators.pattern(/^[A-Za-z0-9]{12,13}$/)]),
            email: this.fb.control(company.email, Validators.email),
            phone: this.fb.control(company.phone, Validators.pattern(/^(\+52\d{10}|\d{10})$/)),
            address: this.fb.control(company.address || ''),
            enabled: this.fb.control(company.enabled),
          })
        );
      },
      error: (err) => {
        console.error('[CompanyEdit] Error loading company:', err);
      },
    });
  }

  private loadCountries(): void {
    this.countryService.getCountries().subscribe();
    const id = this.companyId();
    if (id) {
      this.companyCountryService.getCountries(id).subscribe();
    }
  }

  private createForm(): FormGroup<CompanyControl> {
    return this.fb.group({
      id: this.fb.control(''),
      companyId: this.fb.control<number | null>(null, Validators.required),
      companyName: this.fb.control('', Validators.required),
      tipoPersonaId: this.fb.control(1, Validators.required),
      razonSocial: this.fb.control('', Validators.required),
      rfc: this.fb.control('', [Validators.required, Validators.pattern(/^[A-Za-z0-9]{12,13}$/)]),
      email: this.fb.control('', Validators.email),
      phone: this.fb.control('', Validators.pattern(/^(\+52\d{10}|\d{10})$/)),
      address: this.fb.control(''),
      enabled: this.fb.control(true),
    });
  }

  onSaveCompany(companyData: Company): void {
    if (companyData.id === '') {
      const { id, ...createData } = companyData;
      this.companyService.createCompany(createData as Company).subscribe({
        next: (createdCompany) => {
          this.router.navigate(['/companies/edit', createdCompany.id]);
        },
      });
    } else {
      this.companyService.updateCompany(companyData.id, companyData).subscribe({
        next: () => {
          this.router.navigate(['/companies']);
        },
      });
    }
  }

  cancelForm(): void {
    this.router.navigate(['/companies']);
  }

  onAddCountry(request: CompanyCountryRequest): void {
    const companyId = this.companyId();
    if (!companyId) return;
    this.companyCountryService.addCountry(companyId, request).subscribe();
  }

  onRemoveCountry(companyCountryId: string): void {
    const companyId = this.companyId();
    if (!companyId) return;
    this.companyCountryService.removeCountry(companyId, companyCountryId).subscribe();
  }
}
