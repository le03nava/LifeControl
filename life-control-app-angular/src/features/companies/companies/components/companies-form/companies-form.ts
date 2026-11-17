import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { CompanyControl, CompanyFormValue } from '../../models/company.models';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';
import { AddressFormComponent } from '@shared/ui/address-form';
import { Country } from '@features/companies/countries/models/country.models';
import { Currency } from '../../data/currency.models';
import { REGIMEN_FISCAL_OPTIONS } from '@shared/fiscal/regimen-fiscal';

@Component({
  selector: 'app-companies-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatIconModule,
    AddressFormComponent,
  ],
  templateUrl: './companies-form.html',
  styleUrl: './companies-form.scss',
})
export class CompaniesForm {
  formGroup = input.required<FormGroup<CompanyControl>>();
  serverErrors = input<Record<string, string>>({});
  addressServerErrors = input<Record<string, string>>({});
  countries = input<Country[]>([]);
  currencies = input<Currency[]>([]);
  saveCompany = output<CompanyFormValue>();
  cancelForm = output<void>();

  readonly personaTypes = [
    { value: 1, label: 'Persona Física' },
    { value: 2, label: 'Persona Moral' },
  ];

  /**
   * The issuer's régime vocabulary, from the shared catalog so the receiver's screen reads the same one.
   * The labels are the enum's own descriptions; `regimen-fiscal.spec.ts` keeps them in step with the API.
   */
  readonly regimenFiscalOptions = REGIMEN_FISCAL_OPTIONS;

  /**
   * Whether the loaded company already declares a régime.
   *
   * The empty option is offered **only** while nothing is declared: the API writes the column only when the
   * request carries it (`CompanyService`), and the request DTO normalizes `''` to `null`, which on a PUT
   * means "leave what is stored". Offering "no declarado" over a declared code would show an empty screen
   * while the server keeps the old value.
   *
   * It reads {@link regimenFiscalValue} rather than the control directly: a form control's `value` is not a
   * signal, so a `computed` that read it would be cached once through the `formGroup()` input and would
   * never update when the operator picks a code.
   */
  readonly hasDeclaredRegimen = computed(() => !!this.regimenFiscalValue());

  private readonly regimenFiscalValue = signal('');

  protected readonly rfcErrorMessages: Record<string, (error: unknown) => string> = {
    pattern: () => 'El RFC debe tener 12-13 caracteres alfanuméricos.',
  };

  protected readonly emailErrorMessages: Record<string, (error: unknown) => string> = {
    email: () => 'Ingrese un correo electrónico válido.',
  };

  protected readonly emailDomainErrorMessages: Record<string, (error: unknown) => string> = {
    pattern: () => 'Ingrese un dominio válido (ej.: acme.com).',
    maxlength: () => 'El dominio no puede superar los 255 caracteres.',
  };

  protected readonly phoneErrorMessages: Record<string, (error: unknown) => string> = {
    pattern: () => 'Ingrese un número de teléfono válido.',
  };

  /** The same five-digit shape the API declares with `@Pattern("\\d{5}")` on `fiscalZipCode`. */
  protected readonly fiscalZipCodeErrorMessages: Record<string, (error: unknown) => string> = {
    pattern: () => 'El código postal fiscal debe tener cinco dígitos.',
  };

  private readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    email: () => 'El formato del correo electrónico no es válido.',
    minlength: (err) =>
      `Debe tener al menos ${(err as { requiredLength?: number }).requiredLength} caracteres (llevas ${(err as { actualLength?: number }).actualLength}).`,
    maxlength: (err) =>
      `No puede superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    pattern: () => 'El formato ingresado no es válido.',
    serverError: (err) => err as string,
  };

  protected getErrorMessage(
    control: AbstractControl | null,
    customMessages?: Record<string, (error: unknown) => string>,
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
    // The régime control's value, mirrored into a signal so `hasDeclaredRegimen` follows it, and
    // re-subscribed whenever the input form group is replaced (the edit page rebuilds it after loading).
    effect((onCleanup) => {
      const control = this.formGroup().controls.regimenFiscal;
      this.regimenFiscalValue.set(control.value);
      const sub = control.valueChanges.subscribe((value) => this.regimenFiscalValue.set(value));
      onCleanup(() => sub.unsubscribe());
    });

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
        subscriptions.forEach((sub) => sub.unsubscribe());
      });
    });
  }

  onSave(): void {
    this.formGroup().markAllAsTouched();

    if (this.formGroup().valid) {
      const raw = this.formGroup().getRawValue();
      const hasAddress =
        raw.address &&
        (raw.address.street ||
          raw.address.streetNumber ||
          raw.address.internalNumber ||
          raw.address.neighborhood ||
          raw.address.zipCode ||
          raw.address.city ||
          raw.address.state ||
          raw.address.countryId);
      const companyData: CompanyFormValue = {
        id: raw.id,
        companyKey: raw.companyKey ?? '',
        companyName: raw.companyName,
        tipoPersonaId: raw.tipoPersonaId,
        razonSocial: raw.razonSocial,
        rfc: raw.rfc,
        email: raw.email,
        emailDomain: raw.emailDomain ?? '',
        phone: raw.phone,
        currency: raw.currency,
        // Empty, not absent: the API normalizes `''` to `null`, and `null` on a PUT leaves what is stored.
        regimenFiscal: raw.regimenFiscal ?? '',
        fiscalZipCode: raw.fiscalZipCode ?? '',
        enabled: raw.enabled,
        ...(hasAddress ? { address: raw.address } : {}),
      };
      this.saveCompany.emit(companyData);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
