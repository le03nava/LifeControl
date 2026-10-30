import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { GoodsReceiptService } from './goods-receipt.service';
import { ConfigService } from '@app/services/config.service';
import type { GoodsReceipt, GoodsReceiptRequest } from '../models/receipt.models';
import type { Page } from '../../purchase-orders/models/purchase-order.models';

const TEST_API = 'http://test/api';

const mockReceipt: GoodsReceipt = {
  id: 'gr-1',
  receiptNumber: 'GR-20260101-00001',
  purchaseOrderId: 'po-1',
  orderNumber: 'PO-20260101-00001',
  companyStoreId: 'store-1',
  receivingLocationId: 'loc-1',
  statusId: 'st-received',
  statusName: 'Received',
  receivedBy: 'user-1',
  receivedAt: '2026-01-01T00:00:00Z',
  comments: 'Recepción completa',
  enabled: true,
  lines: [
    {
      id: 'grl-1',
      purchaseOrderDetailId: 'det-1',
      productVariantId: 'var-1',
      quantityReceived: 10.5,
      comments: null,
    },
  ],
};

const mockRequest: GoodsReceiptRequest = {
  purchaseOrderId: 'po-1',
  receivingLocationId: 'loc-1',
  comments: 'Recepción parcial',
  lines: [{ purchaseOrderDetailId: 'det-1', quantityReceived: 5.25, comments: null }],
};

/** The empty first page the filter tests answer with. */
const emptyPage: Page<GoodsReceipt> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  size: 12,
  number: 0,
  first: true,
  last: true,
  empty: true,
};

