import { ChangeDetectionStrategy, Component, effect, input, output } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ProductVariantRequest } from '../../models/product-variant.models';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';

/**
 * Typed control map of the global variant definition form.
 *
 * Only the two global fields are controls: `enabled` is deliberately absent
 * because the definition request does not carry it and re-enabling is a list-page
 * action, and the owning product travels in the route, never in the body.
 */
export interface ProductVariantControl {
  barCode: FormControl<string>;
  variantName: FormControl<string>;
}

/**
 * Presentational form for the **global definition** of a product variant.
 *
 * It owns no HTTP and no routing: the page owns the `FormGroup` and reacts to
 * `saveVariant`/`cancelForm`. Presentational because the same component serves
 * create and edit — `editMode` only changes the copy.
 *
 * `serverErrors` are keyed by control name; the backend field errors are applied
 * to the matching control exactly as `companies-form` does. The 409 collision is
 * NOT a field error (the backend gives no field attribution), so it never
 * travels through this input: the page renders it as a form-level banner.
 */
@Component({
  selector: 'app-product-variant-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './product-variant-form.html',
  styleUrl: './product-variant-form.scss',
})
export class ProductVariantForm {
  formGroup = input.required<FormGroup<ProductVariantControl>>();
  serverErrors = input<Record<string, string>>({});
  /** Only switches the title and submit labels; create and edit share one shape. */
  editMode = input<boolean>(false);

  saveVariant = output<ProductVariantRequest>();
  cancelForm = output<void>();

  protected readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No podés superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    serverError: (err) => err as string,
  };

  protected getErrorMessage(control: AbstractControl | null): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const primerErrorKey = Object.keys(control.errors)[0];
    const errorDetalle = control.errors[primerErrorKey];

    if (this.defaultErrorMessages[primerErrorKey]) {
      return this.defaultErrorMessages[primerErrorKey](errorDetalle);
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
          console.warn(`[ProductVariantForm] No control found for server error key: "${key}"`);
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
      this.saveVariant.emit({ barCode: raw.barCode, variantName: raw.variantName });
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
