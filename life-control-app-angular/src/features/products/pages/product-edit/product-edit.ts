import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { ProductService } from '../../data/product.service';
import { ApiError } from '@shared/models';
import { Product, ProductControl } from '../../models/product.models';
import {
  NonNullableFormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { ProductsForm } from '../../components/products-form/products-form';


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

  productId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  productForm = signal<FormGroup<ProductControl>>(this.createForm());

  isEditMode = signal(false);
  serverErrors = signal<Record<string, string>>({});
  generalError = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.productId();
    if (id) {
      this.isEditMode.set(true);
      this.loadProduct(id);
    }
  }

  private loadProduct(id: string): void {
    this.productService.getProductById(id).subscribe({
      next: (product) => {
        this.productForm.set(
          this.fb.group({
            id: this.fb.control(product.id),
            sku: this.fb.control(product.sku, Validators.required),
            name: this.fb.control(product.name, Validators.required),
            shortName: this.fb.control(product.shortName ?? null),
            satCode: this.fb.control(product.satCode ?? null),
            productType: this.fb.control(product.productType ?? null),
            attributes: this.fb.control(product.attributes ? JSON.stringify(product.attributes) : null),
            enabled: this.fb.control(product.enabled),
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
      sku: this.fb.control('', Validators.required),
      name: this.fb.control('', Validators.required),
      shortName: this.fb.control<string | null>(null),
      satCode: this.fb.control<string | null>(null),
      productType: this.fb.control<string | null>(null),
      attributes: this.fb.control<string | null>(null),
      enabled: this.fb.control(true),
    });
  }

  onSaveProduct(productData: Product): void {
    if (productData.id === '') {
      const { id, ...createData } = productData;
      this.productService.createProduct(createData as Product).subscribe({
        next: (createdProduct) => {
          this.router.navigate(['/products/edit', createdProduct.id]);
        },
        error: (err: HttpErrorResponse) => {
          this.handleServerError(err);
        },
      });
    } else {
      this.productService.updateProduct(productData.id, productData).subscribe({
        next: () => {
          this.router.navigate(['/products']);
        },
        error: (err: HttpErrorResponse) => {
          this.handleServerError(err);
        },
      });
    }
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
    } else if (apiError?.message) {
      this.serverErrors.set({});
      this.generalError.set(apiError.message);
    } else {
      this.serverErrors.set({});
      this.generalError.set('Error inesperado. Intente de nuevo más tarde.');
    }
  }

  cancelForm(): void {
    this.router.navigate(['/products']);
  }

}
