import { ChangeDetectionStrategy, Component, computed, effect, input, output } from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Country } from '@features/companies/countries/models/country.models';
import { REGIMEN_FISCAL_OPTIONS } from '@shared/fiscal/regimen-fiscal';
import { USO_CFDI_OPTIONS } from '@shared/fiscal/uso-cfdi';
import {
  CustomerControl,
  CustomerFormValue,
  FiscalProfileControl,
} from '../../models/customer.models';
import {
  FISCAL_PROFILE_FIELDS,
  FISCAL_PROFILE_INCOMPLETE,
  FiscalProfileField,
  fiscalProfileViolations,
} from '../../validation/fiscal-profile-complete';

/**
 * The customer form, presentational: it renders the group the page owns and emits the value it holds.
 *
 * Modelled on `companies-form`, with one difference that is the point of this unit: the receiver's fiscal
 * profile is a **sub-group** with an all-or-nothing rule, and the refusal is painted beside each field it is
 * about rather than as one banner for the block (`S3` of `odd/tasks/customers-fiscal-form.md`).
 *
 * The rule itself is not written here: `fiscalProfileCompleteValidator` publishes
 * `{ fiscalProfileIncomplete: { legalName: '…', … } }` on the fiscal group, keyed by the API's own property
 * names, and this component only moves each sentence onto the control it names. That move exists because a
 * `mat-error` is shown by its **control's** error state: an error on the group alone would mark the form
 * invalid and paint nothing.
 */
@Component({
  selector: 'app-customer-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
  ],
  templateUrl: './customer-form.html',
  styleUrl: './customer-form.scss',
})
export class CustomerForm {
  formGroup = input.required<FormGroup<CustomerControl>>();
  countries = input<Country[]>([]);
  serverErrors = input<Record<string, string>>({});
  saveCustomer = output<CustomerFormValue>();
  cancelForm = output<void>();

  /**
   * The issuer's régime vocabulary and the receiver's use of the voucher, both from `shared/fiscal` — one copy
   * each, kept in step with the API enums by their own parity specs. The labels are the authority's wording.
   */
  readonly regimenFiscalOptions = REGIMEN_FISCAL_OPTIONS;
  readonly usoCfdiOptions = USO_CFDI_OPTIONS;

  readonly isEditMode = computed(() => !!this.formGroup().controls.id.value);

  private readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    email: () => 'Ingrese un correo electrónico válido.',
    pattern: () => 'El formato ingresado no es válido.',
    maxlength: (err) =>
      `No puede superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    // The refusal carries its own sentence: the rule names the field, so the message is the value.
    [FISCAL_PROFILE_INCOMPLETE]: (err) => err as string,
    serverError: (err) => err as string,
  };

  protected readonly rfcErrorMessages: Record<string, (error: unknown) => string> = {
    pattern: () => 'El RFC debe tener 12-13 caracteres alfanuméricos.',
  };

  /** The same five-digit shape the API declares with `@Pattern("\\d{5}")` on `fiscalZipCode`. */
  protected readonly fiscalZipCodeErrorMessages: Record<string, (error: unknown) => string> = {
    pattern: () => 'El código postal fiscal debe tener cinco dígitos.',
  };

  protected getErrorMessage(
    control: AbstractControl | null,
    customMessages?: Record<string, (error: unknown) => string>,
  ): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const firstErrorKey = Object.keys(control.errors)[0];
    const errorDetail = control.errors[firstErrorKey];

    const allMessages = { ...this.defaultErrorMessages, ...customMessages };
    if (allMessages[firstErrorKey]) {
      return allMessages[firstErrorKey](errorDetail);
    }

    return 'Campo inválido.';
  }

  constructor() {
    // The fiscal group's refusal, moved onto the four controls it names, and re-subscribed whenever the input
    // group is replaced (the edit page rebuilds it after loading the row).
    effect((onCleanup) => {
      const fiscal = this.formGroup().controls.fiscal;
      const apply = () => this.paintFiscalViolations(fiscal);

      apply();
      const subscription = fiscal.valueChanges.subscribe(apply);
      onCleanup(() => subscription.unsubscribe());
    });

    // The API's field-level refusals, mirrored onto the controls they name, and cleared as soon as the
    // operator edits that field. Same shape as `companies-form`, which is where this was measured.
    effect((onCleanup) => {
      const serverErrors = this.serverErrors();
      const formGroup = this.formGroup();

      if (!formGroup || Object.keys(serverErrors).length === 0) return;

      const subscriptions: Subscription[] = [];

      for (const [key, message] of Object.entries(serverErrors)) {
        const control = formGroup.get(key);
        if (!control) {
          console.warn(`[CustomerForm] No control found for server error key: "${key}"`);
          continue;
        }

        control.setErrors(
          { ...(control.errors ?? {}), serverError: message },
          { emitEvent: false },
        );

        subscriptions.push(
          control.valueChanges.subscribe(() => {
            if (control.errors && 'serverError' in control.errors) {
              const { serverError: _, ...otherErrors } = control.errors;
              const remainingKeys = Object.keys(otherErrors);
              control.setErrors(remainingKeys.length > 0 ? otherErrors : null, { emitEvent: true });
            }
          }),
        );
      }

      onCleanup(() => subscriptions.forEach((subscription) => subscription.unsubscribe()));
    });
  }

  /**
   * Moves the group's refusal onto the four controls, one sentence each.
   *
   * The violations are recomputed from the group's value instead of read back from its errors, so the paint
   * never depends on whether the group's validator ran before or after this subscription.
   */
  private paintFiscalViolations(group: FormGroup<FiscalProfileControl>): void {
    const violations = fiscalProfileViolations(group.getRawValue());

    for (const field of FISCAL_PROFILE_FIELDS) {
      this.paintViolation(group.controls[field], violations[field]);
    }
  }

  private paintViolation(control: AbstractControl, message: string | undefined): void {
    const { [FISCAL_PROFILE_INCOMPLETE]: _, ...rest } = control.errors ?? {};
    const next = message ? { ...rest, [FISCAL_PROFILE_INCOMPLETE]: message } : rest;
    const nextErrors = Object.keys(next).length > 0 ? next : null;

    // Only write when the paint would change: `setErrors` marks the parent dirty and emits on every call.
    const current = control.errors;
    const unchanged =
      (current === null && nextErrors === null) ||
      (current !== null &&
        nextErrors !== null &&
        Object.keys(nextErrors).length === Object.keys(current).length &&
        Object.entries(nextErrors).every(([key, value]) => current[key] === value));

    if (!unchanged) {
      control.setErrors(nextErrors, { emitEvent: false });
    }
  }

  protected fiscalError(field: FiscalProfileField): string | null {
    return this.getErrorMessage(this.formGroup().controls.fiscal.controls[field]);
  }

  onSave(): void {
    this.formGroup().markAllAsTouched();

    if (!this.formGroup().valid) {
      return;
    }

    const raw = this.formGroup().getRawValue();
    this.saveCustomer.emit({
      id: raw.id,
      name: raw.name,
      email: raw.email,
      phone: raw.phone,
      rfc: raw.rfc,
      salesChannel: raw.salesChannel,
      enabled: raw.enabled,
      fiscal: raw.fiscal,
    });
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
