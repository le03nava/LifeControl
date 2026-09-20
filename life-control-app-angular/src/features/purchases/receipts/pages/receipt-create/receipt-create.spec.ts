import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router, ActivatedRoute } from '@angular/router';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ReceiptCreate } from './receipt-create';
import { GoodsReceiptService } from '../../data/goods-receipt.service';
import { PurchaseOrderService } from '../../../purchase-orders/data/purchase-order.service';
import { StoreInventorySettingsService } from '@features/inventory/data/store-inventory-settings.service';
import { StoreLocationLookupService } from '@features/inventory/data/store-location-lookup.service';
import { NotificationService } from '@shared/data/notification';
import type { GoodsReceipt } from '../../models/receipt.models';
import type { Page, PurchaseOrder } from '../../../purchase-orders/models/purchase-order.models';
import type {
  StoreInventorySettings,
  StoreLocationSummary,
} from '@features/inventory/models/store-location-summary.models';

const mockOrderAccepted: PurchaseOrder = {
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
  statusId: 'st-accepted',
  statusName: 'Accepted',
  comments: null,
  enabled: true,
  createdAt: '2026-02-01T00:00:00Z',
  updatedAt: '2026-02-01T00:00:00Z',
  details: [
    {
      id: 'det-1',
      purchaseOrderId: 'po-1',
      productId: 'prod-1',
      productName: 'Widget A',
      productVariantId: 'var-1',
      productVariantName: 'Presentación 1L',
      quantity: 10,
      unitPrice: 100,
      total: 1000,
      receivedQuantity: 4,
      comments: null,
      statusId: 'st-detail',
      statusName: 'Pending',
      createdAt: '2026-02-01T00:00:00Z',
      updatedAt: '2026-02-01T00:00:00Z',
    },
    {
      id: 'det-2',
      purchaseOrderId: 'po-1',
      productId: 'prod-2',
      productName: 'Widget B',
      productVariantId: 'var-2',
      productVariantName: null,
      quantity: 3,
      unitPrice: 50,
      total: 150,
      receivedQuantity: 3,
      comments: null,
      statusId: 'st-detail',
      statusName: 'Received',
      createdAt: '2026-02-01T00:00:00Z',
      updatedAt: '2026-02-01T00:00:00Z',
    },
    {
      id: 'det-3',
      purchaseOrderId: 'po-1',
      productId: 'prod-3',
      productName: 'Widget C',
      productVariantId: 'var-3',
      productVariantName: 'Presentación 500ml',
      quantity: 5,
      unitPrice: 30,
      total: 150,
      receivedQuantity: 5,
      comments: null,
      statusId: 'st-detail',
      statusName: 'Pending',
      createdAt: '2026-02-01T00:00:00Z',
      updatedAt: '2026-02-01T00:00:00Z',
    },
  ],
};

const mockOrderDraft: PurchaseOrder = {
  ...mockOrderAccepted,
  id: 'po-2',
  orderNumber: 'PO-00002',
  supplierName: 'Beta Industries',
  statusName: 'Draft',
  statusId: 'st-draft',
};

const mockSettings: StoreInventorySettings = {
  companyStoreId: 'store-1',
  receivingLocationId: 'loc-1',
  salesLocationId: 'loc-2',
};

const mockLocations: StoreLocationSummary[] = [
  {
    id: 'loc-1',
    locationCode: 'DEP',
    locationName: 'Depósito',
    storeZoneId: 'zone-1',
    zoneCode: 'Z1',
    zoneName: 'Zona Norte',
    storeAreaId: 'area-1',
    areaCode: 'A1',
    areaName: 'Área Depósito',
  },
  {
    id: 'loc-9',
    locationCode: 'REC',
    locationName: 'Recepción',
    storeZoneId: 'zone-1',
    zoneCode: 'Z1',
    zoneName: 'Zona Norte',
    storeAreaId: 'area-2',
    areaCode: 'A2',
    areaName: 'Área Andén',
  },
];

const mockCreatedReceipt: GoodsReceipt = {
  id: 'gr-99',
  receiptNumber: 'GR-00099',
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
};

function orderPage(orders: PurchaseOrder[]): Page<PurchaseOrder> {
  return {
    content: orders,
    totalElements: orders.length,
    totalPages: 1,
    size: 12,
    number: 0,
    first: true,
    last: true,
    empty: orders.length === 0,
  };
}

