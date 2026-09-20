import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { BreakpointObserver } from '@angular/cdk/layout';
import { of } from 'rxjs';
import { DetailTable, type DetailTableRow } from './detail-table';
import { ProductVariantPicker } from '../product-variant-picker/product-variant-picker';
import { ProductService } from '@features/products/data/product.service';
import type { Page } from '@features/products/models/product.models';
import type { ProductVariant } from '@features/products/models/product-variant.models';

const PRODUCTS = [
  { id: 'prod-1', name: 'Widget A', sku: 'SKU-1' },
  { id: 'prod-2', name: 'Widget B', sku: 'SKU-2' },
];

const VARIANT_A: ProductVariant = {
  id: 'var-1',
  productId: 'prod-1',
  companyStoreId: 'store-1',
  barCode: 'BAR-1',
  sku: 'SKU-1-A',
  variantName: 'Presentación 1L',
  listPrice: 120,
  costPrice: 80,
  stock: 5,
  enabled: true,
};

const VARIANT_B: ProductVariant = {
  id: 'var-2',
  productId: 'prod-1',
  companyStoreId: 'store-1',
  barCode: 'BAR-2',
  sku: 'SKU-1-B',
  variantName: 'Presentación 2L',
  listPrice: 200,
  costPrice: 140,
  stock: 3,
  enabled: true,
};

/** Viewport width below which the table switches to one card per line item. */
const MOBILE_QUERY = '(max-width: 575px)';

const VARIANT_FROM_OTHER_STORE: ProductVariant = {
  ...VARIANT_A,
  id: 'var-other-store',
  companyStoreId: 'store-2',
};

function variantPage(content: ProductVariant[]): Page<ProductVariant> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 50,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
  };
}

const SAVED_ROW: DetailTableRow = {
  id: 'det-1',
  productId: 'prod-1',
  productName: 'Widget A',
  productVariantId: 'var-1',
  productVariantName: 'Presentación 1L',
  quantity: 4,
  unitPrice: 80,
};

