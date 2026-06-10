import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { of, Observable } from 'rxjs';
import { SalesOrderList } from './sales-order-list';
import { SalesOrderService } from '../../data/sales-order.service';
import type { SalesOrder, Page } from '../../models/sales-order.models';

type ServiceMock = {
  getSalesOrders: ReturnType<typeof vi.fn>;
};

const mockOrders: SalesOrder[] = [
  {
    id: 'so-1',
    orderNumber: 'SO-00001',
    customerId: 'cust-1',
    customerName: 'Juan Pérez',
    companyStoreId: 'store-1',
    companyStoreName: 'Tienda Centro',
    shiftId: 'shift-1',
    shiftName: 'Turno Mañana',
    orderDate: '2026-06-01T10:00:00Z',
    statusId: 'st-draft',
    statusName: 'Draft',
    totalAmount: 1500.0,
    enabled: true,
    createdAt: '2026-06-01T10:00:00Z',
    updatedAt: '2026-06-01T10:00:00Z',
    items: [],
  },
  {
    id: 'so-2',
    orderNumber: 'SO-00002',
    customerId: 'cust-2',
    customerName: 'María García',
    companyStoreId: 'store-2',
    companyStoreName: 'Tienda Norte',
    shiftId: 'shift-2',
    shiftName: 'Turno Tarde',
    orderDate: '2026-06-02T14:00:00Z',
    statusId: 'st-completed',
    statusName: 'Completed',
    totalAmount: 3200.0,
    enabled: true,
    createdAt: '2026-06-02T14:00:00Z',
    updatedAt: '2026-06-02T14:00:00Z',
    items: [],
  },
];

const createMockPage = (
  orders: SalesOrder[],
  page = 0,
  size = 12,
): Page<SalesOrder> => ({
  content: orders,
  totalElements: orders.length,
  totalPages: Math.ceil(orders.length / size) || 1,
  size,
  number: page,
  first: page === 0,
  last: (page + 1) * size >= orders.length,
  empty: orders.length === 0,
});

