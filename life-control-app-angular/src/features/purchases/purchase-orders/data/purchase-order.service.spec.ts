import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { PurchaseOrderService } from './purchase-order.service';
import { ConfigService } from '@app/services/config.service';
import type {
  PurchaseOrder,
  PurchaseOrderRequest,
  PurchaseOrderDetailRequest,
  UpdatePurchaseOrderStatusRequest,
  Page,
} from '../models/purchase-order.models';

const TEST_API = 'http://test/api';

const mockOrder: PurchaseOrder = {
  id: 'po-1',
  orderNumber: 'PO-00001',
  supplierId: 'sup-1',
  supplierName: 'Acme Corp',
  companyStoreId: 'store-1',
  companyStoreName: 'Tienda Central',
  companyId: null,
  companyCountryId: null,
  regionId: null,
  zoneId: null,
  paymentMethodId: 'pm-1',
  paymentMethodName: 'Transferencia',
  statusId: 'st-draft',
  statusName: 'Draft',
  comments: 'Test order',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  details: [],
};

const mockRequest: PurchaseOrderRequest = {
  supplierId: 'sup-1',
  companyStoreId: 'store-1',
  paymentMethodId: 'pm-1',
  comments: 'New order',
};

const mockDetailRequest: PurchaseOrderDetailRequest = {
  productId: 'prod-1',
  quantity: 10,
  unitPrice: 150,
};

const mockStatusRequest: UpdatePurchaseOrderStatusRequest = {
  statusId: 'st-sent',
};

