import { ANIMATION_MODULE_TYPE } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import {
  ActivatedRoute,
  NavigationEnd,
  ParamMap,
  Router,
  convertToParamMap,
} from '@angular/router';
import { ReactiveFormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, Observable, of, Subject, throwError } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { MatTabGroup } from '@angular/material/tabs';
import { MatSelect } from '@angular/material/select';
import { MatStepper } from '@angular/material/stepper';
import { ProductEdit } from './product-edit';
import { Page, Product, ProductAttributeProfile } from '../../models/product.models';
import { ProductService } from '../../data/product.service';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { ProductSupplier } from '../../models/product-supplier.models';
import { ProductCategoryService } from '../../data/product-category.service';
import { ProductCategory } from '../../models/product-category.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanySelectorOption } from '@features/companies/companies/models/company.models';
import { ProductVariantService } from '../../data/product-variant.service';
import { ProductVariant } from '../../models/product-variant.models';
import { ProductSupplierList } from '../product-supplier-list/product-supplier-list';
import { ProductVariantList } from '../product-variant-list/product-variant-list';
import { ProductSupplierDialog } from '../../components/product-supplier-dialog/product-supplier-dialog';
import { ProductsForm } from '../../components/products-form/products-form';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { ProfileResponse } from '@features/user/profile/data/profile.models';

