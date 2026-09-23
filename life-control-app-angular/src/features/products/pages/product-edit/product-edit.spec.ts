import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, ParamMap, Router, convertToParamMap } from '@angular/router';
import { ReactiveFormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { MatTabGroup } from '@angular/material/tabs';
import { ProductEdit } from './product-edit';
import { Page, Product } from '../../models/product.models';
import { ProductService } from '../../data/product.service';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { ProductSupplier } from '../../models/product-supplier.models';
import { ProductVariantService } from '../../data/product-variant.service';
import { ProductVariant } from '../../models/product-variant.models';
import { ProductSupplierList } from '../product-supplier-list/product-supplier-list';
import { ProductVariantList } from '../product-variant-list/product-variant-list';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { ProfileResponse } from '@features/user/profile/data/profile.models';

describe('ProductEdit', () => {
  let component: ProductEdit;
  let fixture: ComponentFixture<ProductEdit>;
  let productServiceMock: {
    createProduct: ReturnType<typeof vi.fn>;
    updateProduct: ReturnType<typeof vi.fn>;
    getProductById: ReturnType<typeof vi.fn>;
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
  let routerMock: { navigate: ReturnType<typeof vi.fn> };
  let dialogMock: { open: ReturnType<typeof vi.fn> };
  let queryParams$: BehaviorSubject<ParamMap>;
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

  function createProductData(overrides: Partial<Product> = {}): Product {
    return {
      id: '',
      sku: 'SKU-001',
      name: 'Test Product',
      enabled: true,
      createdAt: '',
      updatedAt: '',
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
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      companyRegionId: 'region-1',
      companyZoneId: 'zone-1',
      companyStoreId,
    };
  }

  interface SetupOptions {
    edit?: boolean;
    product?: Product;
    queryParams?: Record<string, string>;
    suppliers?: ProductSupplier[];
    variants?: ProductVariant[];
    variantTotalElements?: number;
    profileStoreId?: string | null;
  }

  function setup(options: SetupOptions = {}): void {
    const edit = options.edit ?? false;
    const productId = edit ? 'existing-id' : null;
    const product = options.product ?? createProductData({ id: 'existing-id' });
    const suppliers = options.suppliers ?? [createSupplier(0), createSupplier(1)];
    const variants = options.variants ?? [createVariant(0), createVariant(1)];
    const variantTotalElements = options.variantTotalElements ?? variants.length;

    queryParams$ = new BehaviorSubject<ParamMap>(convertToParamMap(options.queryParams ?? {}));

    routeMock = {
      snapshot: {
        paramMap: { get: (key: string) => (key === 'id' ? productId : null) },
        queryParamMap: { get: (key: string) => queryParams$.value.get(key) },
      },
      queryParamMap: queryParams$.asObservable(),
    };

    productServiceMock = {
      createProduct: vi.fn(),
      updateProduct: vi.fn(),
      getProductById: vi.fn().mockReturnValue(of(product)),
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
    routerMock = { navigate: vi.fn() };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(false) }) };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ProductEdit, NoopAnimationsModule, ReactiveFormsModule],
      providers: [
        { provide: ProductService, useValue: productServiceMock },
        { provide: ProductSupplierService, useValue: supplierServiceMock },
        { provide: ProductVariantService, useValue: variantServiceMock },
        { provide: ProfileService, useValue: profileServiceMock },
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

    expect(productServiceMock.getProductById).toHaveBeenCalledWith('existing-id');
    expect(component.productForm().controls.name.value).toBe('Existing Product');
  });

  it('should navigate to product edit on create success', () => {
    setup();
    const createdProduct = createProductData({ id: 'new-id' });
    productServiceMock.createProduct = vi.fn().mockReturnValue(of(createdProduct));

    component.onSaveProduct(createProductData());
    expect(routerMock.navigate).toHaveBeenCalledWith(['/products/edit', 'new-id']);
  });

  it('should navigate to products admin on update success', () => {
    setup();
    productServiceMock.updateProduct = vi.fn().mockReturnValue(of({} as Product));

    component.onSaveProduct(createProductData({ id: 'existing-id' }));
    expect(routerMock.navigate).toHaveBeenCalledWith(['/products']);
  });

  it('should navigate to products admin on cancel', () => {
    setup();
    component.cancelForm();
    expect(routerMock.navigate).toHaveBeenCalledWith(['/products']);
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
      expect(routerMock.navigate).toHaveBeenCalledWith(['/products']);
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
      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/edit', 'new-id']);
    });
  });

  describe('create mode is not a workspace', () => {
    it('should render neither the tab strip nor the page header', () => {
      setup();
      const el = fixture.nativeElement as HTMLElement;

      expect(el.querySelector('mat-tab-group')).toBeNull();
      expect(el.querySelector('app-page-header')).toBeNull();
    });

    it('should still render the products form', () => {
      setup();
      expect(fixture.nativeElement.querySelector('app-products-form')).not.toBeNull();
    });

    it('should still render the error banner when a general error is set', () => {
      setup();
      component.generalError.set('Algo salió mal');
      fixture.detectChanges();

      const banner = fixture.nativeElement.querySelector('app-error-banner') as HTMLElement | null;
      expect(banner).not.toBeNull();
      expect(banner?.textContent?.trim()).toBe('Algo salió mal');
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
    it('should navigate with the new tab, relativeTo the route, preserving storeId', async () => {
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
      expect(extras.queryParams).toEqual({ storeId: 'store-1', tab: 'proveedores' });
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
});
