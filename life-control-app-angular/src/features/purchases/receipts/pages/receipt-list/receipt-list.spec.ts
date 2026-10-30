import { Component, output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { By } from '@angular/platform-browser';
import { of, Subject, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ReceiptList } from './receipt-list';
import { GoodsReceiptService } from '../../data/goods-receipt.service';
import { PurchaseLocationFilter } from '../../../components/location-filter/location-filter';
import type { PurchaseLocationSelection } from '../../../models/purchase-location-filter.models';
import type { Page } from '../../../purchase-orders/models/purchase-order.models';
import type { GoodsReceipt } from '../../models/receipt.models';

interface ServiceMock {
  getReceipts: ReturnType<typeof vi.fn>;
}

/**
 * Stand-in for the cascade component: the page's own unit is the gate and the query it builds, while
 * the cascade's behaviour (labels, resets, preference preselect) is covered by its own spec. Swapping
 * the real component also keeps this suite from resolving the `LocationPreference` read the cascade
 * injects, which would otherwise leave an HTTP dependency open in a page test.
 */
@Component({
  selector: 'app-purchase-location-filter',
  standalone: true,
  template: '<div class="location-filter-stub"></div>',
})
class LocationFilterStub {
  readonly selection = output<PurchaseLocationSelection>();
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

/** The five required/optional levels, with the three required ones filled by default. */
const location = (
  overrides: Partial<PurchaseLocationSelection> = {},
): PurchaseLocationSelection => ({
  companyId: 'company-1',
  companyCountryId: 'country-1',
  regionId: 'region-1',
  zoneId: null,
  companyStoreId: null,
  ...overrides,
});

/** The exact query object `GoodsReceiptService.getReceipts` receives from the page. */
const expectedQuery = (overrides: Record<string, string | undefined> = {}): unknown => ({
  search: undefined,
  companyId: 'company-1',
  companyCountryId: 'country-1',
  regionId: 'region-1',
  zoneId: undefined,
  companyStoreId: undefined,
  ...overrides,
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
      imports: [ReceiptList],
      providers: [
        provideRouter([]),
        {
          provide: GoodsReceiptService,
          useValue: goodsReceiptService,
        },
      ],
    })
      .overrideComponent(ReceiptList, {
        remove: { imports: [PurchaseLocationFilter] },
        add: { imports: [LocationFilterStub] },
      })
      .compileComponents();

    fixture = TestBed.createComponent(ReceiptList);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  });

  /** Mounts the page with the three required levels already chosen and the first response settled. */
  const mountWithLocation = async (): Promise<ComponentFixture<ReceiptList>> => {
    const f = TestBed.createComponent(ReceiptList);
    f.componentInstance.onLocationChange(location());
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f;
  };

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('required location levels (S2)', () => {
    it('should not request receipts while the required levels are missing', () => {
      fixture.detectChanges();

      expect(goodsReceiptService.getReceipts).not.toHaveBeenCalled();
      expect(component.hasRequiredLocation()).toBe(false);
    });

    it('should explain what is missing instead of listing receipts', () => {
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.location-required-state')).toBeTruthy();
      expect(el.textContent).toContain('Elegí empresa, país y región para ver los recibos.');
      expect(el.querySelector('table')).toBeNull();
      expect(el.querySelector('.loading-skeleton')).toBeNull();
    });

    it('should keep the search field disabled until the required levels are chosen', () => {
      fixture.detectChanges();

      const searchInput: HTMLInputElement | null =
        fixture.nativeElement.querySelector('.search-field input');
      expect(searchInput?.disabled).toBe(true);
    });

    it.each([
      ['company', { companyId: null }],
      ['company country', { companyCountryId: null }],
      ['region', { regionId: null }],
    ])('should stay gated when only the %s level is missing', (_label, override) => {
      component.onLocationChange(location(override));
      fixture.detectChanges();

      expect(component.hasRequiredLocation()).toBe(false);
      expect(goodsReceiptService.getReceipts).not.toHaveBeenCalled();
    });

    it('should accept a selection that leaves zone and store empty', () => {
      component.onLocationChange(location());
      fixture.detectChanges();

      expect(component.hasRequiredLocation()).toBe(true);
    });

    it('should hide the required-levels notice once the location is chosen', async () => {
      const f = await mountWithLocation();

      expect(f.nativeElement.querySelector('.location-required-state')).toBeNull();
      expect(
        (f.nativeElement.querySelector('.search-field input') as HTMLInputElement).disabled,
      ).toBe(false);
    });
  });

  describe('data loading (S1, S2)', () => {
    it('should request the region with the five filters when the required levels are chosen', async () => {
      await mountWithLocation();

      expect(goodsReceiptService.getReceipts).toHaveBeenCalledWith(0, 12, expectedQuery());
    });

    it('should narrow the request with zone and store when both are chosen', async () => {
      const f = TestBed.createComponent(ReceiptList);
      f.componentInstance.onLocationChange(
        location({ zoneId: 'zone-1', companyStoreId: 'store-1' }),
      );
      f.detectChanges();
      await f.whenStable();

      expect(goodsReceiptService.getReceipts).toHaveBeenCalledWith(
        0,
        12,
        expectedQuery({ zoneId: 'zone-1', companyStoreId: 'store-1' }),
      );
    });

    it('should render table rows when data loads', async () => {
      const f = await mountWithLocation();

      const rows = f.debugElement.queryAll(By.css('tr.mat-mdc-row'));
      expect(rows.length).toBe(2);
    });

    it('should display receipt and order numbers in the table', async () => {
      const f = await mountWithLocation();

      const tableEl: HTMLElement = f.nativeElement;
      expect(tableEl.textContent).toContain('GR-00001');
      expect(tableEl.textContent).toContain('PO-00001');
    });

    it('should render the received date through the date pipe', async () => {
      const f = await mountWithLocation();

      const cells = f.debugElement.queryAll(By.css('td.mat-mdc-cell'));
      const dateCell = cells.find((c) => c.nativeElement.textContent.includes('01/03/2026'));
      expect(dateCell).toBeTruthy();
    });

    it('should render the user name when present and an em dash when null', async () => {
      const f = await mountWithLocation();

      const tableEl: HTMLElement = f.nativeElement;
      expect(tableEl.textContent).toContain('jdoe');
      expect(tableEl.textContent).toContain('—');
    });

    it('should render the receipt status label through the status chip', async () => {
      const f = await mountWithLocation();

      const chips = f.debugElement.queryAll(By.css('app-status-chip'));
      expect(chips.length).toBe(2);
      expect((f.nativeElement as HTMLElement).textContent).toContain('Registrado');
    });
  });

  describe('loading state', () => {
    it('should not be loading while the resource is idle for want of a location', () => {
      expect(component.loading()).toBe(false);
    });

    it('should show the loading skeleton while the first read is in flight', () => {
      const pending = new Subject<Page<GoodsReceipt>>();
      goodsReceiptService.getReceipts = vi.fn().mockReturnValue(pending);

      const f = TestBed.createComponent(ReceiptList);
      f.componentInstance.onLocationChange(location());
      f.detectChanges();

      expect(f.nativeElement.querySelector('.loading-skeleton')).toBeTruthy();
      expect(f.componentInstance.loading()).toBe(true);

      pending.complete();
    });
  });

  describe('empty state', () => {
    it('should show empty state when no receipts', async () => {
      goodsReceiptService.getReceipts = vi.fn().mockReturnValue(of(createMockPage([])));

      const f = await mountWithLocation();

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
      comp.onLocationChange(location());
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
      const f = await mountWithLocation();
      const comp = f.componentInstance;

      goodsReceiptService.getReceipts.mockClear();
      goodsReceiptService.getReceipts.mockReturnValue(of(createMockPage(mockReceipts)));

      comp.onRetry();
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      expect(goodsReceiptService.getReceipts).toHaveBeenCalled();
    });
  });

  describe('search (S5)', () => {
    it('should update searchQuery signal', () => {
      component.searchQuery.set('GR-00001');
      expect(component.searchQuery()).toBe('GR-00001');
    });

    it('should clear searchQuery on clearSearch', () => {
      component.searchQuery.set('GR-00001');
      component.clearSearch();
      expect(component.searchQuery()).toBe('');
    });

    it('should debounce the search and combine it with the location filters', async () => {
      vi.useFakeTimers();
      const f = TestBed.createComponent(ReceiptList);
      const comp = f.componentInstance;
      comp.onLocationChange(location());
      f.detectChanges();

      goodsReceiptService.getReceipts.mockClear();
      comp.searchQuery.set('PO-00001');
      f.detectChanges();

      // Nothing yet: the debounce window has not elapsed.
      expect(goodsReceiptService.getReceipts).not.toHaveBeenCalled();

      vi.advanceTimersByTime(300);
      f.detectChanges();

      expect(goodsReceiptService.getReceipts).toHaveBeenCalledTimes(1);
      expect(goodsReceiptService.getReceipts).toHaveBeenCalledWith(
        0,
        12,
        expectedQuery({ search: 'PO-00001' }),
      );

      vi.useRealTimers();
    });

    it('should not search while the required levels are missing', () => {
      vi.useFakeTimers();
      fixture.detectChanges();

      component.searchQuery.set('GR-00001');
      fixture.detectChanges();
      vi.advanceTimersByTime(300);
      fixture.detectChanges();

      expect(goodsReceiptService.getReceipts).not.toHaveBeenCalled();

      vi.useRealTimers();
    });
  });

  describe('pagination (S5)', () => {
    it('should update pageIndex and pageSize on page change', () => {
      component.onPageChange({ pageIndex: 2, pageSize: 24 });
      expect(component.pageIndex()).toBe(2);
      expect(component.pageSize()).toBe(24);
    });

    it('should keep the chosen page after change detection', async () => {
      // Regression: the reset effect must not track `pageIndex` itself, or every page change would
      // snap back to the first page.
      const f = await mountWithLocation();
      const comp = f.componentInstance;

      comp.onPageChange({ pageIndex: 1, pageSize: 12 });
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      expect(comp.pageIndex()).toBe(1);
      expect(goodsReceiptService.getReceipts).toHaveBeenCalledWith(1, 12, expectedQuery());
    });

    it('should go back to the first page when the location filters change', async () => {
      const f = await mountWithLocation();
      const comp = f.componentInstance;

      comp.onPageChange({ pageIndex: 2, pageSize: 12 });
      f.detectChanges();
      await f.whenStable();

      comp.onLocationChange(location({ regionId: 'region-2' }));
      f.detectChanges();
      await f.whenStable();

      expect(comp.pageIndex()).toBe(0);
      expect(goodsReceiptService.getReceipts).toHaveBeenCalledWith(
        0,
        12,
        expectedQuery({ regionId: 'region-2' }),
      );
    });

    it('should reset page to 0 when the debounced search changes', () => {
      vi.useFakeTimers();
      const f = TestBed.createComponent(ReceiptList);
      const comp = f.componentInstance;
      comp.onLocationChange(location());
      f.detectChanges();

      comp.pageIndex.set(2);
      comp.searchQuery.set('GR-00002');
      f.detectChanges();

      vi.advanceTimersByTime(300);
      f.detectChanges();

      expect(comp.pageIndex()).toBe(0);

      vi.useRealTimers();
    });

    it('should not render the paginator when there is a single page', async () => {
      const f = await mountWithLocation();

      expect((f.nativeElement as HTMLElement).querySelector('mat-paginator')).toBeNull();
    });

    it('should render the paginator when there is more than one page', async () => {
      goodsReceiptService.getReceipts = vi
        .fn()
        .mockReturnValue(of(createMockPage(mockReceipts, 0, 1)));

      const f = await mountWithLocation();

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
