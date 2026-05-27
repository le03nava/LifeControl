import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyContextService } from '@shared/data/company-context.service';
import { ApiError } from '@shared/models';
import { Company, CompanyControl } from '@features/companies/companies/models/company.models';
import { CompanyCountryService } from '@features/companies/countries/data';
import {
  NonNullableFormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { CompaniesForm } from '@features/companies/companies/components/companies-form/companies-form';


@Component({
  selector: 'app-company-edit',
  imports: [ReactiveFormsModule, CompaniesForm],
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
  companyCountryService = inject(CompanyCountryService);

  companyId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  companyForm = signal<FormGroup<CompanyControl>>(this.createForm());

  isEditMode = signal(false);
  serverErrors = signal<Record<string, string>>({});
  generalError = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.companyId();
    if (id) {
      this.isEditMode.set(true);
      this.loadCompany(id);
    } else {
      const current = this.companyContextService.currentCompany();
      if (current) {
        this.companyForm().controls.companyKey.setValue(current.companyKey);
      }
    }
  }

  private loadCompany(id: string): void {
    this.companyService.getCompanyById(id).subscribe({
      next: (company) => {
        this.companyForm.set(
          this.fb.group({
            id: this.fb.control(company.id),
            companyKey: this.fb.control(company.companyKey, Validators.required),
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

  private createForm(): FormGroup<CompanyControl> {
    return this.fb.group({
      id: this.fb.control(''),
      companyKey: this.fb.control('', Validators.required),
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
        error: (err: HttpErrorResponse) => {
          this.handleServerError(err);
        },
      });
    } else {
      this.companyService.updateCompany(companyData.id, companyData).subscribe({
        next: () => {
          this.router.navigate(['/companies']);
        },
        error: (err: HttpErrorResponse) => {
          this.handleServerError(err);
        },
      });
    }
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
    } else if (apiError?.message) {
      this.serverErrors.set({});
      this.generalError.set(apiError.message);
    } else {
      this.serverErrors.set({});
      this.generalError.set('Error inesperado. Intente de nuevo más tarde.');
    }
  }

  cancelForm(): void {
    this.router.navigate(['/companies']);
  }

}
