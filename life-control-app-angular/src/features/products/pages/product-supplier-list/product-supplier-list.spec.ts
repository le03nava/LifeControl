import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ProductSupplierList } from './product-supplier-list';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { ProductSupplier } from '../../models/product-supplier.models';
import { Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { Subject, of, throwError } from 'rxjs';

interface SupplierServiceMock {
  getSuppliers: ReturnType<typeof vi.fn>;
  removeSupplier: ReturnType<typeof vi.fn>;
}

describe('ProductSupplierList', () => {
  let component: ProductSupplierList;
  let fixture: ComponentFixture<ProductSupplierList>;
  let serviceMock: SupplierServiceMock;
  let routerMock: { navigate: ReturnType<typeof vi.fn> };
  let dialogMock: { open: ReturnType<typeof vi.fn> };

  const mockProductId = 'prod-1';

  function createSuppliers(overrides: Partial<ProductSupplier>[] = []): ProductSupplier[] {
    if (overrides.length > 0) return overrides.map((o, i) => createSupplier(i, o));
    return [createSupplier(0), createSupplier(1)];
  }

  function createSupplier(
    index: number,
    overrides: Partial<ProductSupplier> = {},
  ): ProductSupplier {
    return {
      id: `ps-${index}`,
      productId: mockProductId,
      supplierId: `sup-${index}`,
      supplierName: `Supplier ${index}`,
      purchaseCost: 100 + index * 10,
      main: index === 0,
      enabled: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      ...overrides,
    };
  }

  function setup(
    options: {
      suppliers?: ProductSupplier[];
      suppliersError?: boolean;
      suppliersPending?: boolean;
    } = {},
  ) {
    const suppliers = options.suppliers ?? createSuppliers();

    serviceMock = {
      getSuppliers: options.suppliersError
        ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })))
        : options.suppliersPending
          ? vi.fn().mockReturnValue(new Subject<ProductSupplier[]>())
          : vi.fn().mockReturnValue(of(suppliers)),
      removeSupplier: vi.fn().mockReturnValue(of(void 0)),
    };

    routerMock = {
      navigate: vi.fn(),
    };

    dialogMock = {
      open: vi.fn().mockReturnValue({
        afterClosed: () => of(false),
      }),
    };

    TestBed.configureTestingModule({
      imports: [ProductSupplierList, NoopAnimationsModule],
      providers: [
        provideHttpClient(),
        { provide: ProductSupplierService, useValue: serviceMock },
        { provide: Router, useValue: routerMock },
        { provide: MatDialog, useValue: dialogMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductSupplierList);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('productId', mockProductId);
  }

  /**
   * Settles the read and the render. More than one flush on purpose: the resource
   * load starts on the flush after the params change, so a single `whenStable()`
   * can return with the read still idle.
   */
  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  describe('Component creation', () => {
    it('should create', async () => {
      setup();
      await settle();
      expect(component).toBeTruthy();
    });

    it('should expose the productId input value', async () => {
      setup();
      await settle();
      expect(component.productId()).toBe(mockProductId);
    });

    it('should fetch suppliers for the productId it is given', async () => {
      setup();
      await settle();
      expect(serviceMock.getSuppliers).toHaveBeenCalledWith(mockProductId);
    });
  });

  describe('Page header removal', () => {
    it('should not render a page header nor a page title', async () => {
      setup();
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('app-page-header')).toBeNull();
      expect(el.querySelector('.page-title')).toBeNull();
    });
  });

  describe('countChange', () => {
    it('should emit the loaded supplier count on a successful load', async () => {
      setup({ suppliers: createSuppliers() });

      const emitted: number[] = [];
      component.countChange.subscribe((count) => emitted.push(count));

      await settle();

      expect(emitted).toEqual([2]);
    });

    it('should emit the new count on the reload that follows a delete', async () => {
      setup({ suppliers: createSuppliers() });

      const emitted: number[] = [];
      component.countChange.subscribe((count) => emitted.push(count));
      await settle();
      expect(emitted).toEqual([2]);

      serviceMock.getSuppliers.mockReturnValue(of([createSupplier(0)]));
      dialogMock.open = vi.fn().mockReturnValue({ afterClosed: () => of(true) });

      component.confirmDelete('ps-0', 'Supplier 0');
      await settle();

      expect(serviceMock.removeSupplier).toHaveBeenCalledWith(mockProductId, 'ps-0');
      expect(emitted).toEqual([2, 1]);
    });

    it('should emit zero when the list resolves empty', async () => {
      setup({ suppliers: [] });

      const emitted: number[] = [];
      component.countChange.subscribe((count) => emitted.push(count));

      await settle();

      expect(emitted).toEqual([0]);
    });

    it('should not emit while the read is still in flight', () => {
      setup({ suppliersPending: true });

      const emitted: number[] = [];
      component.countChange.subscribe((count) => emitted.push(count));

      fixture.detectChanges();

      expect(component.loading()).toBe(true);
      expect(emitted).toEqual([]);
    });
  });

  describe('Add supplier affordance', () => {
    it('should render the add button in its own action row in the table state', async () => {
      setup({ suppliers: createSuppliers() });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.empty-state')).toBeNull();

      const actionButtons = Array.from(
        el.querySelectorAll('.action-row button'),
      ) as HTMLButtonElement[];
      expect(actionButtons.length).toBe(1);
      expect(actionButtons[0].textContent?.trim()).toBe('Agregar proveedor');

      actionButtons[0].click();
      expect(routerMock.navigate).toHaveBeenCalledWith([
        '/products/edit',
        mockProductId,
        'suppliers',
        'create',
      ]);
    });

    it('should keep the empty state add button as the only affordance when empty', async () => {
      setup({ suppliers: [] });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.empty-state')).toBeTruthy();
      expect(el.querySelector('.action-row')).toBeNull();

      const emptyButtons = Array.from(
        el.querySelectorAll('.empty-state button'),
      ) as HTMLButtonElement[];
      expect(emptyButtons.length).toBe(1);
      expect(emptyButtons[0].textContent?.trim()).toBe('Agregar proveedor');

      emptyButtons[0].click();
      expect(routerMock.navigate).toHaveBeenCalledWith([
        '/products/edit',
        mockProductId,
        'suppliers',
        'create',
      ]);
    });
  });

  describe('Navigation', () => {
    it('should navigate to create on addSupplier', async () => {
      setup();
      await settle();

      component.addSupplier();
      expect(routerMock.navigate).toHaveBeenCalledWith([
        '/products/edit',
        mockProductId,
        'suppliers',
        'create',
      ]);
    });

    it('should navigate to edit on editSupplier', async () => {
      setup();
      await settle();

      component.editSupplier('ps-1');
      expect(routerMock.navigate).toHaveBeenCalledWith([
        '/products/edit',
        mockProductId,
        'suppliers',
        'edit',
        'ps-1',
      ]);
    });
  });

  describe('Delete flow', () => {
    it('should open dialog and not delete when cancelled', async () => {
      setup();
      await settle();

      component.confirmDelete('ps-1', 'Supplier 0');

      expect(dialogMock.open).toHaveBeenCalled();
      expect(serviceMock.removeSupplier).not.toHaveBeenCalled();
    });

    it('should open dialog and delete when confirmed', async () => {
      setup();
      await settle();

      dialogMock.open = vi.fn().mockReturnValue({ afterClosed: () => of(true) });

      component.confirmDelete('ps-1', 'Supplier 0');

      expect(dialogMock.open).toHaveBeenCalled();
      expect(serviceMock.removeSupplier).toHaveBeenCalledWith(mockProductId, 'ps-1');
    });
  });

  describe('Display states', () => {
    it('should render the rows it is given', async () => {
      setup({ suppliers: createSuppliers() });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      const supplierCells = Array.from(el.querySelectorAll('td.mat-column-supplierName')).map(
        (cell) => cell.textContent?.trim(),
      );
      expect(supplierCells).toEqual(['Supplier 0', 'Supplier 1']);

      const mainChip = el.querySelector('.main-chip');
      expect(mainChip?.textContent?.trim()).toBe('Principal');
    });

    it('should render the state chips in voseo', async () => {
      setup({
        suppliers: [createSupplier(0, { enabled: true }), createSupplier(1, { enabled: false })],
      });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.enabled-chip')?.textContent?.trim()).toBe('Habilitada');
      expect(el.querySelector('.disabled-chip')?.textContent?.trim()).toBe('Deshabilitada');
    });

    it('should render the row action labels in voseo', async () => {
      setup({ suppliers: [createSupplier(0)] });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      const labels = Array.from(el.querySelectorAll('.actions-cell button')).map((button) =>
        button.getAttribute('aria-label'),
      );
      expect(labels).toEqual([
        'Editar la asignación del proveedor',
        'Eliminar la asignación del proveedor',
      ]);
    });

    it('should render every column header in voseo', async () => {
      setup({ suppliers: createSuppliers() });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      const headers = Array.from(el.querySelectorAll('th.mat-mdc-header-cell')).map((cell) =>
        cell.textContent?.trim(),
      );
      expect(headers).toEqual(['Proveedor', 'Costo de compra', 'Principal', 'Estado', 'Acciones']);
    });

    it('should render the loading skeleton while the request is in flight', () => {
      setup({ suppliersPending: true });
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      expect(component.loading()).toBe(true);
      expect(el.querySelector('.loading-skeleton')).toBeTruthy();
      expect(el.querySelector('.table-card')).toBeNull();
    });

    it('should render the empty state when there are no suppliers', async () => {
      setup({ suppliers: [] });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.empty-state')).toBeTruthy();
      expect(el.querySelector('.empty-title')?.textContent?.trim()).toBe(
        'No hay proveedores asignados',
      );
      expect(el.querySelector('.empty-subtitle')?.textContent?.trim()).toBe(
        'Clic en "Agregar proveedor" para asignar el primero a este producto',
      );
    });
  });

  describe('Retry on error', () => {
    it('should reload the suppliers when retrying after an error', async () => {
      setup({ suppliersError: true });
      await settle();
      expect(component.error()).toBeTruthy();

      serviceMock.getSuppliers.mockReturnValue(of(createSuppliers()));
      component.onRetry();
      await settle();

      expect(serviceMock.getSuppliers).toHaveBeenCalledTimes(2);
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.table-card')).toBeTruthy();
      expect(el.querySelector('.error-state')).toBeNull();
    });
  });

  describe('Error state', () => {
    it('should render the error state when suppliers fail', async () => {
      setup({ suppliersError: true });
      await settle();

      expect(() => component.suppliers()).not.toThrow();
      expect(component.suppliers()).toBeUndefined();
      expect(component.error()).toBeTruthy();

      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.error-state')).toBeTruthy();
      expect(el.querySelector('.loading-skeleton')).toBeNull();
      expect(el.textContent).toContain('Tu sesión expiró');
    });
  });
});