describe('DetailTable', () => {
  let productService: { getProductVariants: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    productService = {
      getProductVariants: vi.fn().mockReturnValue(of(variantPage([VARIANT_A, VARIANT_B]))),
    };

    await TestBed.configureTestingModule({
      imports: [DetailTable, NoopAnimationsModule],
      providers: [{ provide: ProductService, useValue: productService }],
    }).compileComponents();
  });

  function createTable(
    items: DetailTableRow[],
    isDraft = true,
    showReceiptProgress = false,
  ): ComponentFixture<DetailTable> {
    const f = TestBed.createComponent(DetailTable);
    f.componentRef.setInput('items', items);
    f.componentRef.setInput('isDraft', isDraft);
    f.componentRef.setInput('availableProducts', PRODUCTS);
    f.componentRef.setInput('storeId', 'store-1');
    f.componentRef.setInput('showReceiptProgress', showReceiptProgress);
    f.detectChanges();
    return f;
  }

  function cellText(f: ComponentFixture<DetailTable>, column: string): string | undefined {
    const cell = (f.nativeElement as HTMLElement).querySelector(`td.mat-column-${column}`);
    return cell?.textContent?.trim();
  }

  function pickerOf(f: ComponentFixture<DetailTable>): ProductVariantPicker {
    return f.debugElement.query(By.directive(ProductVariantPicker)).componentInstance;
  }

  describe('existing rows', () => {
    it('should render the variant name of every saved line', () => {
      const f = createTable([SAVED_ROW]);

      expect((f.nativeElement as HTMLElement).textContent).toContain('Presentación 1L');
    });

    it('should flag legacy rows that have no variant', () => {
      const legacy: DetailTableRow = {
        ...SAVED_ROW,
        productVariantId: null,
        productVariantName: null,
      };
      const f = createTable([legacy]);

      const text = (f.nativeElement as HTMLElement).textContent ?? '';
      expect(f.componentInstance.hasMissingVariants()).toBe(true);
      expect(text).toContain('Hay líneas sin variante');
      expect(text).toContain('Sin variante');
    });

    it('should not ask for an impossible repair when the order is not a Draft', () => {
      const legacy: DetailTableRow = {
        ...SAVED_ROW,
        productVariantId: null,
        productVariantName: null,
      };
      const f = createTable([legacy], false);

      const text = (f.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Hay líneas sin variante');
      expect(text).toContain('No se pueden reparar en el estado actual');
      expect(text).not.toContain('Eliminá la línea');
    });

    it('should not flag rows that carry a variant', () => {
      const f = createTable([SAVED_ROW]);
      expect(f.componentInstance.hasMissingVariants()).toBe(false);
    });

    it('should emit the remaining rows when one is removed', () => {
      const f = createTable([SAVED_ROW]);
      let emitted: DetailTableRow[] | null = null;
      f.componentInstance.itemsChanged.subscribe((rows) => (emitted = rows));

      f.componentInstance.removeItem(0);

      expect(emitted).not.toBeNull();
      expect(emitted!.length).toBe(0);
    });

    it('should not remove rows when the order is no longer a draft', () => {
      const f = createTable([SAVED_ROW], false);
      let emitted = false;
      f.componentInstance.itemsChanged.subscribe(() => (emitted = true));

      f.componentInstance.removeItem(0);

      expect(emitted).toBe(false);
    });
  });

  describe('receipt progress', () => {
    const RECEIVED_ROW: DetailTableRow = {
      ...SAVED_ROW,
      receivedQuantity: 3,
      statusName: 'Partial Received',
    };

    it('should keep the column set and the rendering when the progress view is off (default)', () => {
      const f = createTable([SAVED_ROW]);

      expect(f.componentInstance.showReceiptProgress()).toBe(false);
      expect(f.componentInstance.displayedColumns()).toEqual([
        'productName',
        'variantName',
        'quantity',
        'unitPrice',
        'subtotal',
        'actions',
      ]);

      const text = (f.nativeElement as HTMLElement).textContent ?? '';
      expect(text).not.toContain('Recibido');
      expect(text).not.toContain('Estado');
      expect(cellText(f, 'received')).toBeUndefined();
      expect(cellText(f, 'lineStatus')).toBeUndefined();
    });

    it('should insert the two progress columns between quantity and unit price when on', () => {
      const f = createTable([RECEIVED_ROW], true, true);

      expect(f.componentInstance.displayedColumns()).toEqual([
        'productName',
        'variantName',
        'quantity',
        'received',
        'lineStatus',
        'unitPrice',
        'subtotal',
        'actions',
      ]);
    });

    it('should render the received quantity and the Spanish detail status', () => {
      const f = createTable([RECEIVED_ROW], true, true);
      const text = (f.nativeElement as HTMLElement).textContent ?? '';

      expect(text).toContain('Recibido');
      expect(text).toContain('Parcialmente Recibida');
      expect(cellText(f, 'received')).toBe('3');
    });

    it('should render an em dash when the line carries no status', () => {
      const f = createTable([{ ...RECEIVED_ROW, statusName: null }], true, true);

      expect(cellText(f, 'lineStatus')).toBe('—');
    });

    it('should render an em dash when the row carries no receivedQuantity', () => {
      const f = createTable([SAVED_ROW], true, true);

      expect(cellText(f, 'received')).toBe('—');
      expect(cellText(f, 'lineStatus')).toBe('—');
    });
  });

  describe('add-line form', () => {
    it('should not allow adding before a variant is picked', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      component.onNewProductChange('prod-1');
      component.onNewUnitPriceChange(10);
      f.detectChanges();

      expect(component.newVariantId()).toBeNull();
      expect(component.canAddItem()).toBe(false);
    });

    it('should pre-fill the unit price with the variant cost on selection', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      component.onNewProductChange('prod-1');
      f.detectChanges();
      expect(productService.getProductVariants).toHaveBeenCalledWith('prod-1', 'store-1', 0, 50);

      pickerOf(f).onSelectionChange('var-2');
      f.detectChanges();

      expect(component.newVariantId()).toBe('var-2');
      expect(component.newUnitPrice()).toBe(140);
      expect(component.canAddItem()).toBe(true);
    });

    it('should keep a price the user typed instead of overwriting it', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      component.onNewProductChange('prod-1');
      f.detectChanges();

      component.onNewUnitPriceChange(99);
      pickerOf(f).onSelectionChange('var-1');
      f.detectChanges();

      expect(component.newUnitPrice()).toBe(99);
    });

    it('should pre-fill again after the variant changes with no typed price', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      component.onNewProductChange('prod-1');
      f.detectChanges();

      pickerOf(f).onSelectionChange('var-1');
      f.detectChanges();
      expect(component.newUnitPrice()).toBe(80);

      pickerOf(f).onSelectionChange('var-2');
      f.detectChanges();
      expect(component.newUnitPrice()).toBe(140);
    });

    it('should keep a typed price across successive variant changes', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      component.onNewProductChange('prod-1');
      f.detectChanges();

      component.onNewUnitPriceChange(99);
      pickerOf(f).onSelectionChange('var-1');
      f.detectChanges();
      pickerOf(f).onSelectionChange('var-2');
      f.detectChanges();

      expect(component.newUnitPrice()).toBe(99);
    });

    it('should clear the variant and the price when the product changes', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      component.onNewProductChange('prod-1');
      f.detectChanges();
      pickerOf(f).onSelectionChange('var-1');
      f.detectChanges();
      expect(component.newUnitPrice()).toBe(80);

      component.onNewProductChange('prod-2');
      f.detectChanges();

      expect(component.newVariantId()).toBeNull();
      expect(component.selectedVariant()).toBeNull();
      expect(component.newUnitPrice()).toBe(0);
    });

    it('should emit a row carrying both variant fields and reset the form', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      let emitted: DetailTableRow[] | null = null;
      component.itemsChanged.subscribe((rows) => (emitted = rows));

      component.onNewProductChange('prod-1');
      f.detectChanges();
      pickerOf(f).onSelectionChange('var-1');
      component.onNewQuantityChange(3);
      f.detectChanges();

      component.addItem();

      expect(emitted).not.toBeNull();
      expect(emitted!.length).toBe(1);
      expect(emitted![0].productVariantId).toBe('var-1');
      expect(emitted![0].productVariantName).toBe('Presentación 1L');
      expect(emitted![0].productId).toBe('prod-1');
      expect(emitted![0].quantity).toBe(3);
      expect(emitted![0].unitPrice).toBe(80);

      // The form is reset so the next line starts clean.
      expect(component.newProductId()).toBe('');
      expect(component.newVariantId()).toBeNull();
      expect(component.selectedVariant()).toBeNull();
      expect(component.newUnitPrice()).toBe(0);
      expect(component.newQuantity()).toBe(1);
    });

    it('should not add a line while the order is not a draft', () => {
      const f = createTable([], false);
      const component = f.componentInstance;

      let emitted = false;
      component.itemsChanged.subscribe(() => (emitted = true));

      component.onNewProductChange('prod-1');
      f.detectChanges();
      pickerOf(f).onSelectionChange('var-1');
      f.detectChanges();

      component.addItem();

      expect(emitted).toBe(false);
    });

    it('should reset the add-row variant selection when the store changes', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      component.onNewProductChange('prod-1');
      f.detectChanges();
      pickerOf(f).onSelectionChange('var-1');
      f.detectChanges();
      expect(component.newVariantId()).toBe('var-1');

      f.componentRef.setInput('storeId', 'store-2');
      f.detectChanges();

      expect(component.newVariantId()).toBeNull();
      expect(component.selectedVariant()).toBeNull();
      expect(component.canAddItem()).toBe(false);
    });

    it('should not add a row whose selected variant belongs to another store', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      let emitted: DetailTableRow[] | null = null;
      component.itemsChanged.subscribe((rows) => (emitted = rows));

      component.onNewProductChange('prod-1');
      // The picker has not caught up with the scope change yet: the signal still
      // holds a variant that belongs to another store.
      component.newVariantId.set('var-other-store');
      component.selectedVariant.set(VARIANT_FROM_OTHER_STORE);
      component.onNewUnitPriceChange(50);

      expect(component.canAddItem()).toBe(false);

      component.addItem();

      expect(emitted).toBeNull();
    });
  });
});

