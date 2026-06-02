import {
  ChangeDetectionStrategy,
  Component,
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
import { ProductSupplierControl, ProductSupplierRequest } from '../../models/product-supplier.models';
import { Supplier } from '../../suppliers/models/supplier.models';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-product-supplier-form',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatSlideToggleModule,
    MatTooltipModule,
    MatIconModule,
  ],
  templateUrl: './product-supplier-form.html',
  styleUrl: './product-supplier-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductSupplierForm {
  formGroup = input.required<FormGroup<ProductSupplierControl>>();
  serverErrors = input<Record<string, string>>({});
  editMode = input<boolean>(false);
  availableSuppliers = input<Supplier[]>([]);

  saveSupplier = output<ProductSupplierRequest>();
  cancelForm = output<void>();

  readonly defaultErrorMessages: Record<string, (error: any) => string> = {
    required: () => 'This field is required.',
    min: (err) => `Value must be at least ${err.min}.`,
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
    return 'Invalid field.';
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
            `[ProductSupplierForm] No control found for server error key: "${key}"`,
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
      const data: ProductSupplierRequest = {
        supplierId: raw.supplierId,
        purchaseCost: raw.purchaseCost,
        main: raw.main,
        enabled: raw.enabled,
      };
      this.saveSupplier.emit(data);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
