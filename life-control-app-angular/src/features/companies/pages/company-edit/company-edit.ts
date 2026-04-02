import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CompanyService } from '@features/companies/data/company.service';
import { Company, CompanyControl } from '@features/companies/models/company.models';
import {
  NonNullableFormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
  AbstractControl,
  ValidationErrors,
} from '@angular/forms';
import { CompaniesForm } from '@features/companies/components/companies-form/companies-form';

// Custom validator for Mexican phone format (+52XXXXXXXXXX or 10 digits)
function phoneValidator(control: AbstractControl): ValidationErrors | null {
  if (!control.value) {
    return null; // Optional field
  }
  const phone = control.value;
  // Allow: +52 followed by 10 digits, or exactly 10 digits, or with dashes/spaces
  const phonePattern = /^(\+52)?[\d\s\-]{10,13}$/;
  const digitsOnly = phone.replace(/\D/g, '');
  
  if (digitsOnly.length !== 10 && digitsOnly.length !== 12) {
    return { phone: 'El teléfono debe tener 10 dígitos o formato +52' };
  }
  return null;
}

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
  private fb = inject(NonNullableFormBuilder);
  private router = inject(Router);

  companyId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  companyForm = signal<FormGroup<CompanyControl>>(this.createForm());

  isEditMode = signal(false);

  ngOnInit(): void {
    const id = this.companyId();
    if (id) {
      this.isEditMode.set(true);
      this.loadCompany(id);
    }
  }

  private loadCompany(id: string): void {
    this.companyService.getCompanyById(id).subscribe({
      next: (company) => {
        this.companyForm.set(
          this.fb.group({
            id: this.fb.control(company.id),
            companyKey: this.fb.control(company.companyKey),
            companyName: this.fb.control(company.companyName, Validators.required),
            tipoPersonaId: this.fb.control(company.tipoPersonaId),
            razonSocial: this.fb.control(company.razonSocial, Validators.required),
            rfc: this.fb.control(company.rfc, [Validators.required, Validators.minLength(12), Validators.maxLength(13)]),
            email: this.fb.control(company.email, [Validators.required, Validators.email]),
            phone: this.fb.control(company.phone, phoneValidator),
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
      companyKey: this.fb.control(''),
      companyName: this.fb.control('', Validators.required),
      tipoPersonaId: this.fb.control(1),
      razonSocial: this.fb.control('', Validators.required),
      rfc: this.fb.control('', [Validators.required, Validators.minLength(12), Validators.maxLength(13)]),
      email: this.fb.control('', [Validators.required, Validators.email]),
      phone: this.fb.control('', phoneValidator),
      address: this.fb.control(''),
      enabled: this.fb.control(true),
    });
  }

  onSaveCompany(companyData: Company): void {
    if (companyData.id === '') {
      // Create new company
      const { id, ...createData } = companyData;
      this.companyService.createCompany(createData as Company).subscribe({
        next: () => {
          this.router.navigate(['/companies']);
        },
      });
    } else {
      // Update existing company
      this.companyService.updateCompany(companyData).subscribe({
        next: () => {
          this.router.navigate(['/companies']);
        },
      });
    }
  }

  cancelForm(): void {
    this.router.navigate(['/companies']);
  }
}
