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
import { of } from 'rxjs';
import { NonNullableFormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { ProductService } from '../../data/product.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { ApiError } from '@shared/models';
import { ProductVariantRequest } from '../../models/product-variant.models';
import {
  ProductVariantControl,
  ProductVariantForm,
} from '../../components/product-variant-form/product-variant-form';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import type { UnsavedChangesAware } from '@core/guards/unsaved-changes.guard';

/**
 * The single message for a 409 on the global definition.
 *
 * The backend answers 409 for a duplicate barcode AND for a duplicate variant
 * name within the product, and it sends no field attribution in either case.
 * Naming both possibilities is the only honest copy: guessing which field
 * collided would present a fact the server never sent. Kept form-level on
 * purpose — never bound to a single control.
 */
const VARIANT_DUPLICATE_MESSAGE =
  'Ya existe una variante con ese código de barras o ese nombre para este producto';

/**
 * Create/edit page for the **global definition** of a product variant.
 *
 * Reads `:id` (the owning product) and the optional `:variantId`. The page is
 * reached both ways, so edit mode re-reads the variant from the API instead of
 * relying on `history.state`: a direct deep link must work as well as an
 * in-app navigation.
 *
 * The route carries `unsavedChangesGuard`, so the page implements
 * {@link UnsavedChangesAware} from the live form and marks it pristine right
 * before the post-save navigation; a successful save must never trip the guard.
 */
@Component({
  selector: 'app-product-variant-edit',
  standalone: true,
  imports: [ReactiveFormsModule, ErrorBanner, ProductVariantForm, PageHeader],
  templateUrl: './product-variant-edit.html',
  styleUrl: './product-variant-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductVariantEdit implements OnInit, UnsavedChangesAware {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly productService = inject(ProductService);
  private readonly productVariantService = inject(ProductVariantService);
  private readonly destroyRef = inject(DestroyRef);

  readonly productId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  /** Present only in edit mode; its absence is what makes this a create page. */
  readonly variantId = signal<string | null>(this.route.snapshot.paramMap.get('variantId'));

  readonly isEditMode = signal(false);

  /** The owning product, loaded only to name it in the header. */
  readonly productResource = rxResource({
    params: () => ({ productId: this.productId() }),
    stream: ({ params }) => {
      if (!params.productId) {
        return of(null);
      }
      return this.productService.getProductById(params.productId);
    },
  });
  readonly product = computed(() =>
    this.productResource.hasValue() ? this.productResource.value() : undefined,
  );

  protected readonly httpErrorMessage = httpErrorMessage;

  readonly headerSubtitle = computed(() => {
    const sku = this.product()?.sku;
    const mode = this.isEditMode() ? 'Editando la variante' : 'Nueva variante';
    return sku ? `SKU: ${sku} — ${mode}` : mode;
  });

  readonly variantForm = signal<FormGroup<ProductVariantControl>>(this.createForm());
  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);

  constructor() {
    // A product id is mandatory: without it there is no product to attach the
    // definition to, so fail closed instead of rendering a broken form.
    effect(() => {
      if (!this.productId()) {
        this.router.navigate(['/products/list']);
      }
    });
  }

  ngOnInit(): void {
    const variantId = this.variantId();
    const productId = this.productId();
    if (variantId && productId) {
      this.isEditMode.set(true);
      this.loadVariant(productId, variantId);
    }
  }

  private createForm(): FormGroup<ProductVariantControl> {
    return this.fb.group({
      barCode: this.fb.control('', [Validators.required, Validators.maxLength(100)]),
      variantName: this.fb.control('', [Validators.required, Validators.maxLength(255)]),
    });
  }

  private loadVariant(productId: string, variantId: string): void {
    this.productVariantService
      .getVariantById(productId, variantId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (variant) => {
          // `patchValue` keeps the form pristine: a programmatic load is not an
          // operator edit and must not arm the unsaved-changes guard.
          this.variantForm().patchValue({
            barCode: variant.barCode,
            variantName: variant.variantName,
          });
        },
        error: (err: HttpErrorResponse) => this.handleLoadError(err),
      });
  }

  onSaveVariant(request: ProductVariantRequest): void {
    const productId = this.productId();
    if (!productId) {
      this.router.navigate(['/products/list']);
      return;
    }

    const variantId = this.variantId();
    const save$ =
      this.isEditMode() && variantId
        ? this.productVariantService.updateVariant(productId, variantId, request)
        : this.productVariantService.createVariant(productId, request);

    save$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        // The route is guarded by `unsavedChangesGuard`; a successful save must
        // reach the list without the discard prompt firing.
        this.variantForm().markAsPristine();
        this.router.navigate(['/products/edit', productId, 'variants']);
      },
      error: (err: HttpErrorResponse) => this.handleServerError(err),
    });
  }

  cancelForm(): void {
    const productId = this.productId();
    if (productId) {
      this.router.navigate(['/products/edit', productId, 'variants']);
    } else {
      this.router.navigate(['/products/list']);
    }
  }

  /** Exposed to `unsavedChangesGuard`: the live form decides. */
  hasUnsavedChanges(): boolean {
    return this.variantForm().dirty;
  }

  private handleLoadError(err: HttpErrorResponse): void {
    this.serverErrors.set({});
    this.generalError.set(httpErrorMessage(err));
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
      return;
    }

    // The 409 collision carries no field attribution, so it becomes one
    // form-level banner naming both uniqueness rules the backend enforces.
    this.serverErrors.set({});
    this.generalError.set(err.status === 409 ? VARIANT_DUPLICATE_MESSAGE : httpErrorMessage(err));
  }
}
