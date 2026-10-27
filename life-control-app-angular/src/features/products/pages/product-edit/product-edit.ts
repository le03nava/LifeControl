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
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { ProductService } from '../../data/product.service';
import { ProductCategoryService } from '../../data/product-category.service';
import { MAX_CATEGORY_DEPTH, ProductCategory } from '../../models/product-category.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import {
  Product,
  ProductControl,
  ProductFormValue,
  ProductRequest,
} from '../../models/product.models';
import { NonNullableFormBuilder, FormGroup, Validators } from '@angular/forms';
import { ProductsForm } from '../../components/products-form/products-form';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { MatTabsModule } from '@angular/material/tabs';
import { MatStepperIntl, MatStepperModule } from '@angular/material/stepper';
import { MatButtonModule } from '@angular/material/button';
import { ProductSupplierList } from '../product-supplier-list/product-supplier-list';
import { ProductVariantList } from '../product-variant-list/product-variant-list';
import type { UnsavedChangesAware } from '@core/guards/unsaved-changes.guard';

/** The three workspace tabs, in display order; the index doubles as `selectedIndex`. */
const WORKSPACE_TABS = ['datos', 'proveedores', 'variantes'] as const;
type WorkspaceTab = (typeof WORKSPACE_TABS)[number];

/**
 * The stepper's screen-reader labels.
 *
 * Material ships English defaults and this repo has no global provider (the English
 * `MatPaginatorIntl` is a recorded follow-up), so the one stepper in the app declares its own
 * instead of depending on `'Editable'` happening to be the same word in Spanish. A completed
 * step that is still editable is the one that renders today.
 */
class SpanishStepperIntl extends MatStepperIntl {
  override optionalLabel = 'Opcional';
  override completedLabel = 'Completado';
  override editableLabel = 'Editable';
}

