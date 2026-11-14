import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter, Router, ActivatedRoute } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { SalesOrderEdit } from './sales-order-edit';
import { ProductVariantSelector } from '../../components/product-variant-selector/product-variant-selector';
import { SalesOrderItemTable } from '../../components/sales-order-item-table/sales-order-item-table';
import { SalesOrderService } from '../../data/sales-order.service';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { NotificationService } from '@shared/data/notification';
import { ConfigService } from '@app/services/config.service';
import type { SalesOrder, SalesOrderItem, CustomerOption } from '../../models/sales-order.models';
import type { ProfileResponse } from '@features/user/profile/data/profile.models';

const TEST_API = 'http://test/api';

const mockItem: SalesOrderItem = {
  id: 'item-1',
  salesOrderId: 'so-1',
  productVariantId: 'pv-1',
  productVariantName: 'Widget Red - Large',
  quantity: 5,
  listPrice: 100,
  discountApplied: 0,
  finalPrice: 500,
  promotionId: undefined,
  statusId: 'st-draft',
  statusName: 'Draft',
  createdAt: '2026-06-01T10:00:00Z',
  updatedAt: '2026-06-01T10:00:00Z',
};

const mockOrder: SalesOrder = {
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
  currency: 'MXN',
  totalAmount: 500,
  enabled: true,
  createdAt: '2026-06-01T10:00:00Z',
  updatedAt: '2026-06-01T10:00:00Z',
  items: [mockItem],
};

const mockProfile: ProfileResponse = {
  keycloakUserId: 'user-1',
  username: 'juan',
  email: 'juan@test.com',
  firstName: 'Juan',
  lastName: 'Pérez',
  companyStoreId: 'store-1',
  assignedStores: null,
  location: {
    companyId: 'company-1',
    companyCountryId: 'country-1',
    companyRegionId: 'region-1',
    companyZoneId: 'zone-1',
    companyStoreId: 'store-1',
    companyStoreName: 'Store One',
  },
};

/**
 * Answers the receiver read the page issues for the customer an existing order names.
 *
 * <p>The page used to GET a <b>fixed</b> customer id before the order existed, which is the fetch this slice
 * deleted; what remains is one scoped read for the id the loaded order states, so a spec that loads an order
 * has one request to answer.</p>
 */
function flushReceiver(readId = 'cust-1', name = 'Juan Pérez'): void {
  const httpTesting = TestBed.inject(HttpTestingController);
  httpTesting
    .match((req) => req.url === `${TEST_API}/customers/${readId}`)
    .forEach((req) => req.flush({ id: readId, name } satisfies CustomerOption));
}

function createActivatedRoute(params: { id?: string }) {
  return {
    snapshot: {
      paramMap: {
        get: vi.fn().mockReturnValue(params.id ?? null),
      },
    },
  };
}

