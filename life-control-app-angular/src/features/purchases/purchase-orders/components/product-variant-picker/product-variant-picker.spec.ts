import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { ProductVariantPicker } from './product-variant-picker';
import { ProductVariantService } from '@features/products/data/product-variant.service';
import type { Page } from '@features/products/models/product.models';
import type { ProductVariant } from '@features/products/models/product-variant.models';

const variantA: ProductVariant = {
  id: 'var-1',
  productId: 'prod-1',
  companyStoreId: 'store-1',
  barCode: 'BAR-001',
  sku: 'SKU-A',
  variantName: 'Presentación 1L',
  listPrice: 120,
  costPrice: 80,
  stock: 10,
  enabled: true,
  measureUnitId: null,
};

const variantB: ProductVariant = {
  id: 'var-2',
  productId: 'prod-1',
  companyStoreId: 'store-1',
  barCode: 'BAR-002',
  sku: 'SKU-B',
  variantName: 'Presentación 2L',
  listPrice: 200,
  costPrice: 140,
  stock: 4,
  enabled: true,
  measureUnitId: null,
};

function variantPage(
  content: ProductVariant[],
  totalElements = content.length,
): Page<ProductVariant> {
  return {
    content,
    totalElements,
    totalPages: 1,
    size: 50,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
  };
}

