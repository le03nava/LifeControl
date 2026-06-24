import {
  Component,
  computed,
  effect,
  input,
  output,
} from '@angular/core';
import {
  AbstractControl,
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

@Component({
  selector: 'app-companies-form',
  standalone: true,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatSelectModule, MatSlideToggleModule, MatIconModule],
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

  private readonly defaultErrorMessages: Record<string, (error: any) => string> = {
    required: () => 'Este campo es obligatorio.',
    email: () => 'El formato del correo electrónico no es válido.',
    minlength: (err) =>
      `Debe tener al menos ${err.requiredLength} caracteres (llevas ${err.actualLength}).`,
    maxlength: (err) =>
      `No puede superar los ${err.requiredLength} caracteres.`,
    pattern: () => 'El formato ingresado no es válido.',
    serverError: (err) => err,
  };

  protected getErrorMessage(
    control: AbstractControl | null,
    customMessages?: Record<string, (error: any) => string>,
  ): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const primerErrorKey = Object.keys(control.errors)[0];
    const errorDetalle = control.errors[primerErrorKey];

    const allMessages = { ...this.defaultErrorMessages, ...customMessages };
    if (allMessages[primerErrorKey]) {
      return allMessages[primerErrorKey](errorDetalle);
    }

    return 'Campo inválido.';
  }

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
        id: raw.id,
        companyKey: raw.companyKey ?? '',
        companyName: raw.companyName,
        tipoPersonaId: raw.tipoPersonaId,
        razonSocial: raw.razonSocial,
        rfc: raw.rfc,
        email: raw.email,
        phone: raw.phone,
        enabled: raw.enabled,
        createdAt: '',
        updatedAt: '',
        // Address fields — only include non-null/non-empty values
        ...(raw.street?.trim() ? { street: raw.street.trim() } : {}),
        ...(raw.streetNumber?.trim() ? { streetNumber: raw.streetNumber.trim() } : {}),
        ...(raw.internalNumber?.trim() ? { internalNumber: raw.internalNumber.trim() } : {}),
        ...(raw.neighborhood?.trim() ? { neighborhood: raw.neighborhood.trim() } : {}),
        ...(raw.zipCode?.trim() ? { zipCode: raw.zipCode.trim() } : {}),
        ...(raw.city?.trim() ? { city: raw.city.trim() } : {}),
        ...(raw.state?.trim() ? { state: raw.state.trim() } : {}),
        ...(raw.countryId?.trim() ? { countryId: raw.countryId.trim() } : {}),
      };
      this.saveCompany.emit(companyData);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
