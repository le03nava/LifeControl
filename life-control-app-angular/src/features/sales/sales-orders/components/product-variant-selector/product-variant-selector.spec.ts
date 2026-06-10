import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { ProductVariantSelector } from './product-variant-selector';
import { ConfigService } from '@app/services/config.service';
import type { ProductVariantOption } from '../../models/sales-order.models';

const TEST_API = 'http://test/api';

const mockProductsPage = {
  content: [
    { id: 'p1', name: 'Laptop Pro', sku: 'LP-001' },
    { id: 'p2', name: 'Mouse Wireless', sku: 'MW-002' },
  ],
  totalElements: 2,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
};

const mockVariantsPage = {
  content: [
    {
      id: 'v1',
      productId: 'p1',
      variantName: 'Laptop Pro 16GB',
      barCode: 'BAR-001',
      sku: 'LP-001-16',
      listPrice: 1200.0,
      stock: 10,
      enabled: true,
    },
    {
      id: 'v2',
      productId: 'p1',
      variantName: 'Laptop Pro 32GB',
      barCode: 'BAR-002',
      sku: 'LP-001-32',
      listPrice: 1500.0,
      stock: 5,
      enabled: true,
    },
  ],
  totalElements: 2,
  totalPages: 1,
  size: 50,
  number: 0,
  first: true,
  last: true,
  empty: false,
};

describe('ProductVariantSelector', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        ProductVariantSelector,
        NoopAnimationsModule,
        HttpClientTestingModule,
      ],
      providers: [
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function createComponent(): ComponentFixture<ProductVariantSelector> {
    const fixture = TestBed.createComponent(ProductVariantSelector);
    fixture.detectChanges();
    return fixture;
  }

  describe('initial state', () => {
    it('should create the component', () => {
      const fixture = createComponent();
      expect(fixture.componentInstance).toBeTruthy();
    });

    it('should render product search input', () => {
      const fixture = createComponent();
      const el: HTMLElement = fixture.nativeElement;
      const inputs = el.querySelectorAll('input');
      expect(inputs.length).toBeGreaterThan(0);
    });

    it('should have empty products and variants initially', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;
      expect(comp.filteredProducts().length).toBe(0);
      expect(comp.variants().length).toBe(0);
    });
  });

  describe('product search (step 1)', () => {
    it('should search products when typing 2+ characters', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onProductSearch('lap');
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/products` &&
          r.params.get('search') === 'lap',
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockProductsPage);
      fixture.detectChanges();

      const products = comp.filteredProducts();
      expect(products.length).toBe(2);
      expect(products[0].name).toBe('Laptop Pro');
      expect(products[1].name).toBe('Mouse Wireless');
    });

    it('should NOT search when fewer than 2 characters', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onProductSearch('l');
      fixture.detectChanges();

      httpMock.expectNone(`${TEST_API}/products`);
      expect(comp.filteredProducts().length).toBe(0);
    });

    it('should handle empty product search results', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onProductSearch('nonexistent');
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/products`,
      );
      req.flush({ ...mockProductsPage, content: [], empty: true });
      fixture.detectChanges();

      expect(comp.filteredProducts().length).toBe(0);
    });
  });

  describe('variant loading (step 2)', () => {
    it('should load variants when a product is selected', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onProductSelect({ id: 'p1', name: 'Laptop Pro', sku: 'LP-001' });
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/products/p1/variants` &&
          r.params.get('page') === '0',
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockVariantsPage);
      fixture.detectChanges();

      expect(comp.variants().length).toBe(2);
      expect(comp.variants()[0].variantName).toBe('Laptop Pro 16GB');
      expect(comp.variants()[0].listPrice).toBe(1200.0);
    });

    it('should clear variants when no product is selected', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onProductSelect(null as any);
      fixture.detectChanges();

      expect(comp.variants().length).toBe(0);
    });

    it('should handle variant load error gracefully', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onProductSelect({ id: 'p99', name: 'Bad Product' });
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/products/p99/variants`,
      );
      req.flush('Not found', {
        status: 404,
        statusText: 'Not Found',
      });
      fixture.detectChanges();

      expect(comp.variants().length).toBe(0);
    });
  });

  describe('variant selection', () => {
    it('should emit selected variant', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      let emitted: ProductVariantOption | null = null;
      comp.variantSelected.subscribe((v: ProductVariantOption) => {
        emitted = v;
      });

      const variant: ProductVariantOption = {
        id: 'v1',
        productId: 'p1',
        variantName: 'Laptop Pro 16GB',
        listPrice: 1200.0,
        stock: 10,
        enabled: true,
      };
      comp.onVariantSelect(variant);

      expect(emitted).not.toBeNull();
      expect(emitted!.id).toBe('v1');
      expect(emitted!.listPrice).toBe(1200.0);
    });

    it('should clear variants after selection to reset the flow', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      // First load variants
      comp.onProductSelect({ id: 'p1', name: 'Laptop Pro' });
      fixture.detectChanges();
      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/products/p1/variants`,
      );
      req.flush(mockVariantsPage);
      fixture.detectChanges();
      expect(comp.variants().length).toBe(2);

      // Select variant — should reset
      comp.onVariantSelect(mockVariantsPage.content[0] as any);
      fixture.detectChanges();
      expect(comp.variants().length).toBe(0);
    });
  });
});
