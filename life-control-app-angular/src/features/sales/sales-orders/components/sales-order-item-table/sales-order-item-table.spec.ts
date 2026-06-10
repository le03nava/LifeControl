import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { SalesOrderItemTable, type ItemTableRow } from './sales-order-item-table';
import type { ProductVariantOption } from '../../models/sales-order.models';

function createItem(overrides: Partial<ItemTableRow> = {}): ItemTableRow {
  return {
    productVariantId: 'v1',
    productVariantName: 'Laptop Pro 16GB',
    quantity: 2,
    listPrice: 1200.0,
    discountApplied: 0,
    ...overrides,
  };
}

const mockVariant: ProductVariantOption = {
  id: 'v2',
  productId: 'p1',
  variantName: 'Mouse Wireless Black',
  listPrice: 50.0,
  stock: 20,
  enabled: true,
};

describe('SalesOrderItemTable', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SalesOrderItemTable, NoopAnimationsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA],
    }).compileComponents();
  });

  function createFixture(
    overrides?: {
      items?: ItemTableRow[];
      isDraft?: boolean;
    },
  ): { fixture: ComponentFixture<SalesOrderItemTable>; comp: SalesOrderItemTable } {
    const fixture = TestBed.createComponent(SalesOrderItemTable);
    const comp = fixture.componentInstance;

    fixture.componentRef.setInput('items', overrides?.items ?? []);
    fixture.componentRef.setInput('isDraft', overrides?.isDraft ?? true);
    fixture.detectChanges();

    return { fixture, comp };
  }

  describe('initial state', () => {
    it('should create the component', () => {
      const { comp } = createFixture();
      expect(comp).toBeTruthy();
    });

    it('should display empty state when no items', () => {
      const { fixture } = createFixture({ items: [] });
      expect(fixture.nativeElement.textContent).toContain('No line items');
    });

    it('should render table with items', () => {
      const { fixture } = createFixture({
        items: [createItem({ productVariantName: 'Laptop Pro 16GB', quantity: 2, listPrice: 1200 })],
      });

      const el: HTMLElement = fixture.nativeElement;
      expect(el.textContent).toContain('Laptop Pro 16GB');
      expect(el.textContent).toContain('2');
    });
  });

  describe('items computation', () => {
    it('should compute subtotal per row', () => {
      const { comp } = createFixture({
        items: [createItem({ quantity: 3, listPrice: 100, discountApplied: 0 })],
      });

      // 3 × 100 - 0 = 300
      const subtotal = comp.rowSubtotal(comp.items()[0]);
      expect(subtotal).toBe(300);
    });

    it('should compute subtotal with discount', () => {
      const { comp } = createFixture({
        items: [createItem({ quantity: 2, listPrice: 100, discountApplied: 10 })],
      });

      // 2 × 100 - 10 = 190
      const subtotal = comp.rowSubtotal(comp.items()[0]);
      expect(subtotal).toBe(190);
    });

    it('should compute line items total', () => {
      const { comp } = createFixture({
        items: [
          createItem({ quantity: 2, listPrice: 100, discountApplied: 0 }),
          createItem({ productVariantId: 'v2', productVariantName: 'Mouse', quantity: 1, listPrice: 50, discountApplied: 5 }),
        ],
      });

      // (2×100-0) + (1×50-5) = 200 + 45 = 245
      expect(comp.lineItemsTotal()).toBe(245);
    });
  });

  describe('item removal', () => {
    it('should emit itemsChanged when item removed (isDraft)', () => {
      const { fixture, comp } = createFixture({
        items: [createItem(), createItem({ productVariantId: 'v2' })],
        isDraft: true,
      });

      let emitted: ItemTableRow[] = [];
      comp.itemsChanged.subscribe((items: ItemTableRow[]) => {
        emitted = items;
      });

      comp.removeItem(0);
      fixture.detectChanges();

      expect(emitted.length).toBe(1);
      expect(emitted[0].productVariantId).toBe('v2');
    });

    it('should NOT remove item when NOT in Draft', () => {
      const { comp } = createFixture({
        items: [createItem(), createItem({ productVariantId: 'v2' })],
        isDraft: false,
      });

      let emitted: ItemTableRow[] | null = null;
      comp.itemsChanged.subscribe((items: ItemTableRow[]) => {
        emitted = items;
      });

      comp.removeItem(0);
      expect(emitted).toBeNull();
    });

    it('should allow removing last item in Draft', () => {
      const { comp } = createFixture({
        items: [createItem()],
        isDraft: true,
      });

      let emitted: ItemTableRow[] = [];
      comp.itemsChanged.subscribe((items: ItemTableRow[]) => {
        emitted = items;
      });

      comp.removeItem(0);
      expect(emitted.length).toBe(0);
    });
  });

  describe('item addition', () => {
    it('should add item from variant and emit', () => {
      const { fixture, comp } = createFixture({
        items: [createItem()],
        isDraft: true,
      });

      let emitted: ItemTableRow[] = [];
      comp.itemsChanged.subscribe((items: ItemTableRow[]) => {
        emitted = items;
      });

      comp.onVariantSelected(mockVariant);
      fixture.detectChanges();

      expect(emitted.length).toBe(2);
      expect(emitted[1].productVariantId).toBe('v2');
      expect(emitted[1].productVariantName).toBe('Mouse Wireless Black');
      expect(emitted[1].listPrice).toBe(50.0);
      expect(emitted[1].quantity).toBe(1);
      expect(emitted[1].discountApplied).toBe(0);
    });

    it('should NOT add item when NOT in Draft', () => {
      const { fixture, comp } = createFixture({
        items: [createItem()],
        isDraft: false,
      });

      let emitted: ItemTableRow[] | null = null;
      comp.itemsChanged.subscribe((items: ItemTableRow[]) => {
        emitted = items;
      });

      comp.onVariantSelected(mockVariant);
      fixture.detectChanges();

      expect(emitted).toBeNull();
    });
  });

  describe('quantity updates', () => {
    it('should update quantity and emit', () => {
      const { fixture, comp } = createFixture({
        items: [createItem({ quantity: 2 })],
        isDraft: true,
      });

      let emitted: ItemTableRow[] = [];
      comp.itemsChanged.subscribe((items: ItemTableRow[]) => {
        emitted = items;
      });

      comp.onQuantityChange(0, 5);
      fixture.detectChanges();

      expect(emitted[0].quantity).toBe(5);
    });

    it('should NOT update quantity when NOT Draft', () => {
      const { comp } = createFixture({
        items: [createItem({ quantity: 2 })],
        isDraft: false,
      });

      let emitted: ItemTableRow[] | null = null;
      comp.itemsChanged.subscribe((items: ItemTableRow[]) => {
        emitted = items;
      });

      comp.onQuantityChange(0, 5);
      expect(emitted).toBeNull();
    });
  });

  describe('price and discount updates', () => {
    it('should update list price and emit', () => {
      const { fixture, comp } = createFixture({
        items: [createItem({ listPrice: 100 })],
        isDraft: true,
      });

      let emitted: ItemTableRow[] = [];
      comp.itemsChanged.subscribe((items: ItemTableRow[]) => {
        emitted = items;
      });

      comp.onListPriceChange(0, 150);
      fixture.detectChanges();

      expect(emitted[0].listPrice).toBe(150);
    });

    it('should update discount and emit', () => {
      const { fixture, comp } = createFixture({
        items: [createItem({ discountApplied: 0 })],
        isDraft: true,
      });

      let emitted: ItemTableRow[] = [];
      comp.itemsChanged.subscribe((items: ItemTableRow[]) => {
        emitted = items;
      });

      comp.onDiscountChange(0, 10);
      fixture.detectChanges();

      expect(emitted[0].discountApplied).toBe(10);
    });
  });
});
