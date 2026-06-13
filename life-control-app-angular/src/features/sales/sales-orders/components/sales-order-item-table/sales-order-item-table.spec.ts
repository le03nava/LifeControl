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

  function createFixture(
    overrides?: {
      items?: ItemTableRow[];
      isDraft?: boolean;
      isSaving?: number | null;
    },
  ): { fixture: ComponentFixture<SalesOrderItemTable>; comp: SalesOrderItemTable } {
    const fixture = TestBed.createComponent(SalesOrderItemTable);
    const comp = fixture.componentInstance;

    fixture.componentRef.setInput('items', overrides?.items ?? []);
    fixture.componentRef.setInput('isDraft', overrides?.isDraft ?? true);
    fixture.componentRef.setInput('isSaving', overrides?.isSaving ?? null);
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

  describe('isSaving input', () => {
    it('should disable inputs when isSaving is non-null', () => {
      const { fixture } = createFixture({
        items: [createItem()],
        isDraft: true,
        isSaving: 0,
      });

      const quantityInput: HTMLInputElement | null =
        fixture.nativeElement.querySelector('input[type="number"]');
      expect(quantityInput).toBeTruthy();
      expect(quantityInput!.disabled).toBe(true);
    });

    it('should enable inputs when isSaving is null', () => {
      const { fixture } = createFixture({
        items: [createItem()],
        isDraft: true,
        isSaving: null,
      });

      const quantityInput: HTMLInputElement | null =
        fixture.nativeElement.querySelector('input[type="number"]');
      expect(quantityInput).toBeTruthy();
      expect(quantityInput!.disabled).toBe(false);
    });

    it('should disable remove button when isSaving is non-null', () => {
      const { fixture } = createFixture({
        items: [createItem()],
        isDraft: true,
        isSaving: 1,
      });

      const removeButton: HTMLButtonElement | null =
        fixture.nativeElement.querySelector('button[color="warn"]');
      expect(removeButton).toBeTruthy();
      expect(removeButton!.disabled).toBe(true);
    });

    it('should enable remove button when isSaving is null and isDraft is true', () => {
      const { fixture } = createFixture({
        items: [createItem()],
        isDraft: true,
        isSaving: null,
      });

      const removeButton: HTMLButtonElement | null =
        fixture.nativeElement.querySelector('button[color="warn"]');
      expect(removeButton).toBeTruthy();
      expect(removeButton!.disabled).toBe(false);
    });
  });
});
