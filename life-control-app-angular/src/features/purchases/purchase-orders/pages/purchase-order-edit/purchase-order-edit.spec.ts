import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LOCALE_ID } from '@angular/core';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import {
  provideRouter,
  withRouterConfig,
  Router,
  RouterLink,
  ActivatedRoute,
} from '@angular/router';
import { By } from '@angular/platform-browser';
import { of, Subject, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { PurchaseOrderEdit, NEW_LINE_KEY } from './purchase-order-edit';
import { DetailTable } from '../../components/detail-table/detail-table';
import { CompanyInfoSection } from '../../components/company-info-section/company-info-section';
import { CompanyCascadeService } from '../../data/company-cascade.service';
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
import { formatMoney } from '@shared/pipes/money.pipe';
import type {
  PurchaseOrder,
  PurchaseOrderDetail,
  Page,
} from '../../models/purchase-order.models';

const TEST_API = 'http://test/api';

/**
 * The locale the money expectation is computed in.
 *
 * `en-US` and not the app's `es-MX`: `MoneyPipe` formats through Angular's `formatCurrency`, and `es-MX` data is
 * registered by `app.config.ts`, which a unit test does not load. The spec states the locale it injects so the
 * expected string and the rendered one are produced by the same rule instead of by a hardcoded literal.
 */
const SPEC_LOCALE = 'en-US';

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
  // Neither the app's `DEFAULT_CURRENCY_CODE` nor the literal the screen used to hardcode: a render that falls back
  // to either of those cannot produce this string, so "the total is shown in the document's currency" is measurable.
  currency: 'EUR',
  subtotal: 1500.0001,
  taxTotal: 0,
  // One ten-thousandth above `quantity × unitPrice` (10 × 150 = 1500), so the assertion that the page shows this
  // number also proves the page is not summing the rows itself.
  totalAmount: 1500.0001,
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
      total: 1500.0001,
      // This order states no tax, which is the zero case the breakdown has to stay silent about.
      taxCode: 'NA',
      taxRate: 0,
      taxAmount: 0,
      receivedQuantity: 0,
      comments: null,
      statusId: 'st-draft',
      statusName: 'Draft',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
  ],
};

/**
 * The same document with a tax, for the breakdown the bar has to state.
 *
 * `taxTotal` is one ten-thousandth above `subtotal × 0.16` (240.0000), so a bar that multiplied the base by the rate
 * instead of reading the response cannot print the string these tests expect. `totalAmount` is the response's own sum,
 * so the three parts stay a coherent document.
 */
const TAXED_ORDER: PurchaseOrder = {
  ...mockOrder,
  subtotal: 1500.0001,
  taxTotal: 240.0001,
  totalAmount: 1740.0002,
  details: [
    {
      ...mockOrder.details[0],
      taxCode: 'IVA',
      taxRate: 0.16,
      taxAmount: 240.0001,
    },
  ],
};

/**
 * A line as the detail routes answer it.
 *
 * Built from the loaded order's own line so every field the response carries is present, and overridable per case: a
 * test that asserts a row came from the answer has to be able to state numbers the request did not send.
 */
function detailFixture(overrides: Partial<PurchaseOrderDetail> = {}): PurchaseOrderDetail {
  return { ...mockOrder.details[0], ...overrides };
}

