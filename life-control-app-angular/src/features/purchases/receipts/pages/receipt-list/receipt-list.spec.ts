import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ReceiptList } from './receipt-list';
import { GoodsReceiptService } from '../../data/goods-receipt.service';
import type { Page } from '../../../purchase-orders/models/purchase-order.models';
import type { GoodsReceipt } from '../../models/receipt.models';

interface ServiceMock {
  getReceipts: ReturnType<typeof vi.fn>;
}

const mockReceipts: GoodsReceipt[] = [
  {
    id: 'gr-1',
    receiptNumber: 'GR-00001',
    purchaseOrderId: 'po-1',
    orderNumber: 'PO-00001',
    companyStoreId: 'store-1',
    receivingLocationId: 'loc-1',
    statusId: 'st-registered',
    statusName: 'Registered',
    receivedBy: 'jdoe',
    receivedAt: '2026-03-01T10:15:00Z',
    comments: null,
    enabled: true,
    lines: [],
  },
  {
    id: 'gr-2',
    receiptNumber: 'GR-00002',
    purchaseOrderId: 'po-2',
    orderNumber: 'PO-00002',
    companyStoreId: 'store-2',
    receivingLocationId: 'loc-2',
    statusId: 'st-registered',
    statusName: 'Registered',
    receivedBy: null,
    receivedAt: '2026-03-02T08:00:00Z',
    comments: 'Sin novedades',
    enabled: true,
    lines: [],
  },
];

const createMockPage = (receipts: GoodsReceipt[], page = 0, size = 12): Page<GoodsReceipt> => ({
  content: receipts,
  totalElements: receipts.length,
  totalPages: Math.ceil(receipts.length / size) || 1,
  size,
  number: page,
  first: page === 0,
  last: (page + 1) * size >= receipts.length,
  empty: receipts.length === 0,
});

