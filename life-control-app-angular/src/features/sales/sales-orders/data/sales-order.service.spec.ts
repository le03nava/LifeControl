import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { SalesOrderService } from './sales-order.service';
import { ConfigService } from '@app/services/config.service';
import type {
  SalesOrder,
  SalesOrderItem,
  SalesOrderRequest,
  SalesOrderItemRequest,
  UpdateSalesOrderStatusRequest,
  Page,
} from '../models/sales-order.models';

const TEST_API = 'http://test/api';

const mockOrder: SalesOrder = {
  id: 'so-1',
  orderNumber: 'SO-00001',
  customerId: 'cust-1',
  customerName: 'John Doe',
  companyStoreId: 'store-1',
  companyStoreName: 'Main Store',
  shiftId: 'shift-1',
  shiftName: 'Morning Shift',
  userId: 'user-1',
  orderDate: '2026-01-01T00:00:00Z',
  statusId: 'st-draft',
  statusName: 'Draft',
  totalAmount: 1500,
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  items: [],
};

const mockItem: SalesOrderItem = {
  id: 'item-1',
  salesOrderId: 'so-1',
  productVariantId: 'pv-1',
  productVariantName: 'Widget Blue Large',
  quantity: 2,
  listPrice: 500,
  discountApplied: 50,
  finalPrice: 450,
  promotionId: undefined,
  statusId: 'st-pending',
  statusName: 'Pending',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const mockRequest: SalesOrderRequest = {
  customerId: 'cust-1',
  companyStoreId: 'store-1',
  shiftId: 'shift-1',
};

const mockItemRequest: SalesOrderItemRequest = {
  productVariantId: 'pv-1',
  quantity: 2,
  listPrice: 500,
  discountApplied: 50,
};

const mockStatusRequest: UpdateSalesOrderStatusRequest = {
  statusId: 'st-pending',
};

describe('SalesOrderService', () => {
  let service: SalesOrderService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        SalesOrderService,
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
      ],
    });
    service = TestBed.inject(SalesOrderService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getSalesOrders', () => {
    it('should fetch paginated orders with default params', async () => {
      const mockPage: Page<SalesOrder> = {
        content: [mockOrder],
        totalElements: 1,
        totalPages: 1,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };

      const promise = firstValueFrom(service.getSalesOrders(0, 12));

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/sales-orders` && r.method === 'GET',
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
      const mockPage: Page<SalesOrder> = {
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
        service.getSalesOrders(0, 12, 'John'),
      );

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/sales-orders` && r.method === 'GET',
      );
      expect(req.request.params.get('search')).toBe('John');
      req.flush(mockPage);

      const result = await promise;
      expect(result.empty).toBe(true);
    });

    it('should handle pagination with custom page and size', async () => {
      const mockPage: Page<SalesOrder> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 24,
        number: 2,
        first: false,
        last: true,
        empty: true,
      };

      const promise = firstValueFrom(service.getSalesOrders(2, 24));

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/sales-orders` && r.method === 'GET',
      );
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('24');
      req.flush(mockPage);

      const result = await promise;
      expect(result.number).toBe(2);
      expect(result.size).toBe(24);
    });

    it('should propagate error on HTTP failure', async () => {
      const promise = firstValueFrom(service.getSalesOrders(0, 12));

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/sales-orders` && r.method === 'GET',
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

  describe('getSalesOrder', () => {
    it('should fetch a single sales order by ID', async () => {
      const promise = firstValueFrom(service.getSalesOrder('so-1'));

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1`,
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockOrder);

      const result = await promise;
      expect(result).toEqual(mockOrder);
      expect(result.id).toBe('so-1');
    });

    it('should construct URL with the provided ID', async () => {
      const customId = '550e8400-e29b-41d4-a716-446655440000';
      const promise = firstValueFrom(service.getSalesOrder(customId));

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/${customId}`,
      );
      expect(req.request.url).toContain(customId);
      req.flush({ ...mockOrder, id: customId });

      const result = await promise;
      expect(result.id).toBe(customId);
    });

    it('should propagate 404 error', async () => {
      const promise = firstValueFrom(service.getSalesOrder('bad-id'));

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/bad-id`,
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
    it('should POST a new sales order', async () => {
      const created: SalesOrder = { ...mockOrder, id: 'so-new' };

      const promise = firstValueFrom(service.create(mockRequest));

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders`,
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(mockRequest);
      req.flush(created);

      const result = await promise;
      expect(result.id).toBe('so-new');
    });

    it('should propagate 400 validation errors', async () => {
      const promise = firstValueFrom(service.create(mockRequest));

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders`,
      );
      req.flush(
        { message: 'Validation failed', errors: { customerId: 'Required' } },
        { status: 400, statusText: 'Bad Request' },
      );

      await expect(promise).rejects.toEqual(
        expect.objectContaining({ status: 400 }),
      );
    });
  });

  describe('update', () => {
    it('should PUT an existing sales order', async () => {
      const updated: SalesOrder = {
        ...mockOrder,
        customerName: 'Jane Doe',
      };
      const updateReq: SalesOrderRequest = {
        ...mockRequest,
        customerId: 'cust-2',
      };

      const promise = firstValueFrom(service.update('so-1', updateReq));

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1`,
      );
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(updateReq);
      req.flush(updated);

      const result = await promise;
      expect(result.customerName).toBe('Jane Doe');
    });
  });

  describe('updateStatus', () => {
    it('should PATCH the sales order status', async () => {
      const updated: SalesOrder = {
        ...mockOrder,
        statusId: 'st-pending',
        statusName: 'Pending',
      };

      const promise = firstValueFrom(
        service.updateStatus('so-1', mockStatusRequest),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1/status`,
      );
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual(mockStatusRequest);
      req.flush(updated);

      const result = await promise;
      expect(result.statusName).toBe('Pending');
    });

    it('should propagate 409 conflict on invalid transition', async () => {
      const promise = firstValueFrom(
        service.updateStatus('so-1', mockStatusRequest),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1/status`,
      );
      req.flush(
        { message: 'Transition not allowed' },
        { status: 409, statusText: 'Conflict' },
      );

      await expect(promise).rejects.toEqual(
        expect.objectContaining({ status: 409 }),
      );
    });
  });

  describe('enable', () => {
    it('should PATCH to enable a sales order', async () => {
      const enabled: SalesOrder = { ...mockOrder, enabled: true };

      const promise = firstValueFrom(service.enable('so-1'));

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1/enable`,
      );
      expect(req.request.method).toBe('PATCH');
      req.flush(enabled);

      const result = await promise;
      expect(result.enabled).toBe(true);
    });
  });

  describe('delete', () => {
    it('should DELETE a sales order', async () => {
      const promise = firstValueFrom(service.delete('so-1'));

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1`,
      );
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      const result = await promise;
      expect(result).toBeNull();
    });
  });

  describe('getItems', () => {
    it('should fetch items for a sales order', async () => {
      const items: SalesOrderItem[] = [mockItem];

      const promise = firstValueFrom(service.getItems('so-1'));

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1/items`,
      );
      expect(req.request.method).toBe('GET');
      req.flush(items);

      const result = await promise;
      expect(result).toEqual(items);
      expect(result.length).toBe(1);
    });
  });

  describe('addItem', () => {
    it('should POST a new line item', async () => {
      const newItem: SalesOrderItem = { ...mockItem, id: 'item-new' };

      const promise = firstValueFrom(
        service.addItem('so-1', mockItemRequest),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1/items`,
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(mockItemRequest);
      req.flush(newItem);

      const result = await promise;
      expect(result.id).toBe('item-new');
    });
  });

  describe('updateItem', () => {
    it('should PUT an existing line item', async () => {
      const updatedItem: SalesOrderItem = {
        ...mockItem,
        quantity: 5,
        finalPrice: 2250,
      };
      const updateReq: SalesOrderItemRequest = {
        ...mockItemRequest,
        quantity: 5,
      };

      const promise = firstValueFrom(
        service.updateItem('so-1', 'item-1', updateReq),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1/items/item-1`,
      );
      expect(req.request.method).toBe('PUT');
      expect(req.request.body.quantity).toBe(5);
      req.flush(updatedItem);

      const result = await promise;
      expect(result.quantity).toBe(5);
    });
  });

  describe('deleteItem', () => {
    it('should DELETE a line item', async () => {
      const promise = firstValueFrom(
        service.deleteItem('so-1', 'item-1'),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1/items/item-1`,
      );
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      const result = await promise;
      expect(result).toBeNull();
    });
  });

  describe('updateItemStatus', () => {
    it('should PATCH the item status', async () => {
      const updatedItem: SalesOrderItem = {
        ...mockItem,
        statusId: 'st-added',
        statusName: 'Added',
      };

      const promise = firstValueFrom(
        service.updateItemStatus('so-1', 'item-1', 'st-added'),
      );

      const req = httpMock.expectOne(
        `${TEST_API}/sales-orders/so-1/items/item-1/status`,
      );
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual({ statusId: 'st-added' });
      req.flush(updatedItem);

      const result = await promise;
      expect(result.statusName).toBe('Added');
    });
  });
});
