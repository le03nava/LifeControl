import {
  Component,
  computed,
  effect,
  input,
  output,
} from '@angular/core';
import {
  FormGroup,
  ReactiveFormsModule,
} from '@angular/forms';
import { Subscription } from 'rxjs';
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
  serverErrors = input<Record<string, string>>({});
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

  constructor() {
    effect((onCleanup) => {
      const serverErrors = this.serverErrors();
      const fg = this.formGroup();

      if (!fg || Object.keys(serverErrors).length === 0) return;

      const subscriptions: Subscription[] = [];

      Object.entries(serverErrors).forEach(([key, message]) => {
        const control = fg.get(key);
        if (control) {
          const currentErrors = control.errors || {};
          control.setErrors({ ...currentErrors, serverError: message }, { emitEvent: false });

          const sub = control.valueChanges.subscribe(() => {
            if (control.errors && 'serverError' in control.errors) {
              const { serverError: _, ...otherErrors } = control.errors;
              const remainingKeys = Object.keys(otherErrors);
              control.setErrors(remainingKeys.length > 0 ? otherErrors : null, { emitEvent: true });
            }
          });
          subscriptions.push(sub);
        } else {
          console.warn(`[CompaniesForm] No control found for server error key: "${key}"`);
        }
      });

      onCleanup(() => {
        subscriptions.forEach(sub => sub.unsubscribe());
      });
    });
  }

  onSave(): void {
    this.formGroup().markAllAsTouched();

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