describe('GoodsReceiptService', () => {
  let service: GoodsReceiptService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [GoodsReceiptService, { provide: ConfigService, useValue: { apiUrl: TEST_API } }],
    });
    service = TestBed.inject(GoodsReceiptService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getReceipts', () => {
    it('should GET ${apiUrl}/goods-receipts with default page/size and omit search', async () => {
      const mockPage: Page<GoodsReceipt> = {
        content: [mockReceipt],
        totalElements: 1,
        totalPages: 1,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };

      const promise = firstValueFrom(service.getReceipts());

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/goods-receipts` && r.method === 'GET',
      );
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      expect(req.request.params.has('search')).toBe(false);
      req.flush(mockPage);

      const result = await promise;
      expect(result).toEqual(mockPage);
      expect(result.content[0].lines[0].quantityReceived).toBe(10.5);
      expect(typeof result.content[0].lines[0].quantityReceived).toBe('number');
    });

    it('should include the search param only when truthy', async () => {
      const mockPage: Page<GoodsReceipt> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: true,
      };

      const promise = firstValueFrom(service.getReceipts(0, 12, { search: 'GR-20260101' }));

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/goods-receipts` && r.method === 'GET',
      );
      expect(req.request.params.get('search')).toBe('GR-20260101');
      req.flush(mockPage);

      const result = await promise;
      expect(result.empty).toBe(true);
    });

    it('should omit the search param when an empty string is passed', async () => {
      const mockPage: Page<GoodsReceipt> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: true,
      };

      const promise = firstValueFrom(service.getReceipts(0, 12, { search: '' }));

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/goods-receipts` && r.method === 'GET',
      );
      expect(req.request.params.has('search')).toBe(false);
      req.flush(mockPage);

      await promise;
    });

    it('should forward custom pagination params', async () => {
      const mockPage: Page<GoodsReceipt> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 24,
        number: 2,
        first: false,
        last: true,
        empty: true,
      };

      const promise = firstValueFrom(service.getReceipts(2, 24));

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/goods-receipts` && r.method === 'GET',
      );
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('24');
      req.flush(mockPage);

      const result = await promise;
      expect(result.number).toBe(2);
      expect(result.size).toBe(24);
    });

    it('should send the five location filters alongside the search', async () => {
      const promise = firstValueFrom(
        service.getReceipts(0, 12, {
          search: 'GR-20260101',
          companyId: 'company-1',
          companyCountryId: 'country-1',
          regionId: 'region-1',
          zoneId: 'zone-1',
          companyStoreId: 'store-1',
        }),
      );

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/goods-receipts` && r.method === 'GET',
      );
      expect(req.request.params.get('search')).toBe('GR-20260101');
      expect(req.request.params.get('companyId')).toBe('company-1');
      expect(req.request.params.get('companyCountryId')).toBe('country-1');
      expect(req.request.params.get('regionId')).toBe('region-1');
      expect(req.request.params.get('zoneId')).toBe('zone-1');
      expect(req.request.params.get('companyStoreId')).toBe('store-1');
      req.flush(emptyPage);

      await promise;
    });

    it('should omit a level that arrives null or as an empty string', async () => {
      // The cascade reports a cleared level as `null`, so the service must not turn it into the
      // literal `null`/`''` a filter would narrow the list with.
      const promise = firstValueFrom(
        service.getReceipts(0, 12, {
          companyId: 'company-1',
          companyCountryId: null,
          regionId: null,
          zoneId: '',
          companyStoreId: null,
        }),
      );

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/goods-receipts` && r.method === 'GET',
      );
      expect(req.request.params.get('companyId')).toBe('company-1');
      expect(req.request.params.has('companyCountryId')).toBe(false);
      expect(req.request.params.has('regionId')).toBe(false);
      expect(req.request.params.has('zoneId')).toBe(false);
      expect(req.request.params.has('companyStoreId')).toBe(false);
      expect(req.request.params.has('search')).toBe(false);
      req.flush(emptyPage);

      await promise;
    });

    it('should send only pagination when the query object is empty', async () => {
      const promise = firstValueFrom(service.getReceipts(1, 24, {}));

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/goods-receipts` && r.method === 'GET',
      );
      expect(req.request.params.keys().sort()).toEqual(['page', 'size']);
      req.flush(emptyPage);

      await promise;
    });

    it('should propagate HTTP failure', async () => {
      const promise = firstValueFrom(service.getReceipts());

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/goods-receipts` && r.method === 'GET',
      );
      req.flush({ message: 'Server error' }, { status: 500, statusText: 'Internal Server Error' });

      await expect(promise).rejects.toEqual(expect.objectContaining({ status: 500 }));
    });
  });

  describe('getReceipt', () => {
    it('should GET ${apiUrl}/goods-receipts/${id}', async () => {
      const promise = firstValueFrom(service.getReceipt('gr-1'));

      const req = httpMock.expectOne((r) => r.url === `${TEST_API}/goods-receipts/gr-1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockReceipt);

      const result = await promise;
      expect(result).toEqual(mockReceipt);
      expect(result.id).toBe('gr-1');
    });

    it('should construct the URL with the provided UUID', async () => {
      const customId = '550e8400-e29b-41d4-a716-446655440000';
      const promise = firstValueFrom(service.getReceipt(customId));

      const req = httpMock.expectOne(`${TEST_API}/goods-receipts/${customId}`);
      req.flush({ ...mockReceipt, id: customId });

      const result = await promise;
      expect(result.id).toBe(customId);
    });

    it('should propagate a 404', async () => {
      const promise = firstValueFrom(service.getReceipt('missing'));

      const req = httpMock.expectOne(`${TEST_API}/goods-receipts/missing`);
      req.flush({ message: 'Not found' }, { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toEqual(expect.objectContaining({ status: 404 }));
    });
  });

  describe('createReceipt', () => {
    it('should POST the request body to ${apiUrl}/goods-receipts', async () => {
      const promise = firstValueFrom(service.createReceipt(mockRequest));

      const req = httpMock.expectOne(`${TEST_API}/goods-receipts`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(mockRequest);
      req.flush(mockReceipt);

      const result = await promise;
      expect(result.id).toBe('gr-1');
    });

    it('should propagate a 400 validation error', async () => {
      const promise = firstValueFrom(service.createReceipt(mockRequest));

      const req = httpMock.expectOne(`${TEST_API}/goods-receipts`);
      expect(req.request.method).toBe('POST');
      req.flush(
        { message: 'Validation failed', errors: { lines: 'must not be empty' } },
        { status: 400, statusText: 'Bad Request' },
      );

      await expect(promise).rejects.toEqual(expect.objectContaining({ status: 400 }));
    });
  });
});
