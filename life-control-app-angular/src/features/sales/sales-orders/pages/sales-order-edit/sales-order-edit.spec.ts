import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { provideRouter, Router, ActivatedRoute } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { SalesOrderEdit } from './sales-order-edit';
import { SalesOrderService } from '../../data/sales-order.service';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { NotificationService } from '@shared/data/notification';
import { ConfigService } from '@app/services/config.service';
import type { SalesOrder, SalesOrderItem } from '../../models/sales-order.models';
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
  companyCountryId: null,
  companyId: null,
  companyRegionId: null,
  companyZoneId: null,
  companyStoreId: 'store-1',
};

function createActivatedRoute(params: { id?: string }) {
  return {
    snapshot: {
      paramMap: {
        get: vi
          .fn()
          .mockReturnValue(params.id ?? null),
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
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
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

    it('should initially have isDraft = true (create mode always Draft)', () => {
      expect(component.isDraft()).toBe(true);
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
      salesOrderService.create = vi
        .fn()
        .mockReturnValue(of(created));

      const form = component.headerForm();
      form.controls.customerId.setValue('cust-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.shiftId.setValue('shift-1');
      component.onSave();

      expect(router.navigate).toHaveBeenCalledWith([
        '/sales/orders',
        'so-new',
      ]);
    });

    it('should not save when form is invalid', () => {
      // Form defaults are empty (required fields missing)
      component.onSave();
      expect(salesOrderService.create).not.toHaveBeenCalled();
    });

    it('should mark all fields as touched when form invalid', () => {
      component.onSave();
      const form = component.headerForm();
      expect(form.controls.customerId.touched).toBe(true);
      expect(form.controls.companyStoreId.touched).toBe(true);
      expect(form.controls.shiftId.touched).toBe(true);
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
  });

  // ══════════════════════════════════════════════════════════
  // EDIT MODE
  // ══════════════════════════════════════════════════════════

  describe('edit mode', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
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

    it('should be in edit mode (isEditMode = true)', () => {
      expect(component.isEditMode()).toBe(true);
    });

    it('should have orderId set from route param', () => {
      expect(component.orderId()).toBe('so-1');
    });

    it('should load order and populate form', () => {
      expect(salesOrderService.getSalesOrder).toHaveBeenCalledWith(
        'so-1',
      );

      const form = component.headerForm();
      expect(form.controls.customerId.value).toBe('cust-1');
      expect(form.controls.companyStoreId.value).toBe('store-1');
      expect(form.controls.shiftId.value).toBe('shift-1');
    });

    it('should populate line items from loaded order', () => {
      const items = component.lineItems();
      expect(items.length).toBe(1);
      expect(items[0].productVariantName).toBe('Widget Red - Large');
      expect(items[0].quantity).toBe(5);
      expect(items[0].listPrice).toBe(100);
    });

    it('should compute isDraft = true when statusName is Draft', () => {
      expect(component.isDraft()).toBe(true);
    });

    it('should compute isDraft = false when statusName is not Draft', () => {
      component.loadedOrder.set({
        ...mockOrder,
        statusName: 'Completed',
      });
      expect(component.isDraft()).toBe(false);
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
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(
        SalesOrderService,
      ) as unknown as typeof salesOrderService;

      fixture.detectChanges();
    });

    it('should compute displayStoreName from loadedOrder', () => {
      expect(component.displayStoreName()).toBe('Tienda Centro');
    });
  });

  // ══════════════════════════════════════════════════════════
  // ONCANCEL
  // ══════════════════════════════════════════════════════════

  describe('onCancel', () => {
    it('should navigate back to order list', async () => {
      await TestBed.configureTestingModule({
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders(null),
      }).compileComponents();

      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      const r = TestBed.inject(Router);
      vi.spyOn(r, 'navigate');

      comp.onCancel();
      expect(r.navigate).toHaveBeenCalledWith(['/sales/orders']);
    });
  });

  // ══════════════════════════════════════════════════════════
  // 409 CONFLICT
  // ══════════════════════════════════════════════════════════

  describe('conflict handling', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(
        SalesOrderService,
      ) as unknown as typeof salesOrderService;

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
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(
        SalesOrderService,
      ) as unknown as typeof salesOrderService;

      fixture.detectChanges();
    });

    function createServerItem(variantId: string, variantName: string, price: number): SalesOrderItem {
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
      return { id, productId: 'prod-1', variantName: name, barCode: 'BAR', sku: 'SKU', listPrice: price, stock: 100, enabled: true };
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

    it('should set savingIndex during addItem and clear on completion', () => {
      const addSubject = new Subject<SalesOrderItem>();
      salesOrderService.addItem = vi.fn().mockReturnValue(addSubject.asObservable());
      const variant = makeVariant('pv-new', 'Test', 50);

      expect(component.savingIndex()).toBeNull();

      component.onVariantSelected(variant);

      // In-flight: savingIndex should be set to the index of the new row
      expect(component.savingIndex()).toBe(1);

      // Complete the request
      const serverItem = createServerItem('pv-new', 'Test', 50);
      addSubject.next(serverItem);
      addSubject.complete();

      expect(component.savingIndex()).toBeNull();
      expect(component.lineItems()).toHaveLength(2);
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

      expect(component.savingIndex()).toBeNull();
      expect(component.generalError()).toContain('Insufficient stock');
      expect(component.lineItems()).toHaveLength(1); // item NOT added
    });
  });

  // ══════════════════════════════════════════════════════════
  // ITEM OPERATIONS (deleteItem, updateItem)
  // ══════════════════════════════════════════════════════════

  describe('item operations', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(
        SalesOrderService,
      ) as unknown as typeof salesOrderService;

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
        salesOrderService.updateItem = vi.fn().mockReturnValue(
          throwError(() => new HttpErrorResponse({ status: 400 })),
        );

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
        salesOrderService.updateItem = vi.fn().mockReturnValue(
          throwError(() => new HttpErrorResponse({ status: 400 })),
        );

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
        salesOrderService.updateItem = vi.fn().mockReturnValue(
          throwError(() => new HttpErrorResponse({ status: 400 })),
        );

        component.onDiscountChanged({ index: 0, value: 10 });

        expect(component.lineItems()[0].discountApplied).toBe(0);
      });
    });

    describe('savingIndex for item operations', () => {
      it('should set savingIndex during deleteItem and clear on success', () => {
        const deleteSubject = new Subject<void>();
        salesOrderService.deleteItem = vi.fn().mockReturnValue(deleteSubject.asObservable());

        expect(component.savingIndex()).toBeNull();

        component.onItemRemoved(0);

        // In-flight
        expect(component.savingIndex()).toBe(0);

        // Complete
        deleteSubject.next();
        deleteSubject.complete();

        expect(component.savingIndex()).toBeNull();
      });

      it('should set savingIndex during updateItem and clear on success', () => {
        const updateSubject = new Subject<SalesOrderItem>();
        salesOrderService.updateItem = vi.fn().mockReturnValue(updateSubject.asObservable());

        expect(component.savingIndex()).toBeNull();
        component.onQuantityChanged({ index: 0, value: 10 });

        expect(component.savingIndex()).toBe(0);

        updateSubject.next({ ...mockItem, quantity: 10, finalPrice: 1000 });
        updateSubject.complete();

        expect(component.savingIndex()).toBeNull();
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
          imports: [
            SalesOrderEdit,
            NoopAnimationsModule,
            HttpClientTestingModule,
          ],
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
          imports: [
            SalesOrderEdit,
            NoopAnimationsModule,
            HttpClientTestingModule,
          ],
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

  describe('loading state', () => {
    it('should set loading to true while fetching order in edit mode', async () => {
      const orderSubject = new Subject<SalesOrder>();

      const mocks = baseMocks();
      mocks.getSalesOrder = vi.fn().mockReturnValue(orderSubject.asObservable());

      await TestBed.configureTestingModule({
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: [
          ...baseProviders('so-1'),
          { provide: SalesOrderService, useValue: mocks },
        ],
      }).compileComponents();

      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      // While the request is in flight, loading should be true
      expect(comp.loading()).toBe(true);

      // Complete the request
      orderSubject.next(mockOrder);
      orderSubject.complete();
      f.detectChanges();

      // After the request completes, loading should be false
      expect(comp.loading()).toBe(false);
    });

    it('should set loading to false after request errors', async () => {
      const orderSubject = new Subject<SalesOrder>();

      const mocks = baseMocks();
      mocks.getSalesOrder = vi.fn().mockReturnValue(orderSubject.asObservable());

      await TestBed.configureTestingModule({
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: [
          ...baseProviders('so-1'),
          { provide: SalesOrderService, useValue: mocks },
        ],
      }).compileComponents();

      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      expect(comp.loading()).toBe(true);

      orderSubject.error(new HttpErrorResponse({ status: 404 }));
      f.detectChanges();

      expect(comp.loading()).toBe(false);
    });

    it('should NOT be loading in create mode', async () => {
      await TestBed.configureTestingModule({
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders(null),
      }).compileComponents();

      const f = TestBed.createComponent(SalesOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      expect(comp.loading()).toBe(false);
    });
  });

  // ══════════════════════════════════════════════════════════
  // CHARGE / COBRAR
  // ══════════════════════════════════════════════════════════

  describe('charge / cobrar', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [
          SalesOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders('so-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(SalesOrderEdit);
      component = fixture.componentInstance;
      salesOrderService = TestBed.inject(
        SalesOrderService,
      ) as unknown as typeof salesOrderService;

      fixture.detectChanges();
    });

    it('should have isPending = false when statusName is Draft', () => {
      expect(component.isPending()).toBe(false);
    });

    it('should have isPending = true when statusName is Pending', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      expect(component.isPending()).toBe(true);
    });

    it('should call chargeSalesOrder with correct id and paymentMethodId on onCharge', () => {
      // Set up as Pending order
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      component.selectedPaymentMethodId.set('pm-1');
      salesOrderService.chargeSalesOrder = vi.fn().mockReturnValue(of({ ...mockOrder, statusName: 'Completed' }));

      component.onCharge();

      expect(salesOrderService.chargeSalesOrder).toHaveBeenCalledWith('so-1', 'pm-1');
    });

    it('should set charging signal during charge and clear on success', () => {
      const chargeSubject = new Subject<SalesOrder>();
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      component.selectedPaymentMethodId.set('pm-1');
      salesOrderService.chargeSalesOrder = vi.fn().mockReturnValue(chargeSubject.asObservable());

      expect(component.charging()).toBe(false);

      component.onCharge();

      expect(component.charging()).toBe(true);

      chargeSubject.next({ ...mockOrder, statusName: 'Completed' });
      chargeSubject.complete();

      expect(component.charging()).toBe(false);
    });

    it('should reload order on successful charge', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      component.selectedPaymentMethodId.set('pm-1');
      salesOrderService.chargeSalesOrder = vi.fn().mockReturnValue(of({ ...mockOrder, statusName: 'Completed' }));
      // Reset the mock so we can verify it's called again
      salesOrderService.getSalesOrder = vi.fn().mockReturnValue(of({ ...mockOrder, statusName: 'Completed' }));

      component.onCharge();

      expect(salesOrderService.getSalesOrder).toHaveBeenCalledWith('so-1');
    });

    it('should show error notification on charge failure', () => {
      const notificationService = TestBed.inject(NotificationService);
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      component.selectedPaymentMethodId.set('pm-1');
      salesOrderService.chargeSalesOrder = vi.fn().mockReturnValue(
        throwError(() => new HttpErrorResponse({
          error: { message: 'Order is not Pending' },
          status: 400,
          statusText: 'Bad Request',
        })),
      );

      component.onCharge();

      expect(notificationService.showError).toHaveBeenCalled();
    });

    it('should not call chargeSalesOrder when no payment method selected', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Pending' });
      component.selectedPaymentMethodId.set('');
      salesOrderService.chargeSalesOrder = vi.fn();

      component.onCharge();

      expect(salesOrderService.chargeSalesOrder).not.toHaveBeenCalled();
    });
  });
});