describe('ReceiptList', () => {
  let component: ReceiptList;
  let fixture: ComponentFixture<ReceiptList>;
  let goodsReceiptService: ServiceMock;
  let router: Router;

  beforeEach(async () => {
    goodsReceiptService = {
      getReceipts: vi.fn().mockReturnValue(of(createMockPage(mockReceipts))),
    };

    await TestBed.configureTestingModule({
      imports: [ReceiptList, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        {
          provide: GoodsReceiptService,
          useValue: goodsReceiptService,
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ReceiptList);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('data loading', () => {
    it('should call getReceipts with default params on init', () => {
      fixture.detectChanges();
      expect(goodsReceiptService.getReceipts).toHaveBeenCalledWith(0, 12, undefined);
    });

    it('should render table rows when data loads', async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      const rows = fixture.debugElement.queryAll(By.css('tr.mat-mdc-row'));
      expect(rows.length).toBe(2);
    });

    it('should display receipt and order numbers in the table', async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      const tableEl: HTMLElement = fixture.nativeElement;
      expect(tableEl.textContent).toContain('GR-00001');
      expect(tableEl.textContent).toContain('PO-00001');
    });

    it('should render the received date through the date pipe', async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      const cells = fixture.debugElement.queryAll(By.css('td.mat-mdc-cell'));
      const dateCell = cells.find((c) => c.nativeElement.textContent.includes('01/03/2026'));
      expect(dateCell).toBeTruthy();
    });

    it('should render the user name when present and an em dash when null', async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      const tableEl: HTMLElement = fixture.nativeElement;
      expect(tableEl.textContent).toContain('jdoe');
      expect(tableEl.textContent).toContain('—');
    });

    it('should render the receipt status label through the status chip', async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      const chips = fixture.debugElement.queryAll(By.css('app-status-chip'));
      expect(chips.length).toBe(2);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Registrado');
    });
  });

  describe('loading state', () => {
    it('should show loading skeleton initially (before first detectChanges)', () => {
      expect(component.loading()).toBe(true);
    });
  });

  describe('empty state', () => {
    it('should show empty state when no receipts', async () => {
      goodsReceiptService.getReceipts = vi.fn().mockReturnValue(of(createMockPage([])));

      const f = TestBed.createComponent(ReceiptList);
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      const el: HTMLElement = f.nativeElement;
      expect(el.querySelector('.empty-state')).toBeTruthy();
      expect(el.textContent).toContain('No hay recibos registrados');
    });
  });

  describe('error state', () => {
    it('should not throw and render the error state when the API fails', async () => {
      goodsReceiptService.getReceipts = vi
        .fn()
        .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

      const f = TestBed.createComponent(ReceiptList);
      const comp = f.componentInstance;
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      expect(() => comp.receipts()).not.toThrow();
      expect(comp.receipts()).toBeUndefined();
      expect(comp.error()).toBeTruthy();

      const el: HTMLElement = f.nativeElement;
      expect(el.querySelector('.error-state')).toBeTruthy();
      expect(el.querySelector('.loading-skeleton')).toBeNull();
      expect(el.textContent).toContain('Tu sesión expiró');
    });

    it('should call the service again after onRetry reload', async () => {
      const f = TestBed.createComponent(ReceiptList);
      const comp = f.componentInstance;
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      goodsReceiptService.getReceipts.mockClear();
      goodsReceiptService.getReceipts.mockReturnValue(of(createMockPage(mockReceipts)));

      comp.onRetry();
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      expect(goodsReceiptService.getReceipts).toHaveBeenCalled();
    });
  });

  describe('search', () => {
    it('should update searchQuery signal', () => {
      component.searchQuery.set('GR-00001');
      expect(component.searchQuery()).toBe('GR-00001');
    });

    it('should clear searchQuery on clearSearch', () => {
      component.searchQuery.set('GR-00001');
      component.clearSearch();
      expect(component.searchQuery()).toBe('');
    });

    it('should debounce search and call the service once with the search term', () => {
      vi.useFakeTimers();
      fixture.detectChanges();

      goodsReceiptService.getReceipts.mockClear();

      component.searchQuery.set('PO-00001');
      fixture.detectChanges();

      // Nothing yet: the debounce window has not elapsed.
      expect(goodsReceiptService.getReceipts).not.toHaveBeenCalled();

      vi.advanceTimersByTime(300);
      fixture.detectChanges();

      expect(goodsReceiptService.getReceipts).toHaveBeenCalledTimes(1);
      expect(goodsReceiptService.getReceipts).toHaveBeenCalledWith(0, 12, 'PO-00001');

      vi.useRealTimers();
    });

    it('should reset page to 0 when the debounced search changes', () => {
      vi.useFakeTimers();
      fixture.detectChanges();

      component.pageIndex.set(2);
      component.searchQuery.set('GR-00002');
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

    it('should call the service with new page params', () => {
      vi.useFakeTimers();
      fixture.detectChanges();
      vi.advanceTimersByTime(0);

      component.onPageChange({ pageIndex: 1, pageSize: 12 });
      fixture.detectChanges();
      vi.advanceTimersByTime(0);

      expect(goodsReceiptService.getReceipts).toHaveBeenCalledWith(1, 12, undefined);

      vi.useRealTimers();
    });

    it('should not render the paginator when there is a single page', async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect((fixture.nativeElement as HTMLElement).querySelector('mat-paginator')).toBeNull();
    });

    it('should render the paginator when there is more than one page', async () => {
      goodsReceiptService.getReceipts = vi
        .fn()
        .mockReturnValue(of(createMockPage(mockReceipts, 0, 1)));

      const f = TestBed.createComponent(ReceiptList);
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      expect((f.nativeElement as HTMLElement).querySelector('mat-paginator')).toBeTruthy();
    });
  });

  describe('navigation', () => {
    it('should navigate to the receipt detail on viewReceipt', () => {
      component.viewReceipt('gr-1');
      expect(router.navigate).toHaveBeenCalledWith(['/purchases/receipts', 'gr-1']);
    });

    it('should navigate to the create page on createReceipt', () => {
      component.createReceipt();
      expect(router.navigate).toHaveBeenCalledWith(['/purchases/receipts/create']);
    });
  });

  describe('displayed columns', () => {
    it('should have correct columns array', () => {
      expect(component.displayedColumns).toEqual([
        'receiptNumber',
        'orderNumber',
        'receivedAt',
        'receivedBy',
        'status',
        'actions',
      ]);
    });
  });
});
