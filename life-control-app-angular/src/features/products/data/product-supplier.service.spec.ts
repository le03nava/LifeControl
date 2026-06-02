import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ProductSupplierService } from './product-supplier.service';
import { ProductSupplier } from '../models/product-supplier.models';

describe('ProductSupplierService', () => {
  let service: ProductSupplierService;
  let httpMock: HttpTestingController;

  const mockSupplier: ProductSupplier = {
    id: 'ps-1',
    productId: 'prod-1',
    supplierId: 'sup-1',
    supplierName: 'Acme Corp',
    purchaseCost: 150.5,
    main: true,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ProductSupplierService],
    });
    service = TestBed.inject(ProductSupplierService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getSuppliers', () => {
    it('should fetch suppliers for a product', async () => {
      const mockSuppliers: ProductSupplier[] = [mockSupplier];

      const promise = firstValueFrom(service.getSuppliers('prod-1'));

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${service.apiUrl}/prod-1/suppliers` &&
          r.method === 'GET',
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockSuppliers);

      const result = await promise;
      expect(result).toEqual(mockSuppliers);
      expect(result.length).toBe(1);
      expect(result[0].supplierName).toBe('Acme Corp');
    });

    it('should construct URL with productId', async () => {
      const promise = firstValueFrom(service.getSuppliers('prod-99'));

      const req = httpMock.expectOne(
        `${service.apiUrl}/prod-99/suppliers`,
      );
      expect(req.request.url).toContain('prod-99');
      req.flush([]);

      await promise;
    });
  });

  describe('addSupplier', () => {
    it('should POST new supplier assignment', async () => {
      const request = {
        supplierId: 'sup-1',
        purchaseCost: 100,
        main: false,
        enabled: true,
      };

      const promise = firstValueFrom(service.addSupplier('prod-1', request));

      const req = httpMock.expectOne(
        `${service.apiUrl}/prod-1/suppliers`,
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockSupplier);

      const result = await promise;
      expect(result).toEqual(mockSupplier);
    });
  });

  describe('updateSupplier', () => {
    it('should PUT updated supplier assignment', async () => {
      const request = {
        supplierId: 'sup-1',
        purchaseCost: 200,
        main: true,
        enabled: true,
      };

      const promise = firstValueFrom(
        service.updateSupplier('prod-1', 'ps-1', request),
      );

      const req = httpMock.expectOne(
        `${service.apiUrl}/prod-1/suppliers/ps-1`,
      );
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush(mockSupplier);

      const result = await promise;
      expect(result).toEqual(mockSupplier);
    });
  });

  describe('removeSupplier', () => {
    it('should DELETE supplier assignment', async () => {
      const promise = firstValueFrom(
        service.removeSupplier('prod-1', 'ps-1'),
      );

      const req = httpMock.expectOne(
        `${service.apiUrl}/prod-1/suppliers/ps-1`,
      );
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await promise;
    });
  });
});