describe('ProductVariantPicker', () => {
  let fixture: ComponentFixture<ProductVariantPicker>;
  let component: ProductVariantPicker;
  let variantService: { getVariants: ReturnType<typeof vi.fn> };

  /** The order's company, which every read of this picker is scoped to. */
  const companyId = 'company-1';

  beforeEach(async () => {
    variantService = {
      getVariants: vi.fn().mockReturnValue(of(variantPage([variantA, variantB]))),
    };

    await TestBed.configureTestingModule({
      imports: [ProductVariantPicker],
      providers: [{ provide: ProductVariantService, useValue: variantService }],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductVariantPicker);
    component = fixture.componentInstance;
    // Required input: the host always supplies it (the order's company), so the spec does too and the
    // fail-closed test below is the one that nulls it.
    fixture.componentRef.setInput('companyId', companyId);
  });

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  describe('loading variants', () => {
    it('should load the product variants for the given store', () => {
      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();

      expect(variantService.getVariants).toHaveBeenCalledWith(
        companyId,
        'prod-1',
        'store-1',
        0,
        50,
      );
      expect(component.variants().length).toBe(2);
      expect(component.variants()[0].variantName).toBe('Presentación 1L');
    });

    it('should scope the read to the order’s company', () => {
      // The one assertion that fails if the company were dropped: `getVariants` is the scoped call, so
      // its first argument is the company the order carries. A company-blind call would also match
      // `/api/products/...`, which no longer exists, and would list another tenant's variants.
      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();

      expect(variantService.getVariants).toHaveBeenCalledTimes(1);
      expect(variantService.getVariants.mock.calls[0][0]).toBe(companyId);
    });

    it('should request nothing when the order has no company yet', () => {
      // An unsaved order may have a product and a store but no company. Failing closed is the only
      // honest answer: guessing one would read another tenant's catalogue.
      fixture.componentRef.setInput('companyId', null);
      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();

      expect(variantService.getVariants).not.toHaveBeenCalled();
      expect(component.variants()).toEqual([]);
      expect(component.missingContext()).toBe(true);
      expect(component.controlDisabled()).toBe(true);
    });

    it('should reload when the order’s company changes', () => {
      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();
      expect(variantService.getVariants).toHaveBeenCalledTimes(1);

      fixture.componentRef.setInput('companyId', 'company-2');
      fixture.detectChanges();

      expect(variantService.getVariants).toHaveBeenCalledTimes(2);
      expect(variantService.getVariants.mock.calls[1][0]).toBe('company-2');
    });

    it('should not request anything while the product is missing', () => {
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();

      expect(variantService.getVariants).not.toHaveBeenCalled();
      expect(component.variants()).toEqual([]);
      expect(component.controlDisabled()).toBe(true);
    });

    it('should not request anything while the store is missing', () => {
      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.detectChanges();

      expect(variantService.getVariants).not.toHaveBeenCalled();
      expect(component.variants()).toEqual([]);
      expect(component.controlDisabled()).toBe(true);
    });

    it('should reload when the store changes', () => {
      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();
      expect(variantService.getVariants).toHaveBeenCalledTimes(1);

      fixture.componentRef.setInput('storeId', 'store-2');
      fixture.detectChanges();

      expect(variantService.getVariants).toHaveBeenCalledTimes(2);
      expect(variantService.getVariants).toHaveBeenLastCalledWith(
        companyId,
        'prod-1',
        'store-2',
        0,
        50,
      );
    });

    it('should clear the options when the store is cleared', () => {
      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();
      expect(component.variants().length).toBe(2);

      fixture.componentRef.setInput('storeId', null);
      fixture.detectChanges();

      expect(component.variants()).toEqual([]);
      expect(component.controlDisabled()).toBe(true);
    });

    it('should clear the options, the total and the selection when the store changes', () => {
      variantService.getVariants.mockReturnValue(of(variantPage([variantA, variantB], 120)));

      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();
      component.onSelectionChange('var-1');
      expect(component.variants().length).toBe(2);
      expect(component.hasMore()).toBe(true);
      expect(component.variantId()).toBe('var-1');

      // The reload stays pending so the intermediate state is observable: the
      // previous store's options must already be gone.
      variantService.getVariants.mockReturnValue(new Subject<Page<ProductVariant>>());
      fixture.componentRef.setInput('storeId', 'store-2');
      fixture.detectChanges();

      expect(component.variants()).toEqual([]);
      expect(component.hasMore()).toBe(false);
      expect(component.variantId()).toBeNull();
      expect(component.loading()).toBe(true);
      expect(component.loadFailed()).toBe(false);
    });

    it('should clear a previous load failure when the scope changes', () => {
      variantService.getVariants.mockReturnValue(throwError(() => new Error('boom')));

      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();
      expect(component.loadFailed()).toBe(true);

      variantService.getVariants.mockReturnValue(new Subject<Page<ProductVariant>>());
      fixture.componentRef.setInput('storeId', 'store-2');
      fixture.detectChanges();

      expect(component.loadFailed()).toBe(false);
    });
  });

  describe('empty and error states', () => {
    it('should render the explicit empty state when the store has no variants', () => {
      variantService.getVariants.mockReturnValue(of(variantPage([])));

      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();

      expect(component.isEmpty()).toBe(true);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Sin variantes para este producto en esta tienda',
      );
    });

    it('should render an inline error when the request fails', () => {
      variantService.getVariants.mockReturnValue(throwError(() => new Error('boom')));

      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();

      expect(component.loadFailed()).toBe(true);
      expect(component.variants()).toEqual([]);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'No se pudieron cargar las variantes',
      );
      // The hint must not promise a retry affordance that does not exist.
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Cambiá el producto o la tienda para reintentar',
      );
    });

    it('should render a quiet hint when the response reports more variants than loaded', () => {
      variantService.getVariants.mockReturnValue(of(variantPage([variantA], 120)));

      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();

      expect(component.hasMore()).toBe(true);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Mostrando las primeras 1 variantes',
      );
    });
  });

  describe('selection', () => {
    beforeEach(() => {
      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.detectChanges();
    });

    it('should update the two-way model and emit the whole variant', () => {
      let emitted: ProductVariant | null = null;
      const sub = component.variantSelected.subscribe((variant) => (emitted = variant));

      component.onSelectionChange('var-2');
      sub.unsubscribe();

      expect(component.variantId()).toBe('var-2');
      expect(emitted).not.toBeNull();
      expect(emitted!.id).toBe('var-2');
      expect(emitted!.costPrice).toBe(140);
    });

    it('should keep the display in sync when the parent resets the model', () => {
      component.onSelectionChange('var-1');
      expect(component.variantId()).toBe('var-1');

      component.variantId.set(null);
      fixture.detectChanges();

      expect(component.variantId()).toBeNull();
    });
  });

  describe('disabled behaviour', () => {
    it('should disable the control when the disabled input is set', () => {
      fixture.componentRef.setInput('productId', 'prod-1');
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();

      expect(component.controlDisabled()).toBe(true);
      const select = (fixture.nativeElement as HTMLElement).querySelector(
        '.mat-mdc-select-disabled',
      );
      expect(select).not.toBeNull();
    });

    it('should stay disabled while no product is selected', () => {
      fixture.componentRef.setInput('storeId', 'store-1');
      fixture.componentRef.setInput('disabled', false);
      fixture.detectChanges();

      expect(component.disabled()).toBe(false);
      expect(component.controlDisabled()).toBe(true);
    });
  });
});