describe('SalesOrderList', () => {
  let component: SalesOrderList;
  let fixture: ComponentFixture<SalesOrderList>;
  let salesOrderService: ServiceMock;
  let router: Router;

  beforeEach(async () => {
    salesOrderService = {
      getSalesOrders: vi
        .fn()
        .mockReturnValue(of(createMockPage(mockOrders))),
    };

    await TestBed.configureTestingModule({
      imports: [SalesOrderList, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        {
          provide: SalesOrderService,
          useValue: salesOrderService,
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SalesOrderList);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('data loading', () => {
    it('should call getSalesOrders with default params on init', () => {
      fixture.detectChanges();
      expect(salesOrderService.getSalesOrders).toHaveBeenCalledWith(
        0,
        12,
        undefined,
      );
    });

    it('should render table rows when data loads', async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      const rows = fixture.debugElement.queryAll(By.css('tr.mat-mdc-row'));
      expect(rows.length).toBe(2);
    });

    it('should display order number in the table', async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      const tableEl: HTMLElement = fixture.nativeElement;
      expect(tableEl.textContent).toContain('SO-00001');
    });

    it('should display customer name in the table', async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      const tableEl: HTMLElement = fixture.nativeElement;
      expect(tableEl.textContent).toContain('Juan Pérez');
      expect(tableEl.textContent).toContain('María García');
    });
  });

  describe('loading state', () => {
    it('should show loading skeleton initially (before first detectChanges)', () => {
      expect(component.loading()).toBe(true);
    });
  });

  describe('empty state', () => {
    beforeEach(() => {
      salesOrderService.getSalesOrders = vi
        .fn()
        .mockReturnValue(of(createMockPage([])));
    });

    it('should show empty state when no orders', async () => {
      const f = TestBed.createComponent(SalesOrderList);
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      const emptyEl: HTMLElement = f.nativeElement;
      expect(emptyEl.textContent).toContain(
        'No sales orders registered',
      );
    });
  });

  describe('error state', () => {
    it('should set error signal when API fails', async () => {
      salesOrderService.getSalesOrders = vi.fn().mockReturnValue(
        new Observable<Page<SalesOrder>>((subscriber) => {
          setTimeout(() => {
            subscriber.error(new Error('Server Error'));
          }, 10);
        }),
      );

      const f = TestBed.createComponent(SalesOrderList);
      const comp = f.componentInstance;
      f.detectChanges();
      await f.whenStable();

      expect(comp.error()).toBeTruthy();
    });

    it('should call service again after onRetry reload', async () => {
      salesOrderService.getSalesOrders = vi
        .fn()
        .mockReturnValue(of(createMockPage(mockOrders)));

      const f = TestBed.createComponent(SalesOrderList);
      const comp = f.componentInstance;
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      salesOrderService.getSalesOrders.mockClear();
      salesOrderService.getSalesOrders.mockReturnValue(
        of(createMockPage(mockOrders)),
      );

      comp.onRetry();
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      expect(salesOrderService.getSalesOrders).toHaveBeenCalled();
    });
  });

  describe('search', () => {
    it('should update searchQuery signal', () => {
      component.searchQuery.set('Juan');
      expect(component.searchQuery()).toBe('Juan');
    });

    it('should clear searchQuery on clearSearch', () => {
      component.searchQuery.set('Juan');
      component.clearSearch();
      expect(component.searchQuery()).toBe('');
    });

    it('should debounce search and call service with search param', () => {
      vi.useFakeTimers();
      fixture.detectChanges();

      component.searchQuery.set('Juan');
      fixture.detectChanges();

      vi.advanceTimersByTime(300);
      fixture.detectChanges();

      expect(salesOrderService.getSalesOrders).toHaveBeenCalledWith(
        0,
        12,
        'Juan',
      );

      vi.useRealTimers();
    });

    it('should reset page to 0 when search changes', () => {
      vi.useFakeTimers();
      fixture.detectChanges();

      component.pageIndex.set(2);
      component.searchQuery.set('María');
      fixture.detectChanges();

      vi.advanceTimersByTime(300);
      fixture.detectChanges();

      expect(component.pageIndex()).toBe(0);

      vi.useRealTimers();
    });
  });

  describe('pagination', () => {
    it('should update pageIndex and pageSize on page change', () => {
      component.onPageChange({ pageIndex: 2, pageSize: 24 });
      expect(component.pageIndex()).toBe(2);
      expect(component.pageSize()).toBe(24);
    });

    it('should call service with new page params', () => {
      vi.useFakeTimers();
      fixture.detectChanges();
      vi.advanceTimersByTime(0);

      component.onPageChange({ pageIndex: 1, pageSize: 12 });
      fixture.detectChanges();
      vi.advanceTimersByTime(0);

      expect(salesOrderService.getSalesOrders).toHaveBeenCalledWith(
        1,
        12,
        undefined,
      );

      vi.useRealTimers();
    });
  });

  describe('navigation', () => {
    it('should navigate to edit page on editOrder', () => {
      component.editOrder('so-1');
      expect(router.navigate).toHaveBeenCalledWith([
        '/sales/orders',
        'so-1',
      ]);
    });

    it('should navigate to create page on createOrder', () => {
      component.createOrder();
      expect(router.navigate).toHaveBeenCalledWith([
        '/sales/orders/new',
      ]);
    });
  });

  describe('status colors', () => {
    it('should expose SO_STATUS_COLORS as statusColor', () => {
      expect(component.statusColor).toBeDefined();
      expect(component.statusColor['Draft']).toBe('#9e9e9e');
      expect(component.statusColor['Completed']).toBe('#607d8b');
    });
  });

  describe('displayed columns', () => {
    it('should have correct columns array', () => {
      expect(component.displayedColumns).toEqual([
        'orderNumber',
        'customerName',
        'status',
        'totalAmount',
        'orderDate',
        'actions',
      ]);
    });
  });
});
