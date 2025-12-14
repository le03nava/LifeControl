import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ProductService } from '@features/products/data/product.service';
import { Product } from '@features/products/models/product.models';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  FormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
  NonNullableFormBuilder,
} from '@angular/forms';
import { ProductsForm } from '@features/products/components/products-form/products-form';
import { ProductControl } from '@features/products/models/product-control';

@Component({
  selector: 'app-product-edit',
  imports: [ReactiveFormsModule, ProductsForm],
  templateUrl: './product-edit.html',
  styleUrl: './product-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductEdit {
  private route = inject(ActivatedRoute);
  private productService = inject(ProductService);
  private fb = inject(NonNullableFormBuilder);
  private router = inject(Router);

  productForm = signal<FormGroup<ProductControl>>(this.createForm());
  productId = signal(this.route.snapshot.paramMap.get('id'));
  product = toSignal(this.productService.getProductById(this.productId() ?? ''), {
    initialValue: { id: '', name: '', description: '', price: 0 } as Product,
  });

  private createForm(): FormGroup<ProductControl> {
    console.log(this.productId);
    return this.fb.group({
      id: this.fb.control(''),
      name: this.fb.control(''),
      description: this.fb.control('', Validators.required),
      price: this.fb.control(0),
    });
  }

  createProduct(productData: Product) {
    this.productService.createProduct(productData).subscribe({
      next: (createdProduct) => {
        console.log('Producto creado:', createdProduct);
      },
    });
  }
  onSubmit(): void {
    const form = this.productForm(); // Obtenemos el valor del Signal

    if (form.valid) {
      console.log('✅ Formulario de Producto Válido (Signal/OnPush). Datos:', form.value);

      // La llamada a `reset()` en el FormGroup nativo notifica a Angular
      // y funciona correctamente con OnPush.
      form.reset();
    } else {
      console.log('❌ Formulario Inválido.');
      form.markAllAsTouched();
    }
  }
}
