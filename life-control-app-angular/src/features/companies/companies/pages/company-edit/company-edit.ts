import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, signal, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyContextService } from '@shared/data/company-context.service';
import { ApiError } from '@shared/models';
import { Company, CompanyControl } from '@features/companies/companies/models/company.models';
import { CountryService } from '@features/countries/data/country.service';
import { Country } from '@features/companies/countries/models/country.models';
import type { AddressControl } from '@shared/models/address.models';
import {
  NonNullableFormBuilder,
  FormGroup,
  FormControl,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { CompaniesForm } from '@features/companies/companies/components/companies-form/companies-form';
import { ErrorBanner } from '@shared/ui';


@Component({
  selector: 'app-company-edit',
  imports: [ReactiveFormsModule, ErrorBanner, CompaniesForm],
  templateUrl: './company-edit.html',
  styleUrl: './company-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyEdit implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly companyService = inject(CompanyService);
  private readonly companyContextService = inject(CompanyContextService);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly router = inject(Router);
  private readonly countryService = inject(CountryService);
  private readonly destroyRef = inject(DestroyRef);

  companyId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  companyForm = signal<FormGroup<CompanyControl>>(this.createForm());

  isEditMode = signal(false);
  serverErrors = signal<Record<string, string>>({});
  addressServerErrors = signal<Record<string, string>>({});
  generalError = signal<string | null>(null);

  // Country catalog for address country selector (error → empty, matching previous behavior)
  countries = toSignal(
    this.countryService.getCountries().pipe(catchError(() => of([] as Country[]))),
    { initialValue: [] },
  );

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
    this.companyService.getCompanyById(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
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
            address: new FormGroup<AddressControl>({
              street: new FormControl<string | null>(company.address?.street ?? null, { nonNullable: false }),
              streetNumber: new FormControl<string | null>(company.address?.streetNumber ?? null, { nonNullable: false }),
              internalNumber: new FormControl<string | null>(company.address?.internalNumber ?? null, { nonNullable: false }),
              neighborhood: new FormControl<string | null>(company.address?.neighborhood ?? null, { nonNullable: false }),
              zipCode: new FormControl<string | null>(company.address?.zipCode ?? null, { nonNullable: false }),
              city: new FormControl<string | null>(company.address?.city ?? null, { nonNullable: false }),
              state: new FormControl<string | null>(company.address?.state ?? null, { nonNullable: false }),
              countryId: new FormControl<string | null>(company.address?.countryId ?? null, { nonNullable: false }),
            }),
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
      address: new FormGroup<AddressControl>({
        street: new FormControl<string | null>(null, { nonNullable: false }),
        streetNumber: new FormControl<string | null>(null, { nonNullable: false }),
        internalNumber: new FormControl<string | null>(null, { nonNullable: false }),
        neighborhood: new FormControl<string | null>(null, { nonNullable: false }),
        zipCode: new FormControl<string | null>(null, { nonNullable: false }),
        city: new FormControl<string | null>(null, { nonNullable: false }),
        state: new FormControl<string | null>(null, { nonNullable: false }),
        countryId: new FormControl<string | null>(null, { nonNullable: false }),
      }),
      enabled: this.fb.control(true),
    });
  }

  onSaveCompany(companyData: Company): void {
    if (companyData.id === '') {
      const { id: _id, address, ...rest } = companyData;
      const hasAddress = address && (
        address.street || address.streetNumber ||
        address.internalNumber || address.neighborhood ||
        address.zipCode || address.city ||
        address.state || address.countryId
      );
      const payload = {
        ...rest,
        ...(hasAddress ? { address } : {}),
      };
      this.companyService.createCompany(payload as unknown as Company).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (createdCompany) => {
          this.router.navigate(['/companies/edit', createdCompany.id]);
        },
        error: (err: HttpErrorResponse) => {
          this.handleServerError(err);
        },
      });
    } else {
      const { id: _id, address, ...rest } = companyData;
      const hasAddress = address && (
        address.street || address.streetNumber ||
        address.internalNumber || address.neighborhood ||
        address.zipCode || address.city ||
        address.state || address.countryId
      );
      const payload = {
        ...rest,
        ...(hasAddress ? { address } : {}),
      };
      this.companyService.updateCompany(companyData.id, payload as unknown as Company).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
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

  constructor() {
    // Server error mapping: extract address.* keys and strip prefix
    effect(() => {
      const allErrors = this.serverErrors();
      const addrErrors: Record<string, string> = {};
      Object.entries(allErrors).forEach(([key, value]) => {
        if (key.startsWith('address.')) {
          addrErrors[key.slice(8)] = value;
        }
      });
      this.addressServerErrors.set(addrErrors);
    });
  }

  cancelForm(): void {
    this.router.navigate(['/companies']);
  }

}
