import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { SupplierList } from './supplier-list';
import { SupplierService } from '../../data/supplier.service';
import { Supplier, Page } from '../../models/supplier.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanySelectorOption } from '@features/companies/companies/models/company.models';

interface SupplierServiceMock {
  getSuppliers: ReturnType<typeof vi.fn>;
  deleteSupplier: ReturnType<typeof vi.fn>;
}

interface CompanyServiceMock {
  getCompanySelector: ReturnType<typeof vi.fn>;
}

describe('SupplierList', () => {
  let component: SupplierList;
  let fixture: ComponentFixture<SupplierList>;
  let supplierService: SupplierServiceMock;
  let companyService: CompanyServiceMock;
  let router: Router;

  /**
   * The company whose catalog the list shows, reached the way an operator reaches it: through
   * `?companyId=`. The catalog is company-scoped on the wire (issue #672), so this filter is the
   * screen's only source of the company.
   */
  const companyId = 'company-1';

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
      companyId,
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

  function createCompanyPage(): Page<CompanySelectorOption> {
    return {
      content: [
        { id: companyId, companyKey: 'lc', companyName: 'Life Control' },
        { id: 'company-2', companyKey: 'ac', companyName: 'Otra Empresa' },
      ],
      totalElements: 2,
      totalPages: 1,
      size: 2,
      number: 0,
      first: true,
      last: true,
      empty: false,
    };
  }

  function setup(options: { companyId?: string | null } = {}): void {
    const scopedCompanyId = options.companyId === undefined ? companyId : options.companyId;

    supplierService = {
      getSuppliers: vi.fn().mockReturnValue(of(createMockPage(mockSuppliers))),
      deleteSupplier: vi.fn().mockReturnValue(of(void 0)),
    };
    companyService = {
      getCompanySelector: vi.fn().mockReturnValue(of(createCompanyPage())),
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SupplierList],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            // Only the query parameter answers: a read that still looked for a `:companyId` segment
            // could not produce a company here, which is what makes these assertions pin the source.
            snapshot: {
              queryParamMap: convertToParamMap(
                scopedCompanyId ? { companyId: scopedCompanyId } : {},
              ),
            },
          },
        },
        { provide: SupplierService, useValue: supplierService },
        { provide: CompanyService, useValue: companyService },
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
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  }

  /** Settles the resource read and the render; the list's effects need more than one flush. */
  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i += 1) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  function bodyText(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  beforeEach(() => {
    setup();
  });

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should load suppliers on creation via rxResource', () => {
    fixture.detectChanges();
    expect(supplierService.getSuppliers).toHaveBeenCalledWith(companyId, 0, 12, undefined);
  });

  it('should render suppliers when data loads', async () => {
    await settle();

    expect(bodyText()).toContain('Acme Supplies');
  });

  it('should not throw and render the error state when the API fails', async () => {
    supplierService.getSuppliers = vi
      .fn()
      .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

    await settle();

    expect(() => component.suppliers()).not.toThrow();
    expect(component.suppliers()).toBeUndefined();
    expect(component.error()).toBeTruthy();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.error-state')).toBeTruthy();
    expect(el.querySelector('.loading-skeleton')).toBeNull();
    expect(el.textContent).toContain('Tu sesión expiró');
  });

  describe('the Empresa filter (S2)', () => {
    it('should offer the claim-authorized company selector, as the products list does', async () => {
      await settle();

      expect(companyService.getCompanySelector).toHaveBeenCalledWith(0, 1000);
      expect(fixture.nativeElement.querySelectorAll('mat-select').length).toBe(1);
    });

    it('should ask the API for the selected company', async () => {
      await settle();

      // The whole point of the unit: the request is scoped to the company the screen shows.
      expect(supplierService.getSuppliers).toHaveBeenCalledWith(companyId, 0, 12, undefined);
    });

    it('should reproduce the list from a shared ?companyId= link', async () => {
      setup({ companyId: 'company-9' });
      await settle();

      expect(component.selectedCompanyId()).toBe('company-9');
      expect(supplierService.getSuppliers).toHaveBeenCalledWith('company-9', 0, 12, undefined);
      expect(bodyText()).not.toContain('Seleccioná una empresa');
    });

    it('should issue no request and ask for a company when the link names none', async () => {
      setup({ companyId: null });
      await settle();

      // There is no cross-company read: an empty grid here would claim that a company nobody named
      // has no suppliers.
      expect(supplierService.getSuppliers).not.toHaveBeenCalled();
      expect(bodyText()).toContain('Seleccioná una empresa');
    });

    it('should issue the scoped request and keep the choice in the URL', async () => {
      setup({ companyId: null });
      await settle();
      expect(supplierService.getSuppliers).not.toHaveBeenCalled();

      component.onCompanyChange('company-2');
      await settle();

      expect(router.navigate).toHaveBeenCalledWith([], {
        relativeTo: TestBed.inject(ActivatedRoute),
        queryParams: { companyId: 'company-2' },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
      expect(supplierService.getSuppliers).toHaveBeenCalledWith('company-2', 0, 12, undefined);
    });

    it('should drop the previous company’s page and search when the filter changes', async () => {
      await settle();
      component.onPageChange({ pageIndex: 2, pageSize: 12 });
      component.searchQuery.set('acme');
      await settle();

      component.onCompanyChange('company-2');

      expect(component.pageIndex()).toBe(0);
      expect(component.searchQuery()).toBe('');
    });
  });

  describe('carrying the company to the other supplier screens', () => {
    it('should open the create form for the selected company', () => {
      component.createSupplier();

      expect(router.navigate).toHaveBeenCalledWith(['/products/suppliers/create'], {
        queryParams: { companyId },
      });
    });

    it('should open the editor for the selected company', () => {
      component.editSupplier('1');

      expect(router.navigate).toHaveBeenCalledWith(['/products/suppliers/edit', '1'], {
        queryParams: { companyId },
      });
    });

    it('should offer no create action while no company is selected', async () => {
      setup({ companyId: null });
      await settle();

      const button = (fixture.nativeElement as HTMLElement).querySelector(
        'button[mat-flat-button]',
      ) as HTMLButtonElement | null;

      expect(button).toBeTruthy();
      expect(button!.disabled).toBe(true);
    });
  });

  it('should delete the supplier of the selected company when the dialog confirms', () => {
    const dialogRef = { afterClosed: vi.fn().mockReturnValue(of(true)) };
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue(
      dialogRef as unknown as ReturnType<MatDialog['open']>,
    );

    component.confirmDelete({ id: '1', name: 'Acme Supplies' });

    expect(supplierService.deleteSupplier).toHaveBeenCalledWith(companyId, '1');
  });

  it('should NOT delete a supplier when the dialog cancels', () => {
    component.confirmDelete({ id: '1', name: 'Acme Supplies' });
    expect(supplierService.deleteSupplier).not.toHaveBeenCalled();
  });

  it('should clear search query', () => {
    component.searchQuery.set('acme');
    component.clearSearch();
    expect(component.searchQuery()).toBe('');
  });

  it('should update page index and size on page change', () => {
    component.onPageChange({ pageIndex: 2, pageSize: 24 });
    expect(component.pageIndex()).toBe(2);
    expect(component.pageSize()).toBe(24);
  });

  it('should reset page to 0 and re-read with the debounced search', () => {
    vi.useFakeTimers();
    fixture.detectChanges();
    component.onPageChange({ pageIndex: 2, pageSize: 12 });

    component.searchQuery.set('acme');
    fixture.detectChanges();
    vi.advanceTimersByTime(300);
    fixture.detectChanges();

    expect(component.pageIndex()).toBe(0);
    expect(supplierService.getSuppliers).toHaveBeenCalledWith(companyId, 0, 12, 'acme');
    vi.useRealTimers();
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
