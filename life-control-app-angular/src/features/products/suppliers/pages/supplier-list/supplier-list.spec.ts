import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { SupplierList } from './supplier-list';
import { SupplierService } from '../../data/supplier.service';
import { Supplier, Page } from '../../models/supplier.models';

interface SupplierServiceMock {
  getSuppliers: ReturnType<typeof vi.fn>;
  deleteSupplier: ReturnType<typeof vi.fn>;
}

describe('SupplierList', () => {
  let component: SupplierList;
  let fixture: ComponentFixture<SupplierList>;
  let supplierService: SupplierServiceMock;

  const mockSuppliers: Supplier[] = [
    {
      id: '1',
      supplierName: 'Acme Supplies',
      razonSocial: 'Acme Supplies SA',
      rfc: 'RFC0000000001',
      email: 'acme@test.com',
      phoneNumber: '5551112222',
      enabled: true,
      createdAt: '',
      updatedAt: '',
    },
  ];

  const createMockPage = (suppliers: Supplier[], page = 0, size = 12): Page<Supplier> => ({
    content: suppliers,
    totalElements: suppliers.length,
    totalPages: Math.ceil(suppliers.length / size),
    size,
    number: page,
    first: page === 0,
    last: (page + 1) * size >= suppliers.length,
    empty: suppliers.length === 0,
  });

  beforeEach(async () => {
    supplierService = {
      getSuppliers: vi.fn().mockReturnValue(of(createMockPage(mockSuppliers))),
      deleteSupplier: vi.fn().mockReturnValue(of(void 0)),
    };

    await TestBed.configureTestingModule({
      imports: [SupplierList, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: SupplierService, useValue: supplierService },
        {
          provide: MatDialog,
          useValue: {
            open: vi.fn().mockReturnValue({ afterClosed: vi.fn().mockReturnValue(of(false)) }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SupplierList);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should load suppliers on creation via rxResource', () => {
    fixture.detectChanges();
    expect(supplierService.getSuppliers).toHaveBeenCalledWith(0, 12, undefined);
  });

  it('should render suppliers when data loads', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Acme Supplies');
  });

  it('should not throw and render the error state when the API fails', async () => {
    supplierService.getSuppliers = vi
      .fn()
      .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(() => component.suppliers()).not.toThrow();
    expect(component.suppliers()).toBeUndefined();
    expect(component.error()).toBeTruthy();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.error-state')).toBeTruthy();
    expect(el.querySelector('.loading-skeleton')).toBeNull();
    expect(el.textContent).toContain('Tu sesión expiró');
  });

  describe('responsive paginator (isMobile signal)', () => {
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

    it('should default to desktop pageSizeOptions', () => {
      setupMatchMedia(false);
      const f = TestBed.createComponent(SupplierList);
      f.detectChanges();
      expect(f.componentInstance.pageSizeOptions()).toEqual([6, 12, 24, 48]);
    });

    it('should return mobile pageSizeOptions when isMobile is true', () => {
      setupMatchMedia(true);
      const f = TestBed.createComponent(SupplierList);
      f.detectChanges();
      expect(f.componentInstance.isMobile()).toBe(true);
      expect(f.componentInstance.pageSizeOptions()).toEqual([6, 12]);
    });

    it('should update isMobile on matchMedia change event', () => {
      const { listeners } = setupMatchMedia(false);
      const f = TestBed.createComponent(SupplierList);
      f.detectChanges();
      expect(f.componentInstance.isMobile()).toBe(false);

      // Simulate viewport resize to mobile
      if (listeners['change']) {
        listeners['change']({ matches: true } as MediaQueryListEvent);
      }
      expect(f.componentInstance.isMobile()).toBe(true);
    });
  });
});
