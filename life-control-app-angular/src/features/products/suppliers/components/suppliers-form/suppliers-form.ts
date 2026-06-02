import {
  ChangeDetectionStrategy,
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
import { Supplier, SupplierControl } from '../../models/supplier.models';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-suppliers-form',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatSlideToggleModule,
    MatIconModule,
  ],
  templateUrl: './suppliers-form.html',
  styleUrl: './suppliers-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SuppliersForm {
  formGroup = input.required<FormGroup<SupplierControl>>();
  serverErrors = input<Record<string, string>>({});
  editMode = input<boolean>(false);
  saveSupplier = output<Supplier>();
  cancelForm = output<void>();

  protected readonly rfcErrorMessages: Record<string, (error: any) => string> = {
    pattern: () => 'El RFC debe tener 12-13 caracteres alfanuméricos.',
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
          console.warn(`[SuppliersForm] No control found for server error key: "${key}"`);
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
      const supplierData: Supplier = {
        id: raw.id,
        supplierName: raw.supplierName,
        razonSocial: raw.razonSocial,
        rfc: raw.rfc,
        email: raw.email,
        phoneNumber: raw.phoneNumber,
        street: raw.street,
        streetNumber: raw.streetNumber,
        neighborhood: raw.neighborhood,
        zipCode: raw.zipCode,
        city: raw.city,
        state: raw.state,
        enabled: raw.enabled ?? true,
        createdAt: '',
        updatedAt: '',
      };
      this.saveSupplier.emit(supplierData);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
