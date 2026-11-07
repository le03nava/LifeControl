import { ComponentFixture, TestBed } from '@angular/core/testing';
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
      imports: [SalesOrderItemTable],
      schemas: [CUSTOM_ELEMENTS_SCHEMA],
    }).compileComponents();
  });

  function createFixture(overrides?: {
    items?: ItemTableRow[];
    isModifiable?: boolean;
    savingRowIds?: ReadonlySet<string>;
    currency?: string;
    documentTotal?: number | null;
  }): { fixture: ComponentFixture<SalesOrderItemTable>; comp: SalesOrderItemTable } {
    const fixture = TestBed.createComponent(SalesOrderItemTable);
    const comp = fixture.componentInstance;

    fixture.componentRef.setInput('items', overrides?.items ?? []);
    fixture.componentRef.setInput('isModifiable', overrides?.isModifiable ?? true);
    fixture.componentRef.setInput('savingRowIds', overrides?.savingRowIds ?? new Set<string>());
    fixture.componentRef.setInput('currency', overrides?.currency ?? 'USD');
    fixture.componentRef.setInput('documentTotal', overrides?.documentTotal ?? null);
    fixture.detectChanges();

    return { fixture, comp };
  }

  /** A row the server answered for: it carries its own parts. */
  function createServerItem(overrides: Partial<ItemTableRow> = {}): ItemTableRow {
    return createItem({
      id: 'item-1',
      subtotal: 300,
      taxAmount: 48,
      finalPrice: 348,
      ...overrides,
    });
  }

  /** Text of the cell in `column` of the first data row. */
  function cellText(fixture: ComponentFixture<SalesOrderItemTable>, column: string): string {
    const headers = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('th')).map(
      (th) => th.textContent?.trim(),
    );
    const index = headers.indexOf(column);
    expect(index, `column ${column} not rendered`).toBeGreaterThanOrEqual(0);
    const cells = fixture.nativeElement.querySelectorAll('tbody tr:first-of-type td');
    return cells[index]?.textContent?.trim() ?? '';
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
    it('should emit itemRemoved with index when item removed (isModifiable)', () => {
      const { fixture, comp } = createFixture({
        items: [createItem(), createItem({ productVariantId: 'v2' })],
        isModifiable: true,
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
        isModifiable: false,
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
        isModifiable: true,
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
        isModifiable: true,
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
        isModifiable: true,
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
        isModifiable: false,
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
        isModifiable: true,
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
        isModifiable: true,
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
        isModifiable: true,
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
        isModifiable: true,
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
        isModifiable: true,
        savingRowIds: new Set(['b']),
      });

      const [first, second, third] = removeButtons(fixture);
      expect(first.disabled).toBe(false);
      expect(second.disabled).toBe(true);
      expect(third.disabled).toBe(false);
    });

    it('should leave every row enabled when nothing is in flight', () => {
      const { fixture } = createFixture({ items: threeRows, isModifiable: true });

      expect(
        Array.from<HTMLInputElement>(
          fixture.nativeElement.querySelectorAll('input[type="number"]'),
        ).some((input) => input.disabled),
      ).toBe(false);
    });

    it('should not disable a row that has no server id', () => {
      const { fixture } = createFixture({
        items: [createItem()],
        isModifiable: true,
        savingRowIds: new Set(['a']),
      });

      expect(inputsOfRow(fixture, 0).some((input) => input.disabled)).toBe(false);
    });

    it('should enable remove button when nothing is in flight and isModifiable is true', () => {
      const { fixture } = createFixture({
        items: [createItem({ id: 'a' })],
        isModifiable: true,
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

  // ─── T8: the server's line parts, the document's currency, four decimals ───

  describe('the line parts the server states', () => {
    it('should render the subtotal, tax and line total the server answered with', () => {
      const { fixture } = createFixture({ items: [createServerItem()] });

      // quantity 2 × list price 1200 − 0 = 2400 would be the local computation. The server said 300, 48 and 348,
      // and those are the numbers the operator has to be able to reconcile against the document.
      expect(cellText(fixture, 'Subtotal')).toContain('300.0000');
      expect(cellText(fixture, 'Tax')).toContain('48.0000');
      expect(cellText(fixture, 'Total')).toContain('348.0000');
    });

    it('should not substitute its own arithmetic for a part the server stated', () => {
      const { fixture } = createFixture({
        items: [
          createServerItem({ quantity: 3, listPrice: 100, discountApplied: 10, subtotal: 290.5 }),
        ],
      });

      expect(cellText(fixture, 'Subtotal')).toContain('290.5000');
      expect(cellText(fixture, 'Subtotal')).not.toContain('290.0000');
    });

    it('should keep a part that a two-decimal display would erase', () => {
      const { fixture } = createFixture({
        items: [createServerItem({ taxAmount: 0.004 })],
      });

      // S2: the rounding to minor units happens once, at the document boundary. A part shown as 0.00 would stop
      // the displayed parts from adding up to the displayed total.
      expect(cellText(fixture, 'Tax')).toContain('0.0040');
    });

    it('should show the line parts of a row that is not editable', () => {
      const { fixture } = createFixture({ items: [createServerItem()], isModifiable: false });

      expect(cellText(fixture, 'Subtotal')).toContain('300.0000');
      expect(cellText(fixture, 'Total')).toContain('348.0000');
    });
  });

  describe('the document currency', () => {
    it('should format every amount with the currency it is given', () => {
      const { fixture } = createFixture({ items: [createServerItem()], currency: 'MXN' });

      // The cell names the currency it was given: a global default cannot produce this.
      expect(cellText(fixture, 'Subtotal')).toContain('MXN');
      expect(cellText(fixture, 'Subtotal')).not.toContain('USD');
    });

    it('should format money differently when the given currency changes', () => {
      const { fixture } = createFixture({ items: [createServerItem()], currency: 'USD' });
      expect(cellText(fixture, 'Subtotal')).toContain('USD');
      expect(cellText(fixture, 'Subtotal')).not.toContain('MXN');

      fixture.componentRef.setInput('currency', 'MXN');
      fixture.detectChanges();
      expect(cellText(fixture, 'Subtotal')).toContain('MXN');
      expect(cellText(fixture, 'Subtotal')).not.toContain('USD');
    });
  });

  describe('the discard notice', () => {
    it('should say a row without server parts is a local estimate', () => {
      const { fixture } = createFixture({
        items: [createItem({ quantity: 3, listPrice: 100, discountApplied: 0 })],
      });

      const notice: HTMLElement | null = fixture.nativeElement.querySelector(
        '[data-testid="local-parts-notice"]',
      );
      expect(notice).toBeTruthy();
      expect(notice!.textContent).toContain('local estimate');
      // The number is still useful, but it is not presented as the server's.
      expect(cellText(fixture, 'Subtotal')).toContain('300.0000');
    });

    it('should name how many rows it cannot represent', () => {
      const { fixture } = createFixture({
        items: [createServerItem(), createItem({ productVariantId: 'v2' })],
      });

      const notice: HTMLElement = fixture.nativeElement.querySelector(
        '[data-testid="local-parts-notice"]',
      );
      expect(notice).toBeTruthy();
      expect(notice.textContent).toContain('1');
    });

    it('should stay silent when every row carries the server\u2019s parts', () => {
      const { fixture } = createFixture({
        items: [createServerItem(), createServerItem({ id: 'item-2', productVariantId: 'v2' })],
      });

      expect(fixture.nativeElement.querySelector('[data-testid="local-parts-notice"]')).toBeNull();
    });
  });

  describe('the document total in the summary', () => {
    it('should show the total the server stated', () => {
      const { fixture } = createFixture({
        items: [createServerItem()],
        documentTotal: 1234.5678,
      });

      const summary: HTMLElement = fixture.nativeElement.querySelector('.summary-row--total')!;
      expect(summary.textContent).toContain('1,234.5678');
    });

    it('should label a local total when the document has none yet', () => {
      const { fixture } = createFixture({
        items: [createItem({ quantity: 2, listPrice: 100, discountApplied: 0 })],
        documentTotal: null,
      });

      const summary: HTMLElement = fixture.nativeElement.querySelector('.summary-row--total')!;
      expect(summary.textContent).toContain('200.0000');
      expect(summary.textContent).toContain('local');
    });
  });

  describe('the money inputs', () => {
    it('should accept four decimals on price and discount', () => {
      const { fixture } = createFixture({ items: [createItem()], isModifiable: true });

      const steps = Array.from<HTMLInputElement>(
        fixture.nativeElement.querySelectorAll('input[type="number"]'),
      )
        .map((input) => input.getAttribute('step'))
        .filter((step) => step !== null);

      // The columns are NUMERIC(19,4); a step of 0.01 makes the browser refuse a legitimate fourth decimal.
      expect(steps).toContain('0.0001');
      expect(steps).not.toContain('0.01');
    });
  });
});
