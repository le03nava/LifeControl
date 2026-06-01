import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ProductService } from './product.service';
import { Product, Page } from '../models/product.models';

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

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
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

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
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

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
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
