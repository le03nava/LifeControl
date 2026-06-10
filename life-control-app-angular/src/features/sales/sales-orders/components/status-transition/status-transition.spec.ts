import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { StatusTransition } from './status-transition';
import { SalesOrderService } from '../../data/sales-order.service';
import { NotificationService } from '@shared/data/notification';
import { ConfigService } from '@app/services/config.service';
import type { SalesOrder } from '../../models/sales-order.models';

const TEST_API = 'http://test/api';

function createOrder(overrides: Partial<SalesOrder> = {}): SalesOrder {
  return {
    id: 'so-1',
    orderNumber: 'SO-00001',
    customerId: 'cust-1',
    customerName: 'John Doe',
    companyStoreId: 'store-1',
    companyStoreName: 'Test Store',
    shiftId: 'shift-1',
    orderDate: '2026-01-01T00:00:00Z',
    statusId: 'st-draft',
    statusName: 'Draft',
    totalAmount: 1200.0,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    items: [],
    ...overrides,
  };
}

const mockStatusTypesPage = {
  content: [
    { id: 'type-so', statusTypeName: 'SALES_ORDER' },
    { id: 'type-other', statusTypeName: 'OTHER' },
  ],
};

const mockStatuses = [
  { id: 'st-draft', name: 'Draft' },
  { id: 'st-pending', name: 'Pending' },
  { id: 'st-completed', name: 'Completed' },
  { id: 'st-cancelled', name: 'Cancelled' },
];

describe('StatusTransition', () => {
  let salesOrderService: {
    updateStatus: ReturnType<typeof vi.fn>;
  };
  let notificationService: {
    showSuccess: ReturnType<typeof vi.fn>;
    showError: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    salesOrderService = {
      updateStatus: vi.fn(),
    };
    notificationService = {
      showSuccess: vi.fn(),
      showError: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [StatusTransition, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
        { provide: SalesOrderService, useValue: salesOrderService },
        { provide: NotificationService, useValue: notificationService },
      ],
    }).compileComponents();
  });

  function setupWithOrder(
    order: SalesOrder,
    httpMock: HttpTestingController,
  ): { fixture: ComponentFixture<StatusTransition>; comp: StatusTransition } {
    const fixture = TestBed.createComponent(StatusTransition);
    const comp = fixture.componentInstance;

    fixture.componentRef.setInput('order', order);
    fixture.detectChanges();

    // Flush status type lookup
    const typeReq = httpMock.expectOne(
      (r) =>
        r.url === `${TEST_API}/status-types` &&
        r.params.get('search') === 'SALES_ORDER',
    );
    typeReq.flush(mockStatusTypesPage);

    // Flush statuses lookup
    const statusReq = httpMock.expectOne(
      (r) =>
        r.url === `${TEST_API}/statuses` &&
        r.params.get('statusTypeId') === 'type-so',
    );
    statusReq.flush(mockStatuses);

    fixture.detectChanges();
    return { fixture, comp };
  }

  describe('initial creation', () => {
    let httpMock: HttpTestingController;

    beforeEach(() => {
      httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
      httpMock.verify();
    });

    it('should create', () => {
      const order = createOrder();
      const { comp } = setupWithOrder(order, httpMock);
      expect(comp).toBeTruthy();
    });

    it('should show current status as chip', () => {
      const order = createOrder({ statusName: 'Draft' });
      const { fixture } = setupWithOrder(order, httpMock);
      expect(fixture.nativeElement.textContent).toContain('Draft');
    });
  });

  describe('valid transitions per status', () => {
    let httpMock: HttpTestingController;

    beforeEach(() => {
      httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
      httpMock.verify();
    });

    it('Draft: should allow Pending and Cancelled', () => {
      const order = createOrder({ statusName: 'Draft' });
      const { comp } = setupWithOrder(order, httpMock);

      const names = comp.validTransitions().map((t) => t.name);
      expect(names).toContain('Pending');
      expect(names).toContain('Cancelled');
      expect(names.length).toBe(2);
    });

    it('Pending: should allow Completed and Cancelled', () => {
      const order = createOrder({
        statusName: 'Pending',
        statusId: 'st-pending',
      });
      const { comp } = setupWithOrder(order, httpMock);

      const names = comp.validTransitions().map((t) => t.name);
      expect(names).toContain('Completed');
      expect(names).toContain('Cancelled');
      expect(names.length).toBe(2);
    });

    it('Completed: should be terminal (no transitions)', () => {
      const order = createOrder({
        statusName: 'Completed',
        statusId: 'st-completed',
      });
      const { comp } = setupWithOrder(order, httpMock);

      expect(comp.isTerminal()).toBe(true);
      expect(comp.validTransitions()).toEqual([]);
    });

    it('Cancelled: should be terminal (no transitions)', () => {
      const order = createOrder({
        statusName: 'Cancelled',
        statusId: 'st-cancelled',
      });
      const { comp } = setupWithOrder(order, httpMock);

      expect(comp.isTerminal()).toBe(true);
      expect(comp.validTransitions()).toEqual([]);
    });
  });

  describe('status change', () => {
    let httpMock: HttpTestingController;

    beforeEach(() => {
      httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
      httpMock.verify();
    });

    it('should call salesOrderService.updateStatus with correct params', () => {
      const order = createOrder({ statusName: 'Draft' });
      const { comp } = setupWithOrder(order, httpMock);

      salesOrderService.updateStatus = vi.fn().mockReturnValue(of(order));

      comp.onStatusChange('st-pending');

      expect(salesOrderService.updateStatus).toHaveBeenCalledWith('so-1', {
        statusId: 'st-pending',
      });
    });

    it('should emit statusChanged on success', () => {
      const order = createOrder({ statusName: 'Draft' });
      const { comp } = setupWithOrder(order, httpMock);

      let emitted = '';
      comp.statusChanged.subscribe((id: string) => {
        emitted = id;
      });

      salesOrderService.updateStatus = vi
        .fn()
        .mockReturnValue(of({ ...order, statusName: 'Pending' }));

      comp.onStatusChange('st-pending');

      expect(emitted).toBe('st-pending');
    });

    it('should show success notification', () => {
      const order = createOrder({ statusName: 'Draft' });
      const { comp } = setupWithOrder(order, httpMock);

      salesOrderService.updateStatus = vi.fn().mockReturnValue(of(order));

      comp.onStatusChange('st-pending');

      expect(notificationService.showSuccess).toHaveBeenCalledWith(
        'Status updated successfully.',
      );
    });

    it('should show error on 409 conflict', () => {
      const order = createOrder({ statusName: 'Draft' });
      const { comp } = setupWithOrder(order, httpMock);

      salesOrderService.updateStatus = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({ status: 409, statusText: 'Conflict' }),
        ),
      );

      comp.onStatusChange('st-cancelled');

      expect(notificationService.showError).toHaveBeenCalledWith(
        'Status transition not allowed.',
      );
    });

    it('should show error on 404', () => {
      const order = createOrder({ statusName: 'Draft' });
      const { comp } = setupWithOrder(order, httpMock);

      salesOrderService.updateStatus = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({ status: 404, statusText: 'Not Found' }),
        ),
      );

      comp.onStatusChange('st-pending');

      expect(notificationService.showError).toHaveBeenCalledWith(
        'Sales order not found.',
      );
    });
  });
});
