import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ProductService } from '@features/products/data/product.service';
import { Product } from '@features/products/models/product.models';
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

  constructor() {
    const id = this.productId();
    if (id) {
      this.productService.getProductById(id).subscribe((product) => {
        console.log(product.id);
        this.productForm.set(
          this.fb.group({
            id: this.fb.control(product.id),
            name: this.fb.control(product.name),
            description: this.fb.control(product.description, Validators.required),
            price: this.fb.control(product.price),
          }),
        );
      });
    }
  }

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
    console.log('product ', productData);
    if (productData.id === '') {
      this.productService.createProduct(productData).subscribe({
        next: (createdProduct) => {
          console.log('Producto creado:', createdProduct);
        },
      });
    } else {
      this.productService.updateProduct(productData).subscribe({
        next: (createdProduct) => {
          console.log('Producto actualizado:', createdProduct);
        },
      });
    }
  }
}
