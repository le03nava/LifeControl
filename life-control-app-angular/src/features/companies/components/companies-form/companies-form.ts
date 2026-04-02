import {
  Component,
  inject,
  signal,
  input,
  output,
  effect,
} from '@angular/core';
import {
  FormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
  FormControl,
} from '@angular/forms';
import { Company, CompanyControl } from '../../models/company.models';
import { Field } from '@shared/ui';

@Component({
  selector: 'app-companies-form',
  standalone: true,
  imports: [ReactiveFormsModule, Field],
  templateUrl: './companies-form.html',
  styleUrl: './companies-form.scss',
})
export class CompaniesForm {
  formGroup = input.required<FormGroup<CompanyControl>>();
  saveCompany = output<Company>();
  cancelForm = output<void>();

  // Signal para detectar modo edición
  isEditMode = signal(false);

  constructor() {
    // Detectar modo edición cuando cambia el formGroup
    effect(() => {
      const form = this.formGroup();
      if (form.get('id')?.value) {
        this.isEditMode.set(true);
      }
    });
  }

  onSave(): void {
    if (this.formGroup().valid) {
      const formData = this.formGroup();

      const companyData: Company = {
        id: formData.get('id')?.value || '',
        companyKey: formData.get('companyKey')?.value || '',
        companyName: formData.get('companyName')?.value || '',
        tipoPersonaId: formData.get('tipoPersonaId')?.value || 1,
        razonSocial: formData.get('razonSocial')?.value || '',
        rfc: formData.get('rfc')?.value || '',
        email: formData.get('email')?.value || '',
        phone: formData.get('phone')?.value || '',
        address: formData.get('address')?.value || '',
        enabled: formData.get('enabled')?.value ?? true,
        createdAt: '',
        updatedAt: '',
      };
      this.saveCompany.emit(companyData);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }

  getControl(controlName: keyof CompanyControl): FormControl<string | number | boolean | null> {
    return this.formGroup().controls[controlName] as FormControl<any | null>;
  }
}