describe('SalesOrderEdit', () => {
  let component: SalesOrderEdit;
  let fixture: ComponentFixture<SalesOrderEdit>;
  let salesOrderService: {
    getSalesOrders: ReturnType<typeof vi.fn>;
    getSalesOrder: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    updateStatus: ReturnType<typeof vi.fn>;
    delete: ReturnType<typeof vi.fn>;
    getItems: ReturnType<typeof vi.fn>;
    addItem: ReturnType<typeof vi.fn>;
    updateItem: ReturnType<typeof vi.fn>;
    deleteItem: ReturnType<typeof vi.fn>;
    updateItemStatus: ReturnType<typeof vi.fn>;
    chargeSalesOrder: ReturnType<typeof vi.fn>;
  };
  let router: Router;

  const baseMocks = () => ({
    getSalesOrders: vi.fn(),
    getSalesOrder: vi.fn().mockReturnValue(of(mockOrder)),
    create: vi.fn().mockReturnValue(of(mockOrder)),
    update: vi.fn().mockReturnValue(of(mockOrder)),
    updateStatus: vi.fn(),
    delete: vi.fn(),
    getItems: vi.fn(),
    addItem: vi.fn(),
    updateItem: vi.fn(),
    deleteItem: vi.fn(),
    updateItemStatus: vi.fn(),
    chargeSalesOrder: vi.fn(),
  });

  const baseProviders = (routeId: string | null) => [
    provideRouter([]),
    { provide: ConfigService, useValue: { apiUrl: TEST_API } },
    {
      provide: SalesOrderService,
      useValue: baseMocks(),
    },
    {
      provide: ProfileService,
      useValue: {
        getProfile: vi.fn().mockReturnValue(of(mockProfile)),
        updateProfile: vi.fn(),
      },
    },
    {
      provide: CompanyStoreService,
      useValue: {
        // W3/T20: the store name and the chain come from the flat, id-addressed read now, so the
        // create-mode flow needs it to answer for the store the profile pre-fills. Without this provider
        // the real service is injected and its request is queued by the testing backend forever, which is
        // why the create-mode tests hung instead of failing loudly.
        getStoreById: vi.fn().mockReturnValue(
          of({
            id: 'store-1',
            companyId: 'comp-1',
            companyCountryId: 'cc-1',
            regionId: 'reg-1',
            zoneId: 'zone-1',
            storeName: 'Tienda Centro',
            enabled: true,
          }),
        ),
        getStores: vi.fn().mockReturnValue(of([])),
      },
    },
    {
      provide: NotificationService,
      useValue: {
        showSuccess: vi.fn(),
        showError: vi.fn(),
      },
    },
    {
      provide: ActivatedRoute,
      useValue: createActivatedRoute({ id: routeId ?? undefined }),
    },
  ];

  // ══════════════════════════════════════════════════════════
  // CREATE MODE
  // ══════════════════════════════════════════════════════════

  describe('create mode', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders(null),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(SalesOrderService) as unknown as typeof salesOrderService;
      router = TestBed.inject(Router);
      vi.spyOn(router, 'navigate');

      fixture.detectChanges();
    });

    it('should create', () => {
      expect(component).toBeTruthy();
    });

    it('should be in create mode (isEditMode = false)', () => {
      expect(component.isEditMode()).toBe(false);
    });

    it('should have empty customerId and shiftId, with store pre-set from profile', () => {
      const form = component.headerForm();
      expect(form.controls.customerId.value).toBe('');
      expect(form.controls.shiftId.value).toBe('');
      // Store comes from user profile preferences
      expect(form.controls.companyStoreId.value).toBe('store-1');
    });

    it('should initially have empty line items', () => {
      expect(component.lineItems()).toEqual([]);
    });

    it('should initially have isModifiable = true (create mode: no order loaded yet)', () => {
      expect(component.isModifiable()).toBe(true);
    });

    it('should call service.create with header fields only (no items)', () => {
      const form = component.headerForm();
      form.controls.customerId.setValue('cust-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.shiftId.setValue('shift-1');
      form.controls.comments.setValue('Test order');

      component.onSave();

      expect(salesOrderService.create).toHaveBeenCalledWith({
        customerId: 'cust-1',
        companyStoreId: 'store-1',
        shiftId: 'shift-1',
      });
    });

    it('should navigate to edit page after successful create', () => {
      const created: SalesOrder = { ...mockOrder, id: 'so-new' };
      salesOrderService.create = vi.fn().mockReturnValue(of(created));

      const form = component.headerForm();
      form.controls.customerId.setValue('cust-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.shiftId.setValue('shift-1');
      component.onSave();

      expect(router.navigate).toHaveBeenCalledWith(['/sales/orders', 'so-new']);
    });

    it('should not save when the store is missing', () => {
      // The store is the one required control: an order with none has no tenant to allocate its number in.
      const form = component.headerForm();
      form.controls.companyStoreId.setValue('');
      salesOrderService.create = vi.fn();
      component.onSave();
      expect(salesOrderService.create).not.toHaveBeenCalled();
    });

    it('should mark all fields as touched when form invalid', () => {
      component.headerForm().controls.companyStoreId.setValue('');
      salesOrderService.create = vi.fn();
      component.onSave();
      const form = component.headerForm();
      expect(form.controls.customerId.touched).toBe(true);
      expect(form.controls.companyStoreId.touched).toBe(true);
      expect(form.controls.shiftId.touched).toBe(true);
    });

    it('should allow saving a counter sale: the customer is not a required field', () => {
      component.headerForm().controls.customerId.setValue('');

      expect(
        component.headerForm().valid,
        'an empty receiver is a counter sale, not a missing required value (S2/S6)',
      ).toBe(true);
      expect(component.isCounterSale()).toBe(true);
    });

    it('should handle server error on create', () => {
      const apiError = {
        status: 400,
        message: 'Validation error',
        errors: { customerId: 'Required' },
      };
      salesOrderService.create = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: apiError,
              status: 400,
              statusText: 'Bad Request',
            }),
        ),
      );

      const form = component.headerForm();
      form.controls.customerId.setValue('cust-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.shiftId.setValue('shift-1');
      component.onSave();

      expect(component.serverErrors()).toEqual({ customerId: 'Required' });
    });

    it('should handle generic server error (5xx)', () => {
      salesOrderService.create = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 500,
              statusText: 'Server Error',
            }),
        ),
      );

      const form = component.headerForm();
      form.controls.customerId.setValue('cust-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.shiftId.setValue('shift-1');
      component.onSave();

      expect(component.generalError()).toContain('Unexpected error');
    });

    // ── Receiver: a customer, or a counter sale with none ──

    it('should not fetch any customer before the order exists', () => {
      const httpTesting = TestBed.inject(HttpTestingController);

      httpTesting.expectNone((req) => req.url.startsWith(`${TEST_API}/customers`));
      expect(component.receiverId()).toBe('');
    });

    it('should render the selector and state the empty receiver as a counter sale', () => {
      const el: HTMLElement = fixture.nativeElement;

      expect(el.querySelector('app-customer-selector')).toBeTruthy();
      const counterSale = el.querySelector('[data-testid="counter-sale"]');
      expect(counterSale?.textContent?.trim()).toBe('Mostrador');
      expect(component.isCounterSale()).toBe(true);
    });

    it('should auto-create the order with no customer, then navigate to edit', async () => {
      await fixture.whenStable();
      fixture.detectChanges();

      expect(salesOrderService.create).toHaveBeenCalledWith({
        customerId: undefined,
        companyStoreId: 'store-1',
        shiftId: undefined,
      });
      expect(router.navigate).toHaveBeenCalledWith(['/sales/orders', 'so-1']);
    });

    it('should keep creating=true while the auto-create request is in-flight', async () => {
      const createSubject = new Subject<SalesOrder>();
      salesOrderService.create = vi.fn().mockReturnValue(createSubject.asObservable());

      // A fresh component, because the auto-create fires as soon as the store is known — the mock has to be
      // in place before it runs.
      const inFlight = TestBed.createComponent(SalesOrderEdit);
      inFlight.detectChanges();

      expect(inFlight.componentInstance.creating()).toBe(true);

      const created: SalesOrder = { ...mockOrder, id: 'so-auto' };
      createSubject.next(created);
      createSubject.complete();
      inFlight.detectChanges();

      expect(inFlight.componentInstance.creating()).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/sales/orders', 'so-auto']);
    });

    it('should set generalError and clear creating when auto-create fails with 4xx', async () => {
      const apiError = {
        status: 400,
        message: 'Validation failed',
      };
      salesOrderService.create = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: apiError,
              status: 400,
              statusText: 'Bad Request',
            }),
        ),
      );

      const failing = TestBed.createComponent(SalesOrderEdit);
      failing.detectChanges();

      expect(failing.componentInstance.creating()).toBe(false);
      expect(failing.componentInstance.generalError()).toBe('Validation failed');
    });

    it('should set generalError and clear creating when auto-create fails with network error', async () => {
      salesOrderService.create = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 0,
              statusText: 'Unknown Error',
            }),
        ),
      );

      const offline = TestBed.createComponent(SalesOrderEdit);
      offline.detectChanges();

      expect(offline.componentInstance.creating()).toBe(false);
      expect(offline.componentInstance.generalError()).toContain('Unexpected error');
    });

    it('should let the operator choose a receiver and send it with the sale', async () => {
      await fixture.whenStable();
      fixture.detectChanges();

      const chosen: CustomerOption = { id: 'cust-9', name: 'Ana Gómez' };
      component.onCustomerSelected(chosen);
      fixture.detectChanges();

      expect(component.headerForm().controls.customerId.value).toBe('cust-9');
      expect(component.isCounterSale()).toBe(false);
      expect(fixture.nativeElement.querySelector('[data-testid="counter-sale"]')).toBeNull();

      component.onSave();

      expect(salesOrderService.create).toHaveBeenLastCalledWith({
        customerId: 'cust-9',
        companyStoreId: 'store-1',
        shiftId: undefined,
      });
    });

    it('should show spinner element when creating is true', () => {
      component.creating.set(true);
      fixture.detectChanges();

      const spinner = fixture.nativeElement.querySelector('[data-testid="creating-spinner"]');
      expect(spinner).toBeTruthy();
      expect(spinner.textContent).toContain('Creating order...');
    });

    it('should auto-create exactly once, not once per change detection', async () => {
      await fixture.whenStable();
      fixture.detectChanges();
      fixture.detectChanges();

      expect(salesOrderService.create).toHaveBeenCalledTimes(1);
    });
  });

  // ══════════════════════════════════════════════════════════
  // EDIT MODE
  // ══════════════════════════════════════════════════════════

  describe('edit mode', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(SalesOrderService) as unknown as typeof salesOrderService;
      router = TestBed.inject(Router);
      vi.spyOn(router, 'navigate');

      fixture.detectChanges();
      flushReceiver();
    });

    it('should be in edit mode (isEditMode = true)', () => {
      expect(component.isEditMode()).toBe(true);
    });

    it('should have orderId set from route param', () => {
      expect(component.orderId()).toBe('so-1');
    });

    it('should load order and populate form', () => {
      expect(salesOrderService.getSalesOrder).toHaveBeenCalledWith('so-1');

      const form = component.headerForm();
      expect(form.controls.customerId.value).toBe('cust-1');
      expect(form.controls.companyStoreId.value).toBe('store-1');
      expect(form.controls.shiftId.value).toBe('shift-1');
    });

    it('should not auto-create an order in edit mode', () => {
      expect(salesOrderService.create).not.toHaveBeenCalled();
    });

    it('should show the receiver the order names and not call it a counter sale', () => {
      expect(component.receiverId()).toBe('cust-1');
      expect(component.selectedCustomer()).toEqual({ id: 'cust-1', name: 'Juan Pérez' });
      expect(component.isCounterSale()).toBe(false);
      expect(fixture.nativeElement.querySelector('[data-testid="counter-sale"]')).toBeNull();
    });

    it('should state a counter sale when the loaded order names no customer', () => {
      salesOrderService.getSalesOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, customerId: null }));

      const counterSale = TestBed.createComponent(SalesOrderEdit);
      counterSale.detectChanges();

      expect(counterSale.componentInstance.receiverId()).toBe('');
      expect(counterSale.componentInstance.isCounterSale()).toBe(true);
      expect(
        counterSale.nativeElement
          .querySelector('[data-testid="counter-sale"]')
          ?.textContent?.trim(),
      ).toBe('Mostrador');
      // No receiver, no read: the page invents neither a customer nor a request for one.
      TestBed.inject(HttpTestingController).expectNone((req) =>
        req.url.startsWith(`${TEST_API}/customers`),
      );
    });

    it('should not fabricate a receiver when the customer read answers 404', () => {
      // A fresh component, because the receiver read is issued as the order loads: this case has to answer
      // it itself instead of inheriting the flush of the shared fixture.
      const unanswered = TestBed.createComponent(SalesOrderEdit);
      unanswered.detectChanges();

      TestBed.inject(HttpTestingController)
        .match((req) => req.url === `${TEST_API}/customers/cust-1`)
        .forEach((req) => req.flush('Not Found', { status: 404, statusText: 'Not Found' }));

      const subject = unanswered.componentInstance;
      expect(subject.receiverId()).toBe('cust-1');
      expect(subject.selectedCustomer()).toBeNull();
      expect(
        subject.isCounterSale(),
        'the sale names a receiver we could not name; it is not a counter sale',
      ).toBe(false);
    });

    it('should populate line items from loaded order', () => {
      const items = component.lineItems();
      expect(items.length).toBe(1);
      expect(items[0].productVariantName).toBe('Widget Red - Large');
      expect(items[0].quantity).toBe(5);
      expect(items[0].listPrice).toBe(100);
    });

    it('should compute isModifiable = true while the order is open, Pending included', () => {
      expect(component.isModifiable()).toBe(true);

      component.loadedOrder.set({ ...mockOrder, statusName: 'Active' });
      expect(component.isModifiable()).toBe(true);

      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      expect(component.isModifiable()).toBe(true);
    });

    it('should compute isModifiable = false only for a terminal status', () => {
      component.loadedOrder.set({
        ...mockOrder,
        statusName: 'Completed',
      });
      expect(component.isModifiable()).toBe(false);

      component.loadedOrder.set({
        ...mockOrder,
        statusName: 'Cancelled',
      });
      expect(component.isModifiable()).toBe(false);
    });

    it('should hand the line table the same gate, so the first scanned line does not lock it', () => {
      const table = fixture.debugElement.query(By.directive(SalesOrderItemTable)).componentInstance;
      expect(table.isModifiable()).toBe(true);

      component.loadedOrder.set({ ...mockOrder, statusName: 'Active' });
      fixture.detectChanges();
      expect(table.isModifiable()).toBe(true);

      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      fixture.detectChanges();
      expect(table.isModifiable()).toBe(true);

      component.loadedOrder.set({ ...mockOrder, statusName: 'Completed' });
      fixture.detectChanges();
      expect(table.isModifiable()).toBe(false);
    });

    it('should call service.update with header-only data on save (no items)', () => {
      component.onSave();

      expect(salesOrderService.update).toHaveBeenCalledWith('so-1', {
        customerId: 'cust-1',
        companyStoreId: 'store-1',
        shiftId: 'shift-1',
      });
    });

    it('should navigate to list after successful update', () => {
      component.onSave();
      expect(router.navigate).toHaveBeenCalledWith(['/sales/orders']);
    });

    it('should handle server error on update', () => {
      const apiError = {
        status: 400,
        message: 'Error',
        errors: { comments: 'Too long' },
      };
      salesOrderService.update = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: apiError,
              status: 400,
              statusText: 'Bad Request',
            }),
        ),
      );

      component.onSave();

      expect(component.serverErrors()).toEqual({ comments: 'Too long' });
    });

    it('should handle load error on edit mode', () => {
      salesOrderService.getSalesOrder = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 404,
              statusText: 'Not Found',
            }),
        ),
      );

      // Re-create to trigger loadOrder with error
      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      expect(comp.generalError()).toContain('Order not found');
    });

    it('should handle load error (5xx)', () => {
      salesOrderService.getSalesOrder = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 500,
              statusText: 'Server Error',
            }),
        ),
      );

      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      expect(comp.generalError()).toContain('Error loading');
    });
  });

  // ══════════════════════════════════════════════════════════
  // LINE ITEMS
  // ══════════════════════════════════════════════════════════

  describe('line items', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(SalesOrderService) as unknown as typeof salesOrderService;

      fixture.detectChanges();
    });

    it('should compute displayStoreName from userStoreName, pre-filled by the flat read (W3/T20)', () => {
      // T20: in create mode the store name arrives resolved from the flat, id-addressed read, so it is no
      // longer null when the fixture is created — that is the behaviour this test now starts from.
      expect(component.displayStoreName()).toBe('Tienda Centro');
      component.userStoreName.set('My Store');
      expect(component.displayStoreName()).toBe('My Store');
    });
  });

  // ══════════════════════════════════════════════════════════
  // CANCEL / BACK
  // ══════════════════════════════════════════════════════════

  describe('cancel navigation', () => {
    it('should have a Back button linking to order list', async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders(null),
      }).compileComponents();

      const f = TestBed.createComponent(SalesOrderEdit);
      // Create mode auto-creates an order and navigates to it; the spy keeps that off the empty test router.
      vi.spyOn(TestBed.inject(Router), 'navigate');
      f.detectChanges();

      const backBtn = f.nativeElement.querySelector('button[routerLink="/sales/orders"]');
      expect(backBtn).toBeTruthy();
      expect(backBtn.textContent).toContain('Back');
    });
  });

  // ══════════════════════════════════════════════════════════
  // 409 CONFLICT
  // ══════════════════════════════════════════════════════════

  describe('conflict handling', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(SalesOrderService) as unknown as typeof salesOrderService;

      fixture.detectChanges();
    });

    it('should handle 409 conflict on update', () => {
      salesOrderService.update = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { message: 'Conflict: resource modified concurrently' },
              status: 409,
              statusText: 'Conflict',
            }),
        ),
      );

      component.onSave();

      expect(component.generalError()).toContain('concurrently modified');
    });
  });

  // ══════════════════════════════════════════════════════════
  // VARIANT SELECTION
  // ══════════════════════════════════════════════════════════

  describe('variant selection', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(SalesOrderService) as unknown as typeof salesOrderService;

      fixture.detectChanges();
    });

    function createServerItem(
      variantId: string,
      variantName: string,
      price: number,
    ): SalesOrderItem {
      return {
        id: `item-${variantId}`,
        salesOrderId: 'so-1',
        productVariantId: variantId,
        productVariantName: variantName,
        quantity: 1,
        listPrice: price,
        discountApplied: 0,
        finalPrice: price,
        promotionId: undefined,
        statusId: 'st-draft',
        statusName: 'Draft',
        createdAt: '2026-06-01T11:00:00Z',
        updatedAt: '2026-06-01T11:00:00Z',
      };
    }

    function makeVariant(id: string, name: string, price: number) {
      return {
        id,
        productId: 'prod-1',
        variantName: name,
        barCode: 'BAR',
        sku: 'SKU',
        listPrice: price,
        stock: 100,
        enabled: true,
        productName: 'Test Product',
      };
    }

    it('should call addItem with correct payload when a variant is selected', () => {
      const variant = makeVariant('pv-new', 'Widget Blue - Medium', 75);
      const serverItem = createServerItem('pv-new', 'Widget Blue - Medium', 75);
      salesOrderService.addItem = vi.fn().mockReturnValue(of(serverItem));

      component.onVariantSelected(variant);

      expect(salesOrderService.addItem).toHaveBeenCalledWith('so-1', {
        productVariantId: 'pv-new',
        quantity: 1,
        listPrice: 75,
        discountApplied: 0,
      });
    });

    it('should append server response to lineItems on successful add', () => {
      const variant = makeVariant('pv-new', 'Widget Blue - Medium', 75);
      const serverItem = createServerItem('pv-new', 'Widget Blue - Medium', 75);
      salesOrderService.addItem = vi.fn().mockReturnValue(of(serverItem));
      // loadOrder() is called after addItem succeeds — return an updated order
      salesOrderService.getSalesOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, items: [...mockOrder.items, serverItem] }));

      expect(component.lineItems()).toHaveLength(1); // pre-populated from mockOrder

      component.onVariantSelected(variant);

      const items = component.lineItems();
      expect(items).toHaveLength(2);
      const newItem = items[1]; // appended, not prepended
      expect(newItem.productVariantId).toBe('pv-new');
      expect(newItem.quantity).toBe(1);
      expect(newItem.listPrice).toBe(75);
      expect(newItem.id).toBe('item-pv-new');
    });

    it('should not block any row while an add is in flight', () => {
      const addSubject = new Subject<SalesOrderItem>();
      salesOrderService.addItem = vi.fn().mockReturnValue(addSubject.asObservable());
      const serverItem = createServerItem('pv-new', 'Test', 50);
      salesOrderService.getSalesOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, items: [...mockOrder.items, serverItem] }));
      const variant = makeVariant('pv-new', 'Test', 50);

      expect(component.savingRowIds().size).toBe(0);

      component.onVariantSelected(variant);

      // The add writes a row that does not exist yet, so no existing row may be
      // blocked by it — the pre-existing row stays editable.
      expect(component.savingRowIds().size).toBe(0);
      expect(component.lineItems()).toHaveLength(1);

      // Complete the request
      addSubject.next(serverItem);
      addSubject.complete();

      expect(component.lineItems()).toHaveLength(2);
      expect(component.savingRowIds().size).toBe(0);
    });

    it('should show error and not add item on 409 InsufficientStock', () => {
      const variant = makeVariant('pv-new', 'Test', 50);
      const httpError = new HttpErrorResponse({
        error: { message: 'Insufficient stock' },
        status: 409,
        statusText: 'Conflict',
      });
      salesOrderService.addItem = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onVariantSelected(variant);

      expect(component.savingRowIds().size).toBe(0);
      expect(component.generalError()).toContain('Insufficient stock');
      expect(component.lineItems()).toHaveLength(1); // item NOT added
    });

    /** Locate the live selector instance so focus can be observed. */
    function scanField(): ProductVariantSelector {
      return fixture.debugElement.query(By.directive(ProductVariantSelector))
        .componentInstance as ProductVariantSelector;
    }

    it('should hand focus back to the scan field after a successful add', () => {
      const variant = makeVariant('pv-new', 'Widget Blue - Medium', 75);
      const serverItem = createServerItem('pv-new', 'Widget Blue - Medium', 75);
      salesOrderService.addItem = vi.fn().mockReturnValue(of(serverItem));
      salesOrderService.getSalesOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, items: [...mockOrder.items, serverItem] }));
      const focusSpy = vi.spyOn(scanField(), 'focusInput');

      component.onVariantSelected(variant);

      expect(focusSpy).toHaveBeenCalled();
    });

    it('should hand focus back to the scan field after a failed add', () => {
      const variant = makeVariant('pv-new', 'Test', 50);
      salesOrderService.addItem = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { message: 'Insufficient stock' },
              status: 409,
            }),
        ),
      );
      const focusSpy = vi.spyOn(scanField(), 'focusInput');

      component.onVariantSelected(variant);

      expect(focusSpy).toHaveBeenCalled();
    });
  });

  // ══════════════════════════════════════════════════════════
  // ITEM OPERATIONS (deleteItem, updateItem)
  // ══════════════════════════════════════════════════════════

  describe('item operations', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(SalesOrderService) as unknown as typeof salesOrderService;

      fixture.detectChanges();
    });

    describe('onItemRemoved', () => {
      it('should call deleteItem with correct orderId and itemId', () => {
        salesOrderService.deleteItem = vi.fn().mockReturnValue(of(void 0));

        expect(component.lineItems()).toHaveLength(1);

        component.onItemRemoved(0);

        expect(salesOrderService.deleteItem).toHaveBeenCalledWith('so-1', 'item-1');
      });

      it('should remove the item from lineItems on successful delete', () => {
        salesOrderService.deleteItem = vi.fn().mockReturnValue(of(void 0));

        component.onItemRemoved(0);

        expect(component.lineItems()).toEqual([]);
      });

      it('should handle deleteItem error without removing the item', () => {
        const httpError = new HttpErrorResponse({
          error: { message: 'Cannot delete item' },
          status: 400,
          statusText: 'Bad Request',
        });
        salesOrderService.deleteItem = vi.fn().mockReturnValue(throwError(() => httpError));

        component.onItemRemoved(0);

        // Item should remain in the list
        expect(component.lineItems()).toHaveLength(1);
      });
    });

    describe('onQuantityChanged', () => {
      it('should call updateItem with correct payload', () => {
        const updatedItem: SalesOrderItem = {
          ...mockItem,
          quantity: 10,
          finalPrice: 1000,
        };
        salesOrderService.updateItem = vi.fn().mockReturnValue(of(updatedItem));

        component.onQuantityChanged({ index: 0, value: 10 });

        expect(salesOrderService.updateItem).toHaveBeenCalledWith('so-1', 'item-1', {
          productVariantId: 'pv-1',
          quantity: 10,
          listPrice: 100,
          discountApplied: 0,
        });
      });

      it('should update lineItems with server response on success', () => {
        const updatedItem: SalesOrderItem = {
          ...mockItem,
          quantity: 10,
          finalPrice: 1000,
        };
        salesOrderService.updateItem = vi.fn().mockReturnValue(of(updatedItem));

        component.onQuantityChanged({ index: 0, value: 10 });

        const items = component.lineItems();
        expect(items[0].quantity).toBe(10);
        expect(items[0].listPrice).toBe(100);
        expect(items[0].discountApplied).toBe(0);
      });

      it('should revert quantity on error', () => {
        salesOrderService.updateItem = vi
          .fn()
          .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400 })));

        component.onQuantityChanged({ index: 0, value: 10 });

        // Should revert to previous value (5 from mockOrder)
        const items = component.lineItems();
        expect(items[0].quantity).toBe(5);
      });
    });

    describe('onListPriceChanged', () => {
      it('should call updateItem with new list price', () => {
        const updatedItem: SalesOrderItem = {
          ...mockItem,
          listPrice: 120,
          finalPrice: 600,
        };
        salesOrderService.updateItem = vi.fn().mockReturnValue(of(updatedItem));

        component.onListPriceChanged({ index: 0, value: 120 });

        expect(salesOrderService.updateItem).toHaveBeenCalledWith('so-1', 'item-1', {
          productVariantId: 'pv-1',
          quantity: 5,
          listPrice: 120,
          discountApplied: 0,
        });
      });

      it('should revert listPrice on error', () => {
        salesOrderService.updateItem = vi
          .fn()
          .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400 })));

        component.onListPriceChanged({ index: 0, value: 120 });

        expect(component.lineItems()[0].listPrice).toBe(100);
      });
    });

    describe('onDiscountChanged', () => {
      it('should call updateItem with new discount', () => {
        const updatedItem: SalesOrderItem = {
          ...mockItem,
          discountApplied: 10,
          finalPrice: 490,
        };
        salesOrderService.updateItem = vi.fn().mockReturnValue(of(updatedItem));

        component.onDiscountChanged({ index: 0, value: 10 });

        expect(salesOrderService.updateItem).toHaveBeenCalledWith('so-1', 'item-1', {
          productVariantId: 'pv-1',
          quantity: 5,
          listPrice: 100,
          discountApplied: 10,
        });
      });

      it('should update lineItems with server response on success', () => {
        const updatedItem: SalesOrderItem = {
          ...mockItem,
          discountApplied: 10,
          finalPrice: 490,
        };
        salesOrderService.updateItem = vi.fn().mockReturnValue(of(updatedItem));

        component.onDiscountChanged({ index: 0, value: 10 });

        expect(component.lineItems()[0].discountApplied).toBe(10);
      });

      it('should revert discount on error', () => {
        salesOrderService.updateItem = vi
          .fn()
          .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400 })));

        component.onDiscountChanged({ index: 0, value: 10 });

        expect(component.lineItems()[0].discountApplied).toBe(0);
      });
    });

    describe('per-row write tracking', () => {
      it('should mark a row while its delete is in flight and release it on success', () => {
        const deleteSubject = new Subject<void>();
        salesOrderService.deleteItem = vi.fn().mockReturnValue(deleteSubject.asObservable());

        expect(component.savingRowIds().size).toBe(0);

        component.onItemRemoved(0);

        // In-flight: only the row being written is marked, by its id.
        expect(component.savingRowIds().has('item-1')).toBe(true);

        // Complete
        deleteSubject.next();
        deleteSubject.complete();

        expect(component.savingRowIds().size).toBe(0);
      });

      it('should mark a row while its update is in flight and release it on success', () => {
        const updateSubject = new Subject<SalesOrderItem>();
        salesOrderService.updateItem = vi.fn().mockReturnValue(updateSubject.asObservable());

        expect(component.savingRowIds().size).toBe(0);
        component.onQuantityChanged({ index: 0, value: 10 });

        expect(component.savingRowIds().has('item-1')).toBe(true);

        updateSubject.next({ ...mockItem, quantity: 10, finalPrice: 1000 });
        updateSubject.complete();

        expect(component.savingRowIds().size).toBe(0);
      });

      it('should release the row when its write fails', () => {
        salesOrderService.updateItem = vi
          .fn()
          .mockReturnValue(
            throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
          );

        component.onQuantityChanged({ index: 0, value: 10 });

        expect(component.savingRowIds().size).toBe(0);
      });

      it('should block a second edit to a row while its own write is open', () => {
        const updateSubject = new Subject<SalesOrderItem>();
        salesOrderService.updateItem = vi.fn().mockReturnValue(updateSubject.asObservable());

        component.onQuantityChanged({ index: 0, value: 9 });
        fixture.detectChanges();

        // The row's local copy has not absorbed the write yet. If the price input
        // stayed live, editing it would read the pre-edit quantity and send it
        // back, silently undoing the quantity that was already accepted.
        const inputs = Array.from<HTMLInputElement>(
          fixture.nativeElement.querySelectorAll('app-sales-order-item-table input[type="number"]'),
        );
        expect(inputs).toHaveLength(3);
        expect(inputs.every((input) => input.disabled)).toBe(true);

        updateSubject.next({ ...mockItem, quantity: 9 });
        updateSubject.complete();
        fixture.detectChanges();

        expect(inputs.some((input) => input.disabled)).toBe(false);
      });

      it('should track two rows independently while both writes are open', () => {
        const firstSubject = new Subject<SalesOrderItem>();
        const secondSubject = new Subject<SalesOrderItem>();
        salesOrderService.updateItem = vi
          .fn()
          .mockReturnValueOnce(firstSubject.asObservable())
          .mockReturnValueOnce(secondSubject.asObservable());

        component.lineItems.set([
          {
            id: 'item-1',
            productVariantId: 'pv-1',
            productVariantName: 'A',
            quantity: 1,
            listPrice: 10,
            discountApplied: 0,
          },
          {
            id: 'item-2',
            productVariantId: 'pv-2',
            productVariantName: 'B',
            quantity: 1,
            listPrice: 20,
            discountApplied: 0,
          },
        ]);

        component.onQuantityChanged({ index: 0, value: 2 });
        component.onQuantityChanged({ index: 1, value: 3 });

        expect(component.savingRowIds().has('item-1')).toBe(true);
        expect(component.savingRowIds().has('item-2')).toBe(true);

        // Settling one row must not release the other: a single index could not
        // represent this state.
        firstSubject.next({ ...mockItem, id: 'item-1', quantity: 2 });
        firstSubject.complete();

        expect(component.savingRowIds().has('item-1')).toBe(false);
        expect(component.savingRowIds().has('item-2')).toBe(true);

        secondSubject.next({ ...mockItem, id: 'item-2', quantity: 3 });
        secondSubject.complete();

        expect(component.savingRowIds().size).toBe(0);
      });
    });
  });

  // ══════════════════════════════════════════════════════════
  // INLINE ITEMS ON SAVE
  // ══════════════════════════════════════════════════════════

  describe('inline items on save', () => {
    describe('edit mode', () => {
      beforeEach(async () => {
        await TestBed.configureTestingModule({
          imports: [SalesOrderEdit, HttpClientTestingModule],
          providers: baseProviders('so-1'),
        }).compileComponents();

        fixture = TestBed.createComponent(SalesOrderEdit);
        component = fixture.componentInstance;
        salesOrderService = TestBed.inject(
          SalesOrderService,
        ) as unknown as typeof salesOrderService;
        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigate');

        fixture.detectChanges();
      });

      it('should save header only without items in edit mode', () => {
        component.onSave();

        expect(salesOrderService.update).toHaveBeenCalledWith('so-1', {
          customerId: 'cust-1',
          companyStoreId: 'store-1',
          shiftId: 'shift-1',
        });
      });

      it('should save header only even when unsaved items exist locally', () => {
        // Items are added via individual API calls and not sent on save
        component.lineItems.update((rows) => [
          ...rows,
          {
            productVariantId: 'pv-new',
            productVariantName: 'New Item',
            quantity: 3,
            listPrice: 50,
            discountApplied: 5,
          },
        ]);

        component.onSave();

        expect(salesOrderService.update).toHaveBeenCalledWith('so-1', {
          customerId: 'cust-1',
          companyStoreId: 'store-1',
          shiftId: 'shift-1',
        });
      });

      it('should handle server error on update with items', () => {
        const apiError = {
          status: 400,
          message: 'Validation failed',
          errors: { 'items[0].quantity': 'Must be positive' },
        };
        salesOrderService.update = vi.fn().mockReturnValue(
          throwError(
            () =>
              new HttpErrorResponse({
                error: apiError,
                status: 400,
                statusText: 'Bad Request',
              }),
          ),
        );

        component.onSave();

        expect(component.serverErrors()).toEqual({
          'items[0].quantity': 'Must be positive',
        });
        expect(component.saving()).toBe(false);
      });
    });

    describe('create mode', () => {
      beforeEach(async () => {
        await TestBed.configureTestingModule({
          imports: [SalesOrderEdit, HttpClientTestingModule],
          providers: baseProviders(null),
        }).compileComponents();

        fixture = TestBed.createComponent(SalesOrderEdit);
        component = fixture.componentInstance;
        salesOrderService = TestBed.inject(
          SalesOrderService,
        ) as unknown as typeof salesOrderService;
        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigate');

        fixture.detectChanges();
      });

      it('should save header-only in create mode (no items)', () => {
        const form = component.headerForm();
        form.controls.customerId.setValue('cust-1');
        form.controls.companyStoreId.setValue('store-1');
        form.controls.shiftId.setValue('shift-1');

        component.onSave();

        expect(salesOrderService.create).toHaveBeenCalledWith({
          customerId: 'cust-1',
          companyStoreId: 'store-1',
          shiftId: 'shift-1',
        });
      });
    });
  });

  // ══════════════════════════════════════════════════════════
  // LOADING STATE
  // ══════════════════════════════════════════════════════════

  describe('bootstrap loading state', () => {
    it('should set initialLoading to true while fetching order in edit mode', async () => {
      const orderSubject = new Subject<SalesOrder>();

      const mocks = baseMocks();
      mocks.getSalesOrder = vi.fn().mockReturnValue(orderSubject.asObservable());

      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: [...baseProviders('so-1'), { provide: SalesOrderService, useValue: mocks }],
      }).compileComponents();

      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      // While the request is in flight, initialLoading should be true
      expect(comp.initialLoading()).toBe(true);

      // Complete the request
      orderSubject.next(mockOrder);
      orderSubject.complete();
      f.detectChanges();

      // After the request completes, initialLoading should be false
      expect(comp.initialLoading()).toBe(false);
    });

    it('should set initialLoading to false after request errors', async () => {
      const orderSubject = new Subject<SalesOrder>();

      const mocks = baseMocks();
      mocks.getSalesOrder = vi.fn().mockReturnValue(orderSubject.asObservable());

      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: [...baseProviders('so-1'), { provide: SalesOrderService, useValue: mocks }],
      }).compileComponents();

      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      expect(comp.initialLoading()).toBe(true);

      orderSubject.error(new HttpErrorResponse({ status: 404 }));
      f.detectChanges();

      expect(comp.initialLoading()).toBe(false);
    });

    it('should NOT be in the bootstrap loading state in create mode', async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders(null),
      }).compileComponents();

      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      // Create mode auto-creates an order and navigates to it; the spy keeps that off the empty test router.
      vi.spyOn(TestBed.inject(Router), 'navigate');
      f.detectChanges();

      expect(comp.initialLoading()).toBe(false);
    });

    it('should not re-arm the page gate when a line item is added', async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders('so-1'),
      }).compileComponents();

      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      const service = TestBed.inject(SalesOrderService) as unknown as {
        addItem: ReturnType<typeof vi.fn>;
        getSalesOrder: ReturnType<typeof vi.fn>;
      };
      f.detectChanges();

      const addedItem: SalesOrderItem = {
        ...mockItem,
        id: 'item-2',
        productVariantId: 'pv-2',
        productVariantName: 'Widget Blue - Medium',
        listPrice: 50,
      };
      service.addItem = vi.fn().mockReturnValue(of(addedItem));
      const syncSubject = new Subject<SalesOrder>();
      service.getSalesOrder = vi.fn().mockReturnValue(syncSubject.asObservable());

      const formBefore = f.debugElement.query(By.css('form'));
      expect(formBefore).not.toBeNull();

      comp.onVariantSelected({
        id: 'pv-2',
        productId: 'prod-1',
        variantName: 'Widget Blue - Medium',
        barCode: 'BAR-2',
        sku: 'SKU-2',
        listPrice: 50,
        stock: 100,
        enabled: true,
        productName: 'Test Product',
      });
      f.detectChanges();

      // The mutation re-syncs in the background without gating the page.
      expect(service.getSalesOrder).toHaveBeenCalledWith('so-1');
      expect(comp.initialLoading()).toBe(false);

      // The content subtree survives the mutation: the same DOM node, not a rebuilt one.
      const formAfter = f.debugElement.query(By.css('form'));
      expect(formAfter).not.toBeNull();
      expect(formAfter.nativeElement).toBe(formBefore.nativeElement);

      // And the local row is already visible before the sync answers.
      expect(comp.lineItems()).toHaveLength(2);
      syncSubject.complete();
    });
  });

  // ══════════════════════════════════════════════════════════
  // SCAN MODE
  // ══════════════════════════════════════════════════════════

  describe('scan mode', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(SalesOrderService) as unknown as typeof salesOrderService;

      fixture.detectChanges();
    });

    it('should default to true on creation', () => {
      expect(component.scanMode()).toBe(true);
    });

    it('should switch to false on toggleScanMode()', () => {
      component.toggleScanMode();
      expect(component.scanMode()).toBe(false);
    });

    it('should switch back to true on second toggle', () => {
      component.toggleScanMode();
      component.toggleScanMode();
      expect(component.scanMode()).toBe(true);
    });

    it('should reset to true on re-navigation (new component instance)', () => {
      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();
      expect(comp.scanMode()).toBe(true);
    });

    it('should pass scanMode input to ProductVariantSelector', () => {
      const selectorEl = fixture.debugElement.query(By.directive(ProductVariantSelector));
      expect(selectorEl).toBeTruthy();
      const selector = selectorEl.componentInstance as ProductVariantSelector;

      expect(selector.scanMode()).toBe(true);

      component.toggleScanMode();
      fixture.detectChanges();
      expect(selector.scanMode()).toBe(false);
    });
  });

  // ══════════════════════════════════════════════════════════
  // CHARGE / COBRAR
  // ══════════════════════════════════════════════════════════

  describe('charge / cobrar', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [SalesOrderEdit, HttpClientTestingModule],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(SalesOrderService) as unknown as typeof salesOrderService;

      fixture.detectChanges();
    });

    it('should have isPending = false when statusName is Draft', () => {
      expect(component.isPending()).toBe(false);
    });

    it('should have isPending = true when statusName is Pending', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      expect(component.isPending()).toBe(true);
    });

    it('should call chargeSalesOrder with the id and the payments the panel stated', () => {
      // Set up as Pending order
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      salesOrderService.chargeSalesOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, statusName: 'Completed' }));

      component.onCharge([{ paymentMethodId: 'pm-1', amount: 500 }]);

      expect(salesOrderService.chargeSalesOrder).toHaveBeenCalledWith('so-1', [
        { paymentMethodId: 'pm-1', amount: 500 },
      ]);
    });

    it('should set charging signal during charge and clear on success', () => {
      const chargeSubject = new Subject<SalesOrder>();
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      salesOrderService.chargeSalesOrder = vi.fn().mockReturnValue(chargeSubject.asObservable());

      expect(component.charging()).toBe(false);

      component.onCharge([{ paymentMethodId: 'pm-1', amount: 500 }]);

      expect(component.charging()).toBe(true);

      chargeSubject.next({ ...mockOrder, statusName: 'Completed' });
      chargeSubject.complete();

      expect(component.charging()).toBe(false);
    });

    it('should reload order on successful charge', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      salesOrderService.chargeSalesOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, statusName: 'Completed' }));
      // Reset the mock so we can verify it's called again
      salesOrderService.getSalesOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, statusName: 'Completed' }));

      component.onCharge([{ paymentMethodId: 'pm-1', amount: 500 }]);

      expect(salesOrderService.getSalesOrder).toHaveBeenCalledWith('so-1');
    });

    it('should show error notification on charge failure', () => {
      const notificationService = TestBed.inject(NotificationService);
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      salesOrderService.chargeSalesOrder = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { message: 'Order is not Pending' },
              status: 400,
              statusText: 'Bad Request',
            }),
        ),
      );

      component.onCharge([{ paymentMethodId: 'pm-1', amount: 500 }]);

      expect(notificationService.showError).toHaveBeenCalled();
    });

    it('should pass the payments the operator stated to chargeSalesOrder', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      salesOrderService.chargeSalesOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, statusName: 'Completed' }));

      component.onCharge([{ paymentMethodId: 'pm-2', amount: 500 }]);

      expect(salesOrderService.chargeSalesOrder).toHaveBeenCalledWith('so-1', [
        { paymentMethodId: 'pm-2', amount: 500 },
      ]);
    });
  });
});
