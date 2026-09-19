import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ProductService } from './product.service';
import { Product, Page } from '../models/product.models';
import { ProductVariant } from '../models/product-variant.models';

describe('ProductService', () => {
  let service: ProductService;
  let httpMock: HttpTestingController;

  const mockProduct: Product = {
    id: '1',
    sku: 'SKU-001',
    name: 'Product A',
    shortName: 'Prod A',
    satCode: '12345678',
    productType: 'Service',
    attributes: { color: 'red' },
    enabled: true,
    createdAt: '',
    updatedAt: '',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ProductService],
    });
    service = TestBed.inject(ProductService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getProducts', () => {
    it('should fetch paginated products with default params', async () => {
      const mockPage: Page<Product> = {
        content: [mockProduct],
        totalElements: 1,
        totalPages: 1,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };

      const pagePromise = firstValueFrom(service.getProducts(0, 12));

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      expect(req.request.params.has('search')).toBe(false);
      req.flush(mockPage);

      const page = await pagePromise;
      expect(page).toEqual(mockPage);
      expect(page.content.length).toBe(1);
      expect(page.content[0].name).toBe('Product A');
    });

    it('should include search param when provided', async () => {
      const mockPage: Page<Product> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: true,
      };

      const pagePromise = firstValueFrom(service.getProducts(0, 12, 'Test'));

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('search')).toBe('Test');
      req.flush(mockPage);

      const page = await pagePromise;
      expect(page.empty).toBe(true);
    });

    it('should handle pagination params correctly', async () => {
      const mockPage: Page<Product> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 24,
        number: 2,
        first: false,
        last: true,
        empty: true,
      };

      const pagePromise = firstValueFrom(service.getProducts(2, 24));

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('24');
      req.flush(mockPage);

      const page = await pagePromise;
      expect(page.number).toBe(2);
      expect(page.size).toBe(24);
    });
  });

  describe('getProductById', () => {
    it('should fetch a single product by ID', async () => {
      const productPromise = firstValueFrom(service.getProductById('1'));

      const req = httpMock.expectOne(`${service.apiUrl}/1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockProduct);

      const product = await productPromise;
      expect(product).toEqual(mockProduct);
    });
  });

  describe('getProductVariants', () => {
    const mockVariant: ProductVariant = {
      id: 'var-1',
      productId: 'prod-1',
      companyStoreId: 'store-1',
      barCode: 'BAR-001',
      sku: 'SKU-001-A',
      variantName: 'Product A — 1L',
      listPrice: 120,
      costPrice: 80,
      stock: 12,
      enabled: true,
    };

    const variantPage = (content: ProductVariant[]): Page<ProductVariant> => ({
      content,
      totalElements: content.length,
      totalPages: 1,
      size: 50,
      number: 0,
      first: true,
      last: true,
      empty: content.length === 0,
    });

    it("should fetch a product's variants for one store with page and size params", async () => {
      const variantsPromise = firstValueFrom(service.getProductVariants('prod-1', 'store-1'));

      const req = httpMock.expectOne(
        (r) => r.url === `${service.apiUrl}/prod-1/variants` && r.method === 'GET',
      );
      expect(req.request.params.get('storeId')).toBe('store-1');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('50');
      req.flush(variantPage([mockVariant]));

      const page = await variantsPromise;
      expect(page.content.length).toBe(1);
      expect(page.content[0].variantName).toBe('Product A — 1L');
      expect(page.content[0].costPrice).toBe(80);
    });

    it('should forward an explicit page and size', async () => {
      const variantsPromise = firstValueFrom(
        service.getProductVariants('prod-9', 'store-7', 2, 25),
      );

      const req = httpMock.expectOne((r) => r.url === `${service.apiUrl}/prod-9/variants`);
      expect(req.request.params.get('storeId')).toBe('store-7');
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('25');
      req.flush(variantPage([]));

      const page = await variantsPromise;
      expect(page.empty).toBe(true);
    });
  });

  describe('createProduct', () => {
    it('should create a new product', async () => {
      const mockResponse: Product = { ...mockProduct, id: 'new-id' };

      const responsePromise = firstValueFrom(service.createProduct(mockProduct));

      const req = httpMock.expectOne(`${service.apiUrl}`);
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);

      const response = await responsePromise;
      expect(response.id).toBe('new-id');
    });
  });

  describe('updateProduct', () => {
    it('should update an existing product', async () => {
      const responsePromise = firstValueFrom(service.updateProduct('1', mockProduct));

      const req = httpMock.expectOne(`${service.apiUrl}/1`);
      expect(req.request.method).toBe('PUT');
      req.flush(mockProduct);

      const response = await responsePromise;
      expect(response.name).toBe('Product A');
    });
  });

  describe('deleteProduct', () => {
    it('should delete a product', async () => {
      const deletePromise = firstValueFrom(service.deleteProduct('1'));

      const req = httpMock.expectOne(`${service.apiUrl}/1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await deletePromise;
    });
  });

  describe('clearError', () => {
    it('should clear error signal', () => {
      service.clearError();
      expect(service.error()).toBeNull();
    });
  });
});
