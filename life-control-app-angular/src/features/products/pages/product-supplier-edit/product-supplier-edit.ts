import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { map, of } from 'rxjs';
import {
  NonNullableFormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { ProductService } from '../../data/product.service';
import { SupplierService } from '../../suppliers/data/supplier.service';
import { ApiError } from '@shared/models';
import {
  ProductSupplier,
  ProductSupplierControl,
  ProductSupplierRequest,
} from '../../models/product-supplier.models';
import { ProductSupplierForm } from '../../components/product-supplier-form/product-supplier-form';
import { PageHeader } from '@shared/ui';

@Component({
  selector: 'app-product-supplier-edit',
  standalone: true,
  imports: [ReactiveFormsModule, ProductSupplierForm, PageHeader],
  templateUrl: './product-supplier-edit.html',
  styleUrl: './product-supplier-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductSupplierEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private fb = inject(NonNullableFormBuilder);
  private productSupplierService = inject(ProductSupplierService);
  private productService = inject(ProductService);
  private supplierService = inject(SupplierService);
  private destroyRef = inject(DestroyRef);

  readonly productId = signal<string | null>(
    this.route.snapshot.paramMap.get('id'),
  );
  readonly psId = signal<string | null>(
    this.route.snapshot.paramMap.get('supplierId'),
  );

  readonly productResource = rxResource({
    params: () => ({ productId: this.productId() }),
    stream: ({ params }) => {
      if (!params.productId) {
        return of(null);
      }
      return this.productService.getProductById(params.productId);
    },
  });
  readonly product = this.productResource.value;

  readonly headerSubtitle = computed(() => {
    const sku = this.product()?.sku;
    const mode = this.isEditMode() ? 'Edit assignment' : 'New assignment';
    return sku ? `SKU: ${sku} — ${mode}` : mode;
  });

  readonly assignmentForm = signal<FormGroup<ProductSupplierControl>>(
    this.createForm(),
  );
  readonly isEditMode = signal(false);
  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);

  // All suppliers for the dropdown
  readonly allSuppliersResource = rxResource({
    stream: () =>
      this.supplierService
        .getAllSuppliers(0, 1000)
        .pipe(map((p) => p.content)),
  });
  readonly allSuppliers = this.allSuppliersResource.value;

  // Already-assigned suppliers for this product (for dropdown filtering)
  readonly assignedSuppliers = signal<ProductSupplier[]>([]);

  // Available suppliers: exclude already-assigned, keep current in edit mode
  readonly availableSuppliers = computed(() => {
    const all = this.allSuppliers() ?? [];
    const assigned = this.assignedSuppliers();
    const currentSupplierId = this.assignmentForm().controls.supplierId.value;

    return all.filter((s) => {
      if (this.isEditMode() && s.id === currentSupplierId) return true;
      return !assigned.some((a) => a.supplierId === s.id);
    });
  });

  constructor() {
    // Redirect if productId is missing
    effect(() => {
      const id = this.productId();
      if (!id) {
        this.router.navigate(['/products/list']);
      }
    });
  }

  ngOnInit(): void {
    const psId = this.psId();
    if (psId) {
      this.isEditMode.set(true);
      this.loadAssignment(psId);
    } else {
      this.loadAssignedSuppliers();
    }
  }

  private loadAssignedSuppliers(): void {
    const productId = this.productId();
    if (!productId) return;

    this.productSupplierService
      .getSuppliers(productId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (assignments) => this.assignedSuppliers.set(assignments),
      });
  }

  private loadAssignment(psId: string): void {
    const productId = this.productId();
    if (!productId) return;

    this.productSupplierService
      .getSuppliers(productId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (assignments) => {
          this.assignedSuppliers.set(assignments);
          const match = assignments.find((a) => a.id === psId);
          if (match) {
            this.assignmentForm.set(this.buildFormFromAssignment(match));
          } else {
            this.generalError.set('Supplier assignment not found.');
          }
        },
        error: () => {
          this.generalError.set('Failed to load supplier assignment.');
        },
      });
  }

  private buildFormFromAssignment(
    assignment: ProductSupplier,
  ): FormGroup<ProductSupplierControl> {
    return this.fb.group({
      id: this.fb.control(assignment.id),
      supplierId: this.fb.control(assignment.supplierId, Validators.required),
      purchaseCost: this.fb.control(assignment.purchaseCost, [
        Validators.required,
        Validators.min(0),
      ]),
      main: this.fb.control(assignment.main),
      enabled: this.fb.control(assignment.enabled),
    });
  }

  private createForm(): FormGroup<ProductSupplierControl> {
    return this.fb.group({
      id: this.fb.control(''),
      supplierId: this.fb.control('', Validators.required),
      purchaseCost: this.fb.control(0, [
        Validators.required,
        Validators.min(0),
      ]),
      main: this.fb.control(false),
      enabled: this.fb.control(true),
    });
  }

  onSaveAssignment(data: ProductSupplierRequest): void {
    const productId = this.productId();
    if (!productId) {
      this.router.navigate(['/products/list']);
      return;
    }

    if (this.isEditMode()) {
      const psId = this.psId()!;
      this.productSupplierService
        .updateSupplier(productId, psId, data)
        .subscribe({
          next: () =>
            this.router.navigate([
              '/products/edit',
              productId,
              'suppliers',
            ]),
          error: (err: HttpErrorResponse) => this.handleServerError(err),
        });
    } else {
      this.productSupplierService
        .addSupplier(productId, data)
        .subscribe({
          next: () =>
            this.router.navigate([
              '/products/edit',
              productId,
              'suppliers',
            ]),
          error: (err: HttpErrorResponse) => this.handleServerError(err),
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
    } else if (err.status === 409) {
      this.serverErrors.set({});
      this.generalError.set(
        'This supplier is already assigned to the product.',
      );
    } else {
      this.serverErrors.set({});
      this.generalError.set('Unexpected error. Please try again later.');
    }
  }

  cancelForm(): void {
    const productId = this.productId();
    if (productId) {
      this.router.navigate(['/products/edit', productId, 'suppliers']);
    } else {
      this.router.navigate(['/products/list']);
    }
  }
}
