import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { ProductList } from './product-list';
import { ProductService } from '../../data/product.service';
import { Product, Page } from '../../models/product.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanySelectorOption } from '@features/companies/companies/models/company.models';

interface ProductServiceMock {
  getProducts: ReturnType<typeof vi.fn>;
  deleteProduct: ReturnType<typeof vi.fn>;
}

interface CompanyServiceMock {
  getCompanySelector: ReturnType<typeof vi.fn>;
}

describe('ProductList', () => {
  let component: ProductList;
  let fixture: ComponentFixture<ProductList>;
  let productService: ProductServiceMock;
  let companyService: CompanyServiceMock;
  let router: Router;

  /**
   * The company whose catalog the list shows.
   *
   * It no longer arrives in the path: the screen's own `Empresa` filter owns it and keeps it in
   * `?companyId=` (S1, S2), so a spec reaches the list the same way an operator does — through the
   * query parameter.
   */
  const companyId = 'company-1';

  const mockProducts: Product[] = [
    {
      id: '1',
      sku: 'SKU-001',
      name: 'Alpha Widget',
      enabled: true,
      createdAt: '',
      updatedAt: '',
      companyId,
      variantMode: 'SIMPLE',
    },
    {
      id: '2',
      sku: 'SKU-002',
      name: 'Beta Gadget',
      enabled: true,
      createdAt: '',
      updatedAt: '',
      companyId,
      variantMode: 'SIMPLE',
    },
    {
      id: '3',
      sku: 'SKU-003',
      name: 'Gamma Tool',
      enabled: false,
      createdAt: '',
      updatedAt: '',
      companyId,
      variantMode: 'SIMPLE',
    },
  ];

  const createMockPage = (products: Product[], page = 0, size = 12): Page<Product> => ({
    content: products,
    totalElements: products.length,
    totalPages: Math.ceil(products.length / size),
    size,
    number: page,
    first: page === 0,
    last: (page + 1) * size >= products.length,
    empty: products.length === 0,
  });

  /** The company selector's row shape: the three fields a `mat-option` paints. */
  function createCompanyPage(): Page<CompanySelectorOption> {
    return {
      content: [
        { id: companyId, companyKey: 'lc', companyName: 'Life Control' },
        { id: 'company-2', companyKey: 'ac', companyName: 'Otra Empresa' },
      ],
      totalElements: 2,
      totalPages: 1,
      size: 2,
      number: 0,
      first: true,
      last: true,
      empty: false,
    };
  }

  interface SetupOptions {
    /** The company the link names, or `null` for a bare `/products/list`. */
    companyId?: string | null;
  }

  function setup(options: SetupOptions = {}): void {
    const scopedCompanyId = options.companyId === undefined ? companyId : options.companyId;

    productService = {
      getProducts: vi.fn().mockReturnValue(of(createMockPage(mockProducts))),
      deleteProduct: vi.fn().mockReturnValue(of(void 0)),
    };
    companyService = {
      getCompanySelector: vi.fn().mockReturnValue(of(createCompanyPage())),
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ProductList, MatIconModule, MatPaginatorModule, MatDialogModule],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            // Only the query parameter answers: a read that still reached a `:companyId` segment could
            // not produce a company here, which is what makes these assertions pin the source.
            snapshot: {
              queryParamMap: convertToParamMap(
                scopedCompanyId ? { companyId: scopedCompanyId } : {},
              ),
            },
          },
        },
        { provide: ProductService, useValue: productService },
        { provide: CompanyService, useValue: companyService },
        {
          provide: MatDialog,
          useValue: {
            open: vi.fn().mockReturnValue({ afterClosed: vi.fn().mockReturnValue(of(false)) }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductList);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  }

  /** Settles the resource read and the render; the list's effects need more than one flush. */
  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i += 1) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  function bodyText(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  beforeEach(() => {
    setup();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load products on creation via rxResource', () => {
    fixture.detectChanges();
    expect(productService.getProducts).toHaveBeenCalledWith(companyId, 0, 12, undefined);
  });

  it('should navigate to edit page', () => {
    component.editProduct('123');
    expect(router.navigate).toHaveBeenCalledWith(['/products/edit', '123'], {
      queryParams: { companyId },
    });
  });

  it('should navigate to the product variants page', () => {
    component.manageVariants('123');
    expect(router.navigate).toHaveBeenCalledWith(['/products/edit', '123', 'variants'], {
      queryParams: { companyId },
    });
  });

  it('should open delete dialog', () => {
    const dialogOpen = vi.spyOn(TestBed.inject(MatDialog), 'open');
    component.confirmDelete({ id: '1', name: 'Alpha Widget' });
    expect(dialogOpen).toHaveBeenCalled();
  });

  it('should delete product when dialog confirms', () => {
    const dialogRef = { afterClosed: vi.fn().mockReturnValue(of(true)) };
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue(
      dialogRef as unknown as ReturnType<MatDialog['open']>,
    );
    component.confirmDelete({ id: '1', name: 'Alpha Widget' });
    expect(productService.deleteProduct).toHaveBeenCalledWith(companyId, '1');
  });

  it('should NOT delete product when dialog cancels', () => {
    const dialogRef = { afterClosed: vi.fn().mockReturnValue(of(false)) };
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue(
      dialogRef as unknown as ReturnType<MatDialog['open']>,
    );
    component.confirmDelete({ id: '1', name: 'Alpha Widget' });
    expect(productService.deleteProduct).not.toHaveBeenCalled();
  });

  it('should clear search query', () => {
    component.searchQuery.set('alpha');
    component.clearSearch();
    expect(component.searchQuery()).toBe('');
  });

  it('should update page index and size on page change', () => {
    component.onPageChange({ pageIndex: 2, pageSize: 24 });
    expect(component.pageIndex()).toBe(2);
    expect(component.pageSize()).toBe(24);
  });

  it('should reset page to 0 when search changes (debounced)', () => {
    vi.useFakeTimers();
    // Start on page 2
    component.pageIndex.set(2);
    expect(component.pageIndex()).toBe(2);

    // Type a search query
    component.searchQuery.set('Alpha');

    // Trigger effect that creates the setTimeout
    fixture.detectChanges();

    // Fast-forward past debounce (300ms)
    vi.advanceTimersByTime(300);

    // Page should reset to 0
    expect(component.pageIndex()).toBe(0);

    // Trigger change detection so rxResource re-fetches with search param
    fixture.detectChanges();

    // API should be called with search param
    expect(productService.getProducts).toHaveBeenCalledWith(companyId, 0, 12, 'Alpha');

    vi.useRealTimers();
  });

  it('should load products with correct params', () => {
    vi.useFakeTimers();
    fixture.detectChanges();
    vi.advanceTimersByTime(0);

    expect(productService.getProducts).toHaveBeenCalledWith(companyId, 0, 12, undefined);

    // Change page
    component.onPageChange({ pageIndex: 1, pageSize: 12 });
    fixture.detectChanges();
    vi.advanceTimersByTime(0);

    expect(productService.getProducts).toHaveBeenCalledWith(companyId, 1, 12, undefined);

    vi.useRealTimers();
  });

  describe('the Empresa filter (S1, S2)', () => {
    it('should offer the claim-authorized company selector, as the other scoped screens do', async () => {
      await settle();

      expect(companyService.getCompanySelector).toHaveBeenCalledWith(0, 1000);
      // One select and nothing else: the company is the screen's only context control.
      expect(fixture.nativeElement.querySelectorAll('mat-select').length).toBe(1);
    });

    it('should reproduce the list from a shared ?companyId= link', async () => {
      setup({ companyId: 'company-9' });
      await settle();

      // The company the link named is the one the screen shows and the one the request is scoped to,
      // which is what makes a copied URL reproduce the list.
      expect(component.selectedCompanyId()).toBe('company-9');
      expect(productService.getProducts).toHaveBeenCalledWith('company-9', 0, 12, undefined);
      expect(bodyText()).not.toContain('Seleccioná una empresa');
    });

    it('should issue no request and ask for a company when the link names none', async () => {
      setup({ companyId: null });
      await settle();

      // No cross-company read exists (S2), so with no company there is nothing to ask for. An empty grid
      // here would claim that a company nobody named has no products.
      expect(productService.getProducts).not.toHaveBeenCalled();
      expect(bodyText()).toContain('Seleccioná una empresa para ver su catálogo');
    });

    it('should issue the scoped request and keep the choice in the URL', async () => {
      setup({ companyId: null });
      await settle();
      expect(productService.getProducts).not.toHaveBeenCalled();
      expect(component.selectedCompanyId()).toBeNull();

      component.onCompanyChange('company-2');
      await settle();

      // `ProductCategoryList`'s shape: the URL is the durable place for the choice, and `replaceUrl`
      // keeps a mid-page change out of the history.
      expect(router.navigate).toHaveBeenCalledWith([], {
        relativeTo: TestBed.inject(ActivatedRoute),
        queryParams: { companyId: 'company-2' },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
      expect(productService.getProducts).toHaveBeenCalledWith('company-2', 0, 12, undefined);
    });

    it('should drop the previous company’s page and search when the filter changes', async () => {
      await settle();
      component.onPageChange({ pageIndex: 2, pageSize: 12 });
      component.searchQuery.set('alpha');
      fixture.detectChanges();
      await settle();

      component.onCompanyChange('company-2');

      // Both belong to the company that was on screen: keeping them would page into, and filter by, a
      // catalog the operator just left.
      expect(component.pageIndex()).toBe(0);
      expect(component.searchQuery()).toBe('');
    });

    it('should carry the chosen company into the create action', () => {
      component.createProduct();

      expect(router.navigate).toHaveBeenCalledWith(['/products/create'], {
        queryParams: { companyId },
      });
    });

    it('should do nothing in any action when no company is chosen', async () => {
      setup({ companyId: null });
      await settle();

      component.createProduct();
      component.editProduct('123');
      component.manageVariants('123');

      // No navigation at all: a company-free destination does not exist, so guessing one would open
      // another company's catalog.
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  describe('responsive paginator (isMobile signal)', () => {
    let originalMatchMedia: typeof window.matchMedia;

    function setupMatchMedia(matches: boolean) {
      const listeners: Record<string, EventListener> = {};
      const mql = {
        matches,
        addEventListener: (type: string, listener: EventListener) => {
          listeners[type] = listener;
        },
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
      };
      window.matchMedia = vi
        .fn()
        .mockReturnValue(mql as unknown as MediaQueryList) as unknown as typeof window.matchMedia;
      return { mql, listeners };
    }

    beforeAll(() => {
      originalMatchMedia = window.matchMedia;
    });

    afterAll(() => {
      window.matchMedia = originalMatchMedia;
    });

    it('should default to desktop pageSizeOptions', () => {
      setupMatchMedia(false);
      const f = TestBed.createComponent(ProductList);
      f.detectChanges();
      expect(f.componentInstance.pageSizeOptions()).toEqual([6, 12, 24, 48]);
    });

    it('should return mobile pageSizeOptions when isMobile is true', () => {
      setupMatchMedia(true);
      const f = TestBed.createComponent(ProductList);
      f.detectChanges();
      expect(f.componentInstance.isMobile()).toBe(true);
      expect(f.componentInstance.pageSizeOptions()).toEqual([6, 12]);
    });

    it('should update isMobile on matchMedia change event', () => {
      const { listeners } = setupMatchMedia(false);
      const f = TestBed.createComponent(ProductList);
      f.detectChanges();
      expect(f.componentInstance.isMobile()).toBe(false);

      // Simulate viewport resize to mobile
      if (listeners['change']) {
        listeners['change']({ matches: true } as MediaQueryListEvent);
      }
      expect(f.componentInstance.isMobile()).toBe(true);
    });
  });

  describe('error state', () => {
    it('should not throw and render the error state when the API fails', async () => {
      productService.getProducts = vi
        .fn()
        .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

      const f = TestBed.createComponent(ProductList);
      const comp = f.componentInstance;
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      expect(() => comp.products()).not.toThrow();
      expect(comp.products()).toBeUndefined();
      expect(comp.error()).toBeTruthy();

      const el: HTMLElement = f.nativeElement;
      expect(el.querySelector('.error-state')).toBeTruthy();
      expect(el.querySelector('.loading-skeleton')).toBeNull();
      expect(el.textContent).toContain('Tu sesión expiró');
    });
  });
});
