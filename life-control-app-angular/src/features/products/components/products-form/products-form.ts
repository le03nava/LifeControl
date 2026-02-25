import {
  Component,
  inject,
  signal,
  input,
  output,
  effect,
} from '@angular/core';
import {
  FormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
  FormControl,
} from '@angular/forms';
import { Product } from '../../models/product.models';
import { ProductControl } from '../../models/product.models';
import { Field } from '@shared/ui';

@Component({
  selector: 'app-products-form',
  standalone: true,
  imports: [ReactiveFormsModule, Field],
  templateUrl: './products-form.html',
  styleUrl: './products-form.scss',
})
export class ProductsForm {
  formGroup = input.required<FormGroup<ProductControl>>();
  saveProduct = output<Product>();
  cancelForm = output<void>();

  // Signal para detectar modo edición
  isEditMode = signal(false);

  constructor() {
    // Detectar modo edición cuando cambia el formGroup
    effect(() => {
      const form = this.formGroup();
      if (form.get('id')?.value) {
        this.isEditMode.set(true);
      }
    });
  }

  onSave(): void {
    if (this.formGroup().valid) {
      const formData = this.formGroup();

      const productData: Product = {
        id: formData.get('id')!.value,
        name: formData.get('name')!.value,
        description: formData.get('description')!.value,
        price: formData.get('price')!.value,
      };
      this.saveProduct.emit(productData);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }

  getControl(controlName: keyof ProductControl): FormControl<string | number | null> {
    return this.formGroup().controls[controlName] as FormControl<any | null>;
  }
}
