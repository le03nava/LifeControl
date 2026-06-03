import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { provideRouter, Router, ActivatedRoute } from '@angular/router';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { PurchaseOrderEdit } from './purchase-order-edit';
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { ProductService } from '@features/products/data/product.service';
import { SupplierService } from '@features/products/suppliers/data/supplier.service';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { NotificationService } from '@shared/data/notification';
import { ConfigService } from '@app/services/config.service';
import type {
  PurchaseOrder,
  PurchaseOrderDetail,
  Page,
} from '../../models/purchase-order.models';

const TEST_API = 'http://test/api';

const mockOrder: PurchaseOrder = {
  id: 'po-1',
  orderNumber: 'PO-00001',
  supplierId: 'sup-1',
  supplierName: 'Acme Corp',
  companyStoreId: 'store-1',
  companyStoreName: 'Tienda Centro',
  paymentMethodId: 'pm-1',
  paymentMethodName: 'Transferencia',
  statusId: 'st-draft',
  statusName: 'Draft',
  comments: 'Test order',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
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

const emptyPage: Page<any> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  size: 1000,
  number: 0,
  first: true,
  last: true,
  empty: true,
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

describe('PurchaseOrderEdit', () => {
  let component: PurchaseOrderEdit;
  let fixture: ComponentFixture<PurchaseOrderEdit>;
  let purchaseOrderService: { [K in keyof PurchaseOrderService]?: ReturnType<typeof vi.fn> };
  let productService: { getProducts: ReturnType<typeof vi.fn> };
  let router: Router;

  // ─── Default service mocks (empty) ──────────────────────

  const baseMocks = () => ({
    getPurchaseOrders: vi.fn(),
    getPurchaseOrder: vi.fn().mockReturnValue(of(mockOrder)),
    create: vi.fn().mockReturnValue(of(mockOrder)),
    update: vi.fn().mockReturnValue(of(mockOrder)),
    updateStatus: vi.fn(),
    addDetail: vi.fn(),
    updateDetail: vi.fn(),
    deleteDetail: vi.fn(),
  });

  const baseProviders = (routeId: string | null) => [
    provideRouter([]),
    { provide: ConfigService, useValue: { apiUrl: TEST_API } },
    {
      provide: PurchaseOrderService,
      useValue: baseMocks(),
    },
    {
      provide: ProductService,
      useValue: {
        getProducts: vi.fn().mockReturnValue(of(emptyPage)),
      },
    },
    {
      provide: SupplierService,
      useValue: {
        getAllSuppliers: vi.fn().mockReturnValue(of(emptyPage)),
      },
    },
    {
      provide: CompanyService,
      useValue: {
        getCompanies: vi.fn().mockReturnValue(of(emptyPage)),
      },
    },
    {
      provide: CompanyCountryService,
      useValue: {
        getCountries: vi.fn().mockReturnValue(of([])),
      },
    },
    {
      provide: CompanyRegionService,
      useValue: {
        getRegions: vi.fn().mockReturnValue(of([])),
      },
    },
    {
      provide: CompanyZoneService,
      useValue: {
        getZones: vi.fn().mockReturnValue(of([])),
      },
    },
    {
      provide: CompanyStoreService,
      useValue: {
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
        imports: [
          PurchaseOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders(null),
      }).compileComponents();

      fixture = TestBed.createComponent(PurchaseOrderEdit);
      component = fixture.componentInstance;
      purchaseOrderService = TestBed.inject(
        PurchaseOrderService,
      ) as unknown as typeof purchaseOrderService;
      productService = TestBed.inject(ProductService) as unknown as typeof productService;
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

    it('should have an empty form initially', () => {
      const form = component.headerForm();
      expect(form.controls.supplierId.value).toBe('');
      expect(form.controls.companyStoreId.value).toBe('');
      expect(form.controls.paymentMethodId.value).toBe('');
    });

    it('should load products for line item dropdown', () => {
      expect(productService.getProducts).toHaveBeenCalledWith(0, 1000);
    });

    it('should call service.create with form data on valid save', () => {
      const form = component.headerForm();
      form.controls.supplierId.setValue('sup-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.paymentMethodId.setValue('pm-1');
      form.controls.comments.setValue('New PO');

      component.onSave();

      expect(purchaseOrderService.create).toHaveBeenCalledWith({
        supplierId: 'sup-1',
        companyStoreId: 'store-1',
        paymentMethodId: 'pm-1',
        comments: 'New PO',
        details: undefined,
      });
    });

    it('should navigate to edit page after successful create', () => {
      const created: PurchaseOrder = { ...mockOrder, id: 'po-new' };
      purchaseOrderService.create = vi
        .fn()
        .mockReturnValue(of(created));

      const form = component.headerForm();
      form.controls.supplierId.setValue('sup-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.paymentMethodId.setValue('pm-1');
      component.onSave();

      expect(router.navigate).toHaveBeenCalledWith([
        '/purchases/orders',
        'po-new',
      ]);
    });

    it('should not save when form is invalid', () => {
      // Form defaults are empty (required fields missing)
      component.onSave();
      expect(purchaseOrderService.create).not.toHaveBeenCalled();
    });

    it('should mark all fields as touched when form invalid', () => {
      component.onSave();
      const form = component.headerForm();
      expect(form.controls.supplierId.touched).toBe(true);
      expect(form.controls.companyStoreId.touched).toBe(true);
      expect(form.controls.paymentMethodId.touched).toBe(true);
    });

    it('should handle server error on create', () => {
      const apiError = {
        status: 400,
        message: 'Validation error',
        errors: { supplierId: 'Required' },
      };
      purchaseOrderService.create = vi.fn().mockReturnValue(
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
      form.controls.supplierId.setValue('sup-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.paymentMethodId.setValue('pm-1');
      component.onSave();

      expect(component.serverErrors()).toEqual({ supplierId: 'Required' });
    });

    it('should handle generic server error (5xx)', () => {
      purchaseOrderService.create = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 500,
              statusText: 'Server Error',
            }),
        ),
      );

      const form = component.headerForm();
      form.controls.supplierId.setValue('sup-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.paymentMethodId.setValue('pm-1');
      component.onSave();

      expect(component.generalError()).toContain('Error inesperado');
    });
  });

  // ══════════════════════════════════════════════════════════
  // EDIT MODE
  // ══════════════════════════════════════════════════════════

  describe('edit mode', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [
          PurchaseOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders('po-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(PurchaseOrderEdit);
      component = fixture.componentInstance;
      purchaseOrderService = TestBed.inject(
        PurchaseOrderService,
      ) as unknown as typeof purchaseOrderService;
      router = TestBed.inject(Router);
      vi.spyOn(router, 'navigate');

      fixture.detectChanges();
    });

    it('should be in edit mode (isEditMode = true)', () => {
      expect(component.isEditMode()).toBe(true);
    });

    it('should have orderId set from route param', () => {
      expect(component.orderId()).toBe('po-1');
    });

    it('should load order and populate form', () => {
      expect(purchaseOrderService.getPurchaseOrder).toHaveBeenCalledWith(
        'po-1',
      );

      const form = component.headerForm();
      expect(form.controls.supplierId.value).toBe('sup-1');
      expect(form.controls.companyStoreId.value).toBe('store-1');
      expect(form.controls.paymentMethodId.value).toBe('pm-1');
    });

    it('should populate line items from loaded order', () => {
      const items = component.lineItems();
      expect(items.length).toBe(1);
      expect(items[0].productName).toBe('Widget A');
      expect(items[0].quantity).toBe(10);
      expect(items[0].unitPrice).toBe(150);
    });

    it('should compute isDraft = true when statusName is Draft', () => {
      expect(component.isDraft()).toBe(true);
    });

    it('should compute isDraft = false when statusName is not Draft', () => {
      // Override loaded order signal directly
      component.loadedOrder.set({
        ...mockOrder,
        statusName: 'Sent',
      });
      expect(component.isDraft()).toBe(false);
    });

    it('should call service.update with form data on save', () => {
      // Mark form as valid by ensuring required fields are set
      // (they are already populated from loadOrder → patchValue)
      component.onSave();

      expect(purchaseOrderService.update).toHaveBeenCalledWith('po-1', {
        supplierId: 'sup-1',
        companyStoreId: 'store-1',
        paymentMethodId: 'pm-1',
        comments: 'Test order',
        details: [
          {
            productId: 'prod-1',
            quantity: 10,
            unitPrice: 150,
          },
        ],
      });
    });

    it('should navigate to list after successful update', () => {
      component.onSave();
      expect(router.navigate).toHaveBeenCalledWith(['/purchases/orders']);
    });

    it('should handle server error on update', () => {
      const apiError = {
        status: 400,
        message: 'Error',
        errors: { comments: 'Too long' },
      };
      purchaseOrderService.update = vi.fn().mockReturnValue(
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
      purchaseOrderService.getPurchaseOrder = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 404,
              statusText: 'Not Found',
            }),
        ),
      );

      // Re-create to trigger loadOrder with error
      const f = TestBed.createComponent(PurchaseOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      expect(comp.generalError()).toContain('no encontrada');
    });
  });

  // ══════════════════════════════════════════════════════════
  // LINE ITEMS
  // ══════════════════════════════════════════════════════════

  describe('line items', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [
          PurchaseOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders('po-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(PurchaseOrderEdit);
      component = fixture.componentInstance;
      purchaseOrderService = TestBed.inject(
        PurchaseOrderService,
      ) as unknown as typeof purchaseOrderService;

      fixture.detectChanges();
    });

    it('should update lineItems when onItemsChanged called', () => {
      const newItems = [
        {
          id: 'det-2',
          productId: 'prod-2',
          productName: 'Widget B',
          quantity: 5,
          unitPrice: 200,
        },
      ];
      component.onItemsChanged(newItems);
      expect(component.lineItems()).toEqual(newItems);
    });

    it('should include line items in save request (update mode)', () => {
      component.onItemsChanged([
        {
          id: 'det-1',
          productId: 'prod-1',
          productName: 'Widget A',
          quantity: 20,
          unitPrice: 150,
        },
      ]);
      component.onSave();

      expect(purchaseOrderService.update).toHaveBeenCalled();
      const callArgs = (purchaseOrderService.update as ReturnType<typeof vi.fn>)
        .mock.calls[0];
      const request = callArgs[1];
      expect(request.details).toEqual([
        { productId: 'prod-1', quantity: 20, unitPrice: 150 },
      ]);
    });

    it('should compute initialStore from loadedOrder', () => {
      expect(component.initialStore()).toEqual({
        id: 'store-1',
        name: 'Tienda Centro',
      });
    });
  });

  // ══════════════════════════════════════════════════════════
  // CANCEL
  // ══════════════════════════════════════════════════════════

  describe('onCancel', () => {
    it('should navigate back to order list', async () => {
      await TestBed.configureTestingModule({
        imports: [
          PurchaseOrderEdit,
          NoopAnimationsModule,
          HttpClientTestingModule,
        ],
        providers: baseProviders(null),
      }).compileComponents();

      const f = TestBed.createComponent(PurchaseOrderEdit);
      const comp = f.componentInstance;
      const r = TestBed.inject(Router);
      vi.spyOn(r, 'navigate');

      comp.onCancel();
      expect(r.navigate).toHaveBeenCalledWith(['/purchases/orders']);
    });
  });
});
