import { ChangeDetectionStrategy, Component, inject, signal, effect, OnInit } from '@angular/core';
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
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-product-edit',
  imports: [ReactiveFormsModule, ProductsForm],
  templateUrl: './product-edit.html',
  styleUrl: './product-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private productService = inject(ProductService);
  private fb = inject(NonNullableFormBuilder);
  private router = inject(Router);
  private http = inject(HttpClient);

  productId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  productForm = signal<FormGroup<ProductControl>>(this.createForm());

  ngOnInit(): void {
    const id = this.productId();
    if (id) {
      this.loadProduct(id);
    }
  }

  private loadProduct(id: string): void {
    this.http.get<Product>(`${this.productService.apiUrl}/${id}`).subscribe({
      next: (product) => {
        this.productForm.set(
          this.fb.group({
            id: this.fb.control(product.id),
            name: this.fb.control(product.name),
            description: this.fb.control(product.description, Validators.required),
            price: this.fb.control(product.price),
          })
        );
      },
      error: (err) => {
        console.error('[ProductEdit] Error loading product:', err);
      },
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
