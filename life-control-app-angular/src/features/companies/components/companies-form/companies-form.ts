import {
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import {
  FormGroup,
  ReactiveFormsModule,
} from '@angular/forms';
import { Company, CompanyControl } from '../../models/company.models';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';
import { FormErrorComponent } from '@shared/ui';

@Component({
  selector: 'app-companies-form',
  standalone: true,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatSelectModule, MatSlideToggleModule, MatIconModule, FormErrorComponent],
  templateUrl: './companies-form.html',
  styleUrl: './companies-form.scss',
})
export class CompaniesForm {
  formGroup = input.required<FormGroup<CompanyControl>>();
  saveCompany = output<Company>();
  cancelForm = output<void>();

  readonly personaTypes = [
    { value: 1, label: 'Persona Física' },
    { value: 2, label: 'Persona Moral' },
  ];

  protected readonly rfcErrorMessages: Record<string, (error: any) => string> = {
    pattern: () => 'El RFC debe tener 12-13 caracteres alfanuméricos.',
  };

  protected readonly emailErrorMessages: Record<string, (error: any) => string> = {
    email: () => 'Ingrese un correo electrónico válido.',
  };

  protected readonly phoneErrorMessages: Record<string, (error: any) => string> = {
    pattern: () => 'Ingrese un número de teléfono válido.',
  };

  readonly isEditMode = computed(() => !!this.formGroup()?.controls.id.value);

  onSave(): void {
    if (this.formGroup().valid) {
      const raw = this.formGroup().getRawValue();
      const companyData: Company = {
        ...raw,
        companyId: raw.companyId ?? 0,
        createdAt: '',
        updatedAt: '',
      };
      this.saveCompany.emit(companyData);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
