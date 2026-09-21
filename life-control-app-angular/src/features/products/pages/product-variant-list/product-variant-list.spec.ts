import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { Subject, of, throwError } from 'rxjs';
import { ProductVariantList } from './product-variant-list';
import { ProductService } from '../../data/product.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { Product, Page } from '../../models/product.models';
import { ProductVariant } from '../../models/product-variant.models';

interface VariantServiceMock {
  getVariants: ReturnType<typeof vi.fn>;
  deleteVariant: ReturnType<typeof vi.fn>;
  enableVariant: ReturnType<typeof vi.fn>;
}

describe('ProductVariantList', () => {
  let component: ProductVariantList;
  let fixture: ComponentFixture<ProductVariantList>;
  let variantServiceMock: VariantServiceMock;
  let routerMock: { navigate: ReturnType<typeof vi.fn> };
  let dialogMock: { open: ReturnType<typeof vi.fn> };

  const mockProductId = 'prod-1';

  const mockProduct: Product = {
    id: mockProductId,
    sku: 'SKU-001',
    name: 'Producto de prueba',
    enabled: true,
    createdAt: '',
    updatedAt: '',
  };

  function createVariant(index: number, overrides: Partial<ProductVariant> = {}): ProductVariant {
    return {
      id: `var-${index}`,
      productId: mockProductId,
      companyStoreId: null,
      barCode: `7790000000${index}`,
      sku: 'SKU-001',
      variantName: `Talla ${index}`,
      listPrice: null,
      costPrice: null,
      stock: null,
      enabled: true,
      ...overrides,
    };
  }

  function createPage(variants: ProductVariant[], totalPages = 1): Page<ProductVariant> {
    return {
      content: variants,
      totalElements: variants.length,
      totalPages,
      size: 12,
      number: 0,
      first: true,
      last: true,
      empty: variants.length === 0,
    };
  }

  function setup(
    options: {
      productId?: string | null;
      variants?: ProductVariant[];
      totalPages?: number;
      variantsError?: boolean;
      variantsPending?: boolean;
    } = {},
  ) {
    const productId = options.productId !== undefined ? options.productId : mockProductId;
    const variants = options.variants ?? [createVariant(1), createVariant(2, { enabled: false })];

    variantServiceMock = {
      getVariants: options.variantsError
        ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })))
        : options.variantsPending
          ? vi.fn().mockReturnValue(new Subject<Page<ProductVariant>>())
          : vi.fn().mockReturnValue(of(createPage(variants, options.totalPages ?? 1))),
      deleteVariant: vi.fn().mockReturnValue(of(void 0)),
      enableVariant: vi.fn().mockReturnValue(of(createVariant(1))),
    };

    routerMock = { navigate: vi.fn() };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(false) }) };

    TestBed.configureTestingModule({
      imports: [ProductVariantList, NoopAnimationsModule],
      providers: [
        { provide: ProductVariantService, useValue: variantServiceMock },
        {
          provide: ProductService,
          useValue: { getProductById: vi.fn().mockReturnValue(of(mockProduct)) },
        },
        { provide: Router, useValue: routerMock },
        { provide: MatDialog, useValue: dialogMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'id' ? productId : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductVariantList);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  describe('Component creation', () => {
    it('should create', () => {
      setup();
      expect(component).toBeTruthy();
    });

    it('should read productId from the route', () => {
      setup();
      expect(component.productId()).toBe(mockProductId);
    });

    it('should fetch the variants without a storeId on init', () => {
      setup();
      expect(variantServiceMock.getVariants).toHaveBeenCalledWith(mockProductId, undefined, 0, 12);
    });

    it('should load the product for the header', () => {
      setup();
      const productService = TestBed.inject(ProductService);
      expect(productService.getProductById).toHaveBeenCalledWith(mockProductId);
    });
  });

  describe('Display states', () => {
    it('should render the product name and SKU in the header', async () => {
      setup();
      await fixture.whenStable();
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Producto de prueba');
      expect(el.textContent).toContain('SKU-001');
    });

    it('should render the identity columns and the enabled state', async () => {
      setup();
      await fixture.whenStable();
      fixture.detectChanges();

      const rows = fixture.debugElement.queryAll(By.css('tr.mat-mdc-row'));
      expect(rows.length).toBe(2);

      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Talla 1');
      expect(el.textContent).toContain('Talla 2');
      expect(el.textContent).toContain('77900000001');
      expect(el.textContent).toContain('Habilitada');
      expect(el.textContent).toContain('Deshabilitada');
    });

    it('should show the loading skeleton while the request is in flight', () => {
      setup({ variantsPending: true });

      const el = fixture.nativeElement as HTMLElement;
      expect(component.loading()).toBe(true);
      expect(el.querySelector('.loading-skeleton')).toBeTruthy();
      expect(el.querySelector('.table-card')).toBeNull();
    });

    it('should show the empty state when there are no variants', async () => {
      setup({ variants: [] });
      await fixture.whenStable();
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.empty-state')).toBeTruthy();
      expect(el.textContent).toContain('No hay variantes registradas');
    });
  });

  describe('Pagination', () => {
    it('should render the paginator only when there is more than one page', async () => {
      setup({ totalPages: 2 });
      await fixture.whenStable();
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      expect(component.hasMultiplePages()).toBe(true);
      expect(el.querySelector('.pagination-section')).toBeTruthy();
    });

    it('should update the page signals and refetch on page change', async () => {
      setup({ totalPages: 2 });
      await fixture.whenStable();
      fixture.detectChanges();

      variantServiceMock.getVariants.mockClear();
      component.onPageChange({ pageIndex: 1, pageSize: 24 });
      fixture.detectChanges();
      await fixture.whenStable();

      expect(component.pageIndex()).toBe(1);
      expect(component.pageSize()).toBe(24);
      expect(variantServiceMock.getVariants).toHaveBeenCalledWith(mockProductId, undefined, 1, 24);
    });
  });

  describe('Navigation', () => {
    it('should navigate to the variants edit target', () => {
      setup();
      component.editVariant('var-1');
      expect(routerMock.navigate).toHaveBeenCalledWith([
        '/products/edit',
        mockProductId,
        'variants',
        'edit',
        'var-1',
      ]);
    });

    it('should navigate to the variants create target', () => {
      setup();
      component.addVariant();
      expect(routerMock.navigate).toHaveBeenCalledWith([
        '/products/edit',
        mockProductId,
        'variants',
        'create',
      ]);
    });
  });

  describe('Disable flow', () => {
    it('should open the dialog and not disable when cancelled', () => {
      setup();
      component.confirmDisable(createVariant(1));

      expect(dialogMock.open).toHaveBeenCalled();
      expect(variantServiceMock.deleteVariant).not.toHaveBeenCalled();
    });

    it('should soft-delete and reload when the dialog confirms', () => {
      setup();
      dialogMock.open = vi.fn().mockReturnValue({ afterClosed: () => of(true) });
      const reloadSpy = vi.spyOn(component.variantsResource, 'reload');

      component.confirmDisable(createVariant(1));

      expect(variantServiceMock.deleteVariant).toHaveBeenCalledWith(mockProductId, 'var-1');
      expect(reloadSpy).toHaveBeenCalled();
    });
  });

  describe('Enable flow', () => {
    it('should re-enable the variant and reload the list', () => {
      setup();
      const reloadSpy = vi.spyOn(component.variantsResource, 'reload');

      component.enableVariant('var-2');

      expect(variantServiceMock.enableVariant).toHaveBeenCalledWith(mockProductId, 'var-2');
      expect(reloadSpy).toHaveBeenCalled();
    });
  });

  describe('Missing productId', () => {
    it('should redirect to the product list when the route has no id', async () => {
      setup({ productId: null });

      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list']);
    });
  });

  describe('Error state', () => {
    it('should render the error state when the variants fail', async () => {
      setup({ variantsError: true });
      await fixture.whenStable();
      fixture.detectChanges();

      expect(() => component.variants()).not.toThrow();
      expect(component.variants()).toBeUndefined();
      expect(component.error()).toBeTruthy();

      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.error-state')).toBeTruthy();
      expect(el.querySelector('.loading-skeleton')).toBeNull();
      expect(el.textContent).toContain('Tu sesión expiró');
    });

    it('should reload the resource on retry', () => {
      setup();
      const reloadSpy = vi.spyOn(component.variantsResource, 'reload');
      component.onRetry();
      expect(reloadSpy).toHaveBeenCalled();
    });
  });
});
