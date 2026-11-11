import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LOCALE_ID } from '@angular/core';
import { By } from '@angular/platform-browser';
import { MatAutocompleteTrigger } from '@angular/material/autocomplete';
import { of } from 'rxjs';
import {
  DetailTable,
  type DetailLineDraft,
  type DetailLinePatch,
  type DetailTableRow,
} from './detail-table';
import { ProductVariantPicker } from '../product-variant-picker/product-variant-picker';
import { ProductVariantService } from '@features/products/data/product-variant.service';
import { formatMoney } from '@shared/pipes/money.pipe';
import type { Page } from '@features/products/models/product.models';
import type { ProductVariant } from '@features/products/models/product-variant.models';

/**
 * The locale the money expectations are computed in.
 *
 * `en-US` and not the app's `es-MX`: `MoneyPipe` formats through `formatCurrency`, and the `es-MX` locale data is
 * registered by `app.config.ts`, which a unit test does not load. Stating it here keeps the expectation and the render
 * on the same rule instead of on a hardcoded string.
 */
const SPEC_LOCALE = 'en-US';

/**
 * The currency the table is told to render in.
 *
 * Neither the app's `DEFAULT_CURRENCY_CODE` nor the literal the templates used to hardcode, so a render that falls back
 * to either cannot pass for the document's own currency.
 */
const DOCUMENT_CURRENCY = 'EUR';

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
  measureUnitId: null,
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
  measureUnitId: null,
};

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
  // The line's total as the server derived it — the base (4 × 80 = 320) plus the tax below — so the cell prints a
  // number no client-side product of the row's own fields can reach.
  total: 371.2002,
  // The rate the server snapshotted, and the amount it derived. `quantity × unitPrice × rate` lands on 51.2000, one
  // ten-thousandth below what the response states, so a tax the table computed cannot pass for the server's.
  taxRate: 0.16,
  taxAmount: 51.2001,
};

