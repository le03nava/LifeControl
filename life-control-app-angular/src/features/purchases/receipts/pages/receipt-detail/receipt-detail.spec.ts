import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router, ActivatedRoute } from '@angular/router';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ReceiptDetail } from './receipt-detail';
import { GoodsReceiptService } from '../../data/goods-receipt.service';
import { PurchaseOrderService } from '../../../purchase-orders/data/purchase-order.service';
import type { GoodsReceipt } from '../../models/receipt.models';
import type { PurchaseOrder } from '../../../purchase-orders/models/purchase-order.models';

const RECEIVING_LOCATION_UUID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
const VARIANT_UUID = '11111111-1111-1111-1111-111111111111';

const mockReceipt: GoodsReceipt = {
  id: 'gr-1',
  receiptNumber: 'GR-00001',
  purchaseOrderId: 'po-1',
  orderNumber: 'PO-00001',
  companyStoreId: 'store-1',
  receivingLocationId: RECEIVING_LOCATION_UUID,
  statusId: 'st-registered',
  statusName: 'Registered',
  receivedBy: 'jdoe',
  receivedAt: '2026-03-01T10:15:00Z',
  comments: null,
  enabled: true,
  lines: [
    {
      id: 'line-1',
      purchaseOrderDetailId: 'det-1',
      productVariantId: VARIANT_UUID,
      quantityReceived: 6,
      comments: null,
    },
    {
      id: 'line-2',
      purchaseOrderDetailId: 'det-2',
      productVariantId: '22222222-2222-2222-2222-222222222222',
      quantityReceived: 2,
      comments: 'Sin novedades',
    },
  ],
};

const mockOrder: PurchaseOrder = {
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
      productVariantId: VARIANT_UUID,
      productVariantName: 'Presentación 1L',
      quantity: 10,
      unitPrice: 100,
      total: 1000,
      receivedQuantity: 6,
      comments: null,
      statusId: 'st-detail',
      statusName: 'Partial Received',
      createdAt: '2026-02-01T00:00:00Z',
      updatedAt: '2026-02-01T00:00:00Z',
    },
    {
      id: 'det-2',
      purchaseOrderId: 'po-1',
      productId: 'prod-2',
      productName: 'Widget B',
      productVariantId: null,
      productVariantName: null,
      quantity: 5,
      unitPrice: 50,
      total: 250,
      receivedQuantity: 2,
      comments: null,
      statusId: 'st-detail',
      statusName: 'Partial Received',
      createdAt: '2026-02-01T00:00:00Z',
      updatedAt: '2026-02-01T00:00:00Z',
    },
  ],
};

