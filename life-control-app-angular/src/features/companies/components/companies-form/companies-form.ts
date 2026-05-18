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
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

@Component({
  selector: 'app-companies-form',
  standalone: true,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './companies-form.html',
  styleUrl: './companies-form.scss',
})
export class CompaniesForm {
  formGroup = input.required<FormGroup<CompanyControl>>();
  companyId = input<number | null>(null);
  saveCompany = output<Company>();
  cancelForm = output<void>();

  isEditMode = signal(false);

  constructor() {
    effect(() => {
      const form = this.formGroup();
      if (form.get('id')?.value) {
        this.isEditMode.set(true);
      }
    });

    effect(() => {
      const id = this.companyId();
      const form = this.formGroup();
      if (id !== null && form) {
        form.controls.companyId.setValue(id);
      }
    });
  }

  onSave(): void {
    if (this.formGroup().valid) {
      const formData = this.formGroup();

      const companyData: Company = {
        id: formData.get('id')?.value || '',
        companyId: formData.get('companyId')?.value ?? 0,
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
