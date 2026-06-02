import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ProductSupplierList } from './product-supplier-list';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { ProductSupplier } from '../../models/product-supplier.models';
import { ActivatedRoute, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';

describe('ProductSupplierList', () => {
  let component: ProductSupplierList;
  let fixture: ComponentFixture<ProductSupplierList>;
  let serviceMock: Partial<Record<keyof ProductSupplierService, unknown>>;
  let routerMock: Partial<Router>;
  let dialogMock: { open: ReturnType<typeof vi.fn> };

  const mockProductId = 'prod-1';

  function createSuppliers(overrides: Partial<ProductSupplier>[] = []): ProductSupplier[] {
    if (overrides.length > 0) return overrides.map((o, i) => createSupplier(i, o));
    return [createSupplier(0), createSupplier(1)];
  }

  function createSupplier(index: number, overrides: Partial<ProductSupplier> = {}): ProductSupplier {
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

  function setup(options: { productId?: string | null; suppliers?: ProductSupplier[] } = {}) {
    const productId = options.productId !== undefined ? options.productId : mockProductId;
    const suppliers = options.suppliers ?? createSuppliers();

    serviceMock = {
      getSuppliers: vi.fn().mockReturnValue(of(suppliers)),
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
        { provide: ProductSupplierService, useValue: serviceMock },
        { provide: Router, useValue: routerMock },
        { provide: MatDialog, useValue: dialogMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'id' ? productId : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductSupplierList);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  describe('Component creation', () => {
    beforeEach(() => setup());

    it('should create', () => {
      expect(component).toBeTruthy();
    });

    it('should read productId from route', () => {
      expect(component.productId()).toBe(mockProductId);
    });

    it('should fetch suppliers on init', () => {
      expect(serviceMock.getSuppliers).toHaveBeenCalledWith(mockProductId);
    });
  });

  describe('Navigation', () => {
    beforeEach(() => setup());

    it('should navigate to create on addSupplier', () => {
      component.addSupplier();
      expect(routerMock.navigate).toHaveBeenCalledWith([
        '/products/edit',
        mockProductId,
        'suppliers',
        'create',
      ]);
    });

    it('should navigate to edit on editSupplier', () => {
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
    beforeEach(() => setup());

    it('should open dialog and not delete when cancelled', () => {
      component.confirmDelete('ps-1', 'Supplier 0');

      expect(dialogMock.open).toHaveBeenCalled();
      expect(serviceMock.removeSupplier).not.toHaveBeenCalled();
    });

    it('should open dialog and delete when confirmed', () => {
      // Override the dialog to return true AFTER setup
      dialogMock.open = vi.fn().mockReturnValue({
        afterClosed: () => of(true),
      });

      component.confirmDelete('ps-1', 'Supplier 0');

      expect(dialogMock.open).toHaveBeenCalled();
      expect(serviceMock.removeSupplier).toHaveBeenCalledWith(mockProductId, 'ps-1');
    });
  });

  describe('Display states', () => {
    it('should display suppliers in table when data exists', async () => {
      setup({ suppliers: createSuppliers() });
      await fixture.whenStable();
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      const table = el.querySelector('table');
      expect(table).toBeTruthy();

      expect(el.textContent).toContain('Supplier 0');
      expect(el.textContent).toContain('Supplier 1');

      const mainChip = el.querySelector('.main-chip');
      expect(mainChip).toBeTruthy();
      expect(mainChip?.textContent).toContain('Main');
    });

    it('should show empty state when no suppliers', async () => {
      setup({ suppliers: [] });
      await fixture.whenStable();
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.empty-state')).toBeTruthy();
      expect(el.textContent).toContain('No suppliers assigned');
    });
  });

  describe('Missing productId', () => {
    it('should have null productId from route', () => {
      setup({ productId: null });
      expect(component.productId()).toBeNull();
    });

    it('should redirect when productId is null by calling navigate', async () => {
      setup({ productId: null });

      // Angular effects run asynchronously — cycle change detection and wait
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      await fixture.whenStable();

      // The effect in constructor checks productId() and redirects
      expect(routerMock.navigate).toHaveBeenCalled();
    });
  });

  describe('Retry on error', () => {
    it('should reload resource on retry', () => {
      setup();

      const reloadSpy = vi.spyOn(component.suppliersResource, 'reload');
      component.onRetry();
      expect(reloadSpy).toHaveBeenCalled();
    });
  });
});
