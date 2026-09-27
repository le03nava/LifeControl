import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { SalesOrderItemTable, type ItemTableRow } from './sales-order-item-table';

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

describe('SalesOrderItemTable', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SalesOrderItemTable, NoopAnimationsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA],
    }).compileComponents();
  });

  function createFixture(overrides?: {
    items?: ItemTableRow[];
    isDraft?: boolean;
    savingRowIds?: ReadonlySet<string>;
  }): { fixture: ComponentFixture<SalesOrderItemTable>; comp: SalesOrderItemTable } {
    const fixture = TestBed.createComponent(SalesOrderItemTable);
    const comp = fixture.componentInstance;

    fixture.componentRef.setInput('items', overrides?.items ?? []);
    fixture.componentRef.setInput('isDraft', overrides?.isDraft ?? true);
    fixture.componentRef.setInput('savingRowIds', overrides?.savingRowIds ?? new Set<string>());
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
        items: [
          createItem({ productVariantName: 'Laptop Pro 16GB', quantity: 2, listPrice: 1200 }),
        ],
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
          createItem({
            productVariantId: 'v2',
            productVariantName: 'Mouse',
            quantity: 1,
            listPrice: 50,
            discountApplied: 5,
          }),
        ],
      });

      // (2×100-0) + (1×50-5) = 200 + 45 = 245
      expect(comp.lineItemsTotal()).toBe(245);
    });
  });

  describe('itemRemoved output', () => {
    it('should emit itemRemoved with index when item removed (isDraft)', () => {
      const { fixture, comp } = createFixture({
        items: [createItem(), createItem({ productVariantId: 'v2' })],
        isDraft: true,
      });

      let emittedIndex: number | undefined;
      comp.itemRemoved.subscribe((index: number) => {
        emittedIndex = index;
      });

      comp.removeItem(0);
      fixture.detectChanges();

      expect(emittedIndex).toBe(0);
    });

    it('should NOT emit itemRemoved when NOT in Draft', () => {
      const { comp } = createFixture({
        items: [createItem(), createItem({ productVariantId: 'v2' })],
        isDraft: false,
      });

      let emittedIndex: number | undefined;
      comp.itemRemoved.subscribe((index: number) => {
        emittedIndex = index;
      });

      comp.removeItem(0);
      expect(emittedIndex).toBeUndefined();
    });

    it('should allow removing last item in Draft', () => {
      const { comp } = createFixture({
        items: [createItem()],
        isDraft: true,
      });

      let emittedIndex: number | undefined;
      comp.itemRemoved.subscribe((index: number) => {
        emittedIndex = index;
      });

      comp.removeItem(0);
      expect(emittedIndex).toBe(0);
    });
  });

  describe('quantityChanged output', () => {
    it('should emit quantityChanged with index and value when Draft', () => {
      const { fixture, comp } = createFixture({
        items: [createItem({ quantity: 2 })],
        isDraft: true,
      });

      let emitted: { index: number; value: number } | undefined;
      comp.quantityChanged.subscribe((data) => {
        emitted = data;
      });

      comp.onQuantityChange(0, 5);
      fixture.detectChanges();

      expect(emitted).toEqual({ index: 0, value: 5 });
    });

    it('should default to 1 when value is falsy', () => {
      const { fixture, comp } = createFixture({
        items: [createItem({ quantity: 2 })],
        isDraft: true,
      });

      let emitted: { index: number; value: number } | undefined;
      comp.quantityChanged.subscribe((data) => {
        emitted = data;
      });

      comp.onQuantityChange(0, 0);
      fixture.detectChanges();

      expect(emitted).toEqual({ index: 0, value: 1 });
    });

    it('should NOT emit quantityChanged when NOT Draft', () => {
      const { comp } = createFixture({
        items: [createItem({ quantity: 2 })],
        isDraft: false,
      });

      let emitted: { index: number; value: number } | undefined;
      comp.quantityChanged.subscribe((data) => {
        emitted = data;
      });

      comp.onQuantityChange(0, 5);
      expect(emitted).toBeUndefined();
    });
  });

  describe('listPriceChanged output', () => {
    it('should emit listPriceChanged with index and value', () => {
      const { fixture, comp } = createFixture({
        items: [createItem({ listPrice: 100 })],
        isDraft: true,
      });

      let emitted: { index: number; value: number } | undefined;
      comp.listPriceChanged.subscribe((data) => {
        emitted = data;
      });

      comp.onListPriceChange(0, 150);
      fixture.detectChanges();

      expect(emitted).toEqual({ index: 0, value: 150 });
    });

    it('should default to 0 when value is falsy', () => {
      const { fixture, comp } = createFixture({
        items: [createItem({ listPrice: 100 })],
        isDraft: true,
      });

      let emitted: { index: number; value: number } | undefined;
      comp.listPriceChanged.subscribe((data) => {
        emitted = data;
      });

      // Simulate NaN from invalid input (would become 0)
      comp.onListPriceChange(1, Number.NaN);
      fixture.detectChanges();

      // NaN || 0 = 0
      expect(emitted).toEqual({ index: 1, value: 0 });
    });
  });

  describe('discountChanged output', () => {
    it('should emit discountChanged with index and value', () => {
      const { fixture, comp } = createFixture({
        items: [createItem({ discountApplied: 0 })],
        isDraft: true,
      });

      let emitted: { index: number; value: number } | undefined;
      comp.discountChanged.subscribe((data) => {
        emitted = data;
      });

      comp.onDiscountChange(0, 10);
      fixture.detectChanges();

      expect(emitted).toEqual({ index: 0, value: 10 });
    });
  });

  describe('savingRowIds input', () => {
    const threeRows: ItemTableRow[] = [
      createItem({ id: 'a' }),
      createItem({ id: 'b', productVariantId: 'v2' }),
      createItem({ id: 'c', productVariantId: 'v3' }),
    ];

    /** The three number inputs of the row at `rowIndex`, in column order. */
    function inputsOfRow(
      fixture: ComponentFixture<SalesOrderItemTable>,
      rowIndex: number,
    ): HTMLInputElement[] {
      const all = Array.from<HTMLInputElement>(
        fixture.nativeElement.querySelectorAll('input[type="number"]'),
      );
      return all.slice(rowIndex * 3, rowIndex * 3 + 3);
    }

    function removeButtons(fixture: ComponentFixture<SalesOrderItemTable>): HTMLButtonElement[] {
      return Array.from<HTMLButtonElement>(
        fixture.nativeElement.querySelectorAll('button[color="warn"]'),
      );
    }

    it('should disable only the rows with a write in flight', () => {
      const { fixture } = createFixture({
        items: threeRows,
        isDraft: true,
        savingRowIds: new Set(['a', 'c']),
      });

      expect(inputsOfRow(fixture, 0)).toHaveLength(3);
      expect(inputsOfRow(fixture, 0).every((input) => input.disabled)).toBe(true);
      expect(inputsOfRow(fixture, 1).some((input) => input.disabled)).toBe(false);
      expect(inputsOfRow(fixture, 2).every((input) => input.disabled)).toBe(true);
    });

    it('should disable the remove button only on rows with a write in flight', () => {
      const { fixture } = createFixture({
        items: threeRows,
        isDraft: true,
        savingRowIds: new Set(['b']),
      });

      const [first, second, third] = removeButtons(fixture);
      expect(first.disabled).toBe(false);
      expect(second.disabled).toBe(true);
      expect(third.disabled).toBe(false);
    });

    it('should leave every row enabled when nothing is in flight', () => {
      const { fixture } = createFixture({ items: threeRows, isDraft: true });

      expect(
        Array.from<HTMLInputElement>(
          fixture.nativeElement.querySelectorAll('input[type="number"]'),
        ).some((input) => input.disabled),
      ).toBe(false);
    });

    it('should not disable a row that has no server id', () => {
      const { fixture } = createFixture({
        items: [createItem()],
        isDraft: true,
        savingRowIds: new Set(['a']),
      });

      expect(inputsOfRow(fixture, 0).some((input) => input.disabled)).toBe(false);
    });

    it('should enable remove button when nothing is in flight and isDraft is true', () => {
      const { fixture } = createFixture({
        items: [createItem({ id: 'a' })],
        isDraft: true,
      });

      const removeButton: HTMLButtonElement | null =
        fixture.nativeElement.querySelector('button[color="warn"]');
      expect(removeButton).toBeTruthy();
      expect(removeButton!.disabled).toBe(false);
    });
  });

  describe('row identity', () => {
    it('should key a row by its server id', () => {
      const { comp } = createFixture();
      expect(comp.trackRow(0, createItem({ id: 'item-9' }))).toBe('item-9');
    });

    it('should fall back to the index for a row without a server id', () => {
      const { comp } = createFixture();
      expect(comp.trackRow(4, createItem())).toBe(4);
    });

    it('should keep existing row DOM when a row is added', () => {
      const { fixture, comp } = createFixture({
        items: [createItem({ id: 'a' }), createItem({ id: 'b', productVariantId: 'v2' })],
      });

      const before = Array.from<Element>(fixture.nativeElement.querySelectorAll('tr'));
      expect(before).toHaveLength(3); // header + 2 data rows

      // Same ids, fresh object identity: exactly what a reconciliation produces.
      const items = comp.items();
      fixture.componentRef.setInput('items', [
        { ...items[0] },
        { ...items[1] },
        createItem({ id: 'c', productVariantId: 'v3', productVariantName: 'Keyboard' }),
      ]);
      fixture.detectChanges();

      const after = Array.from<Element>(fixture.nativeElement.querySelectorAll('tr'));
      expect(after).toHaveLength(4);
      // Reusing the existing row elements is what makes the arrival highlight
      // land on the new row only, and what stops the whole table repainting.
      for (const row of before) {
        expect(after).toContain(row);
      }
    });
  });
});
