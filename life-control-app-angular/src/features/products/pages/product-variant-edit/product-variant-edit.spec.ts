import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { ProductVariantEdit } from './product-variant-edit';
import { ProductVariantDialog } from '../../components/product-variant-dialog/product-variant-dialog';
import { ProductService } from '../../data/product.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { Product } from '../../models/product.models';
import { ProductVariant } from '../../models/product-variant.models';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { NotificationService } from '@shared/data/notification';

describe('ProductVariantEdit', () => {
  let component: ProductVariantEdit;
  let fixture: ComponentFixture<ProductVariantEdit>;
  let productVariantServiceMock: {
    getVariantById: ReturnType<typeof vi.fn>;
    searchVariants: ReturnType<typeof vi.fn>;
  };
  let productServiceMock: { getProductById: ReturnType<typeof vi.fn> };
  let routerMock: { navigate: ReturnType<typeof vi.fn> };
  let dialogMock: { open: ReturnType<typeof vi.fn> };

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
    const variantId = options.variantId !== undefined ? options.variantId : mockVariantId;
    const queryStoreId = options.queryStoreId ?? null;

    productVariantServiceMock = {
      getVariantById: options.getVariantError
        ? vi.fn().mockReturnValue(throwError(() => options.getVariantError))
        : vi.fn().mockReturnValue(of(mockVariant)),
      searchVariants: vi.fn().mockReturnValue(of(emptySearchPage())),
    };
    productServiceMock = { getProductById: vi.fn().mockReturnValue(of(mockProduct)) };
    routerMock = { navigate: vi.fn() };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(null) }) };

    await TestBed.configureTestingModule({
      imports: [ProductVariantEdit, NoopAnimationsModule],
      providers: [
        { provide: ProductService, useValue: productServiceMock },
        { provide: ProductVariantService, useValue: productVariantServiceMock },
        { provide: Router, useValue: routerMock },
        { provide: MatDialog, useValue: dialogMock },
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

  it('should load the product for the header', async () => {
    await setup();
    expect(productServiceMock.getProductById).toHaveBeenCalledWith(mockProductId);
    expect(component.product()?.name).toBe('Producto de prueba');
  });

  it('should render the product name and the per-store subtitle', async () => {
    await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Producto de prueba');
    expect(el.textContent).toContain('SKU: SKU-001 — Stock y precios de esta tienda');
  });

  it('should read the variant and expose its stored barcode for the per-store panel', async () => {
    await setup();

    expect(productVariantServiceMock.getVariantById).toHaveBeenCalledWith(
      mockProductId,
      mockVariantId,
    );
    expect(component.loadedBarCode()).toBe('7791234567890');
    expect(component.loadedVariant()).toEqual(mockVariant);
  });

  it('should surface a variant load failure through the general banner', async () => {
    await setup({ getVariantError: createApiError(404) });

    expect(component.generalError()).toContain('No se encontró');
  });

  it('should fail closed to the product list when the route carries no productId', async () => {
    await setup({ productId: null });

    expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list']);
  });

  describe('definition dialog', () => {
    it('should render the edit action once the variant is loaded', async () => {
      await setup();

      const button = fixture.nativeElement.querySelector(
        'app-page-header button',
      ) as HTMLButtonElement | null;
      expect(button?.textContent).toContain('Editar la definición');
    });

    it('should open the dialog with the loaded variant', async () => {
      await setup();

      component.openDefinitionDialog();

      expect(dialogMock.open).toHaveBeenCalledWith(ProductVariantDialog, {
        data: { productId: mockProductId, variant: mockVariant },
        width: '560px',
      });
    });

    it('should not open the dialog before the variant is loaded', async () => {
      await setup({ getVariantError: createApiError(404) });

      component.openDefinitionDialog();

      expect(dialogMock.open).not.toHaveBeenCalled();
    });

    it('should refresh the stored barcode and variant when the dialog closes with a saved entity', async () => {
      await setup();
      const saved: ProductVariant = {
        ...mockVariant,
        barCode: '7799999999999',
        variantName: 'Talla 41',
      };
      dialogMock.open = vi.fn().mockReturnValue({ afterClosed: () => of(saved) });

      component.openDefinitionDialog();

      expect(component.loadedBarCode()).toBe('7799999999999');
      expect(component.loadedVariant()).toEqual(saved);
    });

    it('should leave the stored barcode untouched when the dialog is cancelled', async () => {
      await setup();

      component.openDefinitionDialog();

      expect(component.loadedBarCode()).toBe('7791234567890');
    });
  });

  describe('hasUnsavedChanges', () => {
    it('should report false when nothing is dirty', async () => {
      await setup();
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should account for the per-store panel in the unsaved-changes guard', async () => {
      await setup();
      expect(component.hasUnsavedChanges()).toBe(false);

      component.onStoreStockDirtyChange(true);
      expect(component.hasUnsavedChanges()).toBe(true);

      component.onStoreStockDirtyChange(false);
      expect(component.hasUnsavedChanges()).toBe(false);
    });
  });
});
