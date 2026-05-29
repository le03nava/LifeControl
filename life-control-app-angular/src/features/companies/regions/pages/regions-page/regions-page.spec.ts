import { By } from '@angular/platform-browser';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { RegionsPage } from './regions-page';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../data/company-region.service';
import { CompanyRegion } from '../../models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';
import { RegionsCard } from '../../components/regions-card/regions-card';

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
    {
      id: 'r-2',
      companyCountryId: 'cc-1',
      companyId: 'company-1',
      countryId: 'c1',
      regionCode: 'NORTE',
      regionName: 'Zona Norte',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
    {
      id: 'r-3',
      companyCountryId: 'cc-1',
      companyId: 'company-1',
      countryId: 'c1',
      regionCode: 'SUR',
      regionName: 'Zona Sur',
      enabled: false,
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

  class MockCompanyCountryService {
    private _assignedCountries = signal<CompanyCountry[]>([]);

    assignedCountries = this._assignedCountries.asReadonly();
    loading = signal(false).asReadonly();
    error = signal<string | null>(null).asReadonly();

    getCountries = vi.fn().mockImplementation(() => {
      this._assignedCountries.set(mockAssignedCountries);
      return of(mockAssignedCountries);
    });
  }

  class MockCompanyRegionService {
    // Public so tests can control state
    _regions = signal<CompanyRegion[]>([]);
    _loading = signal(false);
    _error = signal<string | null>(null);

    readonly regions = this._regions.asReadonly();
    readonly loading = this._loading.asReadonly();
    readonly error = this._error.asReadonly();

    getRegions = vi.fn().mockImplementation(() => {
      this._regions.set(mockRegions);
      return of(mockRegions);
    });
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
        {
          provide: Router,
          useValue: { navigate: vi.fn() },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: {
                get: vi.fn().mockReturnValue(null),
              },
            },
          },
        },
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

  it('should not filter by default (showDisabled = false)', () => {
    expect(component.showDisabled()).toBe(false);
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

  it('should switch selectedCountry when a different country is selected', () => {
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

  // ─── onCreateRegion ──────────────────────────────────────────

  it('should navigate to create with company and country query params when country is selected', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onCreateRegion();

    expect(router.navigate).toHaveBeenCalledWith(['/companies/regions/create'], {
      queryParams: { companyId: 'company-1', countryId: 'cc-1' },
    });
  });

  it('should NOT navigate when no country is selected', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onCreateRegion();

    expect(router.navigate).not.toHaveBeenCalled();
  });

  // ─── onEditRegion ────────────────────────────────────────────

  it('should navigate to edit with region id and state', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
    const region = mockRegions[0];

    component.onEditRegion(region);

    expect(router.navigate).toHaveBeenCalledWith(['/companies/regions/edit', region.id], {
      state: { region },
    });
  });

  // ─── onCardEditRegion ────────────────────────────────────────

  it('should navigate to edit when onCardEditRegion finds the region', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onCardEditRegion('r-2');

    expect(router.navigate).toHaveBeenCalledWith(['/companies/regions/edit', 'r-2'], {
      state: { region: mockRegions[1] },
    });
  });

  it('should NOT navigate when onCardEditRegion does not find the region', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onCardEditRegion('non-existent');

    expect(router.navigate).not.toHaveBeenCalled();
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

  // ─── onToggleRegion ──────────────────────────────────────────

  it('should call onRemoveRegion when toggling an enabled region OFF', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onToggleRegion(mockRegions[0]); // enabled: true

    expect(companyRegionService.removeRegion).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'r-1',
    );
  });

  it('should call onEnableRegion when toggling a disabled region ON', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onToggleRegion(mockRegions[2]); // enabled: false

    expect(companyRegionService.enableRegion).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'r-3',
    );
  });

  // ─── filteredRegions ─────────────────────────────────────────

  it('should show only enabled regions by default', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    expect(component.filteredRegions().length).toBe(2);
    expect(component.filteredRegions().every((r) => r.enabled)).toBe(true);
  });

  it('should show all regions when showDisabled is true', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.showDisabled.set(true);
    expect(component.filteredRegions().length).toBe(3);
  });

  // ─── Rendering ───────────────────────────────────────────────

  describe('rendering', () => {
    it('should show empty prompt when no company is selected', () => {
      const emptyPrompt = fixture.nativeElement.querySelector('.empty-prompt');
      expect(emptyPrompt).toBeTruthy();
      expect(emptyPrompt.textContent).toContain('Seleccioná una empresa');
    });

    it('should show header with title, toggle, and Nueva Región button when country is selected', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      fixture.detectChanges();

      const headerRow = fixture.nativeElement.querySelector('app-page-header');
      expect(headerRow).toBeTruthy();
      expect(headerRow.textContent).toContain('Mostrar deshabilitadas');
      expect(headerRow.textContent).toContain('Nueva Región');
    });

    it('should render an app-regions-card for each enabled region', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      fixture.detectChanges();

      const cards = fixture.nativeElement.querySelectorAll('app-regions-card');
      expect(cards.length).toBe(2);
    });

    it('should render cards for all regions when showDisabled is on', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.showDisabled.set(true);
      fixture.detectChanges();

      const cards = fixture.nativeElement.querySelectorAll('app-regions-card');
      expect(cards.length).toBe(3);
    });

    it('should pass the correct region data to each card', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      fixture.detectChanges();

      const cards = fixture.debugElement.queryAll(By.directive(RegionsCard));
      expect(cards.length).toBe(2);
      expect(cards[0].componentInstance.region()).toEqual(mockRegions[0]);
      expect(cards[1].componentInstance.region()).toEqual(mockRegions[1]);
    });

    it('should have a CSS grid container wrapping the cards', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      fixture.detectChanges();

      const grid = fixture.nativeElement.querySelector('.regions-grid');
      expect(grid).toBeTruthy();
      const cards = grid!.querySelectorAll('app-regions-card');
      expect(cards.length).toBe(2);
    });

    it('should display error message when error is set', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      const companyRegionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      companyRegionService._error.set('Este código de región ya existe');
      fixture.detectChanges();

      const errorEl = fixture.nativeElement.querySelector('.error-state');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Este código de región ya existe');
    });

    it('should display loading text when loading', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      const companyRegionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      companyRegionService._loading.set(true);
      fixture.detectChanges();

      const loadingEl = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingEl).toBeTruthy();
      expect(loadingEl.textContent).toContain('Cargando regiones');
    });

    it('should show empty message when no regions and not loading', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      const companyRegionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      companyRegionService._regions.set([]);
      fixture.detectChanges();

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeTruthy();
      expect(emptyEl.textContent).toContain('No hay regiones registradas');
    });

    it('should NOT show empty message when loading', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      const companyRegionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      companyRegionService._regions.set([]);
      companyRegionService._loading.set(true);
      fixture.detectChanges();

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeNull();
    });
  });
});
