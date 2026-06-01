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
    productServiceMock.getProductById = vi.fn().mockReturnValue(of({
      id: 'existing-id', sku: 'SKU', name: 'Test', enabled: true, createdAt: '', updatedAt: '',
    }));

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
