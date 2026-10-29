import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { SupplierService } from './supplier.service';
import { Page, Supplier, SupplierRequest } from '../models/supplier.models';

/**
 * The catalog's wire contract: every call names the company that owns the supplier.
 *
 * The unscoped `/api/suppliers` answers 404 since the catalog moved under the company (issue #672,
 * T2), so a method that still built the old path would not fail here on a shape mismatch — it would
 * fail in production with a 404 on every supplier screen. These assertions are the only place the
 * path is pinned without a browser.
 */
describe('SupplierService', () => {
  let service: SupplierService;
  let httpMock: HttpTestingController;

  const companyId = 'company-1';

  const mockSupplier: Supplier = {
    id: 'sup-1',
    supplierName: 'Acme Corp',
    razonSocial: 'Acme Corp S.A.',
    rfc: 'RFC-ACME',
    email: 'ventas@acme.com',
    phoneNumber: '555-0000',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    companyId,
  };

  const mockPage: Page<Supplier> = {
    content: [mockSupplier],
    totalElements: 1,
    totalPages: 1,
    size: 12,
    number: 0,
    first: true,
    last: true,
    empty: false,
  };

  const request: SupplierRequest = {
    supplierName: 'Acme Corp',
    razonSocial: 'Acme Corp S.A.',
    rfc: 'RFC-ACME',
    email: 'ventas@acme.com',
    phoneNumber: '555-0000',
    enabled: true,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [SupplierService],
    });
    service = TestBed.inject(SupplierService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should build the supplier collection under the company segment', () => {
    expect(service.suppliersUrl(companyId)).toMatch(/\/companies\/company-1\/suppliers$/);
  });

  describe('getSuppliers', () => {
    it('should fetch one company’s page, paging and size included', async () => {
      const promise = firstValueFrom(service.getSuppliers(companyId, 0, 12));

      const req = httpMock.expectOne(
        (candidate) =>
          candidate.url === service.suppliersUrl(companyId) && candidate.method === 'GET',
      );
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      expect(req.request.params.has('search')).toBe(false);
      req.flush(mockPage);

      const result = await promise;
      expect(result).toEqual(mockPage);
    });

    it('should forward the search term only when one is given', async () => {
      const promise = firstValueFrom(service.getSuppliers(companyId, 1, 24, 'acme'));

      const req = httpMock.expectOne(
        (candidate) => candidate.url === service.suppliersUrl(companyId),
      );
      expect(req.request.params.get('page')).toBe('1');
      expect(req.request.params.get('size')).toBe('24');
      expect(req.request.params.get('search')).toBe('acme');
      req.flush(mockPage);

      await promise;
    });
  });

  describe('getSupplierById', () => {
    it('should fetch the supplier under its company', async () => {
      const promise = firstValueFrom(service.getSupplierById(companyId, 'sup-1'));

      const req = httpMock.expectOne(`${service.suppliersUrl(companyId)}/sup-1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockSupplier);

      expect(await promise).toEqual(mockSupplier);
    });
  });

  describe('createSupplier', () => {
    it('should POST the body under the company that owns it', async () => {
      const promise = firstValueFrom(service.createSupplier(companyId, request));

      const req = httpMock.expectOne(service.suppliersUrl(companyId));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockSupplier);

      expect(await promise).toEqual(mockSupplier);
    });

    it('should expose and clear the write failure, rethrowing it', async () => {
      const promise = firstValueFrom(service.createSupplier(companyId, request));
      httpMock
        .expectOne(service.suppliersUrl(companyId))
        .flush(null, { status: 500, statusText: 'Error' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al crear el proveedor');
      expect(service.loading()).toBe(false);
      service.clearError();
      expect(service.error()).toBeNull();
    });
  });

  describe('updateSupplier', () => {
    it('should PUT the body under the company that owns it', async () => {
      const promise = firstValueFrom(service.updateSupplier(companyId, 'sup-1', request));

      const req = httpMock.expectOne(`${service.suppliersUrl(companyId)}/sup-1`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush(mockSupplier);

      expect(await promise).toEqual(mockSupplier);
    });
  });

  describe('deleteSupplier', () => {
    it('should DELETE the supplier under its company', async () => {
      const promise = firstValueFrom(service.deleteSupplier(companyId, 'sup-1'));

      const req = httpMock.expectOne(`${service.suppliersUrl(companyId)}/sup-1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await promise;
    });
  });

  describe('getAllSuppliers', () => {
    it('should be the same company-scoped read with a wider page', async () => {
      const promise = firstValueFrom(service.getAllSuppliers(companyId));

      const req = httpMock.expectOne(
        (candidate) => candidate.url === service.suppliersUrl(companyId),
      );
      expect(req.request.params.get('size')).toBe('1000');
      req.flush(mockPage);

      await promise;
    });
  });
});