describe('ReceiptCreate', () => {
  let purchaseOrderService: {
    getPurchaseOrders: ReturnType<typeof vi.fn>;
    getPurchaseOrder: ReturnType<typeof vi.fn>;
  };
  let goodsReceiptService: { createReceipt: ReturnType<typeof vi.fn> };
  let settingsService: { getSettings: ReturnType<typeof vi.fn> };
  let locationsService: { getStoreLocations: ReturnType<typeof vi.fn> };
  let notificationService: { showSuccess: ReturnType<typeof vi.fn> };
  let router: Router;
  let queryParams: { purchaseOrderId: string | null };

  beforeEach(async () => {
    queryParams = { purchaseOrderId: null };

    purchaseOrderService = {
      getPurchaseOrders: vi
        .fn()
        .mockReturnValue(of(orderPage([mockOrderAccepted, mockOrderDraft]))),
      getPurchaseOrder: vi.fn().mockReturnValue(of(mockOrderAccepted)),
    };
    goodsReceiptService = { createReceipt: vi.fn().mockReturnValue(of(mockCreatedReceipt)) };
    settingsService = { getSettings: vi.fn().mockReturnValue(of(mockSettings)) };
    locationsService = { getStoreLocations: vi.fn().mockReturnValue(of(mockLocations)) };
    notificationService = { showSuccess: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [ReceiptCreate, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: { get: vi.fn().mockReturnValue(null) },
              queryParamMap: {
                get: (key: string) => queryParams[key as 'purchaseOrderId'] ?? null,
              },
            },
          },
        },
        { provide: PurchaseOrderService, useValue: purchaseOrderService },
        { provide: GoodsReceiptService, useValue: goodsReceiptService },
        { provide: StoreInventorySettingsService, useValue: settingsService },
        { provide: StoreLocationLookupService, useValue: locationsService },
        { provide: NotificationService, useValue: notificationService },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  });

  async function createComponent(
    purchaseOrderId: string | null,
  ): Promise<ComponentFixture<ReceiptCreate>> {
    queryParams.purchaseOrderId = purchaseOrderId;
    const fixture = TestBed.createComponent(ReceiptCreate);
    fixture.detectChanges();
    // The page chains up to three resources (order → settings/locations), so it
    // needs one settle round per hop before the form is fully rendered.
    for (let round = 0; round < 3; round++) {
      await fixture.whenStable();
      fixture.detectChanges();
    }
    return fixture;
  }

  function createPicker(): Promise<ComponentFixture<ReceiptCreate>> {
    return createComponent(null);
  }

  function createWithOrder(): Promise<ComponentFixture<ReceiptCreate>> {
    return createComponent('po-1');
  }

  function textOf(fixture: ComponentFixture<ReceiptCreate>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function receiveButtons(fixture: ComponentFixture<ReceiptCreate>): HTMLButtonElement[] {
    return fixture.debugElement
      .queryAll(By.css('button.receive-button'))
      .map((button) => button.nativeElement as HTMLButtonElement);
  }

  function submitButton(fixture: ComponentFixture<ReceiptCreate>): HTMLButtonElement {
    const button = fixture.debugElement
      .queryAll(By.css('button'))
      .find((candidate) =>
        (candidate.nativeElement as HTMLElement).textContent?.includes('Registrar recepción'),
      );
    return button!.nativeElement as HTMLButtonElement;
  }

  it('should create', async () => {
    const fixture = await createPicker();
    expect(fixture.componentInstance).toBeTruthy();
  });

  // ══════════════════════════════════════════════════════════
  // State A: order picker
  // ══════════════════════════════════════════════════════════

  describe('order picker', () => {
    it('should fetch the first purchase-order page on init', async () => {
      await createPicker();
      expect(purchaseOrderService.getPurchaseOrders).toHaveBeenCalledWith(0, 12, undefined);
    });

    it('should render one row per order', async () => {
      const fixture = await createPicker();
      expect(fixture.debugElement.queryAll(By.css('tr.mat-mdc-row')).length).toBe(2);
      expect(textOf(fixture)).toContain('PO-00001');
      expect(textOf(fixture)).toContain('Beta Industries');
    });

    it('should keep the receive action enabled only for a receivable order', async () => {
      const fixture = await createPicker();
      const buttons = receiveButtons(fixture);

      expect(buttons.length).toBe(2);
      expect(buttons[0].disabled).toBe(false);
      expect(buttons[1].disabled).toBe(true);
    });

    it('should show the empty copy when there is no order', async () => {
      purchaseOrderService.getPurchaseOrders.mockReturnValue(of(orderPage([])));
      const fixture = await createPicker();

      expect(textOf(fixture)).toContain('No hay órdenes de compra para mostrar');
    });

    it('should show the error state and retry the read', async () => {
      purchaseOrderService.getPurchaseOrders.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );
      const fixture = await createPicker();

      expect(textOf(fixture)).toContain('Ocurrió un error en el servidor');

      purchaseOrderService.getPurchaseOrders.mockClear();
      fixture.componentInstance.onRetryOrders();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(purchaseOrderService.getPurchaseOrders).toHaveBeenCalled();
    });

    it('should switch to the form and load the order when Recibir is clicked', async () => {
      const fixture = await createPicker();
      const component = fixture.componentInstance;

      receiveButtons(fixture)[0].click();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(component.isPickerState()).toBe(false);
      expect(purchaseOrderService.getPurchaseOrder).toHaveBeenCalledWith('po-1');
      expect(textOf(fixture)).toContain('Registrar recepción');
    });

    it('should ignore a click on a non-receivable order', async () => {
      const fixture = await createPicker();
      const component = fixture.componentInstance;

      receiveButtons(fixture)[1].click();
      fixture.detectChanges();

      expect(component.isPickerState()).toBe(true);
      expect(purchaseOrderService.getPurchaseOrder).not.toHaveBeenCalled();
    });

    it('should not request the order list when deep-linked into the form', async () => {
      await createWithOrder();
      expect(purchaseOrderService.getPurchaseOrders).not.toHaveBeenCalled();
    });
  });

  // ══════════════════════════════════════════════════════════
  // State B: form
  // ══════════════════════════════════════════════════════════

  describe('form', () => {
    it('should load the deep-linked order', async () => {
      await createWithOrder();
      expect(purchaseOrderService.getPurchaseOrder).toHaveBeenCalledWith('po-1');
    });

    it('should default every draft quantity to its pending amount and drop the rest', async () => {
      const fixture = await createWithOrder();
      const drafts = fixture.componentInstance.drafts();

      expect(drafts.length).toBe(1);
      expect(drafts[0].detailId).toBe('det-1');
      expect(drafts[0].quantity).toBe(6);
      expect(drafts[0].pending).toBe(6);
      expect(drafts[0].alreadyReceived).toBe(4);
      expect(drafts[0].ordered).toBe(10);
    });

    it('should render the line context columns and the variant fallback', async () => {
      const fixture = await createWithOrder();
      const text = textOf(fixture);

      expect(text).toContain('Widget A');
      expect(text).toContain('Presentación 1L');
      expect(text).toContain('Ya recibido');
      expect(text).toContain('Pendiente');
    });

    it('should load the store settings and locations of the order chain', async () => {
      await createWithOrder();

      expect(settingsService.getSettings).toHaveBeenCalledWith({
        companyId: 'company-1',
        companyCountryId: 'country-1',
        regionId: 'region-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
      });
      expect(locationsService.getStoreLocations).toHaveBeenCalledTimes(1);
    });

    it('should show the store configured location as read-only context', async () => {
      const fixture = await createWithOrder();
      expect(textOf(fixture)).toContain('Ubicación configurada: Depósito');
    });

    it('should render the location options disambiguated by zone and area', async () => {
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      component.locationOverride.set('loc-1');
      fixture.detectChanges();

      expect(component.locations().length).toBe(2);
    });
  });

  // ══════════════════════════════════════════════════════════
  // Validation and submit
  // ══════════════════════════════════════════════════════════

  describe('validation', () => {
    it('should block the submit when a quantity exceeds the pending amount', async () => {
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      component.onQuantityInput(0, '99');
      fixture.detectChanges();

      expect(component.formErrors().valid).toBe(false);
      expect(component.rowError(component.drafts()[0])).toBe('No puede superar lo pendiente (6)');
      expect(submitButton(fixture).disabled).toBe(true);

      component.onSubmit();
      expect(goodsReceiptService.createReceipt).not.toHaveBeenCalled();
    });

    it('should block the submit when every line is skipped', async () => {
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      component.onQuantityInput(0, '0');
      fixture.detectChanges();

      expect(component.formErrors().valid).toBe(false);
      expect(textOf(fixture)).toContain('Elegí al menos una línea para recibir');
      expect(submitButton(fixture).disabled).toBe(true);
    });

    it('should report a negative quantity on its row', async () => {
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      component.onQuantityInput(0, '-2');
      fixture.detectChanges();

      expect(component.rowError(component.drafts()[0])).toBe('No puede ser negativo');
    });
  });

  describe('submit', () => {
    it('should post the exact payload of the selected drafts', async () => {
      const fixture = await createWithOrder();
      fixture.componentInstance.onSubmit();

      expect(goodsReceiptService.createReceipt).toHaveBeenCalledWith({
        purchaseOrderId: 'po-1',
        receivingLocationId: null,
        comments: null,
        lines: [{ purchaseOrderDetailId: 'det-1', quantityReceived: 6 }],
      });
    });

    it('should submit the comments trimmed and omit an empty one as null', async () => {
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      component.onCommentsInput('   ');
      component.onSubmit();

      expect(goodsReceiptService.createReceipt).toHaveBeenCalledWith(
        expect.objectContaining({ comments: null }),
      );

      goodsReceiptService.createReceipt.mockClear();
      component.onCommentsInput('  con novedades  ');
      component.onSubmit();

      expect(goodsReceiptService.createReceipt).toHaveBeenCalledWith(
        expect.objectContaining({ comments: 'con novedades' }),
      );
    });

    it('should notify and navigate to the new receipt on success', async () => {
      const fixture = await createWithOrder();
      fixture.componentInstance.onSubmit();

      expect(notificationService.showSuccess).toHaveBeenCalledWith(
        'Recepción registrada: GR-00099',
      );
      expect(router.navigate).toHaveBeenCalledWith(['/purchases/receipts', 'gr-99']);
    });

    it('should require a location when the store has none configured', async () => {
      settingsService.getSettings.mockReturnValue(of(null));
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      expect(component.requiresLocation()).toBe(true);
      expect(textOf(fixture)).toContain(
        'Esta tienda no tiene una ubicación de recepción configurada. Elegí una ubicación para continuar.',
      );
      expect(textOf(fixture)).toContain('Elegí una ubicación de recepción');
      expect(submitButton(fixture).disabled).toBe(true);

      component.onSubmit();
      expect(goodsReceiptService.createReceipt).not.toHaveBeenCalled();
    });

    it('should send the picked override for a store without settings', async () => {
      settingsService.getSettings.mockReturnValue(of(null));
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      component.onLocationChange('loc-9');
      fixture.detectChanges();
      component.onSubmit();

      expect(goodsReceiptService.createReceipt).toHaveBeenCalledWith(
        expect.objectContaining({ receivingLocationId: 'loc-9' }),
      );
    });
  });

  describe('server errors', () => {
    function failWith(status: number, message?: string): void {
      goodsReceiptService.createReceipt.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status, error: message ? { message } : null })),
      );
    }

    async function submitAndReadError(
      status: number,
      message?: string,
    ): Promise<ComponentFixture<ReceiptCreate>> {
      failWith(status, message);
      const fixture = await createWithOrder();
      fixture.componentInstance.onSubmit();
      fixture.detectChanges();
      return fixture;
    }

    it('should map a 400 to the quantity copy', async () => {
      const fixture = await submitAndReadError(400);
      expect(fixture.componentInstance.generalError()).toBe(
        'Revisá las cantidades: el servidor rechazó la recepción.',
      );
    });

    it('should map a 403 to the permission copy', async () => {
      const fixture = await submitAndReadError(403, 'Access denied');
      expect(fixture.componentInstance.generalError()).toBe(
        'No tenés permisos para recibir en esta tienda.',
      );
    });

    it('should map the settings 404 to the actionable store copy and keep the raw detail', async () => {
      const fixture = await submitAndReadError(
        404,
        'Store inventory settings not found for store store-1',
      );
      const component = fixture.componentInstance;

      expect(component.generalError()).toBe(
        'Esta tienda no tiene una ubicación de recepción configurada. Configurala o elegí una ubicación.',
      );
      expect(component.serverDetail()).toBe('Store inventory settings not found for store store-1');
      expect(textOf(fixture)).toContain('Configurala o elegí una ubicación.');
    });

    it('should map any other 404 to the missing-order copy', async () => {
      const fixture = await submitAndReadError(404, 'Purchase order not found');
      expect(fixture.componentInstance.generalError()).toBe('La orden de compra ya no existe.');
    });

    it('should map a 409 to the status copy and show it through the error banner', async () => {
      const fixture = await submitAndReadError(409, 'InvalidStatusTransitionException: bad status');
      const component = fixture.componentInstance;

      expect(component.generalError()).toBe('La orden no está en un estado que permita recibir.');
      expect(component.serverDetail()).toBe('InvalidStatusTransitionException: bad status');
      expect(textOf(fixture)).toContain('La orden no está en un estado que permita recibir.');
    });

    it('should fall back to the shared http copy for any other failure', async () => {
      const fixture = await submitAndReadError(500);
      expect(fixture.componentInstance.generalError()).toBe(
        'Ocurrió un error en el servidor. Intentá de nuevo más tarde.',
      );
    });
  });

  // ══════════════════════════════════════════════════════════
  // Blocking states
  // ══════════════════════════════════════════════════════════

  describe('blocking states', () => {
    it('should block an order that is not receivable and offer another order', async () => {
      purchaseOrderService.getPurchaseOrder.mockReturnValue(of(mockOrderDraft));
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      expect(textOf(fixture)).toContain(
        'Esta orden no está en un estado que permita recibir. Solo se puede recibir una orden Aceptada o En Tránsito.',
      );
      expect(goodsReceiptService.createReceipt).not.toHaveBeenCalled();

      component.chooseAnotherOrder();
      fixture.detectChanges();

      expect(component.isPickerState()).toBe(true);
      expect(purchaseOrderService.getPurchaseOrders).toHaveBeenCalled();
    });

    it('should block an order with no pending lines', async () => {
      purchaseOrderService.getPurchaseOrder.mockReturnValue(
        of({
          ...mockOrderAccepted,
          details: mockOrderAccepted.details.map((detail) => ({
            ...detail,
            statusName: 'Received',
          })),
        }),
      );
      const fixture = await createWithOrder();

      expect(textOf(fixture)).toContain('Esta orden no tiene líneas pendientes de recepción.');
    });

    it('should fail closed when the store chain cannot be resolved', async () => {
      purchaseOrderService.getPurchaseOrder.mockReturnValue(
        of({ ...mockOrderAccepted, companyId: null }),
      );
      const fixture = await createWithOrder();

      expect(textOf(fixture)).toContain('No se pudo resolver la tienda de esta orden.');
      expect(settingsService.getSettings).not.toHaveBeenCalled();
      expect(locationsService.getStoreLocations).not.toHaveBeenCalled();
    });

    it('should block when the order cannot be loaded', async () => {
      purchaseOrderService.getPurchaseOrder.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 404 })),
      );
      const fixture = await createWithOrder();

      expect(textOf(fixture)).toContain('No se encontró el recurso solicitado.');
      expect(textOf(fixture)).toContain('Elegir otra orden');
    });
  });

  // ══════════════════════════════════════════════════════════
  // Unsaved changes
  // ══════════════════════════════════════════════════════════

  describe('unsaved changes', () => {
    it('should report no unsaved changes after the programmatic draft seed', async () => {
      const fixture = await createWithOrder();
      expect(fixture.componentInstance.hasUnsavedChanges()).toBe(false);
    });

    it('should report unsaved changes after a quantity edit', async () => {
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      component.onQuantityInput(0, '3');

      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should report unsaved changes after a location or comments edit', async () => {
      const fixture = await createWithOrder();
      const component = fixture.componentInstance;

      component.onLocationChange('loc-9');
      expect(component.hasUnsavedChanges()).toBe(true);

      component.chooseAnotherOrder();
      expect(component.hasUnsavedChanges()).toBe(false);

      component.onCommentsInput('algo');
      expect(component.hasUnsavedChanges()).toBe(true);
    });
  });

  describe('displayed columns', () => {
    it('should expose the picker columns', async () => {
      const fixture = await createPicker();
      expect(fixture.componentInstance.displayedOrderColumns).toEqual([
        'orderNumber',
        'supplierName',
        'companyStoreName',
        'createdAt',
        'status',
        'actions',
      ]);
    });

    it('should expose the editable line columns', async () => {
      const fixture = await createWithOrder();
      expect(fixture.componentInstance.displayedLineColumns).toEqual([
        'product',
        'variant',
        'ordered',
        'alreadyReceived',
        'pending',
        'quantity',
      ]);
    });
  });
});
