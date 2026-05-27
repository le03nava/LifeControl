import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { CountriesPage } from './countries-page';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../data/company-country.service';
import { CountryService } from '@features/countries/data';
import {
  Country,
  CompanyCountry,
  CompanyCountryRequest,
} from '../../models/country.models';
import { Company, Page } from '../../../companies/models/company.models';

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
  ];

  const mockCountries: Country[] = [
    {
      id: 'c1',
      countryCode: 'MX',
      countryName: 'Mexico',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  // Create mock services using real classes that extend the originals
  class MockCompanyCountryService {
    private _assignedCountries = signal<CompanyCountry[]>([]);
    private _loading = signal(false);
    private _error = signal<string | null>(null);

    assignedCountries = this._assignedCountries.asReadonly();
    loading = this._loading.asReadonly();
    error = this._error.asReadonly();

    getCountries = vi.fn().mockReturnValue(of(mockAssignedCountries));
    addCountry = vi.fn().mockReturnValue(
      of({
        id: 'cc-new',
        companyId: 'company-1',
        countryId: 'c2',
        countryCode: 'US',
        countryName: 'United States',
        localAlias: null,
        createdAt: '2024-01-01',
        updatedAt: '2024-01-01',
      } as CompanyCountry),
    );
    removeCountry = vi.fn().mockReturnValue(of(undefined));
  }

  class MockCountryService {
    private _countries = signal<Country[]>(mockCountries);
    countries = this._countries.asReadonly();

    getCountries = vi.fn().mockReturnValue(of(mockCountries));
  }

  class MockCompanyService {
    getCompanies = vi.fn().mockReturnValue(of(mockCompaniesPage));
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CountriesPage, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: CompanyService, useClass: MockCompanyService },
        { provide: CompanyCountryService, useClass: MockCompanyCountryService },
        { provide: CountryService, useClass: MockCountryService },
      ],

    }).compileComponents();

    fixture = TestBed.createComponent(CountriesPage);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have null selectedCompanyId initially', () => {
    expect(component.selectedCompanyId()).toBeNull();
  });

  it('should set selectedCompanyId when onCompanyChange is called', () => {
    component.onCompanyChange('company-1');
    expect(component.selectedCompanyId()).toBe('company-1');
  });

  it('should clear selectedCompanyId when empty string is passed to onCompanyChange', () => {
    component.onCompanyChange('company-1');
    expect(component.selectedCompanyId()).toBe('company-1');

    component.onCompanyChange('');
    expect(component.selectedCompanyId()).toBe('');
  });

  it('should load company countries and available countries when onCompanyChange is called with valid id', () => {
    const companyCountryService = TestBed.inject(CompanyCountryService);
    const countryService = TestBed.inject(CountryService);

    component.onCompanyChange('company-1');

    expect(companyCountryService.getCountries).toHaveBeenCalledWith('company-1');
    expect(countryService.getCountries).toHaveBeenCalled();
  });

  it('should NOT load countries when onCompanyChange is called with empty string', () => {
    const companyCountryService = TestBed.inject(CompanyCountryService);

    component.onCompanyChange('');

    expect(companyCountryService.getCountries).not.toHaveBeenCalled();
  });

  it('should delegate onAddCountry to companyCountryService when company is selected', () => {
    const companyCountryService = TestBed.inject(CompanyCountryService);
    const request: CompanyCountryRequest = { countryCode: 'BR', localAlias: 'Test' };

    component.onCompanyChange('company-1');
    component.onAddCountry(request);

    expect(companyCountryService.addCountry).toHaveBeenCalledWith('company-1', request);
  });

  it('should NOT call addCountry when no company is selected', () => {
    const companyCountryService = TestBed.inject(CompanyCountryService);
    const request: CompanyCountryRequest = { countryCode: 'BR' };

    component.onAddCountry(request);

    expect(companyCountryService.addCountry).not.toHaveBeenCalled();
  });

  it('should delegate onRemoveCountry to companyCountryService when company is selected', () => {
    const companyCountryService = TestBed.inject(CompanyCountryService);

    component.onCompanyChange('company-1');
    component.onRemoveCountry('cc-1');

    expect(companyCountryService.removeCountry).toHaveBeenCalledWith('company-1', 'cc-1');
  });

  it('should NOT call removeCountry when no company is selected', () => {
    const companyCountryService = TestBed.inject(CompanyCountryService);

    component.onRemoveCountry('cc-1');

    expect(companyCountryService.removeCountry).not.toHaveBeenCalled();
  });

  describe('rendering', () => {
    it('should show empty prompt when no company is selected', () => {
      const emptyPrompt = fixture.nativeElement.querySelector('.empty-prompt');
      expect(emptyPrompt).toBeTruthy();
      expect(emptyPrompt.textContent).toContain('Seleccioná una empresa');
    });
  });
});
