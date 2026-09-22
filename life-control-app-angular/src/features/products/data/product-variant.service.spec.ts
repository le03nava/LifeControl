import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ProductVariantService } from './product-variant.service';
import { Page } from '../models/product.models';
import {
  ProductVariant,
  ProductVariantRequest,
  ProductVariantSearchResult,
  ProductVariantStoreStock,
  ProductVariantStoreStockRequest,
} from '../models/product-variant.models';

describe('ProductVariantService', () => {
  let service: ProductVariantService;
  let httpMock: HttpTestingController;

  const productsUrl = 'http://localhost:9000/api/products';
  const variantsUrl = 'http://localhost:9000/api/variants';

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

  const mockStoreStock: ProductVariantStoreStock = {
    companyStoreId: 'store-1',
    listPrice: 120,
    costPrice: 80,
    stock: 12,
  };

  const variantPage = (content: ProductVariant[]): Page<ProductVariant> => ({
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 12,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ProductVariantService],
    });
    service = TestBed.inject(ProductVariantService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getVariants', () => {
    it('should request the nested URL with page and size but no storeId by default', async () => {
      const promise = firstValueFrom(service.getVariants('prod-1'));

      const req = httpMock.expectOne(
        (r) => r.url === `${productsUrl}/prod-1/variants` && r.method === 'GET',
      );
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      expect(req.request.params.has('storeId')).toBe(false);
      req.flush(variantPage([mockVariant]));

      const page = await promise;
      expect(page.content.length).toBe(1);
      expect(page.content[0].variantName).toBe('Product A — 1L');
    });

    it('should send the storeId and forward explicit page and size when provided', async () => {
      const promise = firstValueFrom(service.getVariants('prod-9', 'store-7', 2, 25));

      const req = httpMock.expectOne(
        (r) => r.url === `${productsUrl}/prod-9/variants` && r.method === 'GET',
      );
      expect(req.request.params.get('storeId')).toBe('store-7');
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('25');
      req.flush(variantPage([]));

      const page = await promise;
      expect(page.empty).toBe(true);
    });

    it('should never send storeId as an empty string', async () => {
      const promise = firstValueFrom(service.getVariants('prod-1', ''));

      const req = httpMock.expectOne((r) => r.url === `${productsUrl}/prod-1/variants`);
      expect(req.request.params.has('storeId')).toBe(false);
      req.flush(variantPage([]));

      await promise;
    });

    it('should omit includeDisabled from the request by default', async () => {
      const promise = firstValueFrom(service.getVariants('prod-1'));

      const req = httpMock.expectOne((r) => r.url === `${productsUrl}/prod-1/variants`);
      expect(req.request.params.has('includeDisabled')).toBe(false);
      req.flush(variantPage([]));

      await promise;
    });

    it('should omit includeDisabled when explicitly passed as false', async () => {
      const promise = firstValueFrom(service.getVariants('prod-1', undefined, 0, 12, false));

      const req = httpMock.expectOne((r) => r.url === `${productsUrl}/prod-1/variants`);
      expect(req.request.params.has('includeDisabled')).toBe(false);
      req.flush(variantPage([]));

      await promise;
    });

    it('should send includeDisabled=true only when the caller opts in', async () => {
      const promise = firstValueFrom(service.getVariants('prod-1', undefined, 0, 12, true));

      const req = httpMock.expectOne((r) => r.url === `${productsUrl}/prod-1/variants`);
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(variantPage([]));

      await promise;
    });

    it('should map a 404 to the not-found message on the error signal', async () => {
      const promise = firstValueFrom(service.getVariants('prod-missing'));

      const req = httpMock.expectOne((r) => r.url === `${productsUrl}/prod-missing/variants`);
      req.flush('not found', { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).toBe('No se encontró la variante');
    });
  });

  describe('getVariantById', () => {
    it('should GET the variant scoped to its product', async () => {
      const promise = firstValueFrom(service.getVariantById('prod-1', 'var-1'));

      const req = httpMock.expectOne(
        (r) => r.url === `${productsUrl}/prod-1/variants/var-1` && r.method === 'GET',
      );
      req.flush(mockVariant);

      const variant = await promise;
      expect(variant).toEqual(mockVariant);
    });

    it('should map a 403 to the permission message on the error signal', async () => {
      const promise = firstValueFrom(service.getVariantById('prod-1', 'var-1'));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants/var-1`);
      req.flush('forbidden', { status: 403, statusText: 'Forbidden' });

      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).toBe(
        'No tenés permisos para administrar las variantes de este producto',
      );
    });
  });

  describe('createVariant', () => {
    const request: ProductVariantRequest = { barCode: 'BAR-9', variantName: 'Talla 38' };

    it('should POST the global definition body to the nested URL', async () => {
      const promise = firstValueFrom(service.createVariant('prod-1', request));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockVariant);

      const created = await promise;
      expect(created).toEqual(mockVariant);
    });

    it('should map a 409 to the duplicate message on the error signal', async () => {
      const promise = firstValueFrom(service.createVariant('prod-1', request));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants`);
      req.flush('conflict', { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).toBe(
        'Ya existe una variante con ese código de barras o ese nombre para este producto',
      );
    });

    it('should map an unexpected status to the generic create message', async () => {
      const promise = firstValueFrom(service.createVariant('prod-1', request));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants`);
      req.flush('boom', { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).toBe('Error al crear la variante');
    });
  });

  describe('updateVariant', () => {
    const request: ProductVariantRequest = { barCode: 'BAR-9', variantName: 'Talla 40' };

    it('should PUT the body to the variant URL', async () => {
      const promise = firstValueFrom(service.updateVariant('prod-1', 'var-1', request));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants/var-1`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush(mockVariant);

      const updated = await promise;
      expect(updated).toEqual(mockVariant);
    });

    it('should map a 409 to the duplicate message on the error signal', async () => {
      const promise = firstValueFrom(service.updateVariant('prod-1', 'var-1', request));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants/var-1`);
      req.flush('conflict', { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).toBe(
        'Ya existe una variante con ese código de barras o ese nombre para este producto',
      );
    });
  });

  describe('deleteVariant', () => {
    it('should DELETE the variant under its product', async () => {
      const promise = firstValueFrom(service.deleteVariant('prod-1', 'var-1'));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants/var-1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await promise;
    });

    it('should map a 404 to the not-found message on the error signal', async () => {
      const promise = firstValueFrom(service.deleteVariant('prod-1', 'var-1'));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants/var-1`);
      req.flush('not found', { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).toBe('No se encontró la variante');
    });
  });

  describe('enableVariant', () => {
    it('should PATCH the enable endpoint', async () => {
      const promise = firstValueFrom(service.enableVariant('prod-1', 'var-1'));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants/var-1/enable`);
      expect(req.request.method).toBe('PATCH');
      req.flush({ ...mockVariant, enabled: true });

      const enabled = await promise;
      expect(enabled.enabled).toBe(true);
    });

    it('should map a 403 to the permission message on the error signal', async () => {
      const promise = firstValueFrom(service.enableVariant('prod-1', 'var-1'));

      const req = httpMock.expectOne(`${productsUrl}/prod-1/variants/var-1/enable`);
      req.flush('forbidden', { status: 403, statusText: 'Forbidden' });

      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).toBe(
        'No tenés permisos para administrar las variantes de este producto',
      );
    });
  });

  describe('upsertStoreStock', () => {
    const request: ProductVariantStoreStockRequest = { listPrice: 150, stock: 5 };

    it('should PUT the store body to the store-scoped variant URL', async () => {
      const promise = firstValueFrom(service.upsertStoreStock('var-1', 'store-1', request));

      const req = httpMock.expectOne(`${variantsUrl}/var-1/stores/store-1`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush(mockStoreStock);

      const stock = await promise;
      expect(stock).toEqual(mockStoreStock);
    });

    it('should map a 404 to the not-found message on the error signal', async () => {
      const promise = firstValueFrom(service.upsertStoreStock('var-1', 'store-1', request));

      const req = httpMock.expectOne(`${variantsUrl}/var-1/stores/store-1`);
      req.flush('not found', { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).toBe('No se encontró la variante');
    });
  });

  describe('searchVariants', () => {
    const searchUrl = 'http://localhost:9000/api/product-variants/search';

    const result: ProductVariantSearchResult = {
      id: 'var-1',
      productId: 'prod-1',
      companyStoreId: 'store-1',
      barCode: 'BAR-001',
      variantName: 'Product A — 1L',
      productName: 'Product A',
      listPrice: 150,
      costPrice: 80,
      stock: 5,
    };

    it('should GET the search endpoint with the query, the store and the paging', async () => {
      const promise = firstValueFrom(service.searchVariants('BAR-001', 'store-1', 0, 1));

      const req = httpMock.expectOne(
        (request) =>
          request.url === searchUrl &&
          request.params.get('q') === 'BAR-001' &&
          request.params.get('storeId') === 'store-1' &&
          request.params.get('page') === '0' &&
          request.params.get('size') === '1',
      );
      expect(req.request.method).toBe('GET');
      req.flush({
        content: [result],
        totalElements: 1,
        totalPages: 1,
        size: 1,
        number: 0,
        first: true,
        last: true,
        empty: false,
      });

      const page = await promise;
      expect(page.content).toEqual([result]);
    });

    it('should map an unexpected failure to the search fallback on the error signal', async () => {
      const promise = firstValueFrom(service.searchVariants('BAR-001', 'store-1'));

      httpMock
        .expectOne((request) => request.url === searchUrl)
        .flush('boom', {
          status: 500,
          statusText: 'Server Error',
        });

      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).toBe('Error al buscar las variantes de la tienda');
    });
  });

  describe('clearError', () => {
    it('should clear the error signal', async () => {
      const promise = firstValueFrom(service.deleteVariant('prod-1', 'var-1'));
      httpMock
        .expectOne(`${productsUrl}/prod-1/variants/var-1`)
        .flush('boom', { status: 500, statusText: 'Server Error' });
      await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
      expect(service.error()).not.toBeNull();

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });
});
