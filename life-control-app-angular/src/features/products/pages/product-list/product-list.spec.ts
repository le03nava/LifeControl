import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ProductList } from './product-list';
import { ProductService } from '../../data/product.service';
import { Product, Page } from '../../models/product.models';
import { of } from 'rxjs';

type ProductServiceMock = {
  getProducts: ReturnType<typeof vi.fn>;
  deleteProduct: ReturnType<typeof vi.fn>;
};

describe('ProductList', () => {
  let component: ProductList;
  let fixture: ComponentFixture<ProductList>;
  let productService: ProductServiceMock;
  let router: Router;

  const mockProducts: Product[] = [
    { id: '1', sku: 'SKU-001', name: 'Alpha Widget', enabled: true, createdAt: '', updatedAt: '' },
    { id: '2', sku: 'SKU-002', name: 'Beta Gadget', enabled: true, createdAt: '', updatedAt: '' },
    { id: '3', sku: 'SKU-003', name: 'Gamma Tool', enabled: false, createdAt: '', updatedAt: '' },
  ];

  const createMockPage = (products: Product[], page: number = 0, size: number = 12): Page<Product> => ({
    content: products,
    totalElements: products.length,
    totalPages: Math.ceil(products.length / size),
    size,
    number: page,
    first: page === 0,
    last: (page + 1) * size >= products.length,
    empty: products.length === 0,
  });

  beforeEach(async () => {
    productService = {
      getProducts: vi.fn().mockReturnValue(of(createMockPage(mockProducts))),
      deleteProduct: vi.fn().mockReturnValue(of(void 0)),
    };

    await TestBed.configureTestingModule({
      imports: [ProductList, MatIconModule, MatPaginatorModule, MatDialogModule, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: ProductService, useValue: productService },
        { provide: MatDialog, useValue: { open: vi.fn().mockReturnValue({ afterClosed: vi.fn().mockReturnValue(of(false)) }) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductList);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load products on creation via rxResource', () => {
    fixture.detectChanges();
    expect(productService.getProducts).toHaveBeenCalledWith(0, 12, undefined);
  });

  it('should navigate to edit page', () => {
    component.editProduct('123');
    expect(router.navigate).toHaveBeenCalledWith(['/products/edit/123']);
  });

  it('should open delete dialog', () => {
    const dialogOpen = vi.spyOn(TestBed.inject(MatDialog), 'open');
    component.confirmDelete({ id: '1', name: 'Alpha Widget' });
    expect(dialogOpen).toHaveBeenCalled();
  });

  it('should delete product when dialog confirms', () => {
    const dialogRef = { afterClosed: vi.fn().mockReturnValue(of(true)) };
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue(dialogRef as any);
    component.confirmDelete({ id: '1', name: 'Alpha Widget' });
    expect(productService.deleteProduct).toHaveBeenCalledWith('1');
  });

  it('should NOT delete product when dialog cancels', () => {
    const dialogRef = { afterClosed: vi.fn().mockReturnValue(of(false)) };
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue(dialogRef as any);
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
    expect(productService.getProducts).toHaveBeenCalledWith(0, 12, 'Alpha');

    vi.useRealTimers();
  });

  it('should load products with correct params', () => {
    vi.useFakeTimers();
    fixture.detectChanges();
    vi.advanceTimersByTime(0);

    expect(productService.getProducts).toHaveBeenCalledWith(0, 12, undefined);

    // Change page
    component.onPageChange({ pageIndex: 1, pageSize: 12 });
    fixture.detectChanges();
    vi.advanceTimersByTime(0);

    expect(productService.getProducts).toHaveBeenCalledWith(1, 12, undefined);

    vi.useRealTimers();
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
      window.matchMedia = vi.fn().mockReturnValue(mql as any) as unknown as typeof window.matchMedia;
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
});
