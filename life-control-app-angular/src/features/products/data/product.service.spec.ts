import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ProductService } from './product.service';
import { Product, Page, ProductAttributeProfile } from '../models/product.models';

describe('ProductService', () => {
  let service: ProductService;
  let httpMock: HttpTestingController;

  /** The company every call is scoped to; the API serves products only under one. */
  const companyId = 'company-1';

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
    companyId,
    variantMode: 'SIMPLE',
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

  describe('company scope', () => {
    it('should build the collection under the company segment', () => {
      // The whole point of the move: there is no company-blind `/products` to fall back to, so the base
      // URL a caller can build is the scoped one and nothing else.
      expect(service.productsUrl(companyId)).toMatch(/\/companies\/company-1\/products$/);
      expect(service.productsUrl(companyId)).not.toMatch(/\/api\/products$/);
    });

    it('should send every product call through the scoped collection', async () => {
      const page: Page<Product> = {
        content: [mockProduct],
        totalElements: 1,
        totalPages: 1,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };
      const scoped = service.productsUrl(companyId);

      const listPromise = firstValueFrom(service.getProducts(companyId));
      httpMock.expectOne((r) => r.url === scoped && r.method === 'GET').flush(page);
      await listPromise;

      const readPromise = firstValueFrom(service.getProductById(companyId, '1'));
      httpMock.expectOne((r) => r.url === `${scoped}/1` && r.method === 'GET').flush(mockProduct);
      await readPromise;

      const createPromise = firstValueFrom(service.createProduct(companyId, mockProduct));
      httpMock.expectOne((r) => r.url === scoped && r.method === 'POST').flush(mockProduct);
      await createPromise;

      const updatePromise = firstValueFrom(service.updateProduct(companyId, '1', mockProduct));
      httpMock.expectOne((r) => r.url === `${scoped}/1` && r.method === 'PUT').flush(mockProduct);
      await updatePromise;

      const deletePromise = firstValueFrom(service.deleteProduct(companyId, '1'));
      httpMock.expectOne((r) => r.url === `${scoped}/1` && r.method === 'DELETE').flush(null);
      await deletePromise;

      const bySupplierPromise = firstValueFrom(service.getProductsBySupplier(companyId, 'sup-1'));
      httpMock
        .expectOne((r) => r.url === `${scoped}/by-supplier/sup-1` && r.method === 'GET')
        .flush([]);
      await bySupplierPromise;
    });
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

      const pagePromise = firstValueFrom(service.getProducts(companyId, 0, 12));

      const req = httpMock.expectOne(
        (r) => r.url === service.productsUrl(companyId) && r.method === 'GET',
      );
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

      const pagePromise = firstValueFrom(service.getProducts(companyId, 0, 12, 'Test'));

      const req = httpMock.expectOne(
        (r) => r.url === service.productsUrl(companyId) && r.method === 'GET',
      );
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

      const pagePromise = firstValueFrom(service.getProducts(companyId, 2, 24));

      const req = httpMock.expectOne(
        (r) => r.url === service.productsUrl(companyId) && r.method === 'GET',
      );
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
      const productPromise = firstValueFrom(service.getProductById(companyId, '1'));

      const req = httpMock.expectOne(`${service.productsUrl(companyId)}/1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockProduct);

      const product = await productPromise;
      expect(product).toEqual(mockProduct);
    });
  });

  describe('createProduct', () => {
    it('should create a new product', async () => {
      const mockResponse: Product = { ...mockProduct, id: 'new-id' };

      const responsePromise = firstValueFrom(service.createProduct(companyId, mockProduct));

      const req = httpMock.expectOne(`${service.productsUrl(companyId)}`);
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);

      const response = await responsePromise;
      expect(response.id).toBe('new-id');
    });
  });

  describe('updateProduct', () => {
    it('should update an existing product', async () => {
      const responsePromise = firstValueFrom(service.updateProduct(companyId, '1', mockProduct));

      const req = httpMock.expectOne(`${service.productsUrl(companyId)}/1`);
      expect(req.request.method).toBe('PUT');
      req.flush(mockProduct);

      const response = await responsePromise;
      expect(response.name).toBe('Product A');
    });
  });

  describe('deleteProduct', () => {
    it('should delete a product', async () => {
      const deletePromise = firstValueFrom(service.deleteProduct(companyId, '1'));

      const req = httpMock.expectOne(`${service.productsUrl(companyId)}/1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await deletePromise;
    });
  });

  describe('getAttributeProfile', () => {
    const profile: ProductAttributeProfile = {
      profileKey: 'ferreteria',
      label: 'Ferretería',
      resolvedRootName: 'Ferretería',
      productAttributes: [
        {
          code: 'marca',
          label: 'Marca',
          level: 'PRODUCT',
          kind: 'TEXT',
          unitLabel: null,
          required: false,
          values: [],
        },
      ],
      variantAttributes: [
        {
          code: 'color',
          label: 'Color',
          level: 'VARIANT',
          kind: 'ENUM',
          unitLabel: null,
          required: true,
          values: [{ code: 'negro', label: 'Negro Mate' }],
        },
      ],
    };

    // The company is part of the wire, so the assertion names the whole tail of the URL.
    const expectedPath = '/companies/company-1/product-categories/cat-1/attribute-profile';

    it('should read the profile of the category under its company', async () => {
      const profilePromise = firstValueFrom(service.getAttributeProfile('company-1', 'cat-1'));

      const req = httpMock.expectOne((r) => r.url.endsWith(expectedPath));
      expect(req.request.method).toBe('GET');
      req.flush(profile);

      await expect(profilePromise).resolves.toEqual(profile);
      expect(service.error()).toBeNull();
    });

    it('should map a 204 to null, not to an error', async () => {
      // A branch without a profile is a legitimate answer: the form renders no attribute inputs.
      const profilePromise = firstValueFrom(service.getAttributeProfile('company-1', 'cat-1'));

      httpMock
        .expectOne((r) => r.url.endsWith(expectedPath))
        .flush(null, { status: 204, statusText: 'No Content' });

      await expect(profilePromise).resolves.toBeNull();
      expect(service.error()).toBeNull();
    });

    it('should set the error signal when the read fails', async () => {
      const profilePromise = firstValueFrom(service.getAttributeProfile('company-1', 'cat-1'));

      httpMock
        .expectOne((r) => r.url.endsWith(expectedPath))
        .flush('not found', { status: 404, statusText: 'Not Found' });

      await expect(profilePromise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar los atributos de la categoría');
    });
  });

  describe('clearError', () => {
    it('should clear error signal', () => {
      service.clearError();
      expect(service.error()).toBeNull();
    });
  });
});