/** A second saved line, for the cases that need one row to stay untouched while another fails. */
const secondDetail: PurchaseOrderDetail = {
  ...mockOrder.details[0],
  id: 'det-2',
  productId: 'prod-2',
  productName: 'Widget B',
  productVariantId: 'var-2',
  productVariantName: 'Presentación 2L',
  quantity: 5,
  unitPrice: 200,
  total: 1000,
  taxRate: 0,
  taxAmount: 0,
  createdAt: '2026-01-02T00:00:00Z',
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

  /**
   * The document total the sticky bar renders, exactly as the DOM states it.
   *
   * `undefined` when the element is absent — which is what create mode must produce: with no server answer there is
   * nothing to show, and a `0` would be a number nobody stated (S6).
   */
  function renderedDocumentTotal(f: ComponentFixture<PurchaseOrderEdit>): string | undefined {
    const element = (f.nativeElement as HTMLElement).querySelector('.summary-total');
    return element?.textContent?.trim();
  }

  /** Everything the sticky summary states, label included. */
  function summaryText(f: ComponentFixture<PurchaseOrderEdit>): string {
    return (
      (f.nativeElement as HTMLElement).querySelector('.actions-summary')?.textContent ?? ''
    ).trim();
  }

  /**
   * One part of the document breakdown the sticky bar states, exactly as the DOM prints it.
   *
   * `undefined` when the part is absent — which is what the tax has to be on an order that states none.
   */
  function renderedBreakdownPart(
    f: ComponentFixture<PurchaseOrderEdit>,
    part: 'subtotal' | 'tax' | 'total',
  ): string | undefined {
    const element = (f.nativeElement as HTMLElement).querySelector(`.summary-${part}`);
    return element?.textContent?.trim();
  }

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
    provideRouter([], withRouterConfig({ resolveNavigationPromiseOnError: true })),
    { provide: LOCALE_ID, useValue: SPEC_LOCALE },
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
        getCompanySelector: vi.fn().mockReturnValue(of(emptyPage)),
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
        // The flat, id-addressed read the cascade seeds from (W3/T20).
        getStoreById: vi.fn().mockReturnValue(
          of({
            id: 'store-1',
            companyId: 'comp-1',
            companyCountryId: 'cc-1',
            regionId: 'reg-1',
            zoneId: 'zone-1',
            storeName: 'Tienda Uno',
            enabled: true,
          }),
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
        imports: [PurchaseOrderEdit, HttpClientTestingModule],
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

    it('should not render the detail area before the order exists', () => {
      // A line is addressed by the order's id on the detail sub-resource, so before there is one the operator sees the
      // header only (S1).
      expect(fixture.debugElement.query(By.directive(DetailTable))).toBeNull();
      expect((fixture.nativeElement as HTMLElement).querySelector('app-detail-table')).toBeNull();
    });

    it('should not offer the receipt action without a loaded order', () => {
      expect(component.loadedOrder()).toBeNull();
      const buttons = fixture.debugElement.queryAll(By.css('.header-tools button'));
      expect(buttons.length).toBe(1);
    });

    it('should render no document total before the order exists', () => {
      // The total is the server's statement about the document; before there is one, a number here would be the
      // client's own arithmetic wearing the server's voice (S6).
      expect(renderedDocumentTotal(fixture)).toBeUndefined();
      expect(summaryText(fixture)).toBe('');
    });

    it('should load supplier products when supplier changes, scoped to the order company', () => {
      const mockProducts = [{ productId: 'prod-1', productName: 'Widget A', sku: 'SKU-001' }];
      productService.getProductsBySupplier = vi.fn().mockReturnValue(of(mockProducts));

      const cascade = fixture.debugElement
        .query(By.directive(CompanyInfoSection))
        .injector.get(CompanyCascadeService);
      cascade.onCompanyChange('company-1');
      fixture.detectChanges();
      component.headerForm().controls.supplierId.setValue('sup-1');

      // The assertion that fails if the company were dropped: the read is company-scoped on the wire, so
      // a call without it either does not compile or hits a `/api/products` path nothing serves.
      expect(productService.getProductsBySupplier).toHaveBeenCalledWith('company-1', 'sup-1');
      expect(component.supplierProducts()).toEqual([
        { id: 'prod-1', name: 'Widget A', sku: 'SKU-001' },
      ]);
    });

    it('should request no supplier products while the order has no company', () => {
      // An unsaved order has no company yet. Nothing is requested and the list stays empty: a
      // company-blind read would answer with another tenant's catalogue.
      productService.getProductsBySupplier = vi.fn().mockReturnValue(of([]));

      const form = component.headerForm();
      form.controls.company.controls.companyId.setValue(null);
      form.controls.supplierId.setValue('sup-1');

      expect(productService.getProductsBySupplier).not.toHaveBeenCalled();
      expect(component.supplierProducts()).toEqual([]);
    });

    it('should expose the order company the variant picker is scoped to', () => {
      // The same company reaches the picker through `<app-detail-table [companyId]>`; this is the value
      // that binding reads, and it tracks the cascade group rather than a second copy.
      //
      // The company is selected through the cascade, which is what the screen does: every cascade write
      // is `setValue(..., { emitEvent: false })`, so a spec that sets the control with an emitting
      // `setValue` measures a channel the application never uses.
      const cascade = fixture.debugElement
        .query(By.directive(CompanyInfoSection))
        .injector.get(CompanyCascadeService);

      expect(component.orderCompanyId()).toBeNull();

      cascade.onCompanyChange('company-1');
      fixture.detectChanges();

      expect(component.orderCompanyId()).toBe('company-1');
    });

    it('should load supplier products for a company chosen through the cascade', () => {
      // Choosing a company and then a supplier is the operator's path, and the product read is scoped to
      // the company the cascade resolved. With the company unobserved it would never be requested.
      productService.getProductsBySupplier = vi.fn().mockReturnValue(of([]));
      const cascade = fixture.debugElement
        .query(By.directive(CompanyInfoSection))
        .injector.get(CompanyCascadeService);

      cascade.onCompanyChange('company-1');
      fixture.detectChanges();
      component.headerForm().controls.supplierId.setValue('sup-1');

      expect(productService.getProductsBySupplier).toHaveBeenCalledWith('company-1', 'sup-1');
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

    it('should call service.create with the header and no details', () => {
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
      });
      // The created order is a Draft with no lines: the create call carries the header and nothing else (S3).
      const request = (purchaseOrderService.create as ReturnType<typeof vi.fn>).mock.calls[0][0];
      expect('details' in request).toBe(false);
    });

    it('should create the order and turn the same screen into its edit surface', () => {
      const created: PurchaseOrder = { ...mockOrder, id: 'po-new' };
      purchaseOrderService.create = vi.fn().mockReturnValue(of(created));

      const form = component.headerForm();
      form.controls.supplierId.setValue('sup-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.paymentMethodId.setValue('pm-1');
      component.onSave();

      // The same page is now the created order's edit surface, and the history entry is replaced so Back does not
      // return to a `/create` URL that would produce a second order (S2).
      expect(component.orderId()).toBe('po-new');
      expect(component.isEditMode()).toBe(true);
      expect(router.navigate).toHaveBeenCalledWith(['/purchases/orders', 'po-new'], {
        replaceUrl: true,
      });

      fixture.detectChanges();

      const table = fixture.debugElement.query(By.directive(DetailTable))
        .componentInstance as DetailTable;
      expect(table).toBeTruthy();
      // The detail area is scoped to the order the server just created.
      expect(table.storeId()).toBe('store-1');
      expect(table.currency()).toBe(created.currency);
    });

    it('should stay on the create screen and create nothing when the create fails', () => {
      purchaseOrderService.create = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { message: 'La tienda no pertenece a la empresa' },
              status: 400,
              statusText: 'Bad Request',
            }),
        ),
      );

      const form = component.headerForm();
      form.controls.supplierId.setValue('sup-1');
      form.controls.companyStoreId.setValue('store-1');
      form.controls.paymentMethodId.setValue('pm-1');
      form.controls.comments.setValue('nota');
      component.onSave();
      fixture.detectChanges();

      expect(component.orderId()).toBeNull();
      expect(component.isEditMode()).toBe(false);
      expect(fixture.debugElement.query(By.directive(DetailTable))).toBeNull();
      expect(router.navigate).not.toHaveBeenCalled();
      // The header is intact and the server's message is what the operator reads (S3).
      expect(component.headerForm().controls.comments.value).toBe('nota');
      expect(component.generalError()).toBe('La tienda no pertenece a la empresa');
      expect(component.saving()).toBe(false);
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
        imports: [PurchaseOrderEdit, HttpClientTestingModule],
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

    it('should follow the company an order reconstruction resolves', () => {
      // Edit mode rebuilds the cascade from the loaded order, and that rebuild patches the control with
      // `emitEvent: false` as well. `reconstructFromOrder` is the exact entry point the screen uses;
      // the page must end up scoped to the order's company, or the supplier read has no tenant.
      const companyService = TestBed.inject(CompanyService) as unknown as {
        getCompanySelector: ReturnType<typeof vi.fn>;
      };
      companyService.getCompanySelector = vi
        .fn()
        .mockReturnValue(of({ ...emptyPage, content: [{ id: 'company-1', companyName: 'Acme' }] }));
      const cascade = fixture.debugElement
        .query(By.directive(CompanyInfoSection))
        .injector.get(CompanyCascadeService);
      cascade.searchCompanies('');

      cascade.reconstructFromOrder({
        ...mockOrder,
        companyId: 'company-1',
        companyCountryId: null,
        regionId: null,
        zoneId: null,
        companyStoreId: '',
      });
      fixture.detectChanges();

      expect(component.orderCompanyId()).toBe('company-1');
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
      // The line total the response carries reaches the row, so the table renders the server's number.
      expect(items[0].total).toBe(mockOrder.details[0].total);
    });

    it('should render the document total the response states, in the order currency', () => {
      expect(renderedDocumentTotal(fixture)).toBe(
        formatMoney(mockOrder.totalAmount, mockOrder.currency, SPEC_LOCALE),
      );
      expect(summaryText(fixture)).toContain('Total');
    });

    it('should keep the served total when a line changes instead of re-summing the rows', () => {
      // The one assertion that fails if any client-side `reduce` came back: a row whose quantity is ten times larger
      // does not move the number the server stated for the document.
      expect(renderedDocumentTotal(fixture)).toBe(
        formatMoney(mockOrder.totalAmount, mockOrder.currency, SPEC_LOCALE),
      );

      purchaseOrderService.updateDetail = vi.fn().mockReturnValue(of(detailFixture({ quantity: 100 })));
      component.updateLine('det-1', { quantity: 100 });
      fixture.detectChanges();

      expect(renderedDocumentTotal(fixture)).toBe(
        formatMoney(mockOrder.totalAmount, mockOrder.currency, SPEC_LOCALE),
      );
    });

    it('should carry no client-side total at all', () => {
      // The removed derivation, asserted by its absence: `orderTotal` summed `quantity × unitPrice` in the browser.
      expect('orderTotal' in component).toBe(false);
    });

    it('should render the three parts the response states', () => {
      purchaseOrderService.getPurchaseOrder = vi.fn().mockReturnValue(of(TAXED_ORDER));
      const f = TestBed.createComponent(PurchaseOrderEdit);
      f.detectChanges();

      expect(renderedBreakdownPart(f, 'subtotal')).toBe(
        formatMoney(TAXED_ORDER.subtotal, TAXED_ORDER.currency, SPEC_LOCALE),
      );
      expect(renderedBreakdownPart(f, 'tax')).toBe(
        formatMoney(TAXED_ORDER.taxTotal, TAXED_ORDER.currency, SPEC_LOCALE),
      );
      expect(renderedBreakdownPart(f, 'total')).toBe(
        formatMoney(TAXED_ORDER.totalAmount, TAXED_ORDER.currency, SPEC_LOCALE),
      );
      // `subtotal × rate` lands a ten-thousandth lower: the bar prints the response's tax, not a product of it.
      expect(renderedBreakdownPart(f, 'tax')).not.toBe(
        formatMoney(
          TAXED_ORDER.subtotal * (TAXED_ORDER.details[0].taxRate ?? 0),
          TAXED_ORDER.currency,
          SPEC_LOCALE,
        ),
      );
    });

    it('should carry the response tax into the line rows', () => {
      purchaseOrderService.getPurchaseOrder = vi.fn().mockReturnValue(of(TAXED_ORDER));
      const f = TestBed.createComponent(PurchaseOrderEdit);
      f.detectChanges();

      const row = f.componentInstance.lineItems()[0];
      expect(row.taxRate).toBe(TAXED_ORDER.details[0].taxRate);
      expect(row.taxAmount).toBe(TAXED_ORDER.details[0].taxAmount);
    });

    it('should render no tax line for an order that states no tax', () => {
      // `mockOrder` states `taxTotal: 0`: an untaxed order must not grow a `0.0000` line it never had.
      expect(renderedBreakdownPart(fixture, 'tax')).toBeUndefined();
      expect(summaryText(fixture)).not.toContain('Impuesto');
      // The parts that are always stated are still there, so the absence above is the tax and not the breakdown.
      expect(renderedBreakdownPart(fixture, 'subtotal')).toBe(
        formatMoney(mockOrder.subtotal, mockOrder.currency, SPEC_LOCALE),
      );
      expect(renderedBreakdownPart(fixture, 'total')).toBe(
        formatMoney(mockOrder.totalAmount, mockOrder.currency, SPEC_LOCALE),
      );
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

    it('should update only the status without resetting the line rows', () => {
      purchaseOrderService.addDetail = vi.fn().mockReturnValue(
        of(detailFixture({ id: 'det-2', productId: 'prod-9', productName: 'Widget nuevo' })),
      );

      component.addLine({ productId: 'prod-9', productVariantId: 'var-9', quantity: 3, unitPrice: 25 });

      component.onStatusChanged('Sent');

      expect(component.loadedOrder()?.statusName).toBe('Sent');
      expect(component.lineItems().map((item) => item.id)).toEqual(['det-1', 'det-2']);
    });

    it('should not report unsaved changes right after loading', () => {
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should report unsaved changes after editing line items', () => {
      purchaseOrderService.updateDetail = vi.fn().mockReturnValue(of(detailFixture({ quantity: 20 })));

      component.updateLine('det-1', { quantity: 20 });

      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should show a success notification after update', () => {
      const notification = TestBed.inject(NotificationService) as unknown as {
        showSuccess: ReturnType<typeof vi.fn>;
      };
      component.onSave();
      expect(notification.showSuccess).toHaveBeenCalledWith('Orden actualizada correctamente.');
    });

    it('should save the header alone, leaving every line to its own route', () => {
      component.onSave();

      expect(purchaseOrderService.update).toHaveBeenCalledWith('po-1', {
        supplierId: 'sup-1',
        companyStoreId: 'store-1',
        paymentMethodId: 'pm-1',
        comments: 'Test order',
      });
      // A batch save would restate lines the detail routes already persisted (S4).
      const request = (purchaseOrderService.update as ReturnType<typeof vi.fn>).mock.calls[0][1];
      expect('details' in request).toBe(false);
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

  describe('line persistence', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [PurchaseOrderEdit, HttpClientTestingModule],
        providers: baseProviders('po-1'),
      }).compileComponents();

      fixture = TestBed.createComponent(PurchaseOrderEdit);
      component = fixture.componentInstance;
      purchaseOrderService = TestBed.inject(
        PurchaseOrderService,
      ) as unknown as typeof purchaseOrderService;

      fixture.detectChanges();
    });

    it('should create one line through the detail route and take the row from the answer', () => {
      const inFlight = new Subject<PurchaseOrderDetail>();
      purchaseOrderService.addDetail = vi.fn().mockReturnValue(inFlight.asObservable());

      component.addLine({
        productId: 'prod-2',
        productVariantId: 'var-2',
        quantity: 5,
        unitPrice: 200,
      });

      expect(purchaseOrderService.addDetail).toHaveBeenCalledWith('po-1', {
        productId: 'prod-2',
        productVariantId: 'var-2',
        quantity: 5,
        unitPrice: 200,
      });
      // The line is pending on its own, and no row exists until the server answers for it (S4).
      expect(component.linePending()[NEW_LINE_KEY]).toBe(true);
      expect(component.lineItems().map((item) => item.id)).toEqual(['det-1']);
      // Adding a line never saves the header as a side effect.
      expect(purchaseOrderService.update).not.toHaveBeenCalled();

      inFlight.next(
        detailFixture({ id: 'det-2', quantity: 6, unitPrice: 190, total: 1140 }),
      );
      inFlight.complete();

      expect(component.linePending()[NEW_LINE_KEY]).toBeUndefined();
      const row = component.lineItems().find((item) => item.id === 'det-2');
      // The answer's numbers, not the request's: the server owns the line's identity and derives its money.
      expect(row?.quantity).toBe(6);
      expect(row?.unitPrice).toBe(190);
      expect(row?.total).toBe(1140);
    });

    it('should restate the document money from the server after a line is added', () => {
      purchaseOrderService.addDetail = vi
        .fn()
        .mockReturnValue(of(detailFixture({ id: 'det-2', total: 1140 })));
      purchaseOrderService.getPurchaseOrder = vi.fn().mockReturnValue(
        of({ ...mockOrder, subtotal: 2640.0001, taxTotal: 0, totalAmount: 2640.0001 }),
      );

      component.addLine({
        productId: 'prod-2',
        productVariantId: 'var-2',
        quantity: 5,
        unitPrice: 200,
      });

      // The document's money is derived by the database from its rows, so the page asks again instead of summing.
      expect(purchaseOrderService.getPurchaseOrder).toHaveBeenCalledWith('po-1');
      expect(component.loadedOrder()?.totalAmount).toBe(2640.0001);
      // Only the money is restated: the rows stay the page's, and nothing was saved as a side effect.
      expect(component.lineItems().map((item) => item.id)).toEqual(['det-1', 'det-2']);
      expect(purchaseOrderService.update).not.toHaveBeenCalled();
    });

    it('should leave no row and report on the add alone when the line is rejected', () => {
      purchaseOrderService.addDetail = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { message: 'La variante no pertenece a la tienda' },
              status: 400,
              statusText: 'Bad Request',
            }),
        ),
      );

      component.addLine({
        productId: 'prod-2',
        productVariantId: 'var-2',
        quantity: 5,
        unitPrice: 200,
      });

      // A rejected POST leaves no row, and its message belongs to that line, not to the document (S5).
      expect(component.lineItems().map((item) => item.id)).toEqual(['det-1']);
      expect(component.lineErrors()[NEW_LINE_KEY]).toBe('La variante no pertenece a la tienda');
      expect(component.linePending()[NEW_LINE_KEY]).toBeUndefined();
      expect(component.generalError()).toBeNull();
    });

    it('should save one edited row through the detail route and reconcile it from the answer', () => {
      purchaseOrderService.updateDetail = vi
        .fn()
        .mockReturnValue(of(detailFixture({ quantity: 12, total: 1800.0004 })));

      component.updateLine('det-1', { quantity: 12 });

      expect(purchaseOrderService.updateDetail).toHaveBeenCalledWith('po-1', 'det-1', {
        productId: 'prod-1',
        productVariantId: 'var-1',
        quantity: 12,
        unitPrice: 150,
      });

      const row = component.lineItems()[0];
      expect(row.quantity).toBe(12);
      // The total is the server's for the edited line, not the one it stated before the edit.
      expect(row.total).toBe(1800.0004);
      expect(component.linePending()['det-1']).toBeUndefined();
      expect(component.lineErrors()['det-1']).toBeUndefined();
    });

    it('should restore the row, keep the other rows and report on it alone when its edit is rejected', () => {
      purchaseOrderService.getPurchaseOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, details: [mockOrder.details[0], secondDetail] }));
      const inFlight = new Subject<PurchaseOrderDetail>();
      purchaseOrderService.updateDetail = vi.fn().mockReturnValue(inFlight.asObservable());

      const f = TestBed.createComponent(PurchaseOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      comp.updateLine('det-1', { quantity: 99 });

      // The edit is on screen while it is in flight, without the amounts the server stated for the base it replaced.
      expect(comp.lineItems()[0].quantity).toBe(99);
      expect(comp.lineItems()[0].total).toBeNull();
      expect(comp.linePending()['det-1']).toBe(true);

      inFlight.error(
        new HttpErrorResponse({
          error: { message: 'La cantidad supera el stock' },
          status: 400,
          statusText: 'Bad Request',
        }),
      );

      const row = comp.lineItems()[0];
      expect(row.quantity).toBe(10);
      expect(row.total).toBe(mockOrder.details[0].total);
      expect(comp.lineErrors()['det-1']).toBe('La cantidad supera el stock');
      expect(comp.linePending()['det-1']).toBeUndefined();
      // Neither the other row nor the document is touched by one rejected line (S5).
      expect(comp.lineItems()[1].id).toBe('det-2');
      expect(comp.lineItems()[1].quantity).toBe(secondDetail.quantity);
      expect(comp.generalError()).toBeNull();
    });

    it('should delete one line through the detail route and drop it from the rows', () => {
      purchaseOrderService.deleteDetail = vi.fn().mockReturnValue(of(undefined));

      component.removeLine('det-1');

      expect(purchaseOrderService.deleteDetail).toHaveBeenCalledWith('po-1', 'det-1');
      expect(component.lineItems()).toEqual([]);
      expect(component.linePending()['det-1']).toBeUndefined();
    });

    it('should put the row back and report on it when the delete is rejected', () => {
      purchaseOrderService.deleteDetail = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { message: 'Solo se pueden eliminar líneas en Borrador' },
              status: 409,
              statusText: 'Conflict',
            }),
        ),
      );

      component.removeLine('det-1');

      // A row the server still holds is never silently hidden.
      expect(component.lineItems().map((item) => item.id)).toEqual(['det-1']);
      expect(component.lineErrors()['det-1']).toBe('Solo se pueden eliminar líneas en Borrador');
      expect(component.generalError()).toBeNull();
    });

    it('should not send an update for a line the API cannot address without a variant', () => {
      purchaseOrderService.getPurchaseOrder = vi.fn().mockReturnValue(
        of({
          ...mockOrder,
          details: [
            { ...mockOrder.details[0], productVariantId: null, productVariantName: null },
          ],
        }),
      );

      const f = TestBed.createComponent(PurchaseOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      comp.updateLine('det-1', { quantity: 12 });

      expect(purchaseOrderService.updateDetail).not.toHaveBeenCalled();
      expect(comp.lineErrors()['det-1']).toContain('no tiene variante');
      // The row keeps the values it had: nothing was sent, so nothing may look saved.
      expect(comp.lineItems()[0].quantity).toBe(10);
    });

    it('should order the rows by createdAt then id, not as the API sent them', () => {
      // The API returns the lines with no `ORDER BY` and the schema has no ordinal, so `createdAt` then `id` is the
      // only stable order available (S9). The two lines written in the same instant are told apart by id.
      const later = { ...mockOrder.details[0], id: 'det-c', createdAt: '2026-03-01T00:00:00Z' };
      const sameInstantA = { ...secondDetail, id: 'det-a', createdAt: '2026-01-01T00:00:00Z' };
      const sameInstantB = { ...secondDetail, id: 'det-b', createdAt: '2026-01-01T00:00:00Z' };
      purchaseOrderService.getPurchaseOrder = vi
        .fn()
        .mockReturnValue(of({ ...mockOrder, details: [later, sameInstantB, sameInstantA] }));

      const f = TestBed.createComponent(PurchaseOrderEdit);
      const comp = f.componentInstance;
      f.detectChanges();

      expect(comp.lineItems().map((item) => item.id)).toEqual(['det-a', 'det-b', 'det-c']);
    });

    it('should keep the creation order after a line is added', () => {
      purchaseOrderService.addDetail = vi.fn().mockReturnValue(
        of(detailFixture({ id: 'det-0', createdAt: '2025-12-31T00:00:00Z' })),
      );

      component.addLine({
        productId: 'prod-9',
        productVariantId: 'var-9',
        quantity: 1,
        unitPrice: 10,
      });

      // The added line was written before the loaded one, so it sorts first even though it arrived last.
      expect(component.lineItems().map((item) => item.id)).toEqual(['det-0', 'det-1']);
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
        imports: [PurchaseOrderEdit, HttpClientTestingModule],
        providers: [
          ...baseProviders(null),
          {
            provide: CompanyService,
            useValue: {
              getCompanySelector: vi.fn().mockReturnValue(
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
              // T20: the chain is resolved from the store id, so the fixture carries it.
              getStoreById: vi.fn().mockReturnValue(
                of({
                  id: 'store-7',
                  companyId: 'comp-1',
                  companyCountryId: 'cc-1',
                  regionId: 'reg-1',
                  zoneId: 'zone-1',
                  storeName: 'Tienda Centro',
                  enabled: true,
                }),
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
                  // T14: the four tenancy ancestors left the wire; the chain comes from the flat read.
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

    it('should clear the line-item scope when the cascade resets the store', () => {
      // The detail area is not on this screen until the order exists (S1), so the value the table is bound to is measured
      // here through the signal itself; the create→edit case asserts the same value reaching the rendered table.
      const cascadeChild = fixture.debugElement.query(By.directive(CompanyInfoSection))
        .componentInstance as CompanyInfoSection;
      expect(component.variantStoreId()).toBe('store-7');

      cascadeChild.onZoneChange('zone-9');
      fixture.detectChanges();

      // The reset is silent too: only the output channel can carry it.
      expect(component.headerForm().controls.companyStoreId.value).toBe('');
      expect(component.variantStoreId()).toBe('');
    });
  });

  // ══════════════════════════════════════════════════════════
  // CANCEL
  // ══════════════════════════════════════════════════════════

  describe('onCancel', () => {
    it('should navigate back to order list', async () => {
      await TestBed.configureTestingModule({
        imports: [PurchaseOrderEdit, HttpClientTestingModule],
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
