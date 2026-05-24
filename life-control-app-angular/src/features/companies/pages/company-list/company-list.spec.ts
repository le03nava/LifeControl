import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CompanyList } from './company-list';
import { CompanyService } from '@features/companies/data/company.service';
import { Company, Page } from '@features/companies/models/company.models';
import { of } from 'rxjs';

type CompanyServiceMock = {
  getCompanies: ReturnType<typeof vi.fn>;
  deleteCompany: ReturnType<typeof vi.fn>;
};

describe('CompanyList', () => {
  let component: CompanyList;
  let fixture: ComponentFixture<CompanyList>;
  let companyService: CompanyServiceMock;
  let router: Router;

  const mockCompanies: Company[] = [
    { id: '1', companyId: 1, companyName: 'Alpha Corp', tipoPersonaId: 1, razonSocial: 'Razon Alpha', rfc: 'RFC0000000001', email: 'alpha@test.com', phone: '5551112222', enabled: true, createdAt: '', updatedAt: '' },
    { id: '2', companyId: 2, companyName: 'Beta Inc', tipoPersonaId: 1, razonSocial: 'Razon Beta', rfc: 'RFC0000000002', email: 'beta@test.com', phone: '5553334444', enabled: true, createdAt: '', updatedAt: '' },
    { id: '3', companyId: 3, companyName: 'Gamma SA', tipoPersonaId: 1, razonSocial: 'Razon Gamma', rfc: 'RFC0000000003', email: 'gamma@test.com', phone: '5555556666', enabled: false, createdAt: '', updatedAt: '' },
  ];

  const createMockPage = (companies: Company[], page: number = 0, size: number = 12): Page<Company> => ({
    content: companies,
    totalElements: companies.length,
    totalPages: Math.ceil(companies.length / size),
    size,
    number: page,
    first: page === 0,
    last: (page + 1) * size >= companies.length,
    empty: companies.length === 0,
  });

  beforeEach(async () => {
    companyService = {
      getCompanies: vi.fn().mockReturnValue(of(createMockPage(mockCompanies))),
      deleteCompany: vi.fn().mockReturnValue(of(void 0)),
    };

    await TestBed.configureTestingModule({
      imports: [CompanyList, MatIconModule, MatPaginatorModule, MatDialogModule, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: CompanyService, useValue: companyService },
        { provide: MatDialog, useValue: { open: vi.fn().mockReturnValue({ afterClosed: vi.fn().mockReturnValue(of(false)) }) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CompanyList);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load companies on creation via rxResource', () => {
    fixture.detectChanges();
    expect(companyService.getCompanies).toHaveBeenCalledWith(0, 12, undefined);
  });

  it('should navigate to edit page', () => {
    component.editCompany('123');
    expect(router.navigate).toHaveBeenCalledWith(['/companies/edit/123']);
  });

  it('should open delete dialog', () => {
    const dialogOpen = vi.spyOn(TestBed.inject(MatDialog), 'open');
    component.confirmDelete({ id: '1', name: 'Alpha Corp' });
    expect(dialogOpen).toHaveBeenCalled();
  });

  it('should delete company when dialog confirms', () => {
    const dialogRef = { afterClosed: vi.fn().mockReturnValue(of(true)) };
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue(dialogRef as any);
    component.confirmDelete({ id: '1', name: 'Alpha Corp' });
    expect(companyService.deleteCompany).toHaveBeenCalledWith('1');
  });

  it('should NOT delete company when dialog cancels', () => {
    const dialogRef = { afterClosed: vi.fn().mockReturnValue(of(false)) };
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue(dialogRef as any);
    component.confirmDelete({ id: '1', name: 'Alpha Corp' });
    expect(companyService.deleteCompany).not.toHaveBeenCalled();
  });

  it('should clear search query', () => {
    component.searchQuery.set('alpha');
    component.clearSearch();
    expect(component.searchQuery()).toBe('');
  });

  it('should update page index and size on page change', () => {
    component.onPageChange({ pageIndex: 2, pageSize: 24 });
    expect(component.pageIndex()).toBe(2);
    expect(component.pageSize()).toBe(24);
  });

  it('should reset page to 0 when search changes (debounced)', () => {
    vi.useFakeTimers();
    // Start on page 2
    component.pageIndex.set(2);
    expect(component.pageIndex()).toBe(2);

    // Type a search query
    component.searchQuery.set('Alpha');

    // Trigger effect that creates the setTimeout
    fixture.detectChanges();

    // Fast-forward past debounce (300ms)
    vi.advanceTimersByTime(300);

    // Page should reset to 0
    expect(component.pageIndex()).toBe(0);

    // Trigger change detection so rxResource re-fetches with search param
    fixture.detectChanges();

    // API should be called with search param
    expect(companyService.getCompanies).toHaveBeenCalledWith(0, 12, 'Alpha');

    vi.useRealTimers();
  });

  it('should load companies with correct params', () => {
    vi.useFakeTimers();
    fixture.detectChanges();
    vi.advanceTimersByTime(0);

    expect(companyService.getCompanies).toHaveBeenCalledWith(0, 12, undefined);

    // Change page
    component.onPageChange({ pageIndex: 1, pageSize: 12 });
    fixture.detectChanges();
    vi.advanceTimersByTime(0);

    expect(companyService.getCompanies).toHaveBeenCalledWith(1, 12, undefined);

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
      window.matchMedia = vi.fn().mockReturnValue(mql as any) as unknown as typeof window.matchMedia;
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
      const f = TestBed.createComponent(CompanyList);
      f.detectChanges();
      expect(f.componentInstance.pageSizeOptions()).toEqual([6, 12, 24, 48]);
    });

    it('should return mobile pageSizeOptions when isMobile is true', () => {
      setupMatchMedia(true);
      const f = TestBed.createComponent(CompanyList);
      f.detectChanges();
      expect(f.componentInstance.isMobile()).toBe(true);
      expect(f.componentInstance.pageSizeOptions()).toEqual([6, 12]);
    });

    it('should hide first/last buttons on mobile', () => {
      setupMatchMedia(true);
      const f = TestBed.createComponent(CompanyList);
      f.detectChanges();

      const paginatorEl = f.nativeElement.querySelector('.pagination-section');
      // With showFirstLastButtons=false, the first/last nav buttons should not render
      expect(f.componentInstance.isMobile()).toBe(true);
    });

    it('should update isMobile on matchMedia change event', () => {
      const { listeners } = setupMatchMedia(false);
      const f = TestBed.createComponent(CompanyList);
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
