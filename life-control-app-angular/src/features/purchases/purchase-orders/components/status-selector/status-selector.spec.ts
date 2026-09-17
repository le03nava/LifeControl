import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { StatusSelector } from './status-selector';
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { StatusService } from '../../data/status.service';
import { NotificationService } from '@shared/data/notification';
import type { PurchaseOrder } from '../../models/purchase-order.models';

function createOrder(overrides: Partial<PurchaseOrder> = {}): PurchaseOrder {
  return {
    id: 'po-1',
    orderNumber: 'PO-00001',
    supplierId: 'sup-1',
    supplierName: 'Acme Corp',
    companyStoreId: 'store-1',
    companyStoreName: 'Tienda Centro',
    companyId: null,
    companyCountryId: null,
    regionId: null,
    zoneId: null,
    paymentMethodId: 'pm-1',
    paymentMethodName: 'Transferencia',
    statusId: 'st-draft',
    statusName: 'Draft',
    comments: null,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    details: [],
    ...overrides,
  };
}

const mockStatuses = [
  { id: 'st-draft', name: 'Draft' },
  { id: 'st-sent', name: 'Sent' },
  { id: 'st-accepted', name: 'Accepted' },
  { id: 'st-in-transit', name: 'In Transit' },
  { id: 'st-received', name: 'Received' },
  { id: 'st-billed', name: 'Billed' },
  { id: 'st-closed', name: 'Closed' },
  { id: 'st-rejected', name: 'Rejected' },
];

