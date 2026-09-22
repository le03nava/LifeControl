import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ProductVariantEdit } from './product-variant-edit';
import { ProductService } from '../../data/product.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { Product } from '../../models/product.models';
import { ProductVariant, ProductVariantRequest } from '../../models/product-variant.models';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { NotificationService } from '@shared/data/notification';

describe('ProductVariantEdit', () => {
  let component: ProductVariantEdit;
  let fixture: ComponentFixture<ProductVariantEdit>;
  let productVariantServiceMock: {
    getVariantById: ReturnType<typeof vi.fn>;
    createVariant: ReturnType<typeof vi.fn>;
    updateVariant: ReturnType<typeof vi.fn>;
    searchVariants: ReturnType<typeof vi.fn>;
  };
  let productServiceMock: { getProductById: ReturnType<typeof vi.fn> };
  let routerMock: { navigate: ReturnType<typeof vi.fn> };

  const mockProductId = 'prod-1';
  const mockVariantId = 'var-1';

  const mockProduct: Product = {
    id: mockProductId,
    sku: 'SKU-001',
    name: 'Producto de prueba',
    enabled: true,
    createdAt: '',
    updatedAt: '',
  };

  const mockVariant: ProductVariant = {
    id: mockVariantId,
    productId: mockProductId,
    companyStoreId: null,
    barCode: '7791234567890',
    sku: 'SKU-001',
    variantName: 'Talla 38',
    listPrice: null,
    costPrice: null,
    stock: null,
    enabled: true,
  };

  function profileResponse(): ProfileResponse {
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
      companyStoreId: 'store-1',
    };
  }

  /** Empty search page: the store has no row for this variant yet. */
  function emptySearchPage() {
    return {
      content: [],
      totalElements: 0,
      totalPages: 0,
      size: 0,
      number: 0,
      first: true,
      last: true,
      empty: true,
    };
  }

  function createApiError(
    status: number,
    overrides: Partial<Record<string, unknown>> = {},
  ): HttpErrorResponse {
    return new HttpErrorResponse({
      error: {
        status,
        message: 'Error',
        path: '/api/products',
        timestamp: '2026-06-01T20:00:00Z',
        correlationId: 'abc-123',
        ...overrides,
      },
      status,
      statusText: 'Error',
    });
  }

  async function setup(
    options: {
      productId?: string | null;
      variantId?: string | null;
      getVariantError?: HttpErrorResponse;
      queryStoreId?: string | null;
    } = {},
  ): Promise<void> {
    const productId = options.productId !== undefined ? options.productId : mockProductId;
    const variantId = options.variantId !== undefined ? options.variantId : null;
    const queryStoreId = options.queryStoreId ?? null;

    productVariantServiceMock = {
      getVariantById: options.getVariantError
        ? vi.fn().mockReturnValue(throwError(() => options.getVariantError))
        : vi.fn().mockReturnValue(of(mockVariant)),
      createVariant: vi.fn().mockReturnValue(of(mockVariant)),
      updateVariant: vi.fn().mockReturnValue(of(mockVariant)),
      searchVariants: vi.fn().mockReturnValue(of(emptySearchPage())),
    };
    productServiceMock = { getProductById: vi.fn().mockReturnValue(of(mockProduct)) };
    routerMock = { navigate: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [ProductVariantEdit, NoopAnimationsModule],
      providers: [
        { provide: ProductService, useValue: productServiceMock },
        { provide: ProductVariantService, useValue: productVariantServiceMock },
        { provide: Router, useValue: routerMock },
        {
          provide: ProfileService,
          useValue: { getProfile: vi.fn().mockReturnValue(of(profileResponse())) },
        },
        { provide: NotificationService, useValue: { showSuccess: vi.fn(), showError: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => {
                  if (key === 'id') return productId;
                  if (key === 'variantId') return variantId;
                  return null;
                },
              },
              queryParamMap: {
                get: (key: string) => (key === 'storeId' ? queryStoreId : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductVariantEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('should create', async () => {
    await setup();
    expect(component).toBeTruthy();
  });

  it('should be in create mode when the route has no variantId', async () => {
    await setup();
    expect(component.isEditMode()).toBe(false);
    expect(component.variantId()).toBeNull();
    expect(component.variantForm().pristine).toBe(true);
  });

  it('should load the product for the header', async () => {
    await setup();
    expect(productServiceMock.getProductById).toHaveBeenCalledWith(mockProductId);
    expect(component.product()?.name).toBe('Producto de prueba');
  });

  it('should render the product name and a create subtitle', async () => {
    await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Producto de prueba');
    expect(el.textContent).toContain('SKU: SKU-001 — Nueva variante');
  });

  it('should be in edit mode and patch the form when the route has a variantId', async () => {
    await setup({ variantId: mockVariantId });

    expect(component.isEditMode()).toBe(true);
    expect(productVariantServiceMock.getVariantById).toHaveBeenCalledWith(
      mockProductId,
      mockVariantId,
    );
    expect(component.variantForm().getRawValue()).toEqual({
      barCode: '7791234567890',
      variantName: 'Talla 38',
    });
    // A programmatic load must not arm the unsaved-changes guard.
    expect(component.variantForm().pristine).toBe(true);
    expect(component.hasUnsavedChanges()).toBe(false);
  });

  it('should surface a load failure through the general banner', async () => {
    await setup({ variantId: mockVariantId, getVariantError: createApiError(404) });

    expect(component.generalError()).toContain('No se encontró');
  });

  describe('hasUnsavedChanges', () => {
    it('should report true once the form is dirty', async () => {
      await setup();
      component.variantForm().markAsDirty();
      expect(component.hasUnsavedChanges()).toBe(true);
    });
  });

  describe('store scope', () => {
    it('should account for the per-store panel in the unsaved-changes guard', async () => {
      await setup({ variantId: mockVariantId });
      expect(component.hasUnsavedChanges()).toBe(false);

      component.onStoreStockDirtyChange(true);
      expect(component.hasUnsavedChanges()).toBe(true);

      component.onStoreStockDirtyChange(false);
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should carry a link-named store back to the list', async () => {
      await setup({ variantId: mockVariantId, queryStoreId: 'store-9' });

      component.cancelForm();

      // Without it, the list would re-resolve the store from the profile and the
      // operator would land on a different one than they came from.
      expect(routerMock.navigate).toHaveBeenCalledWith(
        ['/products/edit', mockProductId, 'variants'],
        { queryParams: { storeId: 'store-9' } },
      );
    });

    it('should not write a profile-resolved store into the link', async () => {
      await setup({ variantId: mockVariantId });

      component.cancelForm();

      expect(routerMock.navigate).toHaveBeenCalledWith(
        ['/products/edit', mockProductId, 'variants'],
        { queryParams: undefined },
      );
    });
  });

  describe('onSaveVariant', () => {
    const request: ProductVariantRequest = { barCode: '7791234567891', variantName: 'Talla 40' };

    it('should create the variant, mark the form pristine and return to the list', async () => {
      await setup();
      component.variantForm().markAsDirty();

      component.onSaveVariant(request);

      expect(productVariantServiceMock.createVariant).toHaveBeenCalledWith(mockProductId, request);
      expect(component.variantForm().pristine).toBe(true);
      expect(routerMock.navigate).toHaveBeenCalledWith(
        ['/products/edit', mockProductId, 'variants'],
        { queryParams: undefined },
      );
    });

    it('should update the variant in edit mode', async () => {
      await setup({ variantId: mockVariantId });

      component.onSaveVariant(request);

      expect(productVariantServiceMock.updateVariant).toHaveBeenCalledWith(
        mockProductId,
        mockVariantId,
        request,
      );
      expect(routerMock.navigate).toHaveBeenCalledWith(
        ['/products/edit', mockProductId, 'variants'],
        { queryParams: undefined },
      );
    });

    it('should redirect to the product list when the route carries no productId', async () => {
      await setup({ productId: null });

      component.onSaveVariant(request);

      expect(productVariantServiceMock.createVariant).not.toHaveBeenCalled();
      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list']);
    });
  });

  describe('error handling', () => {
    const request: ProductVariantRequest = { barCode: '7791234567891', variantName: 'Talla 40' };

    it('should set field-level serverErrors and clear the general banner', async () => {
      await setup();
      productVariantServiceMock.createVariant = vi
        .fn()
        .mockReturnValue(
          throwError(() => createApiError(400, { errors: { barCode: 'Inválido' } })),
        );

      component.onSaveVariant(request);

      expect(component.serverErrors()).toEqual({ barCode: 'Inválido' });
      expect(component.generalError()).toBeNull();
    });

    it('should render the both-possibilities message on a 409 collision', async () => {
      await setup();
      productVariantServiceMock.createVariant = vi
        .fn()
        .mockReturnValue(throwError(() => createApiError(409)));

      component.onSaveVariant(request);
      fixture.detectChanges();

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toBe(
        'Ya existe una variante con ese código de barras o ese nombre para este producto',
      );
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Ya existe una variante con ese código de barras o ese nombre para este producto',
      );
    });

    it('should map a generic failure through httpErrorMessage', async () => {
      await setup();
      productVariantServiceMock.createVariant = vi
        .fn()
        .mockReturnValue(throwError(() => createApiError(500)));

      component.onSaveVariant(request);

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toContain('Ocurrió un error en el servidor');
    });
  });

  describe('cancelForm', () => {
    it('should return to the variant list', async () => {
      await setup();
      component.cancelForm();
      expect(routerMock.navigate).toHaveBeenCalledWith(
        ['/products/edit', mockProductId, 'variants'],
        { queryParams: undefined },
      );
    });

    it('should redirect to the product list without a productId', async () => {
      await setup({ productId: null });
      component.cancelForm();
      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list']);
    });
  });
});