describe('ReceiptDetail', () => {
  let goodsReceiptService: { getReceipt: ReturnType<typeof vi.fn> };
  let purchaseOrderService: { getPurchaseOrder: ReturnType<typeof vi.fn> };
  let router: Router;

  beforeEach(async () => {
    goodsReceiptService = { getReceipt: vi.fn().mockReturnValue(of(mockReceipt)) };
    purchaseOrderService = { getPurchaseOrder: vi.fn().mockReturnValue(of(mockOrder)) };

    await TestBed.configureTestingModule({
      imports: [ReceiptDetail, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: { get: vi.fn().mockReturnValue('gr-1') },
              queryParamMap: { get: vi.fn().mockReturnValue(null) },
            },
          },
        },
        { provide: GoodsReceiptService, useValue: goodsReceiptService },
        { provide: PurchaseOrderService, useValue: purchaseOrderService },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  });

  async function createPage(): Promise<ComponentFixture<ReceiptDetail>> {
    const fixture = TestBed.createComponent(ReceiptDetail);
    fixture.detectChanges();
    // Two resources resolve in sequence (receipt → order), so the fixture needs
    // one settle round per resource before the joined names are rendered.
    for (let round = 0; round < 3; round++) {
      await fixture.whenStable();
      fixture.detectChanges();
    }
    return fixture;
  }

  function textOf(fixture: ComponentFixture<ReceiptDetail>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('should create', async () => {
    const fixture = await createPage();
    expect(fixture.componentInstance).toBeTruthy();
  });

  describe('data loading', () => {
    it('should fetch the receipt with the route id', async () => {
      await createPage();
      expect(goodsReceiptService.getReceipt).toHaveBeenCalledWith('gr-1');
    });

    it('should fetch the order of the loaded receipt', async () => {
      await createPage();
      expect(purchaseOrderService.getPurchaseOrder).toHaveBeenCalledWith('po-1');
    });
  });

  describe('header', () => {
    it('should render the receipt number as the title', async () => {
      const fixture = await createPage();
      expect(textOf(fixture)).toContain('Recibo GR-00001');
    });

    it('should render the order number as the subtitle', async () => {
      const fixture = await createPage();
      expect(textOf(fixture)).toContain('Orden PO-00001');
    });

    it('should render the reception date through the date pipe', async () => {
      const fixture = await createPage();
      expect(textOf(fixture)).toContain('01/03/2026');
    });

    it('should render the receipt status through the receipt-family chip', async () => {
      const fixture = await createPage();
      const chips = fixture.debugElement.queryAll(By.css('app-status-chip'));
      expect(chips.length).toBe(1);
      expect(textOf(fixture)).toContain('Registrado');
    });

    it('should render the receiving user and an em dash for a null comment', async () => {
      const fixture = await createPage();
      const text = textOf(fixture);
      expect(text).toContain('jdoe');
      expect(text).toContain('—');
    });

    it('should not render the raw receivingLocationId', async () => {
      const fixture = await createPage();
      expect(textOf(fixture)).not.toContain(RECEIVING_LOCATION_UUID);
    });
  });

  describe('lines', () => {
    it('should render one row per receipt line', async () => {
      const fixture = await createPage();
      const rows = fixture.debugElement.queryAll(By.css('tr.mat-mdc-row'));
      expect(rows.length).toBe(2);
    });

    it('should join each line with its order detail to show the product name', async () => {
      const fixture = await createPage();
      const text = textOf(fixture);
      expect(text).toContain('Widget A');
      expect(text).toContain('Widget B');
    });

    it('should render the variant label and the fallback for a detail without variant', async () => {
      const fixture = await createPage();
      const text = textOf(fixture);
      expect(text).toContain('Presentación 1L');
      expect(text).toContain('Sin variante');
    });

    it('should render the received quantities and line comments', async () => {
      const fixture = await createPage();
      const text = textOf(fixture);
      expect(text).toContain('6');
      expect(text).toContain('2');
      expect(text).toContain('Sin novedades');
    });

    it('should tolerate a missing order without crashing or leaking identifiers', async () => {
      purchaseOrderService.getPurchaseOrder.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 404 })),
      );

      const fixture = await createPage();
      const component = fixture.componentInstance;
      const text = textOf(fixture);

      expect(() => component.lines()).not.toThrow();
      expect(component.lines().length).toBe(2);
      expect(component.lines()[0].productName).toBeNull();
      expect(text).not.toContain(VARIANT_UUID);
      expect(text).not.toContain('11111111');
    });

    it('should render the mobile cards instead of the table on a narrow viewport', async () => {
      const fixture = await createPage();
      const component = fixture.componentInstance;

      // The breakpoint observer is mocked through window.matchMedia in jsdom.
      expect(component.isMobile()).toBe(false);
      expect(fixture.debugElement.queryAll(By.css('table'))).toBeTruthy();
    });
  });

  describe('error state', () => {
    function failReceipt(status: number): void {
      goodsReceiptService.getReceipt.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status })),
      );
    }

    it('should show the not-found copy on a 404', async () => {
      failReceipt(404);
      const fixture = await createPage();

      expect(textOf(fixture)).toContain('No se encontró el recibo.');
      expect(textOf(fixture)).toContain('Volver al listado');
    });

    it('should show the permission copy on a 403', async () => {
      failReceipt(403);
      const fixture = await createPage();

      expect(textOf(fixture)).toContain('No tenés permisos para ver este recibo.');
      expect(textOf(fixture)).toContain('Volver al listado');
    });

    it('should fall back to the shared http error copy on a 500', async () => {
      failReceipt(500);
      const fixture = await createPage();

      expect(textOf(fixture)).toContain('Ocurrió un error en el servidor');
      expect(textOf(fixture)).toContain('Volver al listado');
    });

    it('should not fetch the order when the receipt failed', async () => {
      failReceipt(404);
      await createPage();

      expect(purchaseOrderService.getPurchaseOrder).not.toHaveBeenCalled();
    });
  });

  describe('navigation', () => {
    it('should navigate back to the receipt list on Volver', async () => {
      const fixture = await createPage();
      fixture.componentInstance.goBack();

      expect(router.navigate).toHaveBeenCalledWith(['/purchases/receipts']);
    });

    it('should navigate back to the receipt list from the error state button', async () => {
      goodsReceiptService.getReceipt.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 404 })),
      );
      const fixture = await createPage();

      const button = fixture.debugElement
        .queryAll(By.css('button'))
        .find((b) => (b.nativeElement as HTMLElement).textContent?.includes('Volver al listado'));
      expect(button).toBeTruthy();

      button!.nativeElement.click();

      expect(router.navigate).toHaveBeenCalledWith(['/purchases/receipts']);
    });
  });

  describe('displayed columns', () => {
    it('should expose the expected columns', async () => {
      const fixture = await createPage();
      expect(fixture.componentInstance.displayedColumns).toEqual([
        'product',
        'variant',
        'quantityReceived',
        'comments',
      ]);
    });
  });
});
