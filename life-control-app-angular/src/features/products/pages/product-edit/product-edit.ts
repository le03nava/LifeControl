import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
  OnInit,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ProductService } from '../../data/product.service';
import { ApiError } from '@shared/models';
import { Product, ProductControl } from '../../models/product.models';
import { NonNullableFormBuilder, FormGroup, Validators } from '@angular/forms';
import { ProductsForm } from '../../components/products-form/products-form';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { MatTabsModule } from '@angular/material/tabs';
import { ProductSupplierList } from '../product-supplier-list/product-supplier-list';
import { ProductVariantList } from '../product-variant-list/product-variant-list';
import type { UnsavedChangesAware } from '@core/guards/unsaved-changes.guard';

/** The three workspace tabs, in display order; the index doubles as `selectedIndex`. */
const WORKSPACE_TABS = ['datos', 'proveedores', 'variantes'] as const;
type WorkspaceTab = (typeof WORKSPACE_TABS)[number];

@Component({
  selector: 'app-product-edit',
  imports: [
    NgTemplateOutlet,
    ErrorBanner,
    PageHeader,
    ProductsForm,
    MatTabsModule,
    ProductSupplierList,
    ProductVariantList,
  ],
  templateUrl: './product-edit.html',
  styleUrl: './product-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductEdit implements OnInit, UnsavedChangesAware {
  private readonly route = inject(ActivatedRoute);
  private readonly productService = inject(ProductService);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  productId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  productForm = signal<FormGroup<ProductControl>>(this.createForm());

  /**
   * The product as loaded, for the workspace header.
   *
   * Deliberately the loaded entity and not the live form value: the header identifies
   * which product the workspace belongs to, so it must not flicker as the operator
   * types in the `Datos` tab. Edits belong to the form, not to the page identity.
   */
  readonly product = signal<Product | null>(null);

  isEditMode = signal(false);
  serverErrors = signal<Record<string, string>>({});
  generalError = signal<string | null>(null);

  /** The supplier count reported by the `Proveedores` tab, fed by its `countChange` output. */
  readonly supplierCount = signal(0);

  /** The variant count reported by the `Variantes` tab, fed by its `countChange` output. */
  readonly variantCount = signal(0);

  /**
   * The query params, read reactively so an in-place tab switch is observed. A snapshot
   * read would never see the change the click itself writes.
   */
  private readonly queryParamMap = toSignal(this.route.queryParamMap, {
    initialValue: convertToParamMap({}),
  });

  /** The validated tab named by `?tab=`, defaulting to `datos` for a missing or unknown value. */
  readonly activeTab = computed<WorkspaceTab>(() => {
    const requested = this.queryParamMap().get('tab');
    return (WORKSPACE_TABS as readonly string[]).includes(requested ?? '')
      ? (requested as WorkspaceTab)
      : 'datos';
  });

  readonly selectedTabIndex = computed(() => WORKSPACE_TABS.indexOf(this.activeTab()));

  readonly skuSubtitle = computed(() => {
    const sku = this.product()?.sku;
    return sku ? `SKU: ${sku}` : '';
  });

  readonly supplierTabLabel = computed(() => `Proveedores (${this.supplierCount()})`);
  readonly variantTabLabel = computed(() => `Variantes (${this.variantCount()})`);

  ngOnInit(): void {
    const id = this.productId();
    if (id) {
      this.isEditMode.set(true);
      this.loadProduct(id);
    }
  }

  private loadProduct(id: string): void {
    this.productService
      .getProductById(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (product) => {
          this.product.set(product);
          this.productForm.set(
            this.fb.group({
              id: this.fb.control(product.id),
              sku: this.fb.control(product.sku, Validators.required),
              name: this.fb.control(product.name, Validators.required),
              shortName: this.fb.control(product.shortName ?? null),
              satCode: this.fb.control(product.satCode ?? null),
              productType: this.fb.control(product.productType ?? null),
              attributes: this.fb.control(
                product.attributes ? JSON.stringify(product.attributes) : null,
              ),
              enabled: this.fb.control(product.enabled),
            }),
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

  /**
   * Writes the selected tab into `?tab=`.
   *
   * The other query params are carried over explicitly, not via
   * `queryParamsHandling: 'merge'`, so the preservation is visible and pinnable: dropping
   * `storeId` would silently change which rows the `Variantes` tab reads.
   *
   * The equality check is the feedback-loop guard. `mat-tab-group` emits
   * `selectedIndexChange` in a microtask after `[selectedIndex]` moves, so a reactive
   * param change would otherwise navigate back into the value it came from.
   */
  onTabChange(index: number): void {
    const requested = WORKSPACE_TABS[index];
    if (!requested || requested === this.activeTab()) {
      return;
    }

    const current = this.queryParamMap();
    const queryParams: Record<string, string> = {};
    current.keys.forEach((key) => {
      if (key !== 'tab') {
        const value = current.get(key);
        if (value !== null) {
          queryParams[key] = value;
        }
      }
    });
    queryParams['tab'] = requested;

    this.router.navigate([], { relativeTo: this.route, queryParams });
  }

  onSaveProduct(productData: Product): void {
    if (productData.id === '') {
      const { id: _id, ...createData } = productData;
      this.productService
        .createProduct(createData as Product)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (createdProduct) => {
            // The route is guarded by `unsavedChangesGuard`; a successful save
            // must reach the edit page without the discard prompt firing.
            this.productForm().markAsPristine();
            this.router.navigate(['/products/edit', createdProduct.id]);
          },
          error: (err: HttpErrorResponse) => {
            this.handleServerError(err);
          },
        });
    } else {
      this.productService
        .updateProduct(productData.id, productData)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () => {
            // The route is guarded by `unsavedChangesGuard`; a successful save
            // must reach the list without the discard prompt firing.
            this.productForm().markAsPristine();
            this.router.navigate(['/products']);
          },
          error: (err: HttpErrorResponse) => {
            this.handleServerError(err);
          },
        });
    }
  }

  /**
   * `GlobalExceptionHandler` answers 409 from two different sources: the explicit
   * duplicate check (`DuplicateProductException`, message `Product with SKU 'X'
   * already exists`) and the uncaught `DataIntegrityViolationException` path
   * (generic "data constraint" message). D5 requires message-discriminated mapping:
   * only the first one identifies a field conflict, so only it marks `sku`.
   */
  private static readonly DUPLICATE_SKU_PATTERN = /sku/i;
  private static readonly DUPLICATE_SKU_MESSAGE = 'Ya existe un producto con ese SKU.';

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
    } else if (this.isDuplicateSkuConflict(err, apiError)) {
      this.serverErrors.set({ sku: ProductEdit.DUPLICATE_SKU_MESSAGE });
      this.generalError.set(null);
    } else if (apiError?.message) {
      this.serverErrors.set({});
      this.generalError.set(apiError.message);
    } else {
      this.serverErrors.set({});
      this.generalError.set('Error inesperado. Intente de nuevo más tarde.');
    }
  }

  private isDuplicateSkuConflict(err: HttpErrorResponse, apiError: ApiError | undefined): boolean {
    return (
      err.status === 409 &&
      typeof apiError?.message === 'string' &&
      ProductEdit.DUPLICATE_SKU_PATTERN.test(apiError.message)
    );
  }

  cancelForm(): void {
    this.router.navigate(['/products']);
  }

  /** Exposed to `unsavedChangesGuard`: the live form decides. */
  hasUnsavedChanges(): boolean {
    return this.productForm().dirty;
  }
}
