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
const TEST_STORE = 'store-1';
const DEBOUNCE_WAIT = 400; // ms to wait for the 300ms debounce to fire

/** Helper: wait for a given number of milliseconds in async tests. */
function waitFor(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function searchResponse(content: ProductVariantOption[]) {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 20,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
  };
}

describe('ProductVariantSelector', () => {
  let httpMock: HttpTestingController;
  let fixture: ComponentFixture<ProductVariantSelector>;
  let component: ProductVariantSelector;

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
    fixture = TestBed.createComponent(ProductVariantSelector);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('storeId', TEST_STORE);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('initial state', () => {
    it('should create the component', () => {
      expect(component).toBeTruthy();
    });

    it('should render a search input', () => {
      const el: HTMLElement = fixture.nativeElement;
      const inputs = el.querySelectorAll('input');
      expect(inputs.length).toBe(1);
    });

    it('should have empty filteredVariants initially', () => {
      expect(component.filteredVariants().length).toBe(0);
    });
  });

  describe('search', () => {
    it('should search variants after debounce delay when typing 2+ characters', async () => {
      component.onSearchChange('lap');
      fixture.detectChanges();

      // Before debounce fires, no HTTP call
      httpMock.expectNone(`${TEST_API}/product-variants/search`);

      // Wait for the 300ms debounce to fire
      await waitFor(DEBOUNCE_WAIT);
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/product-variants/search` &&
          r.params.get('q') === 'lap' &&
          r.params.get('storeId') === TEST_STORE,
      );
      expect(req.request.method).toBe('GET');

      const variant: ProductVariantOption = {
        id: 'v1',
        productId: 'p1',
        variantName: 'Laptop Pro 16GB',
        barCode: 'BAR-001',
        sku: 'LP-001-16',
        listPrice: 1200,
        stock: 10,
        enabled: true,
        productName: 'Laptop Pro',
      };
      req.flush(searchResponse([variant]));
      fixture.detectChanges();

      expect(component.filteredVariants().length).toBe(1);
      expect(component.filteredVariants()[0].variantName).toBe('Laptop Pro 16GB');
      expect(component.filteredVariants()[0].productName).toBe('Laptop Pro');
    }, 10000);

    it('should return multiple results on LIKE search', async () => {
      component.onSearchChange('van');
      fixture.detectChanges();

      await waitFor(DEBOUNCE_WAIT);
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/product-variants/search` &&
          r.params.get('q') === 'van',
      );
      expect(req.request.method).toBe('GET');

      const results: ProductVariantOption[] = [
        {
          id: 'v1',
          productId: 'p1',
          variantName: 'Vanilla 1L',
          barCode: 'BAR-011',
          sku: 'VAN-001',
          listPrice: 80,
          stock: 20,
          enabled: true,
          productName: 'Ice Cream',
        },
        {
          id: 'v2',
          productId: 'p1',
          variantName: 'Vanilla 2L',
          barCode: 'BAR-012',
          sku: 'VAN-002',
          listPrice: 140,
          stock: 15,
          enabled: true,
          productName: 'Ice Cream',
        },
      ];
      req.flush(searchResponse(results));
      fixture.detectChanges();

      expect(component.filteredVariants().length).toBe(2);
      expect(component.filteredVariants()[0].variantName).toBe('Vanilla 1L');
      expect(component.filteredVariants()[1].variantName).toBe('Vanilla 2L');
    }, 10000);

    it('should NOT search when fewer than 2 characters', async () => {
      component.onSearchChange('l');
      fixture.detectChanges();

      await waitFor(DEBOUNCE_WAIT);

      httpMock.expectNone(`${TEST_API}/product-variants/search`);
      expect(component.filteredVariants().length).toBe(0);
    }, 10000);

    it('should handle empty search results', async () => {
      component.onSearchChange('nonexistent');
      fixture.detectChanges();

      await waitFor(DEBOUNCE_WAIT);
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/product-variants/search`,
      );
      req.flush(searchResponse([]));
      fixture.detectChanges();

      expect(component.filteredVariants().length).toBe(0);
    }, 10000);

    it('should NOT search when storeId is null', async () => {
      fixture.componentRef.setInput('storeId', null);
      fixture.detectChanges();

      component.onSearchChange('lap');
      fixture.detectChanges();

      await waitFor(DEBOUNCE_WAIT);

      httpMock.expectNone(`${TEST_API}/product-variants/search`);
      expect(component.filteredVariants().length).toBe(0);
    }, 10000);

    it('should debounce and only fire the last search query', async () => {
      // Type rapidly — only the last query should trigger a search
      component.onSearchChange('la');
      component.onSearchChange('lap');
      component.onSearchChange('lapt');
      component.onSearchChange('lapto');
      fixture.detectChanges();

      await waitFor(DEBOUNCE_WAIT);
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/product-variants/search` &&
          r.params.get('q') === 'lapto',
      );
      expect(req.request.method).toBe('GET');

      const variant: ProductVariantOption = {
        id: 'v1',
        productId: 'p1',
        variantName: 'Laptop Pro 16GB',
        barCode: 'BAR-001',
        sku: 'LP-001-16',
        listPrice: 1200,
        stock: 10,
        enabled: true,
        productName: 'Laptop Pro',
      };
      req.flush(searchResponse([variant]));
      fixture.detectChanges();

      expect(component.filteredVariants().length).toBe(1);
    }, 10000);
  });

  describe('variant selection', () => {
    it('should emit the selected variant and clear input', async () => {
      component.onSearchChange('van');
      fixture.detectChanges();

      await waitFor(DEBOUNCE_WAIT);
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/product-variants/search`,
      );

      const results: ProductVariantOption[] = [
        {
          id: 'v1',
          productId: 'p1',
          variantName: 'Vanilla 1L',
          barCode: 'BAR-011',
          sku: 'VAN-001',
          listPrice: 80,
          stock: 20,
          enabled: true,
          productName: 'Ice Cream',
        },
      ];
      req.flush(searchResponse(results));
      fixture.detectChanges();

      expect(component.filteredVariants().length).toBe(1);

      // Subscribe to the output
      let emitted: ProductVariantOption | null = null;
      const sub = component.variantSelected.subscribe((v) => {
        emitted = v;
      });

      // Select the variant
      component.onVariantSelect(results[0]);
      sub.unsubscribe();

      expect(emitted).not.toBeNull();
      expect(emitted!.id).toBe('v1');
      expect(emitted!.variantName).toBe('Vanilla 1L');
      expect(emitted!.productName).toBe('Ice Cream');

      // Input and results should be cleared
      expect(component.searchQuery()).toBe('');
      expect(component.filteredVariants().length).toBe(0);
    }, 10000);
  });

  describe('error handling', () => {
    it('should clear results on server error', async () => {
      component.onSearchChange('lap');
      fixture.detectChanges();

      await waitFor(DEBOUNCE_WAIT);
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/product-variants/search`,
      );
      req.flush('Server error', {
        status: 500,
        statusText: 'Internal Server Error',
      });
      fixture.detectChanges();

      expect(component.filteredVariants().length).toBe(0);
    }, 10000);
  });
});