describe('ProductEdit', () => {
  let component: ProductEdit;
  let fixture: ComponentFixture<ProductEdit>;
  let productServiceMock: {
    createProduct: ReturnType<typeof vi.fn>;
    updateProduct: ReturnType<typeof vi.fn>;
    getProductById: ReturnType<typeof vi.fn>;
    getAttributeProfile: ReturnType<typeof vi.fn>;
  };
  let supplierServiceMock: {
    getSuppliers: ReturnType<typeof vi.fn>;
    removeSupplier: ReturnType<typeof vi.fn>;
  };
  let variantServiceMock: {
    getVariants: ReturnType<typeof vi.fn>;
    deleteVariant: ReturnType<typeof vi.fn>;
    enableVariant: ReturnType<typeof vi.fn>;
  };
  let profileServiceMock: { getProfile: ReturnType<typeof vi.fn> };
  let companyServiceMock: { getCompanySelector: ReturnType<typeof vi.fn> };
  let categoryServiceMock: { getCategories: ReturnType<typeof vi.fn> };
  let routerMock: {
    navigate: ReturnType<typeof vi.fn>;
    createUrlTree: ReturnType<typeof vi.fn>;
    serializeUrl: ReturnType<typeof vi.fn>;
    events: Subject<unknown>;
  };
  let dialogMock: { open: ReturnType<typeof vi.fn> };
  let queryParams$: BehaviorSubject<ParamMap>;
  /**
   * The path params the route's snapshot reports.
   *
   * A mutable map and not a constant, because the defect this suite pins is a **navigation that reuses
   * the instance**: the router activates the same route config (`edit/:id`) with another id, and the
   * snapshot the component reads has already moved on by the time `NavigationEnd` arrives.
   */
  let paramMapValues: Record<string, string>;
  /**
   * The deferred category tree, when a spec asks for one (`categoriesDeferred`).
   *
   * Exposed as a field so a spec can decide *when* the tree lands — the real sequence is product
   * first, tree second, and that ordering is exactly what F1 was about.
   */
  let tree$: Subject<ProductCategory[]>;
  let routeMock: {
    snapshot: {
      paramMap: { get: (key: string) => string | null };
      queryParamMap: { get: (key: string) => string | null };
    };
    queryParamMap: Observable<ParamMap>;
  };

  function createApiError(overrides: Partial<Record<string, unknown>> = {}): HttpErrorResponse {
    // `overrides.status` drives both the body and the top-level HTTP status, so a
    // 409 case reaches the handler through the same `HttpErrorResponse.status` a
    // real response sets.
    const status = typeof overrides['status'] === 'number' ? overrides['status'] : 400;

    return new HttpErrorResponse({
      error: {
        status: 400,
        message: 'Error de validación',
        errors: undefined,
        path: '/api/products',
        timestamp: '2026-06-01T20:00:00Z',
        correlationId: 'abc-123',
        ...overrides,
      },
      status,
      statusText: status === 409 ? 'Conflict' : 'Bad Request',
    });
  }

  /** The company of the route below: every read and write of this screen is scoped to it. */
  const companyId = 'company-1';

  function createProductData(overrides: Partial<Product> = {}): Product {
    return {
      id: '',
      sku: 'SKU-001',
      name: 'Test Product',
      enabled: true,
      createdAt: '',
      updatedAt: '',
      companyId,
      ...overrides,
    };
  }

  function createSupplier(index: number): ProductSupplier {
    return {
      id: `ps-${index}`,
      productId: 'existing-id',
      supplierId: `sup-${index}`,
      supplierName: `Supplier ${index}`,
      purchaseCost: 100 + index,
      main: index === 0,
      enabled: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
  }

  function createVariant(index: number): ProductVariant {
    return {
      id: `var-${index}`,
      productId: 'existing-id',
      companyStoreId: null,
      barCode: `7790000000${index}`,
      sku: 'SKU-001',
      variantName: `Talla ${index}`,
      listPrice: null,
      costPrice: null,
      stock: null,
      enabled: true,
      measureUnitId: null,
    };
  }

  function createCategory(overrides: Partial<ProductCategory> = {}): ProductCategory {
    return {
      id: 'root-1',
      companyId: 'company-1',
      parentCategoryId: null,
      categoryName: 'Alimentos',
      description: null,
      satCode: null,
      displayOrder: 0,
      depth: 1,
      enabled: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      ...overrides,
    };
  }

  /** The category tree of `company-1`, one full path deep (root -> level 1 -> level 2). */
  function createCategoryTree(): ProductCategory[] {
    return [
      createCategory({ id: 'root-1', categoryName: 'Alimentos', satCode: '11111111' }),
      createCategory({
        id: 'sub1-1',
        parentCategoryId: 'root-1',
        categoryName: 'Bebidas',
        depth: 2,
      }),
      createCategory({
        id: 'sub2-1',
        parentCategoryId: 'sub1-1',
        categoryName: 'Jugos',
        satCode: '22222222',
        depth: 3,
      }),
    ];
  }

  function createCompanyPage(): Page<CompanySelectorOption> {
    return {
      content: [{ id: 'company-1', companyKey: 'lc', companyName: 'Life Control' }],
      totalElements: 1,
      totalPages: 1,
      size: 1,
      number: 0,
      first: true,
      last: true,
      empty: false,
    };
  }

  function createPage(variants: ProductVariant[], totalElements: number): Page<ProductVariant> {
    return {
      content: variants,
      totalElements,
      totalPages: 1,
      size: 12,
      number: 0,
      first: true,
      last: true,
      empty: variants.length === 0,
    };
  }

  function profileResponse(companyStoreId: string | null): ProfileResponse {
    return {
      keycloakUserId: 'user-1',
      username: 'operator',
      email: 'operator@lifecontrol.test',
      firstName: 'Oper',
      lastName: 'Ator',
      companyStoreId,
      assignedStores: null,
      location: companyStoreId
        ? {
            companyId: 'company-1',
            companyCountryId: 'country-1',
            companyRegionId: 'region-1',
            companyZoneId: 'zone-1',
            companyStoreId,
            companyStoreName: 'Store One',
          }
        : null,
    };
  }

  interface SetupOptions {
    edit?: boolean;
    product?: Product;
    /**
     * The company the link names, `?companyId=`.
     *
     * Defaults to {@link companyId} because every existing case is a scoped link; `null` builds a link
     * that names none, which is a state this screen has to answer for (S1/S3).
     */
    companyId?: string | null;
    queryParams?: Record<string, string>;
    suppliers?: ProductSupplier[];
    variants?: ProductVariant[];
    variantTotalElements?: number;
    profileStoreId?: string | null;
    categories?: ProductCategory[];
    /** Leaves `GET /product-categories` in flight until the spec pushes onto `tree$`. */
    categoriesDeferred?: boolean;
    /** Fails `GET /product-categories` with a 500, the shape `categoriesResource` keeps as an error. */
    categoriesFailing?: boolean;
    /** The attribute profile the product's category resolves to; `null` is the API's `204`. */
    attributeProfile?: ProductAttributeProfile | null;
  }

  function setup(options: SetupOptions = {}): void {
    const edit = options.edit ?? false;
    const productId = edit ? 'existing-id' : null;
    const product = options.product ?? createProductData({ id: 'existing-id' });
    const suppliers = options.suppliers ?? [createSupplier(0), createSupplier(1)];
    const variants = options.variants ?? [createVariant(0), createVariant(1)];
    const variantTotalElements = options.variantTotalElements ?? variants.length;

    const scopedCompanyId = options.companyId === undefined ? companyId : options.companyId;
    const initialQueryParams: Record<string, string> = { ...(options.queryParams ?? {}) };
    if (scopedCompanyId) {
      initialQueryParams['companyId'] = scopedCompanyId;
    } else {
      delete initialQueryParams['companyId'];
    }

    queryParams$ = new BehaviorSubject<ParamMap>(convertToParamMap(initialQueryParams));
    paramMapValues = productId ? { id: productId } : {};

    routeMock = {
      snapshot: {
        // Only `id` is a path param any more: the company travels as `?companyId=`, so a read that still
        // expected a `:companyId` segment could not produce one here — which is what makes these
        // assertions pin the source instead of the value.
        paramMap: {
          get: (key: string) => paramMapValues[key] ?? null,
        },
        queryParamMap: { get: (key: string) => queryParams$.value.get(key) },
      },
      queryParamMap: queryParams$.asObservable(),
    };

    productServiceMock = {
      createProduct: vi.fn(),
      updateProduct: vi.fn(),
      getProductById: vi.fn().mockReturnValue(of(product)),
      getAttributeProfile: vi.fn().mockReturnValue(of(options.attributeProfile ?? null)),
    };
    supplierServiceMock = {
      getSuppliers: vi.fn().mockReturnValue(of(suppliers)),
      removeSupplier: vi.fn(),
    };
    variantServiceMock = {
      getVariants: vi.fn().mockReturnValue(of(createPage(variants, variantTotalElements))),
      deleteVariant: vi.fn(),
      enableVariant: vi.fn(),
    };
    profileServiceMock = {
      getProfile: vi.fn().mockReturnValue(of(profileResponse(options.profileStoreId ?? null))),
    };
    companyServiceMock = { getCompanySelector: vi.fn().mockReturnValue(of(createCompanyPage())) };
    tree$ = new Subject<ProductCategory[]>();
    categoryServiceMock = {
      getCategories: options.categoriesFailing
        ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })))
        : options.categoriesDeferred
          ? vi.fn().mockReturnValue(tree$.asObservable())
          : vi.fn().mockReturnValue(of(options.categories ?? [])),
    };
    routerMock = {
      navigate: vi.fn(),
      // `RouterLink` asks the router for the URL tree so it can render an `href`. Only the "no company"
      // state uses it, and the navigation assertions still read the spy, so this only has to be a value
      // the renderer can write.
      createUrlTree: vi.fn((commands: unknown[]) => commands.join('/')),
      serializeUrl: vi.fn((url: string) => url),
      events: new Subject<unknown>(),
    };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(false) }) };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ProductEdit, ReactiveFormsModule],
      providers: [
        // `NoopAnimationsModule` went away with `@angular/animations` in v22, and what it did for
        // Material's tab group was set this token to `NoopAnimations`. Without it `MatTabBody`
        // waits for a real CSS `transitionstart` (with a 100ms fallback timer) before it attaches
        // the selected tab's content, and jsdom never fires one, so the tab body stayed empty.
        { provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations' },
        { provide: ProductService, useValue: productServiceMock },
        { provide: ProductSupplierService, useValue: supplierServiceMock },
        { provide: ProductVariantService, useValue: variantServiceMock },
        { provide: ProfileService, useValue: profileServiceMock },
        { provide: CompanyService, useValue: companyServiceMock },
        { provide: ProductCategoryService, useValue: categoryServiceMock },
        { provide: Router, useValue: routerMock },
        { provide: MatDialog, useValue: dialogMock },
        { provide: ActivatedRoute, useValue: routeMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  /**
   * Settles the reads and the renders. Several flushes on purpose: the shell's
   * child resources load on later flushes, and the tab group emits
   * `selectedIndexChange` in a microtask.
   */
  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i += 1) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  /**
   * Settles the render without waiting for pending tasks.
   *
   * `settle()` cannot be used with a deliberately open request: `whenStable()` waits for it and the spec
   * times out. This flushes change detection (which is what runs the effects) over a few microtasks
   * instead.
   */
  async function settleRender(): Promise<void> {
    for (let i = 0; i < 5; i += 1) {
      fixture.detectChanges();
      await Promise.resolve();
    }
    fixture.detectChanges();
  }

  function tabHeaders(f: ComponentFixture<ProductEdit>): HTMLElement[] {
    return Array.from(f.nativeElement.querySelectorAll('[role="tab"]')) as HTMLElement[];
  }

  function tabLabels(f: ComponentFixture<ProductEdit>): string[] {
    return tabHeaders(f).map((tab) =>
      (tab.querySelector('.mdc-tab__text-label')?.textContent ?? '').trim(),
    );
  }

  function activeTabLabel(f: ComponentFixture<ProductEdit>): string | null {
    const active = f.nativeElement.querySelector(
      '[role="tab"][aria-selected="true"]',
    ) as HTMLElement | null;
    return active ? (active.querySelector('.mdc-tab__text-label')?.textContent ?? '').trim() : null;
  }

  function clickTab(f: ComponentFixture<ProductEdit>, index: number): void {
    tabHeaders(f)[index]?.click();
  }

  /** The step header labels, in stepper order. */
  function stepLabels(f: ComponentFixture<ProductEdit>): string[] {
    return Array.from<Element>(f.nativeElement.querySelectorAll('mat-step-header')).map((header) =>
      (header.querySelector('.mat-step-label')?.textContent ?? '').trim(),
    );
  }

  /** The footer buttons of whichever step is currently instantiated. */
  function stepActionButtons(f: ComponentFixture<ProductEdit>): HTMLButtonElement[] {
    return Array.from(
      f.nativeElement.querySelectorAll('.step-actions button'),
    ) as HTMLButtonElement[];
  }

  function stepActionLabels(f: ComponentFixture<ProductEdit>): string[] {
    return stepActionButtons(f).map((button) => (button.textContent ?? '').trim());
  }

  /** The products form's own title, which is what its `isEditMode` drives. */
  function formTitle(f: ComponentFixture<ProductEdit>): string {
    return (f.nativeElement.querySelector('app-products-form h2')?.textContent ?? '').trim();
  }

  /** The products form's own submit label, driven by the same `isEditMode`. */
  function formSubmitLabel(f: ComponentFixture<ProductEdit>): string {
    return (
      f.nativeElement.querySelector('app-products-form button[type="submit"]')?.textContent ?? ''
    ).trim();
  }

  /**
   * The completion state of the three steps, in order (D28's `[completed]` wiring).
   *
   * Read from the stepper's own `steps` query, not from the DOM: `MatStepper` renders its
   * projected content only on the server, so the `mat-step` elements are not in the client
   * tree at all — only the headers its own template generates are.
   */
  function stepCompletion(f: ComponentFixture<ProductEdit>): boolean[] {
    const stepper = f.debugElement.query(By.directive(MatStepper)).componentInstance as MatStepper;
    return stepper.steps.toArray().map((step) => step.completed);
  }

  /** Persists step 1 the way the form does, so the stepper adopts a real created product. */
  function createProductThroughStepOne(overrides: Partial<Product> = {}): Product {
    const createdProduct = createProductData({ id: 'new-id', ...overrides });
    productServiceMock.createProduct = vi.fn().mockReturnValue(of(createdProduct));
    component.onSaveProduct(createProductData());
    return createdProduct;
  }

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should be in create mode when no id param', () => {
    setup();
    expect(component.isEditMode()).toBe(false);
    expect(component.productId()).toBeNull();
  });

  it('should be in edit mode when id param exists', () => {
    setup({ edit: true });
    expect(component.isEditMode()).toBe(true);
    expect(component.productId()).toBe('existing-id');
  });

  it('should load product in edit mode', async () => {
    setup({
      edit: true,
      product: createProductData({ id: 'existing-id', name: 'Existing Product' }),
    });
    await settle();

    expect(productServiceMock.getProductById).toHaveBeenCalledWith(companyId, 'existing-id');
    expect(component.productForm().controls.name.value).toBe('Existing Product');
  });

  it('should adopt the created product and advance instead of navigating (D26)', () => {
    setup();
    const createdProduct = createProductData({ id: 'new-id', name: 'Zapatilla Runner' });
    productServiceMock.createProduct = vi.fn().mockReturnValue(of(createdProduct));

    component.onSaveProduct(createProductData());

    expect(component.productId()).toBe('new-id');
    expect(component.product()?.name).toBe('Zapatilla Runner');
    // The id write-back is what makes a later step-1 save take the `updateProduct` branch:
    // `onSaveProduct` reads `productData.id`, which comes from this control. The form's
    // *copy* does not follow it — a `computed` cannot track a plain property — so that comes
    // from the `editMode` input instead, pinned by its own test below.
    expect(component.productForm().controls.id.value).toBe('new-id');
    expect(component.stepIndex()).toBe(1);
    expect(routerMock.navigate).not.toHaveBeenCalled();
  });

  it('should save step 1 with PUT once the product exists (D26)', () => {
    setup();
    const createdProduct = createProductData({ id: 'new-id' });
    productServiceMock.createProduct = vi.fn().mockReturnValue(of(createdProduct));
    productServiceMock.updateProduct = vi.fn().mockReturnValue(of(createdProduct));

    component.onSaveProduct(createProductData());
    component.onSaveProduct(createProductData({ id: 'new-id', name: 'Renombrado' }));

    expect(productServiceMock.updateProduct).toHaveBeenCalledWith(
      companyId,
      'new-id',
      expect.objectContaining({ name: 'Renombrado' }),
    );
    // A back-edit on step 1 is not an exit: the operator stays in the stepper.
    expect(routerMock.navigate).not.toHaveBeenCalled();
  });

  it('should navigate to the product list on update success', () => {
    setup({ edit: true });
    productServiceMock.updateProduct = vi.fn().mockReturnValue(of({} as Product));

    component.onSaveProduct(createProductData({ id: 'existing-id' }));
    // The list is a scoped screen, so leaving the workspace carries the company the product belongs to.
    expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list'], {
      queryParams: { companyId },
    });
  });

  it('should navigate to the product list on cancel in create mode', () => {
    setup();
    component.cancelForm();
    expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list'], {
      queryParams: { companyId },
    });
  });

  it('should keep the edit workspace cancel going to the list, not to its own URL', () => {
    setup({ edit: true });
    component.cancelForm();
    expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list'], {
      queryParams: { companyId },
    });
  });

  describe('hasUnsavedChanges', () => {
    it('should report false right after create-mode construction', () => {
      setup();
      expect(component.productForm().pristine).toBe(true);
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should stay pristine after a programmatic load in edit mode', async () => {
      setup({ edit: true });
      await settle();

      expect(component.productForm().pristine).toBe(true);
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should report true after a user edit', () => {
      setup();
      // `setValue` alone does not mark a reactive control dirty in Angular 20;
      // a real user edit both changes the value and marks the control dirty.
      const sku = component.productForm().get('sku');
      sku?.setValue('X');
      sku?.markAsDirty();

      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should clear the flag after a successful update', () => {
      setup({ edit: true });
      productServiceMock.updateProduct = vi.fn().mockReturnValue(of({} as Product));

      component.productForm().get('sku')?.markAsDirty();
      // Precondition: the form must be dirty for `markAsPristine` to be load-bearing.
      expect(component.hasUnsavedChanges()).toBe(true);
      component.onSaveProduct(createProductData({ id: 'existing-id' }));

      expect(component.hasUnsavedChanges()).toBe(false);
      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list'], {
        queryParams: { companyId },
      });
    });

    it('should clear the flag after a successful create', () => {
      setup();
      const createdProduct = createProductData({ id: 'new-id' });
      productServiceMock.createProduct = vi.fn().mockReturnValue(of(createdProduct));

      component.productForm().get('sku')?.markAsDirty();
      // Precondition: the form must be dirty for `markAsPristine` to be load-bearing.
      expect(component.hasUnsavedChanges()).toBe(true);
      component.onSaveProduct(createProductData());

      expect(component.hasUnsavedChanges()).toBe(false);
      // Post-persist there is nothing unsaved, so the guard cannot prompt on the way to
      // step 2 (T12/D27) — and the stepper advances rather than navigating.
      expect(component.stepIndex()).toBe(1);
      expect(routerMock.navigate).not.toHaveBeenCalled();
    });
  });

  describe('create mode is the three-step stepper', () => {
    it('should render the stepper instead of the tab strip', () => {
      setup();
      const el = fixture.nativeElement as HTMLElement;

      expect(el.querySelector('mat-stepper')).not.toBeNull();
      expect(el.querySelector('mat-tab-group')).toBeNull();
    });

    it('should render the three steps, the two association ones marked optional (D24)', () => {
      setup();

      expect(stepLabels(fixture)).toEqual([
        'Datos',
        'Proveedores (opcional)',
        'Variantes (opcional)',
      ]);
    });

    it('should render the products form in step 1 and no page header yet (D29)', () => {
      setup();
      const el = fixture.nativeElement as HTMLElement;

      expect(el.querySelector('app-products-form')).not.toBeNull();
      // Before the product exists the form's own `<h2>` is the only title on screen.
      expect(el.querySelector('app-page-header')).toBeNull();
    });

    it('should still render the error banner when a general error is set', () => {
      setup();
      component.generalError.set('Algo salió mal');
      fixture.detectChanges();

      const banner = fixture.nativeElement.querySelector('app-error-banner') as HTMLElement | null;
      expect(banner).not.toBeNull();
      expect(banner?.textContent?.trim()).toBe('Algo salió mal');
    });

    it('should show the product header once the product exists (D29)', () => {
      setup();
      createProductThroughStepOne({ name: 'Zapatilla Runner', sku: 'ZAP-001' });
      fixture.detectChanges();

      const header = fixture.nativeElement.querySelector('app-page-header') as HTMLElement | null;
      expect(header).not.toBeNull();
      expect(header?.textContent).toContain('Zapatilla Runner');
      expect(header?.textContent).toContain('SKU: ZAP-001');
    });

    it('should not instantiate the association containers before the product exists (D28)', async () => {
      setup();
      component.goToStep(1);
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('app-product-supplier-list')).toBeNull();
      expect(el.querySelector('.step-hint')?.textContent?.trim()).toBe(
        'Primero guardá los datos del producto.',
      );
    });

    it('should mark step 1 completed only once the product exists (D28)', async () => {
      setup();
      // Leaving step 1 without saving already marks it `interacted`, and a step with no
      // `stepControl` counts as completed once interacted. So *this* state — step 2 opened
      // before the product exists — is where the `[completed]` binding is load-bearing
      // instead of agreeing with the CDK's own derivation.
      component.goToStep(1);
      await settle();
      expect(stepCompletion(fixture)).toEqual([false, false, false]);

      createProductThroughStepOne();
      await settle();
      expect(stepCompletion(fixture)).toEqual([true, false, false]);
    });

    it('should flip the form copy to edit once the product exists (D26)', () => {
      setup();
      expect(formTitle(fixture)).toBe('Nuevo Producto');
      expect(formSubmitLabel(fixture)).toBe('Guardar');

      createProductThroughStepOne();
      fixture.detectChanges();

      // The copy and the behaviour have to agree: the next save is a PUT, so the form must
      // not keep saying "Nuevo Producto" beside a "Guardar" button.
      expect(formTitle(fixture)).toBe('Editar Producto');
      expect(formSubmitLabel(fixture)).toBe('Actualizar');
    });

    it('should refresh the product header after a back-edit save', () => {
      setup();
      createProductThroughStepOne({ name: 'Zapatilla Runner', sku: 'ZAP-001' });
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('app-page-header')?.textContent).toContain(
        'Zapatilla Runner',
      );

      productServiceMock.updateProduct = vi
        .fn()
        .mockReturnValue(
          of(createProductData({ id: 'new-id', name: 'Renombrado', sku: 'ZAP-002' })),
        );

      component.onSaveProduct(createProductData({ id: 'new-id', name: 'Renombrado' }));
      fixture.detectChanges();

      // The header reads the entity, not the live form, so a save has to refresh it or the
      // stepper keeps showing the pre-edit identity.
      const header = fixture.nativeElement.querySelector('app-page-header') as HTMLElement | null;
      expect(header?.textContent).toContain('Renombrado');
      expect(header?.textContent).toContain('SKU: ZAP-002');
    });

    it('should render the step-2 footer with Terminar as the skip affordance (D24)', async () => {
      setup();
      createProductThroughStepOne();
      await settle();

      expect(stepActionLabels(fixture)).toEqual(['Atrás', 'Terminar', 'Siguiente']);
    });

    it('should exit to the workspace when the step-2 Terminar button is clicked (D27)', async () => {
      setup();
      createProductThroughStepOne();
      await settle();

      const terminate = stepActionButtons(fixture).find(
        (button) => (button.textContent ?? '').trim() === 'Terminar',
      );
      terminate?.click();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/edit', 'new-id'], {
        queryParams: { companyId },
      });
    });

    it('should navigate to the list on Terminar before the product exists (D27)', () => {
      setup();
      component.finish();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list'], {
        queryParams: { companyId },
      });
    });

    it('should navigate to the workspace on Cancelar once the product exists (D27)', () => {
      setup();
      createProductThroughStepOne();
      component.cancelForm();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/edit', 'new-id'], {
        queryParams: { companyId },
      });
    });

    it('should report unsaved changes after a back-edit on step 1 (T12)', () => {
      setup();
      createProductThroughStepOne();
      expect(component.hasUnsavedChanges()).toBe(false);

      const sku = component.productForm().get('sku');
      sku?.setValue('OTRO');
      sku?.markAsDirty();

      expect(component.hasUnsavedChanges()).toBe(true);
    });
  });

  describe('the association steps reuse the workspace containers (D24/T11)', () => {
    it('should not instantiate an unvisited association step even after the product exists (D28)', async () => {
      setup();
      // The product now exists and step 2 is selected, so the `@if (productId())` guard
      // inside step 3 would already pass: what keeps its container out is the laziness of
      // `matStepContent`, and nothing else.
      createProductThroughStepOne();
      await settle();

      expect(fixture.nativeElement.querySelector('app-product-variant-list')).toBeNull();
    });

    it('should host the real supplier container in step 2 with the created product id', async () => {
      setup();
      createProductThroughStepOne();
      await settle();

      const list = fixture.debugElement.query(By.directive(ProductSupplierList));
      expect(list).not.toBeNull();
      expect(list.componentInstance.productId()).toBe('new-id');
    });

    it('should host the real variant container in step 3 with the created product id', async () => {
      setup();
      createProductThroughStepOne();
      component.goToStep(2);
      await settle();

      const list = fixture.debugElement.query(By.directive(ProductVariantList));
      expect(list).not.toBeNull();
      expect(list.componentInstance.productId()).toBe('new-id');
    });

    it('should let the step container own the dialog, receiving the created product id', async () => {
      setup();
      createProductThroughStepOne();
      await settle();

      const list = fixture.debugElement.query(By.directive(ProductSupplierList));
      list.componentInstance.addSupplier();

      expect(dialogMock.open).toHaveBeenCalledWith(
        ProductSupplierDialog,
        expect.objectContaining({
          data: { companyId, productId: 'new-id', assignment: undefined },
        }),
      );
    });

    it('should not reload the step list when the dialog closes with null (T11/D22)', async () => {
      setup();
      createProductThroughStepOne();
      await settle();

      const readsBefore = supplierServiceMock.getSuppliers.mock.calls.length;
      // `null` is the close contract for a cancelled or failed association, so the
      // failed item never reaches the step's list and nothing is rolled back.
      dialogMock.open.mockReturnValue({ afterClosed: () => of(null) });

      fixture.debugElement.query(By.directive(ProductSupplierList)).componentInstance.addSupplier();
      await settle();

      expect(supplierServiceMock.getSuppliers.mock.calls.length).toBe(readsBefore);
    });

    it('should reload the step list when the dialog closes with a saved entity', async () => {
      setup();
      createProductThroughStepOne();
      await settle();

      const readsBefore = supplierServiceMock.getSuppliers.mock.calls.length;
      dialogMock.open.mockReturnValue({
        afterClosed: () => of(createSupplier(0)),
      });

      fixture.debugElement.query(By.directive(ProductSupplierList)).componentInstance.addSupplier();
      await settle();

      expect(supplierServiceMock.getSuppliers.mock.calls.length).toBe(readsBefore + 1);
    });
  });

  describe('edit mode is the product workspace', () => {
    it('should render the page header with the loaded product name and SKU', async () => {
      setup({
        edit: true,
        product: createProductData({ id: 'existing-id', name: 'Zapatilla Runner', sku: 'ZAP-001' }),
      });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('app-page-header .page-title')?.textContent?.trim()).toBe(
        'Zapatilla Runner',
      );
      expect(el.querySelector('app-page-header .page-subtitle')?.textContent?.trim()).toBe(
        'SKU: ZAP-001',
      );
    });

    it('should render the three tab labels with the counts the children report', async () => {
      setup({
        edit: true,
        suppliers: [createSupplier(0), createSupplier(1)],
        variantTotalElements: 5,
      });
      await settle();

      expect(tabLabels(fixture)).toEqual(['Datos', 'Proveedores (2)', 'Variantes (5)']);
    });

    it('should carry the child counts before either association tab is activated', async () => {
      setup({
        edit: true,
        suppliers: [createSupplier(0), createSupplier(1)],
        variantTotalElements: 5,
      });
      await settle();

      // Contract 3: eager instantiation. Datos is the active tab and neither association
      // tab was clicked, yet both labels already carry the counts their children emitted.
      // A lazy tab strip would render `Proveedores (0)` / `Variantes (0)` here.
      expect(activeTabLabel(fixture)).toBe('Datos');
      expect(tabLabels(fixture)[1]).toBe('Proveedores (2)');
      expect(tabLabels(fixture)[2]).toBe('Variantes (5)');
    });

    it('should update a tab label when its child reports a new count', async () => {
      setup({ edit: true });
      await settle();

      // The child content is only in the host debug tree once its tab is the active one
      // (an inactive tab body is a detached portal), so activate each tab before driving
      // its output.
      clickTab(fixture, 1);
      fixture.detectChanges();
      await fixture.whenStable();

      const supplierList = fixture.debugElement.query(By.directive(ProductSupplierList));
      expect(supplierList).not.toBeNull();
      supplierList?.componentInstance.countChange.emit(7);
      fixture.detectChanges();
      expect(tabLabels(fixture)[1]).toBe('Proveedores (7)');

      clickTab(fixture, 2);
      fixture.detectChanges();
      await fixture.whenStable();

      const variantList = fixture.debugElement.query(By.directive(ProductVariantList));
      expect(variantList).not.toBeNull();
      variantList?.componentInstance.countChange.emit(9);
      fixture.detectChanges();
      expect(tabLabels(fixture)[2]).toBe('Variantes (9)');
    });
  });

  describe('the route names another product (S1)', () => {
    /**
     * The reported defect, as a navigation: `edit/:id` is the same route config for every product, so
     * the router **reuses this instance**. Reading the id and the company from a snapshot left the
     * workspace pointing at the first product forever (record `products-company-filter`, L5).
     */
    function navigateTo(id: string, companyId: string): void {
      paramMapValues = { id };
      queryParams$.next(convertToParamMap({ companyId }));
      routerMock.events.next(
        new NavigationEnd(1, '/products/edit/next', `/products/edit/next?companyId=${companyId}`),
      );
    }

    it('should load the product the new id and company address, and render it', () => {
      setup({ edit: true });
      productServiceMock.getProductById.mockClear();
      productServiceMock.getProductById.mockReturnValue(
        of(createProductData({ id: 'next-id', name: 'Segundo Producto', sku: 'SKU-002' })),
      );

      navigateTo('next-id', 'company-2');

      // One request, under the company the route names — not the one the first activation saw.
      expect(productServiceMock.getProductById).toHaveBeenCalledTimes(1);
      expect(productServiceMock.getProductById).toHaveBeenCalledWith('company-2', 'next-id');
      expect(component.productId()).toBe('next-id');
      expect(component.selectedCompanyId()).toBe('company-2');
      expect(component.product()?.sku).toBe('SKU-002');
      expect(component.productForm().controls.sku.value).toBe('SKU-002');
    });

    it('should not reload the product when only the tab the route names changes', async () => {
      setup({ edit: true, queryParams: { tab: 'datos' } });
      await settle();
      expect(productServiceMock.getProductById).toHaveBeenCalledTimes(1);

      paramMapValues = { id: 'existing-id' };
      queryParams$.next(convertToParamMap({ companyId, tab: 'variantes' }));
      routerMock.events.next(
        new NavigationEnd(1, '/products/edit/existing-id', '/products/edit/existing-id'),
      );
      await settle();

      // The product is the screen's identity; the tab is not. The same id under the same company is
      // the same product, so a `?tab=` write must not spend a second read.
      expect(productServiceMock.getProductById).toHaveBeenCalledTimes(1);
      expect(activeTabLabel(fixture)).toBe('Variantes (2)');
    });
  });

  describe('?tab= drives the selected tab', () => {
    it('should select Datos by default when the param is missing', async () => {
      setup({ edit: true });
      await settle();

      expect(activeTabLabel(fixture)).toBe('Datos');
    });

    it('should select the Proveedores tab for ?tab=proveedores', async () => {
      setup({ edit: true, queryParams: { tab: 'proveedores' } });
      await settle();

      expect(activeTabLabel(fixture)).toBe('Proveedores (2)');
    });

    it('should select the Variantes tab for ?tab=variantes', async () => {
      setup({ edit: true, queryParams: { tab: 'variantes' } });
      await settle();

      expect(activeTabLabel(fixture)).toBe('Variantes (2)');
    });

    it('should fall back to Datos for an unrecognised value', async () => {
      setup({ edit: true, queryParams: { tab: 'no-existe' } });
      await settle();

      expect(activeTabLabel(fixture)).toBe('Datos');
    });
  });

  describe('tab navigation writes ?tab=', () => {
    it('should navigate with the new tab, relativeTo the route, preserving storeId and the company', async () => {
      setup({ edit: true, queryParams: { storeId: 'store-1' } });
      await settle();

      clickTab(fixture, 1);
      fixture.detectChanges();
      await fixture.whenStable();

      expect(routerMock.navigate).toHaveBeenCalledTimes(1);
      const [commands, extras] = routerMock.navigate.mock.calls[0] as [
        unknown[],
        { relativeTo: unknown; queryParams: Record<string, string> },
      ];
      expect(commands).toEqual([]);
      expect(extras.relativeTo).toBe(TestBed.inject(ActivatedRoute));
      // The company survives the tab switch: the `Proveedores` and `Variantes` tabs are scoped to it, so
      // dropping it would send the operator to a workspace that cannot read its own product.
      expect(extras.queryParams).toEqual({
        storeId: 'store-1',
        companyId,
        tab: 'proveedores',
      });
    });

    it('should not re-navigate when the requested tab already matches the param', async () => {
      setup({ edit: true, queryParams: { tab: 'proveedores' } });
      await settle();

      const tabGroup = fixture.debugElement.query(By.directive(MatTabGroup));

      // Positive control: a different tab does navigate, so the handler is really wired
      // and this test cannot pass because the output never reached it.
      tabGroup.triggerEventHandler('selectedIndexChange', 2);
      fixture.detectChanges();
      await fixture.whenStable();
      expect(routerMock.navigate).toHaveBeenCalledTimes(1);

      // The guard: the tab that already matches the param must not navigate again.
      routerMock.navigate.mockClear();
      tabGroup.triggerEventHandler('selectedIndexChange', 1);
      fixture.detectChanges();
      await fixture.whenStable();
      expect(routerMock.navigate).not.toHaveBeenCalled();
    });

    it('should not re-navigate when a query-param change selects the tab reactively', async () => {
      setup({ edit: true, queryParams: { tab: 'variantes' } });
      await settle();
      expect(activeTabLabel(fixture)).toBe('Variantes (2)');
      expect(routerMock.navigate).not.toHaveBeenCalled();

      const handlerSpy = vi.spyOn(component, 'onTabChange');
      queryParams$.next(convertToParamMap({ tab: 'proveedores' }));
      await settle();

      // The reactive change really did drive the tab group, which emitted
      // `selectedIndexChange`; the guard is what keeps it from navigating back.
      expect(handlerSpy).toHaveBeenCalledWith(1);
      expect(activeTabLabel(fixture)).toBe('Proveedores (2)');
      expect(routerMock.navigate).not.toHaveBeenCalled();
    });
  });

  describe('request body', () => {
    /**
     * The API refuses the body *before* it validates any field — it is configured with
     * `spring.jackson.deserialization.fail-on-unknown-properties=true` — and answers
     * `400 Malformed or unreadable request body`. So the body has to carry exactly what
     * `ProductRequest` declares: never `id`, `enabled`, `createdAt` or `updatedAt`.
     */
    const requestFields = {
      sku: 'SKU-777',
      name: 'Zapatilla Runner',
      shortName: 'Runner',
      satCode: '12345678',
      productType: 'PRODUCT',
      attributes: { color: 'red' },
    };

    it('sends only the fields ProductRequest declares when creating', () => {
      setup();
      const response = createProductData({ id: 'new-id' });
      productServiceMock.createProduct = vi.fn().mockReturnValue(of(response));

      component.onSaveProduct(createProductData({ id: '', ...requestFields }));

      expect(productServiceMock.createProduct).toHaveBeenCalledTimes(1);
      expect(productServiceMock.createProduct.mock.calls[0][1]).toStrictEqual({
        ...requestFields,
        // S3: no selection travels as an explicit `null`, which is how the link is cleared.
        categoryId: null,
      });
    });

    it('sends only the fields ProductRequest declares when updating', () => {
      setup();
      const response = createProductData({ id: 'existing-id' });
      productServiceMock.updateProduct = vi.fn().mockReturnValue(of(response));

      component.onSaveProduct(createProductData({ id: 'existing-id', ...requestFields }));

      expect(productServiceMock.updateProduct).toHaveBeenCalledTimes(1);
      // `updateProduct(companyId, id, data)`: the body is the last argument.
      expect(productServiceMock.updateProduct.mock.calls[0][2]).toStrictEqual({
        ...requestFields,
        categoryId: null,
      });
    });

    it('carries the selected category id in the body', () => {
      setup({ edit: true, categories: createCategoryTree() });
      const response = createProductData({ id: 'existing-id' });
      productServiceMock.updateProduct = vi.fn().mockReturnValue(of(response));

      component.onSaveProduct(
        createProductData({ id: 'existing-id', ...requestFields, categoryId: 'sub2-1' }),
      );

      expect(productServiceMock.updateProduct.mock.calls[0][2]).toStrictEqual({
        ...requestFields,
        categoryId: 'sub2-1',
      });
    });
  });

  describe('product category classification (S1-S8)', () => {
    it('should resolve a saved path through the company of the link, keeping the saved SAT code (S6, S8, S9)', async () => {
      setup({
        edit: true,
        // The company is the link's (`?companyId=`): that is the only source, and the product's own
        // `companyId` is what proves the row belongs to it.
        companyId: 'company-7',
        product: createProductData({
          id: 'existing-id',
          categoryId: 'sub2-1',
          satCode: '99999999',
        }),
        categories: createCategoryTree(),
      });
      await settle();

      // The query parameter decides the tree, without navigating, and the tree is read including the
      // disabled nodes (S8).
      expect(component.selectedCompanyId()).toBe('company-7');
      expect(categoryServiceMock.getCategories).toHaveBeenLastCalledWith('company-7', true);
      expect(routerMock.navigate).not.toHaveBeenCalled();

      // The three selects hold the saved ids, root -> deepest (S6).
      expect(component.savedCategoryPath()).toEqual(['root-1', 'sub1-1', 'sub2-1']);
      const controls = component.productForm().controls;
      expect(controls.categoryRootId.value).toBe('root-1');
      expect(controls.subcategory1Id.value).toBe('sub1-1');
      expect(controls.subcategory2Id.value).toBe('sub2-1');
      expect(controls.categoryId.value).toBe('sub2-1');

      // The code on screen is the product's, not the category's ('22222222'), and the preload is not a
      // pick so there is no source to announce.
      expect(controls.satCode.value).toBe('99999999');
      const formText = fixture.nativeElement.querySelector('app-products-form')?.textContent ?? '';
      expect(formText).not.toContain('Código SAT tomado de:');
    });

    it('should refuse to move a saved product to another company (S2)', async () => {
      setup({ edit: true, categories: createCategoryTree() });
      await settle();

      expect(component.selectedCompanyId()).toBe(companyId);
      expect(categoryServiceMock.getCategories).toHaveBeenCalledWith(companyId, true);

      routerMock.navigate.mockClear();
      component.onCompanyChange('company-2');
      await settle();

      // The API scopes every write to the product's own company and refuses a category of another one,
      // so the screen cannot move it: no navigation, and the tree stays the one the URL named.
      expect(component.selectedCompanyId()).toBe(companyId);
      expect(routerMock.navigate).not.toHaveBeenCalled();
      expect(categoryServiceMock.getCategories).toHaveBeenLastCalledWith(companyId, true);
    });

    it('should read the link company’s tree in create mode without asking again (S2, S10)', async () => {
      setup({ categories: createCategoryTree() });
      await settle();

      // The company arrives as `?companyId=` (the operator came from All Products), so the tree load is
      // not waiting on a pick: it is already that company's tree, and the form's `Empresa` field only
      // has to show what the link said.
      expect(component.selectedCompanyId()).toBe(companyId);
      expect(categoryServiceMock.getCategories).toHaveBeenCalledWith(companyId, true);
      expect(fixture.nativeElement.querySelector('app-products-form')).not.toBeNull();
      expect(component.productForm().controls.categoryId.value).toBe('');

      // Picking another company before the product exists is *not* a navigation away: the choice is written
      // into the URL in place (S3) and the tree follows it.
      routerMock.navigate.mockClear();
      component.onCompanyChange('company-2');
      expect(component.selectedCompanyId()).toBe('company-2');
      expect(routerMock.navigate).toHaveBeenCalledWith([], {
        relativeTo: TestBed.inject(ActivatedRoute),
        queryParams: { companyId: 'company-2' },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
      await settle();
      expect(categoryServiceMock.getCategories).toHaveBeenLastCalledWith('company-2', true);
    });

    it('should keep the saved classification while the tree is still in flight (S6, F1)', async () => {
      setup({
        edit: true,
        product: createProductData({
          id: 'existing-id',
          categoryId: 'sub2-1',
          satCode: '99999999',
        }),
        categoriesDeferred: true,
      });
      // The real wire order: the product answers first, the tree request is still open.
      await settleRender();

      const controls = component.productForm().controls;
      expect(categoryServiceMock.getCategories).toHaveBeenCalledWith('company-1', true);
      // `null` is "unknown yet": the form must not act on it, so the seeded classification survives.
      expect(component.savedCategoryPath()).toBeNull();
      expect(controls.categoryId.value).toBe('sub2-1');
      expect(controls.categoryRootId.value).toBe('');
      expect(controls.satCode.value).toBe('99999999');

      tree$.next(createCategoryTree());
      tree$.complete();
      await settleRender();

      expect(component.savedCategoryPath()).toEqual(['root-1', 'sub1-1', 'sub2-1']);
      expect(controls.categoryRootId.value).toBe('root-1');
      expect(controls.subcategory1Id.value).toBe('sub1-1');
      expect(controls.subcategory2Id.value).toBe('sub2-1');
      expect(controls.categoryId.value).toBe('sub2-1');
      // The code is the product's ('22222222' is the category's) and the preload announces nothing.
      expect(controls.satCode.value).toBe('99999999');
      const formText = fixture.nativeElement.querySelector('app-products-form')?.textContent ?? '';
      expect(formText).not.toContain('Código SAT tomado de:');
    });

    it('should render the tree load failure instead of throwing during change detection (F2)', async () => {
      setup({
        edit: true,
        queryParams: { companyId: 'company-1' },
        product: createProductData({
          id: 'existing-id',
          categoryId: 'sub2-1',
        }),
        categoriesFailing: true,
      });
      await settle();

      // Degraded, not fatal: the form renders over an empty tree, the path stays "unknown" so the
      // classification is not wiped, and the failure is stated instead of swallowed.
      expect(component.categoryLoadError()).toBe(
        'Ocurrió un error en el servidor. Intentá de nuevo más tarde.',
      );
      expect(component.categoryTree()).toEqual([]);
      expect(component.savedCategoryPath()).toBeNull();
      expect(component.productForm().controls.categoryId.value).toBe('sub2-1');

      const banner = fixture.nativeElement.querySelector('app-error-banner') as HTMLElement | null;
      expect(banner?.textContent ?? '').toContain(
        'Ocurrió un error en el servidor. Intentá de nuevo más tarde.',
      );
      expect(fixture.nativeElement.querySelector('app-products-form')).not.toBeNull();
    });

    it('should keep the pick and its SAT hint when the company select reports another company (S2)', async () => {
      setup({ edit: true, categories: createCategoryTree() });
      await settle();

      const form = fixture.debugElement.query(By.directive(ProductsForm))
        .componentInstance as ProductsForm;
      form.onRootChange('root-1');
      fixture.detectChanges();

      expect(component.productForm().controls.categoryId.value).toBe('root-1');
      expect(form.satCodeHint()).toBe('Código SAT tomado de: Alimentos');

      // The select is disabled once the product exists, so this is the belt-and-braces path: a reported
      // change must not silently discard a classification the screen cannot move to another company.
      component.onCompanyChange('company-2');
      await settle();

      const controls = component.productForm().controls;
      expect(controls.categoryId.value).toBe('root-1');
      expect(form.satCodeHint()).toBe('Código SAT tomado de: Alimentos');
    });
  });

  describe('the company comes from ?companyId= (S1, S3)', () => {
    /** The form's `Empresa` select, the first of its four selects. */
    function empresaSelect(f: ComponentFixture<ProductEdit>): MatSelect {
      return f.debugElement.queryAll(By.directive(MatSelect))[0].componentInstance as MatSelect;
    }

    it('should issue no request and say where to open the product when an edit link names no company', async () => {
      setup({ edit: true, companyId: null });
      await settle();

      // A product id alone is not addressable on `/api/companies/{companyId}/products`, and inventing a
      // company would read and write in someone else's catalog. So: no request at all.
      expect(productServiceMock.getProductById).not.toHaveBeenCalled();
      expect(categoryServiceMock.getCategories).not.toHaveBeenCalled();

      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('no se puede abrir sin su empresa');
      // And the way out is a link to the screen that does ask for the company.
      expect(fixture.nativeElement.querySelector('a[href="/products/list"]')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('mat-tab-group')).toBeNull();
    });

    it('should refuse to save a new product without a company', () => {
      setup({ companyId: null });
      productServiceMock.createProduct = vi
        .fn()
        .mockReturnValue(of(createProductData({ id: 'new-id' })));

      component.onSaveProduct(createProductData());

      expect(productServiceMock.createProduct).not.toHaveBeenCalled();
      expect(component.generalError()).toContain('Elegí una empresa');
    });

    it('should preselect the Empresa field from the link on create', async () => {
      setup({ companyId: 'company-4', categories: createCategoryTree() });
      await settle();

      const form = fixture.debugElement.query(By.directive(ProductsForm))
        .componentInstance as ProductsForm;

      expect(component.selectedCompanyId()).toBe('company-4');
      expect(form.selectedCompanyId()).toBe('company-4');
    });

    it('should enable the Empresa field on create and keep it disabled on edit', async () => {
      setup({ edit: true, categories: createCategoryTree() });
      await settle();
      expect(empresaSelect(fixture).disabled).toBe(true);

      setup({ categories: createCategoryTree() });
      await settle();

      // Create asks for the company in its own form (S3): the field is the operator's, not the link's.
      expect(empresaSelect(fixture).disabled).toBe(false);
    });
  });

  describe('attribute profile (S4, S7)', () => {
    const shoeProfile: ProductAttributeProfile = {
      profileKey: 'calzado',
      label: 'Calzado',
      resolvedRootName: 'Calzado',
      productAttributes: [
        {
          code: 'marca',
          label: 'Marca',
          level: 'PRODUCT',
          kind: 'TEXT',
          unitLabel: null,
          required: false,
          values: [],
        },
      ],
      variantAttributes: [
        {
          code: 'color',
          label: 'Color',
          level: 'VARIANT',
          kind: 'ENUM',
          unitLabel: null,
          required: false,
          values: [],
        },
      ],
    };

    it('should read the profile of the product category and hand it to the form (S4)', async () => {
      setup({
        edit: true,
        product: createProductData({
          id: 'existing-id',
          categoryId: 'sub2-1',
        }),
        categories: createCategoryTree(),
        attributeProfile: shoeProfile,
      });
      await settle();

      expect(productServiceMock.getAttributeProfile).toHaveBeenCalledWith('company-1', 'sub2-1');
      expect(component.attributeProfile()).toEqual(shoeProfile);

      const form = fixture.debugElement.query(By.directive(ProductsForm))
        .componentInstance as ProductsForm;
      expect(form.attributeProfile()).toEqual(shoeProfile);
      // Only the product level renders on this screen; `color` belongs to the variant dialog.
      expect(form.productAttributeDefinitions().map((definition) => definition.code)).toEqual([
        'marca',
      ]);
    });

    it('should not read a profile when the product has no category', async () => {
      setup({ edit: true, categories: createCategoryTree() });
      await settle();

      expect(productServiceMock.getAttributeProfile).not.toHaveBeenCalled();
      expect(component.attributeProfile()).toBeUndefined();
    });
  });

  describe('serverErrors handling', () => {
    it('should set serverErrors signal and clear generalError when apiError has field-level errors', () => {
      setup();
      const httpError = createApiError({
        errors: { sku: 'SKU inválido', name: 'Nombre ya registrado' },
      });
      productServiceMock.createProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveProduct(createProductData());

      expect(component.serverErrors()).toEqual({
        sku: 'SKU inválido',
        name: 'Nombre ya registrado',
      });
      expect(component.generalError()).toBeNull();
    });

    it('should set generalError signal when apiError has no field-level errors but has message', () => {
      setup();
      const httpError = createApiError({
        errors: undefined,
        message: 'Error interno del servidor',
      });
      productServiceMock.createProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveProduct(createProductData());

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toBe('Error interno del servidor');
    });

    it('should set fallback generalError when apiError has neither errors nor message', () => {
      setup();
      const httpError = new HttpErrorResponse({
        error: { status: 500 },
        status: 500,
        statusText: 'Internal Server Error',
      });
      productServiceMock.createProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveProduct(createProductData());

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toBe('Error inesperado. Intente de nuevo más tarde.');
    });

    it('should handle field-level errors on updateProduct as well', () => {
      setup();
      const httpError = createApiError({
        errors: { name: 'Nombre ya existe' },
      });
      productServiceMock.updateProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveProduct(createProductData({ id: 'existing-id' }));

      expect(component.serverErrors()).toEqual({
        name: 'Nombre ya existe',
      });
      expect(component.generalError()).toBeNull();
    });

    it('should map a duplicate-SKU 409 onto the sku control and clear the banner on create', () => {
      setup();
      const httpError = createApiError({
        status: 409,
        message: "Product with SKU 'SKU-001' already exists",
        errors: undefined,
      });
      productServiceMock.createProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveProduct(createProductData());

      expect(component.serverErrors()).toEqual({ sku: 'Ya existe un producto con ese SKU.' });
      expect(component.generalError()).toBeNull();
    });

    it('should reach the sku control, not just the serverErrors signal', () => {
      setup();
      const httpError = createApiError({
        status: 409,
        message: "Product with SKU 'SKU-001' already exists",
        errors: undefined,
      });
      productServiceMock.createProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveProduct(createProductData());
      fixture.detectChanges();

      expect(component.productForm().get('sku')?.errors?.['serverError']).toBe(
        'Ya existe un producto con ese SKU.',
      );
    });

    it('should keep the general banner for a 409 that is not a SKU conflict', () => {
      setup();
      const httpError = createApiError({
        status: 409,
        message: 'The operation conflicts with an existing resource or violates a data constraint',
        errors: undefined,
      });
      productServiceMock.createProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveProduct(createProductData());

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toBe(
        'The operation conflicts with an existing resource or violates a data constraint',
      );
    });

    it('should let an errors map win over a duplicate-SKU 409', () => {
      setup();
      const httpError = createApiError({
        status: 409,
        message: "Product with SKU 'SKU-001' already exists",
        errors: { sku: 'SKU duplicado' },
      });
      productServiceMock.createProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveProduct(createProductData());

      expect(component.serverErrors()).toEqual({ sku: 'SKU duplicado' });
      expect(component.generalError()).toBeNull();
    });

    it('should map a duplicate-SKU 409 onto the sku control on update as well', () => {
      setup();
      const httpError = createApiError({
        status: 409,
        message: "Product with SKU 'SKU-001' already exists",
        errors: undefined,
      });
      productServiceMock.updateProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveProduct(createProductData({ id: 'existing-id' }));

      expect(component.serverErrors()).toEqual({ sku: 'Ya existe un producto con ese SKU.' });
      expect(component.generalError()).toBeNull();
    });
  });

  describe('embedded variant panel dirty aggregation (D37)', () => {
    it('should report unsaved changes when the embedded panel is dirty and false otherwise', async () => {
      setup({ edit: true, queryParams: { tab: 'variantes' } });
      await settle();

      const variantList = fixture.debugElement.query(By.directive(ProductVariantList));
      expect(variantList).not.toBeNull();
      expect(component.hasUnsavedChanges()).toBe(false);

      variantList.componentInstance.dirtyChange.emit(true);
      fixture.detectChanges();

      expect(component.variantPanelDirty()).toBe(true);
      expect(component.hasUnsavedChanges()).toBe(true);

      variantList.componentInstance.dirtyChange.emit(false);
      fixture.detectChanges();

      expect(component.variantPanelDirty()).toBe(false);
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should aggregate the form and the embedded panel flags independently', async () => {
      setup({ edit: true, queryParams: { tab: 'variantes' } });
      await settle();

      const variantList = fixture.debugElement.query(By.directive(ProductVariantList));
      const sku = component.productForm().get('sku');
      sku?.setValue('X');
      sku?.markAsDirty();
      expect(component.hasUnsavedChanges()).toBe(true);

      component.productForm().markAsPristine();
      variantList.componentInstance.dirtyChange.emit(true);
      fixture.detectChanges();
      expect(component.hasUnsavedChanges()).toBe(true);

      variantList.componentInstance.dirtyChange.emit(false);
      fixture.detectChanges();
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should bind the dirty output on the create stepper variant step too', async () => {
      setup();
      createProductThroughStepOne();
      component.goToStep(2);
      await settle();

      const variantList = fixture.debugElement.query(By.directive(ProductVariantList));
      expect(variantList).not.toBeNull();
      variantList.componentInstance.dirtyChange.emit(true);
      fixture.detectChanges();

      expect(component.variantPanelDirty()).toBe(true);
      expect(component.hasUnsavedChanges()).toBe(true);
    });
  });
});