describe('StatusSelector', () => {
  let purchaseOrderService: {
    updateStatus: ReturnType<typeof vi.fn>;
  };
  let statusService: {
    getStatusTypeIdByName: ReturnType<typeof vi.fn>;
    getStatusesByTypeId: ReturnType<typeof vi.fn>;
  };
  let notificationService: {
    showSuccess: ReturnType<typeof vi.fn>;
    showError: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    purchaseOrderService = {
      updateStatus: vi.fn(),
    };
    statusService = {
      getStatusTypeIdByName: vi.fn().mockReturnValue(of('type-po')),
      getStatusesByTypeId: vi.fn().mockReturnValue(of(mockStatuses)),
    };
    notificationService = {
      showSuccess: vi.fn(),
      showError: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [StatusSelector, NoopAnimationsModule],
      providers: [
        { provide: PurchaseOrderService, useValue: purchaseOrderService },
        { provide: StatusService, useValue: statusService },
        { provide: NotificationService, useValue: notificationService },
      ],
    }).compileComponents();
  });

  /** Creates the component with a given order and runs initial change detection. */
  function setupWithOrder(order: PurchaseOrder): ComponentFixture<StatusSelector> {
    const f = TestBed.createComponent(StatusSelector);
    f.componentRef.setInput('order', order);
    f.detectChanges();
    return f;
  }

  describe('initial creation', () => {
    it('should create', () => {
      const component = TestBed.createComponent(StatusSelector).componentInstance;
      expect(component).toBeTruthy();
    });

    it('should resolve the PURCHASE_ORDER status type and load statuses', () => {
      const order = createOrder();
      setupWithOrder(order);

      expect(statusService.getStatusTypeIdByName).toHaveBeenCalledWith('PURCHASE_ORDER');
      expect(statusService.getStatusesByTypeId).toHaveBeenCalledWith('type-po');
    });

    it('should set statusFetchFailed when statuses cannot be loaded', () => {
      statusService.getStatusesByTypeId = vi
        .fn()
        .mockReturnValue(throwError(() => new Error('boom')));

      const order = createOrder();
      const f = setupWithOrder(order);

      expect(f.componentInstance.statusFetchFailed()).toBe(true);
    });
  });

  describe('valid transitions per status', () => {
    it('Draft: dropdown should show Sent and Rejected', () => {
      const order = createOrder({ statusName: 'Draft' });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      const transitions = comp.validTransitions();
      const names = transitions.map((t) => t.name);
      expect(names).toContain('Sent');
      expect(names).toContain('Rejected');
      expect(names).toHaveLength(2);
    });

    it('Sent: dropdown should show Accepted and Rejected', () => {
      const order = createOrder({
        statusName: 'Sent',
        statusId: 'st-sent',
      });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      const names = comp.validTransitions().map((t) => t.name);
      expect(names).toContain('Accepted');
      expect(names).toContain('Rejected');
      expect(names).toHaveLength(2);
    });

    it('Accepted: dropdown should show In Transit and Rejected', () => {
      const order = createOrder({
        statusName: 'Accepted',
        statusId: 'st-accepted',
      });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      const names = comp.validTransitions().map((t) => t.name);
      expect(names).toContain('In Transit');
      expect(names).toContain('Rejected');
      expect(names).toHaveLength(2);
    });

    it('In Transit: dropdown should show Received and Rejected', () => {
      const order = createOrder({
        statusName: 'In Transit',
        statusId: 'st-in-transit',
      });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      const names = comp.validTransitions().map((t) => t.name);
      expect(names).toContain('Received');
      expect(names).toContain('Rejected');
      expect(names).toHaveLength(2);
    });

    it('Received: dropdown should show Billed and Rejected', () => {
      const order = createOrder({
        statusName: 'Received',
        statusId: 'st-received',
      });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      const names = comp.validTransitions().map((t) => t.name);
      expect(names).toContain('Billed');
      expect(names).toContain('Rejected');
      expect(names).toHaveLength(2);
    });

    it('Billed: dropdown should show Closed and Rejected', () => {
      const order = createOrder({
        statusName: 'Billed',
        statusId: 'st-billed',
      });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      const names = comp.validTransitions().map((t) => t.name);
      expect(names).toContain('Closed');
      expect(names).toContain('Rejected');
      expect(names).toHaveLength(2);
    });

    it('Closed: dropdown should be disabled (terminal)', () => {
      const order = createOrder({
        statusName: 'Closed',
        statusId: 'st-closed',
      });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      expect(comp.isTerminal()).toBe(true);
      expect(comp.validTransitions()).toEqual([]);
    });

    it('Rejected: dropdown should be disabled (terminal)', () => {
      const order = createOrder({
        statusName: 'Rejected',
        statusId: 'st-rejected',
      });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      expect(comp.isTerminal()).toBe(true);
      expect(comp.validTransitions()).toEqual([]);
    });
  });

  describe('progress stepper', () => {
    it('marks past steps as done and the current step as current', () => {
      const order = createOrder({ statusName: 'Accepted', statusId: 'st-accepted' });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      const states = comp.steps().map((s) => s.state);
      expect(states[0]).toBe('done');
      expect(states[1]).toBe('done');
      expect(states[2]).toBe('current');
      expect(states.slice(3).every((s) => s === 'upcoming')).toBe(true);
    });

    it('renders the Spanish step labels', () => {
      const order = createOrder({ statusName: 'Draft' });
      const f = setupWithOrder(order);
      const labels = f.componentInstance.steps().map((s) => s.label);
      expect(labels).toContain('Borrador');
      expect(labels).toContain('En Tránsito');
    });
  });

  describe('status change', () => {
    it('should call purchaseOrderService.updateStatus with correct UUID', () => {
      const order = createOrder({ statusName: 'Draft' });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      purchaseOrderService.updateStatus = vi.fn().mockReturnValue(of(order));

      comp.onStatusChange('st-sent');

      expect(purchaseOrderService.updateStatus).toHaveBeenCalledWith('po-1', {
        statusId: 'st-sent',
      });
    });

    it('should emit the new status name on successful update', () => {
      const order = createOrder({ statusName: 'Draft' });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      let emitted = '';
      comp.statusChanged.subscribe((name: string) => {
        emitted = name;
      });

      purchaseOrderService.updateStatus = vi
        .fn()
        .mockReturnValue(of({ ...order, statusName: 'Sent' }));

      comp.onStatusChange('st-sent');

      expect(emitted).toBe('Sent');
    });

    it('should show success notification on status change', () => {
      const order = createOrder({ statusName: 'Draft' });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      purchaseOrderService.updateStatus = vi.fn().mockReturnValue(of(order));

      comp.onStatusChange('st-sent');

      expect(notificationService.showSuccess).toHaveBeenCalledWith(
        'Estado actualizado correctamente.',
      );
    });

    it('should show error notification on 409 conflict', () => {
      const order = createOrder({ statusName: 'Draft' });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      purchaseOrderService.updateStatus = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              statusText: 'Conflict',
            }),
        ),
      );

      comp.onStatusChange('st-rejected');

      expect(notificationService.showError).toHaveBeenCalledWith(
        'Transición de estado no permitida.',
      );
    });

    it('should show error notification on 404', () => {
      const order = createOrder({ statusName: 'Draft' });
      const f = setupWithOrder(order);
      const comp = f.componentInstance;

      purchaseOrderService.updateStatus = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 404,
              statusText: 'Not Found',
            }),
        ),
      );

      comp.onStatusChange('st-sent');

      expect(notificationService.showError).toHaveBeenCalledWith('Orden de compra no encontrada.');
    });
  });

  describe('loading states', () => {
    it('should show loading text before statuses are fetched', () => {
      statusService.getStatusesByTypeId = vi.fn().mockReturnValue(of([]));
      const order = createOrder({ statusName: 'Draft' });
      const f = setupWithOrder(order);

      const el: HTMLElement = f.nativeElement;
      expect(el.textContent).toContain('Cargando transiciones');
    });
  });
});