@Component({
  selector: 'app-product-edit',
  imports: [
    NgTemplateOutlet,
    ErrorBanner,
    PageHeader,
    ProductsForm,
    MatTabsModule,
    MatStepperModule,
    MatButtonModule,
    ProductSupplierList,
    ProductVariantList,
  ],
  templateUrl: './product-edit.html',
  styleUrl: './product-edit.scss',
  providers: [{ provide: MatStepperIntl, useClass: SpanishStepperIntl }],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductEdit implements OnInit, UnsavedChangesAware {
  private readonly route = inject(ActivatedRoute);
  private readonly productService = inject(ProductService);
  private readonly categoryService = inject(ProductCategoryService);
  private readonly companyService = inject(CompanyService);
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

  /**
   * The active create-stepper step.
   *
   * `mat-stepper` reads it through `[selectedIndex]` and writes it back through
   * `(selectedIndexChange)`, so a header click and a footer button are the same code path
   * and there is no `ViewChild` to keep in sync.
   */
  readonly stepIndex = signal(0);

  serverErrors = signal<Record<string, string>>({});
  generalError = signal<string | null>(null);

  /** The supplier count reported by the `Proveedores` tab, fed by its `countChange` output. */
  readonly supplierCount = signal(0);

  /** The variant count reported by the `Variantes` tab, fed by its `countChange` output. */
  readonly variantCount = signal(0);

  /**
   * The embedded per-store panel's dirty flag, fed by the `Variantes` host's
   * `dirtyChange` output (D37).
   *
   * The route already carries `canDeactivate: [unsavedChangesGuard]`, and a panel whose
   * edits the guard cannot see would let a stray navigation discard stock and prices
   * silently — the same defect the association dialogs closed on their own close.
   */
  readonly variantPanelDirty = signal(false);

  /**
   * The companies whose category tree the form can read (S2).
   *
   * The picker is read once with the same page size `ProductCategoryList` uses, so both screens offer
   * the same list of companies.
   */
  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /**
   * Which company's tree feeds the three selects, seeded from `?companyId=` and kept there (S2).
   *
   * A product is global while its category belongs to one company, so the tree this screen reads is
   * decided here and not by the header context (see `savedCategoryPath`).
   */
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  /**
   * The selected company's whole tree, disabled nodes included (S8).
   *
   * `includeDisabled=true` is not a preference: without it a product classified under a since
   * disabled category would render three empty selects over a stored `category_id`, i.e. the saved
   * classification would be invisible and the next save would silently drop it.
   *
   * `undefined` params is the "no company selected" state and issues no request, exactly as
   * `ProductCategoryList.categoriesResource` does.
   */
  readonly categoriesResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      return companyId ? { companyId } : undefined;
    },
    stream: ({ params }) => this.categoryService.getCategories(params.companyId, true),
    defaultValue: [] as ProductCategory[],
  });

  /**
   * Whether the tree for the selected company has actually answered.
   *
   * `status()` is the only safe gate here: `value()` throws while a read is in flight or has failed,
   * and `hasValue()` is `true` from construction because the resource declares `defaultValue: []` —
   * which is precisely how F1 wiped a saved classification before the tree that proves it arrived.
   */
  private readonly treeLoaded = computed(() => this.categoriesResource.status() === 'resolved');

  /**
   * The selected company's tree, safe to render.
   *
   * `categoriesResource.value()` **throws** unless the read resolved (Angular raises
   * `ResourceValueError`), so it is never read unguarded: a 500 on this read degrades to an empty tree
   * plus `categoryLoadError` below, exactly as `ProductCategoryList.categories` does. Swallowing the
   * failure into an empty tree and saying nothing would be the worse answer — the operator would see
   * three empty selects and conclude the product is unclassified.
   */
  readonly categoryTree = computed<ProductCategory[]>(() =>
    this.treeLoaded() ? this.categoriesResource.value() : [],
  );

  /** The tree read failed, in the shared copy; `null` while nothing failed. */
  readonly categoryLoadError = computed<string | null>(() => {
    const error = this.categoriesResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  /**
   * Set when the operator moves to another company's tree (S2).
   *
   * The three selects and the SAT hint live in the form and are only reset through `categoryPath`, so
   * the operator's own action is what answers `[]` — a *certain* "no classification" — from then on.
   * It is set only by `onCompanyChange` and not by the seeding in `loadProduct`, which is a write-back
   * of saved state and must not discard anything. Before F1 existed this clearing was a side effect of
   * `savedCategoryPath` answering `[]` whenever the leaf was not in the (still empty) tree; that is
   * exactly the behaviour F1 removes, so the clearing is stated here instead of inferred.
   */
  private readonly categoryDiscarded = signal(false);

  /**
   * The saved classification as ids from the root to the deepest node (S6).
   *
   * Three answers, and the difference between them is the point:
   * - `null` — the host does not know yet (the tree is in flight, or its read failed). The form must
   *   leave itself alone; applying `[]` here would wipe the saved classification and the next save
   *   would send `categoryId: null` (F1).
   * - `[]` — the product is certainly unclassified (it has no `categoryId`), or the operator discarded
   *   it by moving to another company's tree (S2).
   * - the ids — resolved through the tree.
   *
   * The leaf comes from the `categoryId` control, not from the three selects: the selects can only be
   * filled once a company's tree has arrived, and a tree that fails to load must not erase the fact
   * that the product is classified. The levels above it are recovered by walking `parentCategoryId` up
   * the tree — never stored three times — and the walk stops at `MAX_CATEGORY_DEPTH` so a malformed
   * parent chain cannot loop.
   *
   * Deliberately not reactive to the controls' own `valueChanges`: a recomputation triggered by a pick
   * would feed the form's silent `categoryPath` effect with the pick itself, and that effect withdraws
   * the SAT hint the pick just set (S4). It does track `productForm()` and the resource, which is what
   * makes it compute once the load and the tree have both arrived, in either order.
   */
  readonly savedCategoryPath = computed<string[] | null>(() => {
    if (this.categoryDiscarded()) return [];

    const leafId = this.productForm().controls.categoryId.value;
    if (!leafId) return [];

    // No answer yet: the tree is loading, or the read failed and `categoryLoadError` states it.
    if (!this.treeLoaded()) return null;

    const byId = new Map(this.categoryTree().map((node) => [node.id, node]));
    let current = byId.get(leafId);
    if (!current) return [];

    const path: string[] = [];
    while (current && path.length < MAX_CATEGORY_DEPTH) {
      path.unshift(current.id);
      current = current.parentCategoryId ? byId.get(current.parentCategoryId) : undefined;
    }
    return path;
  });

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
          // S6: the product's own classification decides which tree renders its path, so the company
          // is seeded from the category the API reports. It is a write-back of the saved state, not a
          // navigation: the operator may be linked with `?companyId=` pointing elsewhere, and the
          // classification still has to resolve.
          if (product.categoryCompanyId) {
            this.selectedCompanyId.set(product.categoryCompanyId);
          }
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
              categoryId: this.fb.control(product.categoryId ?? ''),
              categoryRootId: this.fb.control(''),
              subcategory1Id: this.fb.control(''),
              subcategory2Id: this.fb.control(''),
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
      // The three selects are a view of this one id (S3); they stay empty until a company and a tree
      // are picked, which is also the create-mode state (S10).
      categoryId: this.fb.control(''),
      categoryRootId: this.fb.control(''),
      subcategory1Id: this.fb.control(''),
      subcategory2Id: this.fb.control(''),
    });
  }

  /**
   * Keeps the company selection in the URL (replace, so mid-form changes do not pollute history),
   * exactly as `ProductCategoryList.onCompanyChange` does (S2, D2).
   *
   * Changing company also discards the classification: the three selects and the SAT hint are reset
   * through `categoryDiscarded`, not as a side effect of the new tree happening not to contain the old
   * leaf.
   */
  onCompanyChange(companyId: string | null): void {
    const next = companyId || null;
    // S2, stated rather than inferred: the classification and the hint that described a node of the
    // previous tree are discarded by the operator's own action.
    this.categoryDiscarded.set(true);
    this.selectedCompanyId.set(next);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { companyId: next },
      queryParamsHandling: 'merge',
      replaceUrl: true,
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

  onSaveProduct(productData: ProductFormValue): void {
    // The wire body is built explicitly against `ProductRequest` (S4): the response model also
    // carries `id` and `enabled`, and the API rejects the body before validating any field.
    const request: ProductRequest = {
      sku: productData.sku,
      name: productData.name,
      shortName: productData.shortName,
      satCode: productData.satCode,
      productType: productData.productType,
      attributes: productData.attributes,
      // S3: the deepest selected node, or an explicit `null` — which is how the field is cleared.
      categoryId: productData.categoryId ?? null,
    };
    if (productData.id === '') {
      this.productService
        .createProduct(request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (createdProduct) => {
            if (this.isEditMode()) {
              // Not reachable from the create route: the create branch needs an empty
              // `id`, and the create route is the only one that renders the stepper. It
              // stays for the pre-existing edit-mode case where the product load failed
              // and the form still holds its empty-id initial value.
              this.productForm().markAsPristine();
              this.router.navigate(['/products/edit', createdProduct.id]);
              return;
            }
            this.adoptCreatedProduct(createdProduct);
            // After the id write-back, not before, so a write-back that ever started
            // marking the control dirty could not arm the guard on the way to step 2.
            this.productForm().markAsPristine();
          },
          error: (err: HttpErrorResponse) => {
            this.handleServerError(err);
          },
        });
    } else {
      this.productService
        .updateProduct(productData.id, request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (updatedProduct) => {
            // The header reads the entity, not the live form (it must not flicker while the
            // operator types), so a saved back-edit has to refresh it — otherwise the
            // stepper would keep showing the pre-edit name and SKU.
            this.product.set(updatedProduct);
            this.productForm().markAsPristine();
            if (this.isEditMode()) {
              // The route is guarded by `unsavedChangesGuard`; a successful save
              // must reach the list without the discard prompt firing.
              this.router.navigate(['/products']);
            }
            // In the create stepper this is a back-edit of step 1 (D26) and the operator
            // stays where they are: the step is the editing surface, and leaving would
            // discard the context they came back for.
          },
          error: (err: HttpErrorResponse) => {
            this.handleServerError(err);
          },
        });
    }
  }

  /**
   * Adopts the product the stepper's step 1 just created (D26).
   *
   * The id is written back into the form on purpose: `ProductsForm` derives its own
   * `isEditMode` from `controls.id.value`, so this single write flips the form's copy to its
   * edit register and makes a later step-1 save take the `updateProduct` branch above. The
   * caller marks the form pristine afterwards.
   */
  private adoptCreatedProduct(created: Product): void {
    this.product.set(created);
    this.productId.set(created.id);
    this.productForm().controls.id.setValue(created.id);
    this.stepIndex.set(1);
  }

  /** Moves the create stepper to a step; the step header and the footer both come through here. */
  goToStep(index: number): void {
    this.stepIndex.set(index);
  }

  /**
   * Post-persist exit (D27): the product exists, so leaving lands on its workspace. Before
   * the product exists the same affordance is the pre-persist exit and lands on the list.
   */
  finish(): void {
    const id = this.productId();
    this.router.navigate(id ? ['/products/edit', id] : ['/products']);
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
    // The edit workspace's Cancelar returns to the list, as it always has. The create
    // stepper's Cancelar is the pre-persist exit and shares the post-persist one (D27).
    if (this.isEditMode()) {
      this.router.navigate(['/products']);
      return;
    }
    this.finish();
  }

  /** Exposed to `unsavedChangesGuard`: the live form and the embedded panel decide. */
  hasUnsavedChanges(): boolean {
    return this.productForm().dirty || this.variantPanelDirty();
  }
}
