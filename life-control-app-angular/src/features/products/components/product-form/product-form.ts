import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  FormBuilder,
  FormControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ProductService } from '@features/products/data/product.service';
import { NotificationService } from '@shared/data';
import { Button, Field } from '@shared/ui';

import { ProductForm } from '@features/products/models/product.form';
@Component({
  selector: 'app-product-form',
  imports: [ReactiveFormsModule, Button, Field],
  templateUrl: './product-form.html',
  styleUrl: './product-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormProduct {
  private fb = inject(NonNullableFormBuilder);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private productService = inject(ProductService);
  private notificationService = inject(NotificationService);

  isEditMode = signal(false);
  isSubmitting = signal(false);
  userId = signal<string | null>(null);

  productForm = this.fb.group<ProductForm>({
    id: this.fb.control(''),
    name: this.fb.control(''),
    description: this.fb.control('', Validators.required),
    price: this.fb.control(0),
  });

  constructor() {
    // Check if we're in edit mode
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.userId.set(id);
    }
    console.log('productForm', this.productForm.value);

    console.log('productForm', this.productForm);
  }

  async handleSubmit(): Promise<void> {
    if (this.productForm.invalid || this.isSubmitting()) {
      return;
    }

    this.isSubmitting.set(true);

    try {
      const formValue = this.productForm.value;
      const productData = {
        name: formValue.name!,
        description: formValue.description!,
        price: formValue.price,
      };
      console.log('productData ', productData);
      //this.router.navigate(['/users']);
    } catch (_error) {
      this.notificationService.showError(
        this.isEditMode() ? 'Failed to update user' : 'Failed to create user',
      );
    } finally {
      this.isSubmitting.set(false);
    }
  }

  goBack(): void {
    console.log('formContro  ', this.productForm);
  }

  public getControlByName(name: string): FormControl<any> {
    const control = this.productForm.get(name);

    // Se lanza un error si el control no se encuentra o no es un FormControl,
    // garantizando que el template siempre reciba el tipo esperado.
    if (!control) {
      throw new Error(`Control '${name}' no encontrado en el FormGroup.`);
    }

    // Aseguramos que el tipo de retorno sea el esperado por el FieldComponent.
    return control as FormControl<any>;
  }
}