describe('DetailTable', () => {
  let variantService: { getVariants: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    variantService = {
      getVariants: vi.fn().mockReturnValue(of(variantPage([VARIANT_A, VARIANT_B]))),
    };

    await TestBed.configureTestingModule({
      imports: [DetailTable],
      providers: [
        { provide: LOCALE_ID, useValue: SPEC_LOCALE },
        { provide: ProductVariantService, useValue: variantService },
      ],
    }).compileComponents();
  });

  function createTable(
    items: DetailTableRow[],
    isDraft = true,
    showReceiptProgress = false,
    currency = DOCUMENT_CURRENCY,
  ): ComponentFixture<DetailTable> {
    const f = TestBed.createComponent(DetailTable);
    f.componentRef.setInput('items', items);
    f.componentRef.setInput('isDraft', isDraft);
    f.componentRef.setInput('availableProducts', PRODUCTS);
    f.componentRef.setInput('storeId', 'store-1');
    // The variant picker is company-scoped: the table forwards the order's company like the store.
    f.componentRef.setInput('companyId', 'company-1');
    // The document's currency, as the order screen hands it down. Never a literal of the table's own.
    f.componentRef.setInput('currency', currency);
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

  /** Opens the product autocomplete overlay so its projected options are in the DOM. */
  function openPanel(f: ComponentFixture<DetailTable>): void {
    f.debugElement
      .query(By.directive(MatAutocompleteTrigger))
      .injector.get(MatAutocompleteTrigger)
      .openPanel();
    f.detectChanges();
  }

  /** The visible option labels inside the open product panel. */
  function panelOptionTexts(): string[] {
    return Array.from(
      document.querySelectorAll('.mat-mdc-autocomplete-panel mat-option') as NodeListOf<Element>,
    ).map((option) => option.textContent?.trim() ?? '');
  }

  /** The rendered data rows, header excluded. */
  function bodyRows(f: ComponentFixture<DetailTable>): HTMLElement[] {
    return Array.from((f.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('tbody tr'));
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

    it('should emit the id of the row the operator removed', () => {
      const f = createTable([SAVED_ROW]);
      let removed: string | null = null;
      f.componentInstance.lineRemoved.subscribe((id) => (removed = id));

      f.componentInstance.removeItem(0);

      // The table deletes nothing itself: the host persists the delete, and the row leaves when the answer arrives (S4).
      expect(removed).toBe('det-1');
    });

    it('should not remove rows when the order is no longer a draft', () => {
      const f = createTable([SAVED_ROW], false);
      let emitted = false;
      f.componentInstance.lineRemoved.subscribe(() => (emitted = true));

      f.componentInstance.removeItem(0);

      expect(emitted).toBe(false);
    });
  });

  describe('line money', () => {
    it('should render the line total the server stated, not quantity × unit price', () => {
      const f = createTable([SAVED_ROW]);

      expect(cellText(f, 'total')).toBe(
        formatMoney(SAVED_ROW.total, DOCUMENT_CURRENCY, SPEC_LOCALE),
      );
      // The row's own arithmetic would land on 320.0000: the cell shows a different number, so nothing multiplied.
      expect(cellText(f, 'total')).not.toBe(
        formatMoney(SAVED_ROW.quantity * SAVED_ROW.unitPrice, DOCUMENT_CURRENCY, SPEC_LOCALE),
      );
    });

    it('should render the tax the server stated, with the rate it snapshotted', () => {
      const f = createTable([SAVED_ROW]);

      expect(cellText(f, 'taxAmount')).toContain(
        formatMoney(SAVED_ROW.taxAmount, DOCUMENT_CURRENCY, SPEC_LOCALE),
      );
      // The derivation this cell may not perform: the base times the rate, which is a different number from the one
      // the response carried.
      expect(cellText(f, 'taxAmount')).not.toContain(
        formatMoney(
          SAVED_ROW.quantity * SAVED_ROW.unitPrice * (SAVED_ROW.taxRate ?? 0),
          DOCUMENT_CURRENCY,
          SPEC_LOCALE,
        ),
      );
      // The rate the server sent, printed as the percentage a reader expects from a fraction.
      expect(cellText(f, 'taxAmount')).toContain('16%');
      expect((f.nativeElement as HTMLElement).textContent).toContain('Impuesto');
    });

    it('should render nothing in the tax column for a line the server has not answered for', () => {
      // A row added in the browser states no tax, and a `0` there would be a number nobody stated (S8).
      const f = createTable([{ ...SAVED_ROW, total: null, taxRate: null, taxAmount: null }]);

      expect(cellText(f, 'taxAmount')).toBe('');
    });

    it('should emit the quantity change of the edited row, and nothing else', () => {
      const f = createTable([SAVED_ROW]);
      let emitted: { id: string; changes: DetailLinePatch } | null = null;
      f.componentInstance.lineEdited.subscribe((edit) => (emitted = edit));

      f.componentInstance.onRowQuantityChange(0, 2.5);

      // The row is the host's: the table names the line and the field that changed. Dropping the derived total and tax
      // is the host's move, with the rest of the row it reconciles from the answer.
      expect(emitted).toEqual({ id: 'det-1', changes: { quantity: 2.5 } });
    });

    it('should render the unit price in the document currency, not in a literal of its own', () => {
      // Read-only view: in Draft the cell is an input, and the point here is what the rendered amount states.
      const f = createTable([SAVED_ROW], false);

      expect(cellText(f, 'unitPrice')).toBe(
        formatMoney(SAVED_ROW.unitPrice, DOCUMENT_CURRENCY, SPEC_LOCALE),
      );
    });

    it('should render nothing for a line the server has not answered for', () => {
      // A row added in the browser has no server total, and a `0` there would be a number nobody stated (S6).
      const f = createTable([{ ...SAVED_ROW, total: null }]);

      expect(cellText(f, 'total')).toBe('');
    });

    it('should emit the unit price change of the edited row', () => {
      const f = createTable([SAVED_ROW]);
      let emitted: { id: string; changes: DetailLinePatch } | null = null;
      f.componentInstance.lineEdited.subscribe((edit) => (emitted = edit));

      f.componentInstance.onRowUnitPriceChange(0, 99);

      expect(emitted).toEqual({ id: 'det-1', changes: { unitPrice: 99 } });
    });

    it('should never sum the rows itself', () => {
      const f = createTable([SAVED_ROW]);

      // The removed derivations, asserted by their absence: `lineItemsTotal` summed the rows and `rowSubtotal`
      // multiplied one row's fields.
      expect('lineItemsTotal' in f.componentInstance).toBe(false);
      expect('rowSubtotal' in f.componentInstance).toBe(false);
      // The footer that printed the summed total is gone: the page states the document's total instead.
      expect((f.nativeElement as HTMLElement).querySelector('.line-items-total')).toBeNull();
    });
  });

  describe('server-searched catalogue', () => {
    it('should emit the term the operator typed and narrow nothing itself', () => {
      const f = createTable([SAVED_ROW]);
      let emitted: string | null = null;
      f.componentInstance.searchChanged.subscribe((term) => (emitted = term));

      f.componentInstance.onSearchChange('widget b');
      f.detectChanges();

      // The host owns the search: the table names the term and keeps rendering the options it is handed (S6).
      expect(emitted).toBe('widget b');
      openPanel(f);
      expect(panelOptionTexts().length).toBe(2);
    });

    it('should say the supplier has no products when the catalogue is empty and no term was applied', () => {
      const f = createTable([]);
      f.componentRef.setInput('availableProducts', []);
      f.componentRef.setInput('searching', false);
      f.componentRef.setInput('searchFailed', false);
      f.componentRef.setInput('searchedTerm', '');
      f.detectChanges();

      openPanel(f);

      expect(panelOptionTexts()).toEqual(['El proveedor no tiene productos cargados.']);
      // An answer, not a selectable product.
      expect(
        document
          .querySelectorAll('.mat-mdc-autocomplete-panel mat-option')
          .item(0)
          .hasAttribute('disabled'),
      ).toBe(true);
    });

    it('should say nothing matched the term instead of blaming the supplier', () => {
      const f = createTable([]);
      f.componentRef.setInput('availableProducts', []);
      f.componentRef.setInput('searching', false);
      f.componentRef.setInput('searchFailed', false);
      f.componentRef.setInput('searchedTerm', 'zz');
      f.detectChanges();

      openPanel(f);

      // The same empty list, a different fact: "nothing matched 'zz'" is not "this supplier has nothing" (S6).
      expect(panelOptionTexts()).toEqual(['Sin resultados para "zz".']);
    });

    it('should report a read in flight distinctly from an empty catalogue', () => {
      const f = createTable([]);
      f.componentRef.setInput('availableProducts', []);
      f.componentRef.setInput('searching', true);
      f.detectChanges();

      openPanel(f);

      expect(panelOptionTexts()).toEqual(['Buscando productos…']);
    });

    it('should report a failed read distinctly from an empty catalogue', () => {
      const f = createTable([]);
      f.componentRef.setInput('availableProducts', []);
      f.componentRef.setInput('searching', false);
      f.componentRef.setInput('searchFailed', true);
      f.detectChanges();

      openPanel(f);

      expect(panelOptionTexts()).toEqual(['No se pudo leer el catálogo de productos.']);
    });
  });

  describe('per-line state', () => {
    const SECOND_ROW: DetailTableRow = {
      ...SAVED_ROW,
      id: 'det-2',
      productId: 'prod-2',
      productName: 'Widget B',
      productVariantId: 'var-2',
      productVariantName: 'Presentación 2L',
      createdAt: '2026-01-02T00:00:00Z',
    };

    it('should mark the row that is being written, and only that row', () => {
      const f = createTable([SAVED_ROW, SECOND_ROW]);
      f.componentRef.setInput('linePending', { 'det-1': true });
      f.detectChanges();

      const [first, second] = bodyRows(f);
      expect(first.querySelector('.line-pending')).not.toBeNull();
      expect(second.querySelector('.line-pending')).toBeNull();
      // A row whose write is in flight is neither re-edited nor removed underneath the request.
      expect(first.querySelector<HTMLInputElement>('input[type="number"]')!.disabled).toBe(true);
      expect(first.querySelector<HTMLButtonElement>('button')!.disabled).toBe(true);
      expect(second.querySelector<HTMLInputElement>('input[type="number"]')!.disabled).toBe(false);
    });

    it('should show the message a rejected row carries, on that row alone', () => {
      const f = createTable([SAVED_ROW, SECOND_ROW]);
      f.componentRef.setInput('lineErrors', { 'det-1': 'La cantidad supera el stock' });
      f.detectChanges();

      const [first, second] = bodyRows(f);
      expect(first.textContent).toContain('La cantidad supera el stock');
      expect(second.textContent).not.toContain('La cantidad supera el stock');
      // In the row, never in a page-wide banner: the banner is the header's (S5).
      expect((f.nativeElement as HTMLElement).querySelector('app-error-banner')).toBeNull();
    });

    it('should show the add attempt its own pending and error state', () => {
      const f = createTable([]);
      f.componentRef.setInput('newLineError', 'No se pudo agregar la línea.');
      f.detectChanges();

      const addRow = (f.nativeElement as HTMLElement).querySelector('.add-line-item')!;
      expect(addRow.textContent).toContain('No se pudo agregar la línea.');

      f.componentRef.setInput('newLinePending', true);
      f.detectChanges();

      expect(addRow.querySelector('.line-pending')).not.toBeNull();
      // While the add is in flight the row cannot post the same line twice.
      expect(f.componentInstance.canAddItem()).toBe(false);
    });
  });

  describe('fractional quantities', () => {
    it('should let the inline row edit accept a fractional quantity', () => {
      const f = createTable([SAVED_ROW]);
      const input = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
        'td.mat-column-quantity input[type="number"]',
      );

      expect(input).not.toBeNull();
      expect(input!.step).toBe('0.01');
      expect(input!.min).toBe('0.01');
    });

    it('should let the new-line field accept a fractional quantity', () => {
      const f = createTable([]);
      const addLine = (f.nativeElement as HTMLElement).querySelector<HTMLElement>('.add-line-item');
      // The add-line row carries two numeric fields: quantity first, unit price second.
      const quantity = addLine!.querySelectorAll<HTMLInputElement>('input[type="number"]')[0];

      expect(quantity.step).toBe('0.01');
      expect(quantity.min).toBe('0.01');
      // The new-line field keeps its own handler, not the inline-edit one.
      expect(quantity.value).toBe('1');
    });

    it('should keep a fraction typed in the inline edit instead of rounding it', () => {
      const f = createTable([SAVED_ROW]);
      let emitted: { id: string; changes: DetailLinePatch } | null = null;
      f.componentInstance.lineEdited.subscribe((edit) => (emitted = edit));

      f.componentInstance.onRowQuantityChange(0, 2.5);

      expect(emitted).not.toBeNull();
      expect(emitted!.changes['quantity']).toBe(2.5);
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
        'taxAmount',
        'total',
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
        'taxAmount',
        'total',
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
      expect(variantService.getVariants).toHaveBeenCalledWith(
        'company-1',
        'prod-1',
        'store-1',
        0,
        50,
      );

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

    it('should emit the line to create and reset the form', () => {
      const f = createTable([]);
      const component = f.componentInstance;

      let emitted: DetailLineDraft | null = null;
      component.lineAdded.subscribe((line) => (emitted = line));

      component.onNewProductChange('prod-1');
      f.detectChanges();
      pickerOf(f).onSelectionChange('var-1');
      component.onNewQuantityChange(3);
      f.detectChanges();

      component.addItem();

      // The payload names the variant and the pricing the operator chose, and carries no id: the row is the answer's,
      // not this payload's (S4).
      expect(emitted).toEqual({
        productId: 'prod-1',
        productVariantId: 'var-1',
        quantity: 3,
        unitPrice: 80,
      });

      // The form is reset so the next line starts clean.
      expect(component.newProductId()).toBe('');
      expect(component.newVariantId()).toBeNull();
      expect(component.selectedVariant()).toBeNull();
      expect(component.newUnitPrice()).toBe(0);
      expect(component.newQuantity()).toBe(1);
    });

    it('should not offer the add row at all when the order is not a Draft', () => {
      // Draft orders keep it, and that guard is the order's status alone (S10).
      expect(
        (createTable([SAVED_ROW], true).nativeElement as HTMLElement).querySelector(
          '.add-line-item',
        ),
      ).not.toBeNull();
      expect(
        (createTable([SAVED_ROW], false).nativeElement as HTMLElement).querySelector(
          '.add-line-item',
        ),
      ).toBeNull();
    });

    it('should not add a line while the order is not a draft', () => {
      const f = createTable([], false);
      const component = f.componentInstance;

      let emitted = false;
      component.lineAdded.subscribe(() => (emitted = true));

      // The add row is not rendered outside Draft, so the state is set here directly: the guard has to refuse on its
      // own instead of relying on the form being absent from the DOM.
      component.newProductId.set('prod-1');
      component.onVariantSelected(VARIANT_A);
      component.onVariantIdChange(VARIANT_A.id);
      component.onNewUnitPriceChange(50);

      component.addItem();

      expect(component.canAddItem()).toBe(false);
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

      let emitted: DetailLineDraft | null = null;
      component.lineAdded.subscribe((line) => (emitted = line));

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
  let originalMatchMedia: typeof window.matchMedia;

  function setupMatchMedia(matches: boolean) {
    const listeners: Record<string, EventListener> = {};
    const mql = {
      matches,
      addEventListener: (type: string, listener: EventListener) => {
        listeners[type] = listener;
      },
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
    };
    window.matchMedia = vi
      .fn()
      .mockReturnValue(mql as unknown as MediaQueryList) as unknown as typeof window.matchMedia;
    return { mql, listeners };
  }

  beforeAll(() => {
    originalMatchMedia = window.matchMedia;
  });

  afterAll(() => {
    window.matchMedia = originalMatchMedia;
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DetailTable],
      providers: [
        { provide: LOCALE_ID, useValue: SPEC_LOCALE },
        {
          provide: ProductVariantService,
          useValue: { getVariants: vi.fn().mockReturnValue(of(variantPage([VARIANT_A]))) },
        },
      ],
    }).compileComponents();
  });

  function createMobileTable(
    items: DetailTableRow[],
    showReceiptProgress: boolean,
    currency = DOCUMENT_CURRENCY,
  ): ComponentFixture<DetailTable> {
    const f = TestBed.createComponent(DetailTable);
    f.componentRef.setInput('items', items);
    f.componentRef.setInput('isDraft', true);
    f.componentRef.setInput('availableProducts', PRODUCTS);
    f.componentRef.setInput('storeId', 'store-1');
    // The variant picker is company-scoped: the table forwards the order's company like the store.
    f.componentRef.setInput('companyId', 'company-1');
    f.componentRef.setInput('currency', currency);
    f.componentRef.setInput('showReceiptProgress', showReceiptProgress);
    f.detectChanges();
    return f;
  }

  it('should render the receipt progress facts in the per-line card', () => {
    setupMatchMedia(true);
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
    setupMatchMedia(true);
    const f = createMobileTable([SAVED_ROW], false);

    const cards = (f.nativeElement as HTMLElement).querySelectorAll('.line-item-card');
    expect(cards.length).toBe(1);

    const text = (f.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Recibido');
  });

  it('should let the per-line card accept a fractional quantity', () => {
    setupMatchMedia(true);
    const f = createMobileTable([SAVED_ROW], false);

    const input = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '.line-item-card input[type="number"]',
    );

    expect(input).not.toBeNull();
    expect(input!.step).toBe('0.01');
    expect(input!.min).toBe('0.01');
  });

  it('should render the server line total in the per-line card', () => {
    setupMatchMedia(true);
    const f = createMobileTable([SAVED_ROW], false);

    const total = (f.nativeElement as HTMLElement).querySelector('.line-item-card .total-value');

    expect(total?.textContent?.trim()).toBe(
      formatMoney(SAVED_ROW.total, DOCUMENT_CURRENCY, SPEC_LOCALE),
    );
  });

  it('should render the server tax in the per-line card', () => {
    setupMatchMedia(true);
    const f = createMobileTable([SAVED_ROW], false);

    const card = (f.nativeElement as HTMLElement).querySelector('.line-item-card');
    const tax = card?.querySelector('.line-tax-amount');

    expect(tax?.textContent?.trim()).toBe(
      formatMoney(SAVED_ROW.taxAmount, DOCUMENT_CURRENCY, SPEC_LOCALE),
    );
    // The desktop column is mirrored instead of silently dropped, label included.
    expect(card?.textContent).toContain('Impuesto');
  });
});