describe('PurchaseOrderService', () => {
  let service: PurchaseOrderService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        PurchaseOrderService,
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
      ],
    });
    service = TestBed.inject(PurchaseOrderService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getPurchaseOrders', () => {
    it('should fetch paginated orders with default params', async () => {
      const mockPage: Page<PurchaseOrder> = {
        content: [mockOrder],
        totalElements: 1,
        totalPages: 1,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };

      const promise = firstValueFrom(service.getPurchaseOrders(0, 12));

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/purchase-orders` && r.method === 'GET',
      );
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      expect(req.request.params.has('search')).toBe(false);
      req.flush(mockPage);

      const result = await promise;
      expect(result).toEqual(mockPage);
      expect(result.content.length).toBe(1);
    });

    it('should include search param when provided', async () => {
      const mockPage: Page<PurchaseOrder> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: true,
      };

      const promise = firstValueFrom(
        service.getPurchaseOrders(0, 12, 'Acme'),
      );

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/purchase-orders` && r.method === 'GET',
      );
      expect(req.request.params.get('search')).toBe('Acme');
      req.flush(mockPage);

      const result = await promise;
      expect(result.empty).toBe(true);
    });

    it('should handle pagination params with custom page and size', async () => {
      const mockPage: Page<PurchaseOrder> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 24,
        number: 2,
        first: false,
        last: true,
        empty: true,
      };

      const promise = firstValueFrom(service.getPurchaseOrders(2, 24));

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/purchase-orders` && r.method === 'GET',
      );
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('24');
      req.flush(mockPage);

      const result = await promise;
      expect(result.number).toBe(2);
      expect(result.size).toBe(24);
    });

    it('should propagate error on HTTP failure', async () => {
      const promise = firstValueFrom(service.getPurchaseOrders(0, 12));

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/purchase-orders` && r.method === 'GET',
      );
      req.flush(
        { message: 'Server error' },
        { status: 500, statusText: 'Internal Server Error' },
      );

      await expect(promise).rejects.toEqual(
        expect.objectContaining({ status: 500 }),
      );
    });
  });

  describe('getPurchaseOrder', () => {
    it('should fetch a single purchase order by ID', async () => {
      const promise = firstValueFrom(service.getPurchaseOrder('po-1'));

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders/po-1`,
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockOrder);

      const result = await promise;
      expect(result).toEqual(mockOrder);
      expect(result.id).toBe('po-1');
    });

    it('should construct URL with the provided UUID', async () => {
      const customId = '550e8400-e29b-41d4-a716-446655440000';
      const promise = firstValueFrom(service.getPurchaseOrder(customId));

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders/${customId}`,
      );
      expect(req.request.url).toContain(customId);
      req.flush({ ...mockOrder, id: customId });

      const result = await promise;
      expect(result.id).toBe(customId);
    });

    it('should propagate error on 404', async () => {
      const promise = firstValueFrom(service.getPurchaseOrder('bad-id'));

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders/bad-id`,
      );
      req.flush(
        { message: 'Not found' },
        { status: 404, statusText: 'Not Found' },
      );

      await expect(promise).rejects.toEqual(
        expect.objectContaining({ status: 404 }),
      );
    });
  });

  describe('create', () => {
    it('should POST a new purchase order', async () => {
      const created: PurchaseOrder = { ...mockOrder, id: 'po-new' };

      const promise = firstValueFrom(service.create(mockRequest));

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders`,
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(mockRequest);
      req.flush(created);

      const result = await promise;
      expect(result.id).toBe('po-new');
    });

    it('should propagate 400 validation errors', async () => {
      const promise = firstValueFrom(service.create(mockRequest));

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders`,
      );
      req.flush(
        { message: 'Validation failed', errors: { supplierId: 'Required' } },
        { status: 400, statusText: 'Bad Request' },
      );

      await expect(promise).rejects.toEqual(
        expect.objectContaining({ status: 400 }),
      );
    });
  });

  describe('update', () => {
    it('should PUT an existing purchase order', async () => {
      const updated: PurchaseOrder = {
        ...mockOrder,
        comments: 'Updated comments',
      };

      const promise = firstValueFrom(
        service.update('po-1', { ...mockRequest, comments: 'Updated comments' }),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders/po-1`,
      );
      expect(req.request.method).toBe('PUT');
      expect(req.request.body.comments).toBe('Updated comments');
      req.flush(updated);

      const result = await promise;
      expect(result.comments).toBe('Updated comments');
    });
  });

  describe('updateStatus', () => {
    it('should PATCH the purchase order status', async () => {
      const updated: PurchaseOrder = {
        ...mockOrder,
        statusId: 'st-sent',
        statusName: 'Sent',
      };

      const promise = firstValueFrom(
        service.updateStatus('po-1', mockStatusRequest),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders/po-1/status`,
      );
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual(mockStatusRequest);
      req.flush(updated);

      const result = await promise;
      expect(result.statusName).toBe('Sent');
    });

    it('should propagate 409 conflict on invalid transition', async () => {
      const promise = firstValueFrom(
        service.updateStatus('po-1', mockStatusRequest),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders/po-1/status`,
      );
      req.flush(
        { message: 'Transición de estado no permitida' },
        { status: 409, statusText: 'Conflict' },
      );

      await expect(promise).rejects.toEqual(
        expect.objectContaining({ status: 409 }),
      );
    });
  });

  describe('addDetail', () => {
    it('should POST a new detail line item', async () => {
      const updated: PurchaseOrder = {
        ...mockOrder,
        details: [
          {
            id: 'det-1',
            purchaseOrderId: 'po-1',
            productId: 'prod-1',
            productName: 'Widget A',
            quantity: 10,
            unitPrice: 150,
            total: 1500,
            receivedQuantity: 0,
            comments: null,
            statusId: 'st-draft',
            statusName: 'Draft',
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        ],
      };

      const promise = firstValueFrom(
        service.addDetail('po-1', mockDetailRequest),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders/po-1/details`,
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(mockDetailRequest);
      req.flush(updated);

      const result = await promise;
      expect(result.details.length).toBe(1);
    });
  });

  describe('updateDetail', () => {
    it('should PUT an existing detail line item', async () => {
      const updated: PurchaseOrder = {
        ...mockOrder,
        details: [
          {
            id: 'det-1',
            purchaseOrderId: 'po-1',
            productId: 'prod-1',
            productName: 'Widget A',
            quantity: 20,
            unitPrice: 150,
            total: 3000,
            receivedQuantity: 0,
            comments: null,
            statusId: 'st-draft',
            statusName: 'Draft',
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        ],
      };

      const updatedDetail: PurchaseOrderDetailRequest = {
        ...mockDetailRequest,
        quantity: 20,
      };

      const promise = firstValueFrom(
        service.updateDetail('po-1', 'det-1', updatedDetail),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders/po-1/details/det-1`,
      );
      expect(req.request.method).toBe('PUT');
      expect(req.request.body.quantity).toBe(20);
      req.flush(updated);

      const result = await promise;
      expect(result.details[0].quantity).toBe(20);
    });
  });

  describe('deleteDetail', () => {
    it('should DELETE a detail line item', async () => {
      const promise = firstValueFrom(
        service.deleteDetail('po-1', 'det-1'),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/purchase-orders/po-1/details/det-1`,
      );
      expect(req.request.method).toBe('DELETE');
      req.flush(mockOrder);

      const result = await promise;
      expect(result).toEqual(mockOrder);
    });

    it('should construct the correct nested URL', async () => {
      const promise = firstValueFrom(
        service.deleteDetail('po-abc', 'det-xyz'),
      );

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/purchase-orders/po-abc/details/det-xyz` &&
          r.method === 'DELETE',
      );
      req.flush(mockOrder);

      await promise;
    });
  });
});
