import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CountriesEdit } from './countries-edit';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../data/company-country.service';
import { CountryService } from '@features/countries/data/country.service';
import { CompanyCountry, CountrySaveEvent, Country } from '../../models/country.models';
import { Company, Page } from '../../../companies/models/company.models';
  const mockAssignedCountries: CompanyCountry[] = [
    {
      id: "cc-1",
      companyId: "company-1",
      countryId: "c1",
      countryCode: "MX",
      countryName: "Mexico",
      localAlias: "Sucursal CDMX",
      createdAt: "2024-01-01",
      updatedAt: "2024-01-01",
    },
    {
      id: "cc-2",
      companyId: "company-1",
      countryId: "c2",
      countryCode: "US",
      countryName: "United States",
      localAlias: null,
      createdAt: "2024-01-01",
      updatedAt: "2024-01-01",
    },
  ];

describe('CountriesEdit', () => {
  let component: CountriesEdit;
  let fixture: ComponentFixture<CountriesEdit>;
  let companyCountryServiceMock: MockCompanyCountryService;

  let routeMock: {
    snapshot: {
      paramMap: { get: ReturnType<typeof vi.fn> };
      queryParamMap: { get: ReturnType<typeof vi.fn> };
    };
  };
  let routerMock: {
    navigate: ReturnType<typeof vi.fn>;
  };
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

  const mockCompanies: Company[] = [
    {
      id: 'company-1',
      companyKey: 'COMP001',
      companyName: 'Test Company',
      tipoPersonaId: 1,
      razonSocial: 'Test Company SA',
      rfc: 'RFC123456789',
      email: 'test@test.com',
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

  const mockCompanyCountry: CompanyCountry = {
    id: 'cc-1',
    companyId: 'company-1',
    countryId: 'c1',
    countryCode: 'MX',
    countryName: 'Mexico',
    localAlias: 'Sucursal CDMX',
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  const mockCatalogCountries: Country[] = [
    {
      id: 'c1',
      countryCode: 'MX',
      countryName: 'Mexico',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  beforeEach(async () => {
    // Reset history.state para cada test (no depender del state de tests anteriores)
    window.history.replaceState(null, '');

    routeMock = {
      snapshot: {
        paramMap: { get: vi.fn() },
        queryParamMap: { get: vi.fn().mockReturnValue(null) },
      },
    };
    routerMock = { navigate: vi.fn() };
    companyCountryServiceMock = new MockCompanyCountryService();

    await TestBed.configureTestingModule({
      imports: [CountriesEdit, NoopAnimationsModule],
      providers: [
        {
          provide: CompanyService,
          useValue: {
            getCompanies: vi.fn().mockReturnValue(of(mockCompaniesPage)),
          },
        },
        {
          provide: CompanyCountryService,
          useValue: companyCountryServiceMock,
        },
        {
          provide: CountryService,
          useValue: {
            countries: signal(mockCatalogCountries).asReadonly(),
            getCountries: vi.fn().mockReturnValue(of(mockCatalogCountries)),
          },
        },
        { provide: ActivatedRoute, useValue: routeMock },
        { provide: Router, useValue: routerMock },
      ],
    }).compileComponents();
  });

  /** Helper: create component with current mocks and detect changes. */
  function createComponent(): { component: CountriesEdit; fixture: ComponentFixture<CountriesEdit> } {
    const f = TestBed.createComponent(CountriesEdit);
    const c = f.componentInstance;
    f.detectChanges();
    return { component: c, fixture: f };
  }

  // ─── Creation ──────────────────────────────────────────

  it('should create', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();
    expect(component).toBeTruthy();
  });

  // ─── Create mode ───────────────────────────────────────

  it('should be in create mode when no id param is present', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    expect(component.isEditMode()).toBe(false);
    expect(component.countryId()).toBeNull();
  });

  it('should not navigate away in create mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    createComponent();

    expect(routerMock.navigate).not.toHaveBeenCalled();
  });

  // ─── Edit mode ─────────────────────────────────────────

  it('should detect edit mode from route param', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('cc-1');
    window.history.replaceState({ cc: mockCompanyCountry }, '');
    const { component } = createComponent();

    expect(component.isEditMode()).toBe(true);
    expect(component.countryId()).toBe('cc-1');
  });

  it('should restore CompanyCountry from history.state in edit mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('cc-1');
    window.history.replaceState({ cc: mockCompanyCountry }, '');
    const { component } = createComponent();

    expect(component.ccToEdit()).toEqual(mockCompanyCountry);
  });

  it('should redirect to countries list when edit mode has no history state', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('cc-1');
    window.history.replaceState(null, '');
    createComponent();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/countries']);
  });

  // ─── Save: addCountry (create mode) ────────────────────

  it('should call addCountry when saving in create mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const event: CountrySaveEvent = {
      companyId: 'company-1',
      request: { countryCode: 'US', localAlias: 'Test' },
    };
    component.onSaveCountry(event);

    expect(companyCountryServiceMock.addCountry).toHaveBeenCalledWith(
      'company-1',
      event.request,
    );
    expect(companyCountryServiceMock.updateCountry).not.toHaveBeenCalled();
  });

  it('should navigate to countries list after successful create', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const event: CountrySaveEvent = {
      companyId: 'company-1',
      request: { countryCode: 'US', localAlias: 'Test' },
    };
    component.onSaveCountry(event);

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/countries'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  // ─── Save: updateCountry (edit mode) ────────────────────

  it('should call updateCountry when saving in edit mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('cc-1');
    window.history.replaceState({ cc: mockCompanyCountry }, '');
    const { component } = createComponent();

    const event: CountrySaveEvent = {
      companyId: 'company-1',
      request: { countryCode: 'MX', localAlias: 'Updated' },
      countryId: 'cc-1',
    };
    component.onSaveCountry(event);

    expect(companyCountryServiceMock.updateCountry).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      event.request,
    );
    expect(companyCountryServiceMock.addCountry).not.toHaveBeenCalled();
  });

  it('should navigate to countries list after successful update', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('cc-1');
    window.history.replaceState({ cc: mockCompanyCountry }, '');
    const { component } = createComponent();

    const event: CountrySaveEvent = {
      companyId: 'company-1',
      request: { countryCode: 'MX', localAlias: 'Updated' },
      countryId: 'cc-1',
    };
    component.onSaveCountry(event);

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/countries'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  // ─── Cancel navigation ─────────────────────────────────

  it('should navigate to countries list on cancel', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    component.onCancelForm();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/countries']);
  });

  // ─── Server errors ─────────────────────────────────────

  it('should set serverErrors when save fails with field-level errors', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const httpError = new HttpErrorResponse({
      error: {
        status: 400,
        message: 'Error de validación',
        errors: { localAlias: 'Alias ya existe' },
        path: '/api/companies/company-1/countries',
        timestamp: '2026-05-27T20:00:00Z',
        correlationId: 'abc-123',
      },
      status: 400,
      statusText: 'Bad Request',
    });
    companyCountryServiceMock.addCountry.mockReturnValue(throwError(() => httpError));

    const event: CountrySaveEvent = {
      companyId: 'company-1',
      request: { countryCode: 'MX', localAlias: 'Duplicated' },
    };
    component.onSaveCountry(event);

    expect(component.serverErrors()).toEqual({ localAlias: 'Alias ya existe' });
  });
});
