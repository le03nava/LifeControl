import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { RegionsPage } from './regions-page';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../data/company-region.service';
import {
  CompanyRegion,
  CompanyRegionRequest,
} from '../../models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';

describe('RegionsPage', () => {
  let component: RegionsPage;
  let fixture: ComponentFixture<RegionsPage>;

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

  const mockRegions: CompanyRegion[] = [
    {
      id: 'r-1',
      companyCountryId: 'cc-1',
      companyId: 'company-1',
      countryId: 'c1',
      regionCode: 'CENTRO',
      regionName: 'Zona Centro',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  const mockNewRegion: CompanyRegion = {
    id: 'r-new',
    companyCountryId: 'cc-1',
    companyId: 'company-1',
    countryId: 'c1',
    regionCode: 'NORTE',
    regionName: 'Zona Norte',
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  // Create mock services using plain objects with vi.fn() (Vitest)
  class MockCompanyCountryService {
    private _assignedCountries = signal<CompanyCountry[]>([]);
    private _loading = signal(false);
    private _error = signal<string | null>(null);

    assignedCountries = this._assignedCountries.asReadonly();
    loading = this._loading.asReadonly();
    error = this._error.asReadonly();

    getCountries = vi.fn().mockReturnValue(of(mockAssignedCountries));
  }

  class MockCompanyRegionService {
    private _regions = signal<CompanyRegion[]>([]);
    private _loading = signal(false);
    private _error = signal<string | null>(null);

    regions = this._regions.asReadonly();
    loading = this._loading.asReadonly();
    error = this._error.asReadonly();

    getRegions = vi.fn().mockReturnValue(of(mockRegions));
    addRegion = vi.fn().mockReturnValue(of(mockNewRegion));
    updateRegion = vi.fn().mockReturnValue(of(mockNewRegion));
    removeRegion = vi.fn().mockReturnValue(of(undefined));
    enableRegion = vi.fn().mockReturnValue(of(mockNewRegion));
  }

  class MockCompanyService {
    getCompanies = vi.fn().mockReturnValue(of(mockCompaniesPage));
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RegionsPage, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: CompanyService, useClass: MockCompanyService },
        { provide: CompanyCountryService, useClass: MockCompanyCountryService },
        { provide: CompanyRegionService, useClass: MockCompanyRegionService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RegionsPage);
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

  it('should have null selectedCountry initially', () => {
    expect(component.selectedCountry()).toBeNull();
  });

  // ─── onCompanyChange ─────────────────────────────────────────

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

  it('should clear selectedCountry when company changes', () => {
    const mockCC = mockAssignedCountries[0];
    component.onSelectCountry(mockCC);
    expect(component.selectedCountry()).toBe(mockCC);

    component.onCompanyChange('company-2');
    expect(component.selectedCountry()).toBeNull();
  });

  it('should load assigned countries when onCompanyChange is called with valid id', () => {
    const companyCountryService = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;

    component.onCompanyChange('company-1');

    expect(companyCountryService.getCountries).toHaveBeenCalledWith('company-1');
  });

  it('should NOT load countries when onCompanyChange is called with empty string', () => {
    const companyCountryService = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;

    component.onCompanyChange('');

    expect(companyCountryService.getCountries).not.toHaveBeenCalled();
  });

  // ─── onSelectCountry ─────────────────────────────────────────

  it('should set selectedCountry when onSelectCountry is called', () => {
    const mockCC = mockAssignedCountries[0];

    component.onSelectCountry(mockCC);

    expect(component.selectedCountry()).toBe(mockCC);
  });

  it('should load regions for the selected country', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;
    const mockCC = mockAssignedCountries[0];

    component.onSelectCountry(mockCC);

    expect(companyRegionService.getRegions).toHaveBeenCalledWith(
      mockCC.companyId,
      mockCC.id,
    );
  });

  it('should switch selectedCountry when a different country chip is clicked', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;
    const firstCC = mockAssignedCountries[0];
    const secondCC = mockAssignedCountries[1];

    component.onSelectCountry(firstCC);
    expect(component.selectedCountry()).toBe(firstCC);

    component.onSelectCountry(secondCC);
    expect(component.selectedCountry()).toBe(secondCC);
    expect(companyRegionService.getRegions).toHaveBeenCalledWith(
      secondCC.companyId,
      secondCC.id,
    );
  });

  // ─── onAddRegion ─────────────────────────────────────────────

  it('should delegate onAddRegion to companyRegionService when country is selected', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;
    const request: CompanyRegionRequest = {
      regionCode: 'NORTE',
      regionName: 'Zona Norte',
    };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onAddRegion(request);

    expect(companyRegionService.addRegion).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      request,
    );
  });

  it('should NOT call addRegion when no country is selected', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;
    const request: CompanyRegionRequest = {
      regionCode: 'SUR',
      regionName: 'Zona Sur',
    };

    component.onAddRegion(request);

    expect(companyRegionService.addRegion).not.toHaveBeenCalled();
  });

  // ─── onUpdateRegion ──────────────────────────────────────────

  it('should delegate onUpdateRegion to companyRegionService when country is selected', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;
    const event = {
      id: 'r-1',
      data: { regionCode: 'CENTRO', regionName: 'Centro Mod' } as CompanyRegionRequest,
    };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onUpdateRegion(event);

    expect(companyRegionService.updateRegion).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'r-1',
      event.data,
    );
  });

  it('should NOT call updateRegion when no country is selected', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;
    const event = {
      id: 'r-1',
      data: { regionCode: 'CENTRO' } as CompanyRegionRequest,
    };

    component.onUpdateRegion(event);

    expect(companyRegionService.updateRegion).not.toHaveBeenCalled();
  });

  // ─── onRemoveRegion ──────────────────────────────────────────

  it('should delegate onRemoveRegion to companyRegionService when country is selected', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onRemoveRegion('r-1');

    expect(companyRegionService.removeRegion).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'r-1',
    );
  });

  it('should NOT call removeRegion when no country is selected', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;

    component.onRemoveRegion('r-1');

    expect(companyRegionService.removeRegion).not.toHaveBeenCalled();
  });

  // ─── onEnableRegion ──────────────────────────────────────────

  it('should delegate onEnableRegion to companyRegionService when country is selected', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onEnableRegion('r-1');

    expect(companyRegionService.enableRegion).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'r-1',
    );
  });

  it('should NOT call enableRegion when no country is selected', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;

    component.onEnableRegion('r-1');

    expect(companyRegionService.enableRegion).not.toHaveBeenCalled();
  });

  // ─── Rendering ───────────────────────────────────────────────

  describe('rendering', () => {
    it('should show empty prompt when no company is selected', () => {
      const emptyPrompt = fixture.nativeElement.querySelector('.empty-prompt');
      expect(emptyPrompt).toBeTruthy();
      expect(emptyPrompt.textContent).toContain('Seleccioná una empresa');
    });
  });
});
