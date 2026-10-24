import { Component, output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { PurchaseOrderList } from './purchase-order-list';
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { PurchaseOrderLocationFilter } from '../../components/location-filter/location-filter';
import type { PurchaseOrderLocationSelection } from '../../models/purchase-order-location-filter.models';
import type { PurchaseOrder, Page } from '../../models/purchase-order.models';

interface ServiceMock {
  getPurchaseOrders: ReturnType<typeof vi.fn>;
}

/**
 * Stand-in for the cascade component: the page's own unit is the gate and the query it builds, while
 * the cascade's behaviour (labels, resets, preference preselect) is covered by its own spec. Swapping
 * the real component keeps this suite from having to mock six data services it does not test.
 */
@Component({
  selector: 'app-purchase-order-location-filter',
  standalone: true,
  template: '<div class="location-filter-stub"></div>',
})
class LocationFilterStub {
  readonly selection = output<PurchaseOrderLocationSelection>();
}

const mockOrders: PurchaseOrder[] = [
  {
    id: 'po-1',
    orderNumber: 'PO-00001',
    supplierId: 'sup-1',
    supplierName: 'Acme Corp',
    companyStoreId: 'store-1',
    companyStoreName: 'Tienda Centro',
    companyId: 'company-1',
    companyCountryId: 'country-1',
    regionId: 'region-1',
    zoneId: 'zone-1',
    paymentMethodId: 'pm-1',
    paymentMethodName: 'Transferencia',
    statusId: 'st-draft',
    statusName: 'Draft',
    comments: null,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    details: [],
  },
  {
    id: 'po-2',
    orderNumber: 'PO-00002',
    supplierId: 'sup-2',
    supplierName: 'Beta Industries',
    companyStoreId: 'store-2',
    companyStoreName: 'Tienda Norte',
    companyId: 'company-1',
    companyCountryId: 'country-1',
    regionId: 'region-1',
    zoneId: 'zone-1',
    paymentMethodId: 'pm-2',
    paymentMethodName: 'Efectivo',
    statusId: 'st-sent',
    statusName: 'Sent',
    comments: 'Urgente',
    enabled: true,
    createdAt: '2026-02-01T00:00:00Z',
    updatedAt: '2026-02-01T00:00:00Z',
    details: [],
  },
];

const createMockPage = (orders: PurchaseOrder[], page = 0, size = 12): Page<PurchaseOrder> => ({
  content: orders,
  totalElements: orders.length,
  totalPages: Math.ceil(orders.length / size) || 1,
  size,
  number: page,
  first: page === 0,
  last: (page + 1) * size >= orders.length,
  empty: orders.length === 0,
});

/** The five required/optional levels, with the three required ones filled by default. */
const location = (
  overrides: Partial<PurchaseOrderLocationSelection> = {},
): PurchaseOrderLocationSelection => ({
  companyId: 'company-1',
  companyCountryId: 'country-1',
  regionId: 'region-1',
  zoneId: null,
  companyStoreId: null,
  ...overrides,
});

/** The exact query object `PurchaseOrderService.getPurchaseOrders` receives from the page. */
const expectedQuery = (overrides: Record<string, string | undefined> = {}): unknown => ({
  search: undefined,
  companyId: 'company-1',
  companyCountryId: 'country-1',
  regionId: 'region-1',
  zoneId: undefined,
  companyStoreId: undefined,
  ...overrides,
});

describe('PurchaseOrderList', () => {
  let fixture: ComponentFixture<PurchaseOrderList>;
  let purchaseOrderService: ServiceMock;
  let router: Router;

  beforeEach(async () => {
    purchaseOrderService = {
      getPurchaseOrders: vi.fn().mockReturnValue(of(createMockPage(mockOrders))),
    };

    await TestBed.configureTestingModule({
      imports: [PurchaseOrderList],
      providers: [
        provideRouter([]),
        {
          provide: PurchaseOrderService,
          useValue: purchaseOrderService,
        },
      ],
    })
      .overrideComponent(PurchaseOrderList, {
        remove: { imports: [PurchaseOrderLocationFilter] },
        add: { imports: [LocationFilterStub] },
      })
      .compileComponents();

    fixture = TestBed.createComponent(PurchaseOrderList);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  });

  /** Mounts the page with the three required levels already chosen and the first response settled. */
  const mountWithLocation = async (): Promise<ComponentFixture<PurchaseOrderList>> => {
    const f = TestBed.createComponent(PurchaseOrderList);
    f.componentInstance.onLocationChange(location());
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f;
  };

  it('should create', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  describe('required location levels (S2)', () => {
    it('should not request orders while the required levels are missing', () => {
      fixture.detectChanges();

      expect(purchaseOrderService.getPurchaseOrders).not.toHaveBeenCalled();
      expect(fixture.componentInstance.hasRequiredLocation()).toBe(false);
    });

    it('should explain what is missing instead of listing orders', () => {
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.location-required-state')).toBeTruthy();
      expect(el.textContent).toContain(
        'Elegí empresa, país y región para ver las órdenes de compra.',
      );
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
      fixture.componentInstance.onLocationChange(location(override));
      fixture.detectChanges();

      expect(fixture.componentInstance.hasRequiredLocation()).toBe(false);
      expect(purchaseOrderService.getPurchaseOrders).not.toHaveBeenCalled();
    });

    it('should accept a selection that leaves zone and store empty', () => {
      fixture.componentInstance.onLocationChange(location());
      fixture.detectChanges();

      expect(fixture.componentInstance.hasRequiredLocation()).toBe(true);
    });
  });

  describe('data loading (S2, S4)', () => {
    it('should request the region when the required levels are chosen', async () => {
      await mountWithLocation();

      expect(purchaseOrderService.getPurchaseOrders).toHaveBeenCalledWith(0, 12, expectedQuery());
    });

    it('should narrow the request with zone and store when both are chosen', async () => {
      const f = TestBed.createComponent(PurchaseOrderList);
      f.componentInstance.onLocationChange(
        location({ zoneId: 'zone-1', companyStoreId: 'store-1' }),
      );
      f.detectChanges();
      await f.whenStable();

      expect(purchaseOrderService.getPurchaseOrders).toHaveBeenCalledWith(
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

    it('should display order number and supplier name in the table', async () => {
      const f = await mountWithLocation();

      const cells = f.debugElement.queryAll(By.css('td.mat-mdc-cell'));
      expect(cells.some((c) => c.nativeElement.textContent.includes('PO-00001'))).toBe(true);
      const tableEl: HTMLElement = f.nativeElement;
      expect(tableEl.textContent).toContain('Acme Corp');
      expect(tableEl.textContent).toContain('Beta Industries');
    });

    it('should hide the required-levels notice once the list is shown', async () => {
      const f = await mountWithLocation();

      expect(f.nativeElement.querySelector('.location-required-state')).toBeNull();
    });
  });

  describe('loading state', () => {
    it('should not be loading while the resource is idle for want of a location', () => {
      expect(fixture.componentInstance.loading()).toBe(false);
    });
  });

  describe('empty state', () => {
    it('should show empty state when no orders', async () => {
      purchaseOrderService.getPurchaseOrders = vi.fn().mockReturnValue(of(createMockPage([])));

      const f = await mountWithLocation();

      const emptyEl: HTMLElement = f.nativeElement;
      expect(emptyEl.textContent).toContain('No hay órdenes de compra registradas');
    });
  });

  describe('error state', () => {
    it('should not throw and render the error state when the API fails', async () => {
      purchaseOrderService.getPurchaseOrders = vi
        .fn()
        .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

      const f = TestBed.createComponent(PurchaseOrderList);
      const comp = f.componentInstance;
      comp.onLocationChange(location());
      f.detectChanges();
      await f.whenStable();
      f.detectChanges();

      expect(() => comp.orders()).not.toThrow();
      expect(comp.orders()).toBeUndefined();
      expect(comp.error()).toBeTruthy();

      const el: HTMLElement = f.nativeElement;
      expect(el.querySelector('.error-state')).toBeTruthy();
      expect(el.querySelector('.loading-skeleton')).toBeNull();
      expect(el.textContent).toContain('Tu sesión expiró');
    });

    it('should call service again after onRetry reload', async () => {
      const f = await mountWithLocation();
      const comp = f.componentInstance;

      purchaseOrderService.getPurchaseOrders.mockClear();
      purchaseOrderService.getPurchaseOrders.mockReturnValue(of(createMockPage(mockOrders)));

      comp.onRetry();
      f.detectChanges();
      await f.whenStable();

      expect(purchaseOrderService.getPurchaseOrders).toHaveBeenCalled();
    });
  });

  describe('search (S5)', () => {
    it('should update searchQuery signal', () => {
      expect(fixture.componentInstance.searchQuery()).toBe('');

      fixture.componentInstance.searchQuery.set('Acme');
      expect(fixture.componentInstance.searchQuery()).toBe('Acme');
    });

    it('should clear searchQuery on clearSearch', () => {
      fixture.componentInstance.searchQuery.set('Acme');
      fixture.componentInstance.clearSearch();
      expect(fixture.componentInstance.searchQuery()).toBe('');
    });

    it('should debounce the search and combine it with the location filters', async () => {
      vi.useFakeTimers();
      const f = TestBed.createComponent(PurchaseOrderList);
      const comp = f.componentInstance;
      comp.onLocationChange(location());
      f.detectChanges();

      purchaseOrderService.getPurchaseOrders.mockClear();
      comp.searchQuery.set('Acme');
      f.detectChanges();

      vi.advanceTimersByTime(300);
      f.detectChanges();

      expect(purchaseOrderService.getPurchaseOrders).toHaveBeenCalledWith(
        0,
        12,
        expectedQuery({ search: 'Acme' }),
      );

      vi.useRealTimers();
    });

    it('should not search while the required levels are missing', () => {
      vi.useFakeTimers();
      fixture.detectChanges();

      fixture.componentInstance.searchQuery.set('Acme');
      fixture.detectChanges();
      vi.advanceTimersByTime(300);
      fixture.detectChanges();

      expect(purchaseOrderService.getPurchaseOrders).not.toHaveBeenCalled();

      vi.useRealTimers();
    });
  });

  describe('pagination', () => {
    it('should update pageIndex and pageSize on page change', () => {
      fixture.componentInstance.onPageChange({ pageIndex: 2, pageSize: 24 });
      expect(fixture.componentInstance.pageIndex()).toBe(2);
      expect(fixture.componentInstance.pageSize()).toBe(24);
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
      expect(purchaseOrderService.getPurchaseOrders).toHaveBeenCalledWith(1, 12, expectedQuery());
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
      expect(purchaseOrderService.getPurchaseOrders).toHaveBeenCalledWith(
        0,
        12,
        expectedQuery({ regionId: 'region-2' }),
      );
    });
  });

  describe('navigation', () => {
    it('should navigate to edit page on editOrder', () => {
      fixture.componentInstance.editOrder('po-1');
      expect(router.navigate).toHaveBeenCalledWith(['/purchases/orders', 'po-1']);
    });

    it('should navigate to create page on createOrder', () => {
      fixture.componentInstance.createOrder();
      expect(router.navigate).toHaveBeenCalledWith(['/purchases/orders/create']);
    });
  });

  describe('displayed columns', () => {
    it('should have correct columns array', () => {
      expect(fixture.componentInstance.displayedColumns).toEqual([
        'orderNumber',
        'supplierName',
        'status',
        'companyStoreName',
        'createdAt',
        'actions',
      ]);
    });
  });
});
