import { AbstractControl, ValidationErrors } from '@angular/forms';

/**
 * Custom message map: reactive-form error key -> copy for the resolved error payload.
 * A key absent from the map falls back to the generic `Campo inválido.` message.
 */
export type ErrorMessages = Record<string, (error: unknown) => string>;

// ─── Message fragments (exact copy, extracted verbatim from the four forms) ───
const REQUIRED_MESSAGE = (): string => 'Este campo es obligatorio.';
const MAX_LENGTH_MESSAGE = (error: unknown): string =>
  `No puede superar los ${(error as { requiredLength?: number }).requiredLength} caracteres.`;
const MIN_MESSAGE = (error: unknown): string =>
  `El valor mínimo permitido es ${(error as { min?: number }).min}.`;
const EMAIL_MESSAGE = (): string => 'Ingrese un correo electrónico válido.';
const SERVER_ERROR_MESSAGE = (error: unknown): string => error as string;
/** Mirrors the backend `Integer` type of `displayOrder`, which `@Min(0)` alone cannot express. */
const INTEGER_MESSAGE = (): string => 'Debe ser un número entero.';

/**
 * Rejects fractional values on a numeric control.
 *
 * `Validators.min(0)` accepts `1.5`, but the backend models `displayOrder` as an
 * `Integer` — a decimal would be rejected with a 400 after the user already "saved".
 */
export function integerValidator(control: AbstractControl): ValidationErrors | null {
  const value = control.value;
  if (value === null || value === undefined || value === '') return null;
  return Number.isInteger(value) ? null : { integer: true };
}

/**
 * Messages for the leaf store forms (area / zone / location), which validate numeric fields.
 * Kept separate from {@link CONTACT_FORM_ERROR_MESSAGES} on purpose: `min` is not a known
 * error key for the store contact form, so it must keep falling back to `Campo inválido.`.
 */
export const LEAF_FORM_ERROR_MESSAGES: ErrorMessages = {
  required: REQUIRED_MESSAGE,
  maxlength: MAX_LENGTH_MESSAGE,
  min: MIN_MESSAGE,
  integer: INTEGER_MESSAGE,
  serverError: SERVER_ERROR_MESSAGE,
};

/**
 * Messages for the store contact form (name / email / phone / address), which validates email.
 * `email` is not a known error key for the leaf forms, so the maps stay separate.
 */
export const CONTACT_FORM_ERROR_MESSAGES: ErrorMessages = {
  required: REQUIRED_MESSAGE,
  maxlength: MAX_LENGTH_MESSAGE,
  email: EMAIL_MESSAGE,
  serverError: SERVER_ERROR_MESSAGE,
};

/**
 * Builds the `getErrorMessage(control, customMessages?)` accessor the form templates bind to.
 * The returned function keeps the signature and the first-error-wins semantics the four forms
 * already had, so no template changes when a form starts using this util.
 */
export function createErrorMessageResolver(
  defaultMessages: ErrorMessages,
): (control: AbstractControl | null, customMessages?: ErrorMessages) => string | null {
  return function getErrorMessage(
    control: AbstractControl | null,
    customMessages?: ErrorMessages,
  ): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const primerErrorKey = Object.keys(control.errors)[0];
    const errorDetalle = control.errors[primerErrorKey];

    const allMessages = { ...defaultMessages, ...customMessages };
    if (allMessages[primerErrorKey]) {
      return allMessages[primerErrorKey](errorDetalle);
    }

    return 'Campo inválido.';
  };
}
