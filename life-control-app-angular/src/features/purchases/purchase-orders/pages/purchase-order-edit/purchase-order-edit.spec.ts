import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { provideRouter, Router, RouterLink, ActivatedRoute } from '@angular/router';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { PurchaseOrderEdit } from './purchase-order-edit';
import { DetailTable } from '../../components/detail-table/detail-table';
import { CompanyInfoSection } from '../../components/company-info-section/company-info-section';
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { ProductService } from '@features/products/data/product.service';
import { SupplierService } from '@features/products/suppliers/data/supplier.service';
import { PaymentMethodService } from '../../data/payment-method.service';
import { StatusService } from '../../data/status.service';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { NotificationService } from '@shared/data/notification';
import { ConfigService } from '@app/services/config.service';
import type { PurchaseOrder, Page } from '../../models/purchase-order.models';

const TEST_API = 'http://test/api';

const mockOrder: PurchaseOrder = {
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
      productVariantId: 'var-1',
      productVariantName: 'Presentación 1L',
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

const emptyPage: Page<unknown> = {
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
        get: vi.fn().mockReturnValue(params.id ?? null),
      },
    },
  };
}

describe('PurchaseOrderEdit', () => {
  let component: PurchaseOrderEdit;
  let fixture: ComponentFixture<PurchaseOrderEdit>;
  let purchaseOrderService: { [K in keyof PurchaseOrderService]?: ReturnType<typeof vi.fn> };
  let productService: { getProductsBySupplier: ReturnType<typeof vi.fn> };
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
        getProductsBySupplier: vi.fn().mockReturnValue(of([])),
      },
    },
    {
      provide: SupplierService,
      useValue: {
        getSuppliers: vi.fn().mockReturnValue(of(emptyPage)),
        getSupplierById: vi.fn().mockReturnValue(
          of({
            id: 'sup-1',
            supplierName: 'Acme Corp',
            razonSocial: 'Acme Corp S.A.',
            rfc: 'RFC-ACME',
            email: 'ventas@acme.com',
            phoneNumber: '555-0000',
            enabled: true,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          }),
        ),
      },
    },
    {
      provide: PaymentMethodService,
      useValue: {
        getPaymentMethods: vi.fn().mockReturnValue(of([])),
      },
    },
    {
      provide: StatusService,
      useValue: {
        getStatusTypeIdByName: vi.fn().mockReturnValue(of('type-po')),
        getStatusesByTypeId: vi.fn().mockReturnValue(of([])),
      },
    },
    {
      provide: CompanyService,
      useValue: {
        getCompanies: vi.fn().mockReturnValue(of(emptyPage)),
        getCompanyById: vi.fn().mockReturnValue(of({})),
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
      provide: ProfileService,
      useValue: {
        getProfile: vi.fn().mockReturnValue(
          of({
            keycloakUserId: 'user-1',
            username: 'testuser',
            email: 'test@example.com',
            firstName: 'Test',
            lastName: 'User',
            companyId: null,
            companyCountryId: null,
            companyRegionId: null,
            companyZoneId: null,
            companyStoreId: null,
          }),
        ),
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
        imports: [PurchaseOrderEdit, NoopAnimationsModule, HttpClientTestingModule],
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

    it('should treat a new order as draft so line items are editable', () => {
      expect(component.isDraft()).toBe(true);
    });

    it('should have an empty form initially', () => {
      const form = component.headerForm();
      expect(form.controls.supplierId.value).toBe('');
      expect(form.controls.companyStoreId.value).toBe('');
      expect(form.controls.paymentMethodId.value).toBe('');
    });

    it('should initially have empty supplier products (no supplier selected)', () => {
      expect(component.supplierProducts()).toEqual([]);
    });

    it('should keep the receipt progress view off while no order is loaded', () => {
      const table = fixture.debugElement.query(By.directive(DetailTable))
        .componentInstance as DetailTable;

      expect(table.showReceiptProgress()).toBe(false);
    });

    it('should not offer the receipt action without a loaded order', () => {
      expect(component.loadedOrder()).toBeNull();
      const buttons = fixture.debugElement.queryAll(By.css('.header-tools button'));
      expect(buttons.length).toBe(1);
    });

    it('should load supplier products when supplier changes', () => {
      const mockProducts = [{ productId: 'prod-1', productName: 'Widget A', sku: 'SKU-001' }];
      productService.getProductsBySupplier = vi.fn().mockReturnValue(of(mockProducts));

      const form = component.headerForm();
      form.controls.supplierId.setValue('sup-1');

      expect(productService.getProductsBySupplier).toHaveBeenCalledWith('sup-1');
      expect(component.supplierProducts()).toEqual([
        { id: 'prod-1', name: 'Widget A', sku: 'SKU-001' },
      ]);
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

    it('should navigate to the order list after successful create', () => {
      const created: PurchaseOrder = { ...mockOrder, id: 'po-new' };
      purchaseOrderService.create = vi.fn().mockReturnValue(of(created));

      const form = component.headerForm();
      form.controls.supplierId.setValue('sup-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.paymentMethodId.setValue('pm-1');
      component.onSave();

      expect(router.navigate).toHaveBeenCalledWith(['/purchases/orders']);
    });

    it('should show a success notification after create', () => {
      const notification = TestBed.inject(NotificationService) as unknown as {
        showSuccess: ReturnType<typeof vi.fn>;
      };
      purchaseOrderService.create = vi.fn().mockReturnValue(of(mockOrder));

      const form = component.headerForm();
      form.controls.supplierId.setValue('sup-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.paymentMethodId.setValue('pm-1');
      component.onSave();

      expect(notification.showSuccess).toHaveBeenCalledWith('Orden creada correctamente.');
    });

    it('should not report unsaved changes on a fresh form', () => {
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should report unsaved changes after editing the header', () => {
      component.headerForm().controls.comments.setValue('draft note');
      expect(component.hasUnsavedChanges()).toBe(true);
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
        imports: [PurchaseOrderEdit, NoopAnimationsModule, HttpClientTestingModule],
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

    // The receipt action is only rendered for a receivable order. It has to be
    // a router link so the `receipts/create` route guard runs.
    function receiptAction(f: ComponentFixture<PurchaseOrderEdit>) {
      return f.debugElement
        .queryAll(By.css('.header-tools button'))
        .find((button) => (button.nativeElement.textContent ?? '').includes('Recibir mercadería'));
    }

    it('should be in edit mode (isEditMode = true)', () => {
      expect(component.isEditMode()).toBe(true);
    });

    it('should have orderId set from route param', () => {
      expect(component.orderId()).toBe('po-1');
    });

    it('should load order and populate form', () => {
      expect(purchaseOrderService.getPurchaseOrder).toHaveBeenCalledWith('po-1');

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
      expect(items[0].productVariantId).toBe('var-1');
      expect(items[0].productVariantName).toBe('Presentación 1L');
    });

    it('should enable the receipt progress view of the detail table for a loaded order', () => {
      const table = fixture.debugElement.query(By.directive(DetailTable))
        .componentInstance as DetailTable;

      expect(table.showReceiptProgress()).toBe(true);
    });

    it('should map receivedQuantity and statusName into the loaded line items', () => {
      const row = component.lineItems()[0];
      expect(row.receivedQuantity).toBe(0);
      expect(row.statusName).toBe('Draft');
    });

    it('should keep the unsaved-changes baseline after loading a received line', () => {
      const receivedOrder: PurchaseOrder = {
        ...mockOrder,
        statusName: 'Accepted',
        details: [{ ...mockOrder.details[0], receivedQuantity: 4, statusName: 'Partial Received' }],
      };
      purchaseOrderService.getPurchaseOrder = vi.fn().mockReturnValue(of(receivedOrder));

      const f = TestBed.createComponent(PurchaseOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      expect(comp.lineItems()[0].receivedQuantity).toBe(4);
      expect(comp.lineItems()[0].statusName).toBe('Partial Received');
      // The baseline is taken from the same mapped rows, so the receipt fields
      // must not make a freshly loaded order look dirty.
      expect(comp.hasUnsavedChanges()).toBe(false);
    });

    it('should offer the receipt action for a receivable order, pointing at the create page', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Accepted' });
      fixture.detectChanges();

      const action = receiptAction(fixture);
      expect(action).toBeDefined();

      const link = action!.injector.get(RouterLink);
      expect(link.urlTree?.toString()).toBe('/purchases/receipts/create?purchaseOrderId=po-1');
      expect(link.queryParams).toEqual({ purchaseOrderId: 'po-1' });
    });

    it('should offer the receipt action while the order is In Transit', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'In Transit' });
      fixture.detectChanges();

      expect(receiptAction(fixture)).toBeDefined();
    });

    it('should not offer the receipt action for a Draft order', () => {
      expect(component.loadedOrder()?.statusName).toBe('Draft');
      expect(receiptAction(fixture)).toBeUndefined();
    });

    it('should not offer the receipt action for a disabled order', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Accepted', enabled: false });
      fixture.detectChanges();

      expect(receiptAction(fixture)).toBeUndefined();
    });

    it('should not offer the receipt action for a Closed order', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Closed' });
      fixture.detectChanges();

      expect(receiptAction(fixture)).toBeUndefined();
    });

    it('should scope the line-item variant picker to the loaded order store', () => {
      expect(component.variantStoreId()).toBe('store-1');
    });

    it('should follow the header store selection for the variant picker', () => {
      component.headerForm().controls.companyStoreId.setValue('store-9');
      expect(component.variantStoreId()).toBe('store-9');
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

    it('should update only the status without resetting unsaved line items', () => {
      component.onItemsChanged([
        {
          id: 'det-temp',
          productId: 'prod-9',
          productName: 'Unsaved Widget',
          productVariantId: 'var-9',
          productVariantName: 'Presentación 9',
          quantity: 3,
          unitPrice: 25,
        },
      ]);

      component.onStatusChanged('Sent');

      expect(component.loadedOrder()?.statusName).toBe('Sent');
      expect(component.lineItems()).toEqual([
        {
          id: 'det-temp',
          productId: 'prod-9',
          productName: 'Unsaved Widget',
          productVariantId: 'var-9',
          productVariantName: 'Presentación 9',
          quantity: 3,
          unitPrice: 25,
        },
      ]);
    });

    it('should not report unsaved changes right after loading', () => {
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should report unsaved changes after editing line items', () => {
      component.onItemsChanged([
        {
          id: 'det-1',
          productId: 'prod-1',
          productName: 'Widget A',
          productVariantId: 'var-1',
          productVariantName: 'Presentación 1L',
          quantity: 20,
          unitPrice: 150,
        },
      ]);
      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should show a success notification after update', () => {
      const notification = TestBed.inject(NotificationService) as unknown as {
        showSuccess: ReturnType<typeof vi.fn>;
      };
      component.onSave();
      expect(notification.showSuccess).toHaveBeenCalledWith('Orden actualizada correctamente.');
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
            productVariantId: 'var-1',
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
        imports: [PurchaseOrderEdit, NoopAnimationsModule, HttpClientTestingModule],
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
          productVariantId: 'var-2',
          productVariantName: 'Presentación 2L',
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
          productVariantId: 'var-1',
          productVariantName: 'Presentación 1L',
          quantity: 20,
          unitPrice: 150,
        },
      ]);
      component.onSave();

      expect(purchaseOrderService.update).toHaveBeenCalled();
      const callArgs = (purchaseOrderService.update as ReturnType<typeof vi.fn>).mock.calls[0];
      const request = callArgs[1];
      expect(request.details).toEqual([
        { productId: 'prod-1', productVariantId: 'var-1', quantity: 20, unitPrice: 150 },
      ]);
    });

    it('should abort the save when a legacy line has no variant', () => {
      component.onItemsChanged([
        {
          id: 'det-legacy',
          productId: 'prod-1',
          productName: 'Widget A',
          productVariantId: null,
          productVariantName: null,
          quantity: 20,
          unitPrice: 150,
        },
      ]);

      component.onSave();

      expect(component.hasLinesWithoutVariant()).toBe(true);
      expect(purchaseOrderService.update).not.toHaveBeenCalled();
      // The save never starts, so no half-saved state is left behind.
      expect(component.saving()).toBe(false);
      // A silent abort is a UX bug: the click must tell the user which line is
      // unusable and what to do about it.
      expect(component.generalError()).toContain('1: Widget A');
      expect(component.generalError()).toContain('sin variante');
      // The order is still a Draft, so the actionable repair is offered.
      expect(component.generalError()).toContain(
        'Eliminá esas líneas y agregalas de nuevo eligiendo una variante.',
      );
    });

    it('should not ask for an impossible repair when the order is not a Draft', () => {
      component.loadedOrder.set({ ...mockOrder, statusName: 'Sent' });
      component.onItemsChanged([
        {
          id: 'det-legacy',
          productId: 'prod-1',
          productName: 'Widget A',
          productVariantId: null,
          productVariantName: null,
          quantity: 20,
          unitPrice: 150,
        },
      ]);

      component.onSave();

      expect(purchaseOrderService.update).not.toHaveBeenCalled();
      expect(component.generalError()).toContain('1: Widget A');
      expect(component.generalError()).toContain('no se pueden reparar en el estado actual');
      // Outside Draft the delete/add controls are disabled, so the repair
      // instruction must not be shown.
      expect(component.generalError()).not.toContain('Eliminá esas líneas');
    });
  });

  // ══════════════════════════════════════════════════════════
  // SILENT STORE RESOLUTION
  // ══════════════════════════════════════════════════════════

  describe('silent store resolution (create mode)', () => {
    beforeEach(async () => {
      // The real `CompanyInfoSection` + `CompanyCascadeService` run here against
      // profile data, so the store is applied with `emitEvent: false` — exactly
      // the path `companyStoreId.valueChanges` cannot see.
      await TestBed.configureTestingModule({
        imports: [PurchaseOrderEdit, NoopAnimationsModule, HttpClientTestingModule],
        providers: [
          ...baseProviders(null),
          {
            provide: CompanyService,
            useValue: {
              getCompanies: vi.fn().mockReturnValue(
                of({
                  ...emptyPage,
                  content: [{ id: 'comp-1', companyName: 'Empresa Uno', rfc: 'RFC-001' }],
                }),
              ),
              getCompanyById: vi.fn().mockReturnValue(
                of({
                  id: 'comp-1',
                  companyName: 'Empresa Uno',
                  rfc: 'RFC-001',
                  address: null,
                  phone: '555-1000',
                  email: 'contacto@empresauno.com',
                }),
              ),
            },
          },
          {
            provide: CompanyCountryService,
            useValue: {
              getCountries: vi.fn().mockReturnValue(of([{ id: 'cc-1', countryName: 'México' }])),
            },
          },
          {
            provide: CompanyRegionService,
            useValue: {
              getRegions: vi.fn().mockReturnValue(of([{ id: 'reg-1', regionName: 'Norte' }])),
            },
          },
          {
            provide: CompanyZoneService,
            useValue: {
              getZones: vi.fn().mockReturnValue(of([{ id: 'zone-1', zoneName: 'Zona A' }])),
            },
          },
          {
            provide: CompanyStoreService,
            useValue: {
              getStores: vi
                .fn()
                .mockReturnValue(
                  of([{ id: 'store-7', storeName: 'Tienda Centro', enabled: true }]),
                ),
            },
          },
          {
            provide: ProfileService,
            useValue: {
              getProfile: vi.fn().mockReturnValue(
                of({
                  keycloakUserId: 'user-1',
                  username: 'testuser',
                  email: 'test@example.com',
                  firstName: 'Test',
                  lastName: 'User',
                  companyId: 'comp-1',
                  companyCountryId: 'cc-1',
                  companyRegionId: 'reg-1',
                  companyZoneId: 'zone-1',
                  companyStoreId: 'store-7',
                }),
              ),
            },
          },
        ],
      }).compileComponents();

      fixture = TestBed.createComponent(PurchaseOrderEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();
      fixture.detectChanges();
    });

    it('should scope the picker to the silently pre-filled profile store', () => {
      // `valueChanges` never fires for this patch, so only the child output can
      // have produced it.
      expect(component.headerForm().controls.companyStoreId.value).toBe('store-7');
      expect(component.variantStoreId()).toBe('store-7');
    });

    it('should push the resolved store into the detail table store input', () => {
      const table = fixture.debugElement.query(By.directive(DetailTable))
        .componentInstance as DetailTable;

      expect(table.storeId()).toBe('store-7');
    });

    it('should clear the table store input when the cascade resets the store', () => {
      const cascadeChild = fixture.debugElement.query(By.directive(CompanyInfoSection))
        .componentInstance as CompanyInfoSection;
      const table = fixture.debugElement.query(By.directive(DetailTable))
        .componentInstance as DetailTable;
      expect(table.storeId()).toBe('store-7');

      cascadeChild.onZoneChange('zone-9');
      fixture.detectChanges();

      // The reset is silent too: only the output channel can carry it.
      expect(component.headerForm().controls.companyStoreId.value).toBe('');
      expect(component.variantStoreId()).toBe('');
      expect(table.storeId()).toBe('');
    });
  });

  // ══════════════════════════════════════════════════════════
  // CANCEL
  // ══════════════════════════════════════════════════════════

  describe('onCancel', () => {
    it('should navigate back to order list', async () => {
      await TestBed.configureTestingModule({
        imports: [PurchaseOrderEdit, NoopAnimationsModule, HttpClientTestingModule],
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
