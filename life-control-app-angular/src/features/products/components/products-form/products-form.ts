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
import { Product, ProductControl } from '../../models/product.models';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-products-form',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatSlideToggleModule,
    MatIconModule,
  ],
  templateUrl: './products-form.html',
  styleUrl: './products-form.scss',
})
export class ProductsForm {
  formGroup = input.required<FormGroup<ProductControl>>();
  serverErrors = input<Record<string, string>>({});
  saveProduct = output<Product>();
  cancelForm = output<void>();

  readonly defaultErrorMessages: Record<string, (error: any) => string> = {
    required: () => 'Este campo es obligatorio.',
    minlength: (err) =>
      `Debe tener al menos ${err.requiredLength} caracteres (llevas ${err.actualLength}).`,
    maxlength: (err) =>
      `No puede superar los ${err.requiredLength} caracteres.`,
    pattern: () => 'El formato ingresado no es válido.',
    json: () => 'Ingrese un JSON válido.',
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
          control.setErrors(
            { ...currentErrors, serverError: message },
            { emitEvent: false },
          );

          const sub = control.valueChanges.subscribe(() => {
            if (control.errors && 'serverError' in control.errors) {
              const { serverError: _, ...otherErrors } = control.errors;
              const remainingKeys = Object.keys(otherErrors);
              control.setErrors(
                remainingKeys.length > 0 ? otherErrors : null,
                { emitEvent: true },
              );
            }
          });
          subscriptions.push(sub);
        } else {
          console.warn(
            `[ProductsForm] No control found for server error key: "${key}"`,
          );
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

      let parsedAttributes: Record<string, any> | undefined;
      if (raw.attributes && raw.attributes.trim()) {
        try {
          parsedAttributes = JSON.parse(raw.attributes);
        } catch {
          parsedAttributes = undefined;
        }
      }

      const productData: Product = {
        id: raw.id,
        sku: raw.sku,
        name: raw.name,
        shortName: raw.shortName ?? undefined,
        satCode: raw.satCode ?? undefined,
        productType: raw.productType ?? undefined,
        attributes: parsedAttributes,
        enabled: raw.enabled ?? true,
        createdAt: '',
        updatedAt: '',
      };
      this.saveProduct.emit(productData);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
