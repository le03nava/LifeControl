import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ProductEdit } from './product-edit';
import { Product } from '../../models/product.models';
import { ProductService } from '../../data/product.service';
import { ActivatedRoute, Router } from '@angular/router';
import { ReactiveFormsModule } from '@angular/forms';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

describe('ProductEdit', () => {
  let component: ProductEdit;
  let fixture: ComponentFixture<ProductEdit>;
  let productServiceMock: Partial<Record<keyof ProductService, unknown>>;
  let routerMock: Partial<Router>;

  function createApiError(overrides: Partial<Record<string, unknown>> = {}): HttpErrorResponse {
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
      status: 400,
      statusText: 'Bad Request',
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

  beforeEach(async () => {
    productServiceMock = {
      createProduct: vi.fn(),
      updateProduct: vi.fn(),
      getProductById: vi.fn(),
    };
    routerMock = {
      navigate: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [ProductEdit, NoopAnimationsModule, ReactiveFormsModule],
      providers: [
        { provide: ProductService, useValue: productServiceMock },
        { provide: Router, useValue: routerMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: () => null } },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should be in create mode when no id param', () => {
    expect(component.isEditMode()).toBe(false);
    expect(component.productId()).toBeNull();
  });

  it('should be in edit mode when id param exists', () => {
    productServiceMock.getProductById = vi.fn().mockReturnValue(
      of({
        id: 'existing-id',
        sku: 'SKU',
        name: 'Test',
        enabled: true,
        createdAt: '',
        updatedAt: '',
      }),
    );

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ProductEdit, NoopAnimationsModule, ReactiveFormsModule],
      providers: [
        { provide: ProductService, useValue: productServiceMock },
        { provide: Router, useValue: routerMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: () => 'existing-id' } },
          },
        },
      ],
    }).compileComponents();

    const f = TestBed.createComponent(ProductEdit);
    const c = f.componentInstance;
    f.detectChanges();

    expect(c.isEditMode()).toBe(true);
    expect(c.productId()).toBe('existing-id');
  });

  it('should load product in edit mode', () => {
    const mockProduct: Product = {
      id: 'existing-id',
      sku: 'SKU-123',
      name: 'Existing Product',
      shortName: 'EP',
      satCode: 'SAT-001',
      productType: 'Physical',
      attributes: { color: 'red' },
      enabled: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };

    productServiceMock.getProductById = vi.fn().mockReturnValue(of(mockProduct));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ProductEdit, NoopAnimationsModule, ReactiveFormsModule],
      providers: [
        { provide: ProductService, useValue: productServiceMock },
        { provide: Router, useValue: routerMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: () => 'existing-id' } },
          },
        },
      ],
    }).compileComponents();

    const f = TestBed.createComponent(ProductEdit);
    const c = f.componentInstance;
    f.detectChanges();

    expect(productServiceMock.getProductById).toHaveBeenCalledWith('existing-id');
    expect(c.productForm().controls.name.value).toBe('Existing Product');
  });

  it('should navigate to product edit on create success', () => {
    const createdProduct: Product = {
      id: 'new-id',
      sku: 'SKU-NEW',
      name: 'New Product',
      enabled: true,
      createdAt: '',
      updatedAt: '',
    };
    productServiceMock.createProduct = vi.fn().mockReturnValue(of(createdProduct));

    component.onSaveProduct(createProductData());
    expect(routerMock.navigate).toHaveBeenCalledWith(['/products/edit', 'new-id']);
  });

  it('should navigate to products admin on update success', () => {
    productServiceMock.updateProduct = vi.fn().mockReturnValue(of({} as Product));

    const existingProduct = createProductData({ id: 'existing-id' });
    component.onSaveProduct(existingProduct);
    expect(routerMock.navigate).toHaveBeenCalledWith(['/products']);
  });

  it('should navigate to products admin on cancel', () => {
    component.cancelForm();
    expect(routerMock.navigate).toHaveBeenCalledWith(['/products']);
  });

  describe('hasUnsavedChanges', () => {
    it('should report false right after create-mode construction', () => {
      expect(component.productForm().pristine).toBe(true);
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should stay pristine after a programmatic load in edit mode', () => {
      productServiceMock.getProductById = vi
        .fn()
        .mockReturnValue(of(createProductData({ id: 'existing-id' })));

      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [ProductEdit, NoopAnimationsModule, ReactiveFormsModule],
        providers: [
          { provide: ProductService, useValue: productServiceMock },
          { provide: Router, useValue: routerMock },
          {
            provide: ActivatedRoute,
            useValue: {
              snapshot: { paramMap: { get: () => 'existing-id' } },
            },
          },
        ],
      }).compileComponents();

      const f = TestBed.createComponent(ProductEdit);
      const c = f.componentInstance;
      f.detectChanges();

      expect(c.productForm().pristine).toBe(true);
      expect(c.hasUnsavedChanges()).toBe(false);
    });

    it('should report true after a user edit', () => {
      // `setValue` alone does not mark a reactive control dirty in Angular 20;
      // a real user edit both changes the value and marks the control dirty.
      const sku = component.productForm().get('sku');
      sku?.setValue('X');
      sku?.markAsDirty();

      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should clear the flag after a successful update', () => {
      productServiceMock.updateProduct = vi.fn().mockReturnValue(of({} as Product));

      component.productForm().get('sku')?.markAsDirty();
      // Precondition: the form must be dirty for `markAsPristine` to be load-bearing.
      expect(component.hasUnsavedChanges()).toBe(true);
      component.onSaveProduct(createProductData({ id: 'existing-id' }));

      expect(component.hasUnsavedChanges()).toBe(false);
      expect(routerMock.navigate).toHaveBeenCalledWith(['/products']);
    });

    it('should clear the flag after a successful create', () => {
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

  describe('edit-mode product actions', () => {
    function buttonLabels(f: ComponentFixture<ProductEdit>): string[] {
      const buttons = Array.from(f.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
      return buttons.map((button) => (button.textContent ?? '').trim());
    }

    function findButtonByLabel(
      f: ComponentFixture<ProductEdit>,
      label: string,
    ): HTMLButtonElement | undefined {
      const buttons = Array.from(f.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
      return buttons.find((button) => (button.textContent ?? '').trim() === label);
    }

    function createEditModeFixture(): ComponentFixture<ProductEdit> {
      productServiceMock.getProductById = vi
        .fn()
        .mockReturnValue(of(createProductData({ id: 'existing-id' })));

      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [ProductEdit, NoopAnimationsModule, ReactiveFormsModule],
        providers: [
          { provide: ProductService, useValue: productServiceMock },
          { provide: Router, useValue: routerMock },
          {
            provide: ActivatedRoute,
            useValue: {
              snapshot: { paramMap: { get: () => 'existing-id' } },
            },
          },
        ],
      }).compileComponents();

      const f = TestBed.createComponent(ProductEdit);
      f.detectChanges();
      return f;
    }

    it('should render both product actions with voseo labels in edit mode', () => {
      const f = createEditModeFixture();
      const labels = buttonLabels(f);

      expect(labels.filter((label) => label === 'Administrar proveedores')).toHaveLength(1);
      expect(labels.filter((label) => label === 'Variantes')).toHaveLength(1);
    });

    it('should navigate to the product variants when the Variantes button is clicked', () => {
      const f = createEditModeFixture();

      const variantsButton = findButtonByLabel(f, 'Variantes');
      expect(variantsButton).toBeDefined();
      variantsButton?.click();

      expect(routerMock.navigate).toHaveBeenCalledWith([
        '/products/edit',
        'existing-id',
        'variants',
      ]);
    });

    it('should navigate to the product suppliers when the suppliers button is clicked', () => {
      const f = createEditModeFixture();

      const suppliersButton = findButtonByLabel(f, 'Administrar proveedores');
      expect(suppliersButton).toBeDefined();
      suppliersButton?.click();

      expect(routerMock.navigate).toHaveBeenCalledWith([
        '/products/edit',
        'existing-id',
        'suppliers',
      ]);
    });

    it('should render neither product action in create mode', () => {
      const labels = buttonLabels(fixture);

      expect(labels).not.toContain('Administrar proveedores');
      expect(labels).not.toContain('Variantes');
    });
  });

  describe('serverErrors handling', () => {
    it('should set serverErrors signal and clear generalError when apiError has field-level errors', () => {
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
      const httpError = createApiError({
        errors: { name: 'Nombre ya existe' },
      });
      productServiceMock.updateProduct = vi.fn().mockReturnValue(throwError(() => httpError));

      const existingProduct = createProductData({ id: 'existing-id' });
      component.onSaveProduct(existingProduct);

      expect(component.serverErrors()).toEqual({
        name: 'Nombre ya existe',
      });
      expect(component.generalError()).toBeNull();
    });
  });
});
