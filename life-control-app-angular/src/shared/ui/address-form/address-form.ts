import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
} from '@angular/core';
import {
  AbstractControl,
  FormGroup,
  ReactiveFormsModule,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { Subscription } from 'rxjs';
import { AddressControl } from '../../models/address.models';
import { Country } from '../../../features/companies/countries/models/country.models';

@Component({
  selector: 'app-address-form',
  standalone: true,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatSelectModule, MatIconModule],
  templateUrl: './address-form.html',
  styleUrl: './address-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AddressFormComponent {
  /** REQUIRED: parent passes its address sub-FormGroup */
  addressFormGroup = input.required<FormGroup<AddressControl>>();

  /** OPTIONAL: country catalog for the country <mat-select> */
  countries = input<Country[]>([]);

  /** OPTIONAL: server-side validation errors (already stripped of 'address.' prefix) */
  serverErrors = input<Record<string, string>>({});

  /** OPTIONAL: hide the address section heading */
  hideHeading = input<boolean>(false);

  // ─── Helpers for mat-select compareWith ──────────────────────
  protected compareCountryById = (optionId: string | null, selectedId: string | null): boolean => {
    return optionId === selectedId;
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
      const fg = this.addressFormGroup();

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
          console.warn(`[AddressFormComponent] No control found for server error key: "${key}"`);
        }
      });

      onCleanup(() => {
        subscriptions.forEach(sub => sub.unsubscribe());
      });
    });
  }
}