describe('DetailTable (mobile)', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DetailTable, NoopAnimationsModule],
      providers: [
        {
          provide: ProductService,
          useValue: { getProductVariants: vi.fn().mockReturnValue(of(variantPage([VARIANT_A]))) },
        },
        {
          provide: BreakpointObserver,
          useValue: {
            observe: () => of({ matches: true, breakpoints: { [MOBILE_QUERY]: true } }),
          },
        },
      ],
    }).compileComponents();
  });

  function createMobileTable(
    items: DetailTableRow[],
    showReceiptProgress: boolean,
  ): ComponentFixture<DetailTable> {
    const f = TestBed.createComponent(DetailTable);
    f.componentRef.setInput('items', items);
    f.componentRef.setInput('isDraft', true);
    f.componentRef.setInput('availableProducts', PRODUCTS);
    f.componentRef.setInput('storeId', 'store-1');
    f.componentRef.setInput('showReceiptProgress', showReceiptProgress);
    f.detectChanges();
    return f;
  }

  it('should render the receipt progress facts in the per-line card', () => {
    const f = createMobileTable(
      [{ ...SAVED_ROW, receivedQuantity: 3, statusName: 'Partial Received' }],
      true,
    );

    const cards = (f.nativeElement as HTMLElement).querySelectorAll('.line-item-card');
    expect(cards.length).toBe(1);

    const text = (f.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Recibido');
    expect(text).toContain('Parcialmente Recibida');
  });

  it('should keep the per-line card unchanged when the progress view is off', () => {
    const f = createMobileTable([SAVED_ROW], false);

    const text = (f.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Recibido');
  });
});
