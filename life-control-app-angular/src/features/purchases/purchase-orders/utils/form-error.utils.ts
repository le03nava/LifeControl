import type { AbstractControl } from '@angular/forms';

/**
 * Returns the standard "required" message when the control is invalid and has
 * been touched, so validation feedback stays consistent across the editor.
 */
export function requiredFieldError(control: AbstractControl | null | undefined): string | null {
  if (control && control.invalid && control.touched && control.hasError('required')) {
    return 'Este campo es requerido.';
  }
  return null;
}

/** Returns the server-side validation error for a field, if any. */
export function serverError(errors: Record<string, string>, field: string): string | null {
  return errors[field] ?? null;
}
