import { ChangeDetectionStrategy, Component, inject, signal, effect } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ProductService } from '@features/products/data/product.service';
import { Product, ProductControl } from '@features/products/models/product.models';
import {
  NonNullableFormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { ProductsForm } from '@features/products/components/products-form/products-form';
import { httpResource } from '@angular/common/http';

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

  productId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  // Usar httpResource para obtener el producto por ID
  private productResource = httpResource<Product>(
    () => (this.productId() ? `${this.productService.apiUrl}/${this.productId()}` : undefined),
    { defaultValue: { id: '', name: '', description: '', price: 0 } as Product }
  );

  productForm = signal<FormGroup<ProductControl>>(this.createForm());

  constructor() {
    // Usar effect para actualizar el formulario cuando cambia el producto
    effect(() => {
      const product = this.productResource.value();
      if (product && product.id) {
        this.productForm.set(
          this.fb.group({
            id: this.fb.control(product.id),
            name: this.fb.control(product.name),
            description: this.fb.control(product.description, Validators.required),
            price: this.fb.control(product.price),
          }),
        );
      }
    });
  }

  private createForm(): FormGroup<ProductControl> {
    return this.fb.group({
      id: this.fb.control(''),
      name: this.fb.control(''),
      description: this.fb.control('', Validators.required),
      price: this.fb.control(0),
    });
  }

  onSaveProduct(productData: Product): void {
    if (productData.id === '') {
      this.productService.createProduct(productData).subscribe({
        next: () => {
          this.router.navigate(['/products']);
        },
      });
    } else {
      this.productService.updateProduct(productData).subscribe({
        next: () => {
          this.router.navigate(['/products']);
        },
      });
    }
  }

  cancelForm(): void {
    this.router.navigate(['/products']);
  }
}
