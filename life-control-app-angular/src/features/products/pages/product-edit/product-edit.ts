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
import { ActivatedRoute, Router, RouterLink, convertToParamMap } from '@angular/router';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { ProductService } from '../../data/product.service';
import { productsCompanyId } from '../../data/products-company';
import { ProductCategoryService } from '../../data/product-category.service';
import { MAX_CATEGORY_DEPTH, ProductCategory } from '../../models/product-category.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import {
  Product,
  ProductAttributeProfile,
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
    RouterLink,
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

  /**
   * Which company's tree feeds the form: `?companyId=`, seeded once and then kept in the URL.
   *
   * A signal and not a plain read, because on **create** the operator chooses it here — the form's
   * `Empresa` field is enabled (S3) — and the tree has to follow that pick without the router destroying
   * the component. `ProductCategoryList`'s shape: a signal seeded from the query parameter and written
   * back with `replaceUrl`.
   *
   * On **edit** it is simply what the URL said, and it is required: a product id alone cannot be
   * addressed on `/api/companies/{companyId}/products`, and the field is disabled because the product's
   * owner is fixed. `null` there is a stated error, not a company to invent (see the template).
   */
  readonly selectedCompanyId = signal<string | null>(productsCompanyId(this.route));

  /**
   * The company every read and write of this screen is scoped to: the chosen one.
   *
   * Two names on purpose, because they are two different facts: `selectedCompanyId` is the state the
   * operator can change on create, and this is the scope every request is sent under. It is also not a
   * response field — the product's own `companyId` proves the row belongs there, it does not decide the
   * screen, so a second read of it could only disagree with the URL.
   */
  readonly companyId = computed(() => this.selectedCompanyId());

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
   * The deepest category the form currently has selected, or `null` when it has none.
   *
   * Fed by the form's `categoryChange` and seeded from the loaded product, because the host cannot
   * derive it: the form writes the `categoryId` control with `emitEvent: false`, and
   * `savedCategoryPath` deliberately does not track control values (it would feed the form's seeding
   * effect with the pick itself).
   */
  readonly selectedCategoryId = signal<string | null>(null);

  /**
   * The attribute profile of the selected category (T4), read here and handed to the form as an input.
   *
   * The read is the host's because `categoryTree` is: the form is presentational and owns no HTTP. No
   * request is issued until both halves of the contract are known — the company that owns the
   * category and the category itself — which is also what keeps an unclassified product silent.
   */
  readonly attributeProfileResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      const categoryId = this.selectedCategoryId();
      return companyId && categoryId ? { companyId, categoryId } : undefined;
    },
    stream: ({ params }) =>
      this.productService.getAttributeProfile(params.companyId, params.categoryId),
  });

  /**
   * The profile, or `undefined` while it is not known.
   *
   * `undefined` and `null` are different answers and the form depends on the difference: `undefined`
   * is "no answer yet" (no category, a read in flight, or a failed one) and leaves the rendered
   * field-set alone, while `null` is the API's `204` — this branch declares none — and is what makes
   * the form say so. `value()` throws unless the read resolved, so it is never read unguarded.
   */
  readonly attributeProfile = computed<ProductAttributeProfile | null | undefined>(() =>
    this.attributeProfileResource.status() === 'resolved'
      ? (this.attributeProfileResource.value() ?? null)
      : undefined,
  );

  /** The profile read failed, in the shared copy; `null` while nothing failed. */
  readonly attributeProfileError = computed<string | null>(() => {
    const error = this.attributeProfileResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  /**
   * The saved classification as ids from the root to the deepest node (S6).
   *
   * Two answers, and the difference between them is the point:
   * - `null` — the host does not know yet (the tree is in flight, or its read failed). The form must
   *   leave itself alone; applying `[]` here would wipe the saved classification and the next save
   *   would send `categoryId: null` (F1).
   * - `[]` — the product is certainly unclassified (it has no `categoryId`).
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
    const companyId = this.companyId();
    if (!companyId) {
      // Nothing to read: `GET /api/companies/{companyId}/products/{id}` has no company-free form, and
      // the template states the missing company instead of rendering a form over a product nobody loaded.
      return;
    }
    this.productService
      .getProductById(companyId, id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (product) => {
          this.product.set(product);
          // The saved classification is the category whose profile the form needs, and it is known
          // before the tree arrives: the attribute inputs do not depend on the tree at all.
          this.selectedCategoryId.set(product.categoryId ?? null);
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
   * Keeps the create screen's company in the URL and lets the tree follow it.
   *
   * `replaceUrl`, exactly as `ProductCategoryList.onCompanyChange` does: the pick is not a step in a flow,
   * it is the same form under another company, and a history entry per pick would make the back button
   * walk the operator through every company they tried. `merge` so `?tab=` and `?storeId=` survive.
   *
   * On **edit** this does nothing at all. The previous shape navigated to another company's create
   * screen; now that the company is a query parameter, navigating is exactly what S3 removed — the
   * operator would lose the product they were editing. The field is disabled there anyway, so this is
   * the belt-and-braces path: a reported change must not move a saved product, nor discard the
   * classification the screen cannot carry to another company.
   */
  onCompanyChange(companyId: string | null): void {
    const next = companyId || null;
    if (this.productId() !== null) {
      return;
    }
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

  /**
   * Follows the form's own selection, so the attribute field-set is the one of the category it shows.
   *
   * Clearing the selection is a real answer: with no category there is no profile, and the read is not
   * even issued.
   */
  onCategoryChange(categoryId: string | null): void {
    this.selectedCategoryId.set(categoryId);
  }

  onSaveProduct(productData: ProductFormValue): void {
    // No company, no endpoint: every write is scoped to `/api/companies/{companyId}/products`, so this
    // refuses instead of guessing one. On create it is reachable only before the operator picks in the
    // form's `Empresa` field; on edit the screen states the missing company as soon as it loads.
    const companyId = this.companyId();
    if (!companyId) {
      this.generalError.set('Elegí una empresa antes de guardar el producto.');
      return;
    }

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
        .createProduct(companyId, request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (createdProduct) => {
            if (this.isEditMode()) {
              // Not reachable from the create route: the create branch needs an empty
              // `id`, and the create route is the only one that renders the stepper. It
              // stays for the pre-existing edit-mode case where the product load failed
              // and the form still holds its empty-id initial value.
              this.productForm().markAsPristine();
              void this.router.navigate(['/products/edit', createdProduct.id], {
                queryParams: { companyId },
              });
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
        .updateProduct(companyId, productData.id, request)
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
              void this.router.navigate(['/products/list'], { queryParams: { companyId } });
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
   *
   * Both destinations carry the company, because both are scoped screens (`/products/list` shows one
   * company's catalog). With no company there is nothing scoped to go to, and the hub is the one
   * destination that does not need one.
   */
  finish(): void {
    const id = this.productId();
    const companyId = this.companyId();
    if (!companyId) {
      void this.router.navigate(['/products']);
      return;
    }
    void this.router.navigate(id ? ['/products/edit', id] : ['/products/list'], {
      queryParams: { companyId },
    });
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
    // The edit workspace's Cancelar returns to the list, as it always has — with the company, because the
    // list is the catalog of one company. The create stepper's Cancelar is the pre-persist exit and
    // shares the post-persist one (D27).
    const companyId = this.companyId();
    if (this.isEditMode() && companyId) {
      void this.router.navigate(['/products/list'], { queryParams: { companyId } });
      return;
    }
    this.finish();
  }

  /** Exposed to `unsavedChangesGuard`: the live form and the embedded panel decide. */
  hasUnsavedChanges(): boolean {
    return this.productForm().dirty || this.variantPanelDirty();
  }
}
