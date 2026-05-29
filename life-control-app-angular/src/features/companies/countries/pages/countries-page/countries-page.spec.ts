import { By } from '@angular/platform-browser';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { CountriesPage } from './countries-page';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../data/company-country.service';
import { CompanyCountry } from '../../models/country.models';
import { Company, Page } from '../../../companies/models/company.models';
import { CountriesCard } from '../../components/countries-card/countries-card';

describe('CountriesPage', () => {
  let component: CountriesPage;
  let fixture: ComponentFixture<CountriesPage>;

  const mockCompanies: Company[] = [
    {
      id: 'company-1',
      companyKey: 'COMP001',
      companyName: 'Test Company One',
      tipoPersonaId: 1,
      razonSocial: 'Test Company One SA',
      rfc: 'ABC123456789',
      email: 'test@company1.com',
      phone: '+521234567890',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  const mockCompaniesPage: Page<Company> = {
    content: mockCompanies,
    totalElements: 1,
    totalPages: 1,
    size: 1000,
    number: 0,
    first: true,
    last: true,
    empty: false,
  };

  const mockAssignedCountries: CompanyCountry[] = [
    {
      id: 'cc-1',
      companyId: 'company-1',
      countryId: 'c1',
      countryCode: 'MX',
      countryName: 'Mexico',
      localAlias: 'Sucursal CDMX',
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
    {
      id: 'cc-2',
      companyId: 'company-1',
      countryId: 'c2',
      countryCode: 'US',
      countryName: 'United States',
      localAlias: null,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  class MockCompanyCountryService {
    private _assignedCountries = signal<CompanyCountry[]>([]);
    _loading = signal(false);
    _error = signal<string | null>(null);

    readonly assignedCountries = this._assignedCountries.asReadonly();
    readonly loading = this._loading.asReadonly();
    readonly error = this._error.asReadonly();

    getCountries = vi.fn().mockImplementation(() => {
      this._assignedCountries.set(mockAssignedCountries);
      return of(mockAssignedCountries);
    });
    addCountry = vi.fn().mockReturnValue(of(mockAssignedCountries[0]));
    updateCountry = vi.fn().mockReturnValue(of(mockAssignedCountries[0]));
    removeCountry = vi.fn().mockReturnValue(of(undefined));
  }

  class MockCompanyService {
    getCompanies = vi.fn().mockReturnValue(of(mockCompaniesPage));
  }

  let routerMock: { navigate: ReturnType<typeof vi.fn> };
  let routeMock: {
    snapshot: {
      queryParamMap: { get: ReturnType<typeof vi.fn> };
    };
  };

  beforeEach(async () => {
    routerMock = { navigate: vi.fn() };
    routeMock = {
      snapshot: {
        queryParamMap: {
          get: vi.fn().mockReturnValue(null),
        },
      },
    };

    await TestBed.configureTestingModule({
      imports: [CountriesPage, NoopAnimationsModule],
      providers: [
        { provide: CompanyService, useClass: MockCompanyService },
        { provide: CompanyCountryService, useClass: MockCompanyCountryService },
        { provide: Router, useValue: routerMock },
        { provide: ActivatedRoute, useValue: routeMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CountriesPage);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ─── Basic creation and initial state ────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have null selectedCompanyId initially', () => {
    expect(component.selectedCompanyId()).toBeNull();
  });

  // ─── onCompanyChange ─────────────────────────────────────────

  it('should set selectedCompanyId on company change', () => {
    component.onCompanyChange('company-1');
    expect(component.selectedCompanyId()).toBe('company-1');
  });

  it('should clear selectedCompanyId when empty string is passed to onCompanyChange', () => {
    component.onCompanyChange('company-1');
    expect(component.selectedCompanyId()).toBe('company-1');

    component.onCompanyChange('');
    expect(component.selectedCompanyId()).toBe('');
  });

  it('should load countries on company change', () => {
    const companyCountryService = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;

    component.onCompanyChange('company-1');

    expect(companyCountryService.getCountries).toHaveBeenCalledWith('company-1');
  });

  it('should NOT load countries when empty string', () => {
    const companyCountryService = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;

    component.onCompanyChange('');

    expect(companyCountryService.getCountries).not.toHaveBeenCalled();
  });

  // ─── onCreateCountry ─────────────────────────────────────────

  it('should navigate to create with correct queryParams', () => {
    component.selectedCompanyId.set('company-1');
    component.onCreateCountry();

    expect(routerMock.navigate).toHaveBeenCalledWith(
      ['/companies/countries/create'],
      { queryParams: { companyId: 'company-1' } },
    );
  });

  it('should NOT navigate when no company is selected', () => {
    component.onCreateCountry();

    expect(routerMock.navigate).not.toHaveBeenCalled();
  });

  // ─── onCardEditCountry ───────────────────────────────────────

  it('should navigate to edit with state', () => {
    component.onCompanyChange('company-1');
    component.onCardEditCountry('cc-1');

    expect(routerMock.navigate).toHaveBeenCalledWith(
      ['/companies/countries/edit', 'cc-1'],
      { state: { cc: mockAssignedCountries[0] } },
    );
  });

  it('should NOT navigate when cc is not found', () => {
    component.onCompanyChange('company-1');
    component.onCardEditCountry('non-existent');

    expect(routerMock.navigate).not.toHaveBeenCalled();
  });

  // ─── onDeleteCountry ─────────────────────────────────────────

  it('should call removeCountry on delete', () => {
    const companyCountryService = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;

    component.selectedCompanyId.set('company-1');
    component.onDeleteCountry('cc-1');

    expect(companyCountryService.removeCountry).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
    );
  });

  it('should NOT call removeCountry when no company is selected', () => {
    const companyCountryService = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;

    component.onDeleteCountry('cc-1');

    expect(companyCountryService.removeCountry).not.toHaveBeenCalled();
  });

  // ─── Rendering ───────────────────────────────────────────────

  it('should show empty prompt when no company is selected', () => {
    const emptyPrompt = fixture.nativeElement.querySelector('.empty-prompt');
    expect(emptyPrompt).toBeTruthy();
    expect(emptyPrompt.textContent).toContain('Seleccioná una empresa');
  });

  it('should render app-countries-card when countries are loaded', () => {
    component.onCompanyChange('company-1');
    fixture.detectChanges();

    const cards = fixture.nativeElement.querySelectorAll('app-countries-card');
    expect(cards.length).toBe(2);
  });

  it('should pass the correct country data to each card', () => {
    component.onCompanyChange('company-1');
    fixture.detectChanges();

    const cards = fixture.debugElement.queryAll(By.directive(CountriesCard));
    expect(cards.length).toBe(2);
    expect(cards[0].componentInstance.cc()).toEqual(mockAssignedCountries[0]);
    expect(cards[1].componentInstance.cc()).toEqual(mockAssignedCountries[1]);
  });

  it('should display error message when error is set', () => {
    component.onCompanyChange('company-1');
    const svc = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;
    svc._error.set('Error al cargar los países');
    fixture.detectChanges();

    const errorEl = fixture.nativeElement.querySelector('.error-state');
    expect(errorEl).toBeTruthy();
    expect(errorEl.textContent).toContain('Error al cargar los países');
  });

  it('should display loading text when loading', () => {
    component.onCompanyChange('company-1');
    const svc = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;
    svc._loading.set(true);
    fixture.detectChanges();

    const loadingEl = fixture.nativeElement.querySelector('.loading-text');
    expect(loadingEl).toBeTruthy();
    expect(loadingEl.textContent).toContain('Cargando países');
  });

  it('should show empty message when no countries and not loading', () => {
    component.onCompanyChange('company-1');
    const svc = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;
    // Accessing private property for test - using type assertion
    (svc as any)._assignedCountries.set([]);
    fixture.detectChanges();

    const emptyEl = fixture.nativeElement.querySelector('.empty-state');
    expect(emptyEl).toBeTruthy();
    expect(emptyEl.textContent).toContain('No hay países asignados');
  });

  it('should NOT show empty message when loading even if no countries', () => {
    component.onCompanyChange('company-1');
    const svc = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;
    // Accessing private property for test - using type assertion
    (svc as any)._assignedCountries.set([]);
    svc._loading.set(true);
    fixture.detectChanges();

    const emptyEl = fixture.nativeElement.querySelector('.empty-state');
    expect(emptyEl).toBeNull();
  });
});
