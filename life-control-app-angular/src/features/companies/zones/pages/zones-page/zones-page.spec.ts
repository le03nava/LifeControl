import { By } from '@angular/platform-browser';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { ZonesPage } from './zones-page';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../data/company-zone.service';
import { CompanyZone } from '../../models/zone.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';
import { ZonesCard } from '../../components/zones-card/zones-card';

describe('ZonesPage', () => {
  let component: ZonesPage;
  let fixture: ComponentFixture<ZonesPage>;

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

  const mockRegions: CompanyRegion[] = [
    {
      id: 'reg-1',
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
      id: 'reg-2',
      companyCountryId: 'cc-1',
      companyId: 'company-1',
      countryId: 'c1',
      regionCode: 'NORTE',
      regionName: 'Zona Norte',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  const mockZones: CompanyZone[] = [
    {
      id: 'zone-1',
      companyRegionId: 'reg-1',
      companyCountryId: 'cc-1',
      companyId: 'company-1',
      countryId: 'c1',
      zoneCode: 'CDMX-DT',
      zoneName: 'Downtown',
      description: 'Centro histórico',
      displayOrder: 1,
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
    {
      id: 'zone-2',
      companyRegionId: 'reg-1',
      companyCountryId: 'cc-1',
      companyId: 'company-1',
      countryId: 'c1',
      zoneCode: 'CDMX-NR',
      zoneName: 'Narvarte',
      description: 'Zona residencial',
      displayOrder: 2,
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
    {
      id: 'zone-3',
      companyRegionId: 'reg-1',
      companyCountryId: 'cc-1',
      companyId: 'company-1',
      countryId: 'c1',
      zoneCode: 'CDMX-SR',
      zoneName: 'Del Sur',
      description: undefined,
      displayOrder: undefined,
      enabled: false,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

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
  }

  class MockCompanyZoneService {
    _zones = signal<CompanyZone[]>([]);
    _loading = signal(false);
    _error = signal<string | null>(null);

    readonly zones = this._zones.asReadonly();
    readonly loading = this._loading.asReadonly();
    readonly error = this._error.asReadonly();

    getZones = vi.fn().mockImplementation(() => {
      this._zones.set(mockZones);
      return of(mockZones);
    });
    addZone = vi.fn().mockReturnValue(of(mockZones[0]));
    updateZone = vi.fn().mockReturnValue(of(mockZones[0]));
    removeZone = vi.fn().mockReturnValue(of(undefined));
    enableZone = vi.fn().mockReturnValue(of(mockZones[0]));
  }

  class MockCompanyService {
    getCompanies = vi.fn().mockReturnValue(of(mockCompaniesPage));
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ZonesPage, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: CompanyService, useClass: MockCompanyService },
        { provide: CompanyCountryService, useClass: MockCompanyCountryService },
        { provide: CompanyRegionService, useClass: MockCompanyRegionService },
        { provide: CompanyZoneService, useClass: MockCompanyZoneService },
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

    fixture = TestBed.createComponent(ZonesPage);
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

  it('should have null selectedRegion initially', () => {
    expect(component.selectedRegion()).toBeNull();
  });

  it('should not filter by default (showDisabled = false)', () => {
    expect(component.showDisabled()).toBe(false);
  });

  // ─── onCompanyChange ─────────────────────────────────────────

  it('should set selectedCompanyId when onCompanyChange is called', () => {
    component.onCompanyChange('company-1');
    expect(component.selectedCompanyId()).toBe('company-1');
  });

  it('should clear selectedCompanyId when empty string is passed', () => {
    component.onCompanyChange('company-1');
    expect(component.selectedCompanyId()).toBe('company-1');

    component.onCompanyChange('');
    expect(component.selectedCompanyId()).toBe('');
  });

  it('should clear selectedCountry and selectedRegion when company changes', () => {
    const mockCC = mockAssignedCountries[0];
    component.onSelectCountry(mockCC);
    component.onSelectRegion(mockRegions[0]);
    expect(component.selectedCountry()).toBe(mockCC);
    expect(component.selectedRegion()).toBe(mockRegions[0]);

    component.onCompanyChange('company-2');
    expect(component.selectedCountry()).toBeNull();
    expect(component.selectedRegion()).toBeNull();
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

  it('should clear selectedRegion when country changes', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    expect(component.selectedRegion()).toBe(mockRegions[0]);

    component.onSelectCountry(mockAssignedCountries[0]);
    expect(component.selectedRegion()).toBeNull();
  });

  // ─── onSelectRegion ──────────────────────────────────────────

  it('should set selectedRegion when onSelectRegion is called', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);

    expect(component.selectedRegion()).toBe(mockRegions[0]);
  });

  it('should load zones for the selected region', () => {
    const companyZoneService = TestBed.inject(
      CompanyZoneService,
    ) as unknown as MockCompanyZoneService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);

    expect(companyZoneService.getZones).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'reg-1',
    );
  });

  // ─── onCreateZone ────────────────────────────────────────────

  it('should navigate to create with company, country and region query params when region is selected', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onCreateZone();

    expect(router.navigate).toHaveBeenCalledWith(['/companies/zones/create'], {
      queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1' },
    });
  });

  it('should NOT navigate when no region is selected', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onCreateZone();

    expect(router.navigate).not.toHaveBeenCalled();
  });

  // ─── onEditZone ──────────────────────────────────────────────

  it('should navigate to edit with zone id and state', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
    const zone = mockZones[0];

    component.onEditZone(zone);

    expect(router.navigate).toHaveBeenCalledWith(['/companies/zones/edit', zone.id], {
      state: { zone },
    });
  });

  // ─── onCardEditZone ──────────────────────────────────────────

  it('should navigate to edit when onCardEditZone finds the zone', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onCardEditZone('zone-2');

    expect(router.navigate).toHaveBeenCalledWith(['/companies/zones/edit', 'zone-2'], {
      state: { zone: mockZones[1] },
    });
  });

  it('should NOT navigate when onCardEditZone does not find the zone', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onCardEditZone('non-existent');

    expect(router.navigate).not.toHaveBeenCalled();
  });

  // ─── onRemoveZone ────────────────────────────────────────────

  it('should delegate onRemoveZone to companyZoneService when region is selected', () => {
    const companyZoneService = TestBed.inject(
      CompanyZoneService,
    ) as unknown as MockCompanyZoneService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onRemoveZone('zone-1');

    expect(companyZoneService.removeZone).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'reg-1',
      'zone-1',
    );
  });

  it('should NOT call removeZone when no region is selected', () => {
    const companyZoneService = TestBed.inject(
      CompanyZoneService,
    ) as unknown as MockCompanyZoneService;

    component.onRemoveZone('zone-1');

    expect(companyZoneService.removeZone).not.toHaveBeenCalled();
  });

  // ─── onEnableZone ────────────────────────────────────────────

  it('should delegate onEnableZone to companyZoneService when region is selected', () => {
    const companyZoneService = TestBed.inject(
      CompanyZoneService,
    ) as unknown as MockCompanyZoneService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onEnableZone('zone-1');

    expect(companyZoneService.enableZone).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'reg-1',
      'zone-1',
    );
  });

  it('should NOT call enableZone when no region is selected', () => {
    const companyZoneService = TestBed.inject(
      CompanyZoneService,
    ) as unknown as MockCompanyZoneService;

    component.onEnableZone('zone-1');

    expect(companyZoneService.enableZone).not.toHaveBeenCalled();
  });

  // ─── onCardToggleZone ────────────────────────────────────────

  it('should call onEnableZone when card toggle emits enable=true', () => {
    const companyZoneService = TestBed.inject(
      CompanyZoneService,
    ) as unknown as MockCompanyZoneService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onCardToggleZone({ id: 'zone-3', enable: true });

    expect(companyZoneService.enableZone).toHaveBeenCalledWith(
      'company-1', 'cc-1', 'reg-1', 'zone-3',
    );
    expect(companyZoneService.removeZone).not.toHaveBeenCalled();
  });

  it('should call onRemoveZone when card toggle emits enable=false', () => {
    const companyZoneService = TestBed.inject(
      CompanyZoneService,
    ) as unknown as MockCompanyZoneService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onCardToggleZone({ id: 'zone-1', enable: false });

    expect(companyZoneService.removeZone).toHaveBeenCalledWith(
      'company-1', 'cc-1', 'reg-1', 'zone-1',
    );
    expect(companyZoneService.enableZone).not.toHaveBeenCalled();
  });

  // ─── onToggleZone ────────────────────────────────────────────

  it('should call onRemoveZone when toggling an enabled zone OFF', () => {
    const companyZoneService = TestBed.inject(
      CompanyZoneService,
    ) as unknown as MockCompanyZoneService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onToggleZone(mockZones[0]); // enabled: true

    expect(companyZoneService.removeZone).toHaveBeenCalledWith(
      'company-1', 'cc-1', 'reg-1', 'zone-1',
    );
  });

  it('should call onEnableZone when toggling a disabled zone ON', () => {
    const companyZoneService = TestBed.inject(
      CompanyZoneService,
    ) as unknown as MockCompanyZoneService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onToggleZone(mockZones[2]); // enabled: false

    expect(companyZoneService.enableZone).toHaveBeenCalledWith(
      'company-1', 'cc-1', 'reg-1', 'zone-3',
    );
  });

  // ─── filteredZones ─────────────────────────────────────────

  it('should show only enabled zones by default', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    expect(component.filteredZones().length).toBe(2);
    expect(component.filteredZones().every((z) => z.enabled)).toBe(true);
  });

  it('should show all zones when showDisabled is true', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.showDisabled.set(true);
    expect(component.filteredZones().length).toBe(3);
  });

  // ─── Rendering ───────────────────────────────────────────────

  describe('rendering', () => {
    it('should show empty prompt when no company is selected', () => {
      const emptyPrompt = fixture.nativeElement.querySelector('.empty-prompt');
      expect(emptyPrompt).toBeTruthy();
      expect(emptyPrompt.textContent).toContain('Seleccioná una empresa');
    });

    it('should show header with title and Nueva Zona button when region is selected', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      fixture.detectChanges();

      const headerRow = fixture.nativeElement.querySelector('app-page-header');
      expect(headerRow).toBeTruthy();
      expect(headerRow.textContent).toContain('Nueva Zona');
    });

    it('should render an app-zones-card for each enabled zone', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      fixture.detectChanges();

      const cards = fixture.nativeElement.querySelectorAll('app-zones-card');
      expect(cards.length).toBe(2);
    });

    it('should render cards for all zones when showDisabled is on', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      component.showDisabled.set(true);
      fixture.detectChanges();

      const cards = fixture.nativeElement.querySelectorAll('app-zones-card');
      expect(cards.length).toBe(3);
    });

    it('should pass the correct zone data to each card', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      fixture.detectChanges();

      const cards = fixture.debugElement.queryAll(By.directive(ZonesCard));
      expect(cards.length).toBe(2);
      expect(cards[0].componentInstance.zone()).toEqual(mockZones[0]);
      expect(cards[1].componentInstance.zone()).toEqual(mockZones[1]);
    });

    it('should have a CSS grid container wrapping the cards', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      fixture.detectChanges();

      const grid = fixture.nativeElement.querySelector('.zones-grid');
      expect(grid).toBeTruthy();
      const cards = grid!.querySelectorAll('app-zones-card');
      expect(cards.length).toBe(2);
    });

    it('should display error message when error is set', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      const companyZoneService = TestBed.inject(
        CompanyZoneService,
      ) as unknown as MockCompanyZoneService;
      companyZoneService._error.set('Error al cargar las zonas');
      fixture.detectChanges();

      const errorEl = fixture.nativeElement.querySelector('.error-state');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Error al cargar las zonas');
    });

    it('should display loading text when loading', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      const companyZoneService = TestBed.inject(
        CompanyZoneService,
      ) as unknown as MockCompanyZoneService;
      companyZoneService._loading.set(true);
      fixture.detectChanges();

      const loadingEl = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingEl).toBeTruthy();
      expect(loadingEl.textContent).toContain('Cargando zonas');
    });

    it('should show empty message when no zones and not loading', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      const companyZoneService = TestBed.inject(
        CompanyZoneService,
      ) as unknown as MockCompanyZoneService;
      companyZoneService._zones.set([]);
      fixture.detectChanges();

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeTruthy();
      expect(emptyEl.textContent).toContain('No hay zonas registradas');
    });

    it('should NOT show empty message when loading', () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      const companyZoneService = TestBed.inject(
        CompanyZoneService,
      ) as unknown as MockCompanyZoneService;
      companyZoneService._zones.set([]);
      companyZoneService._loading.set(true);
      fixture.detectChanges();

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeNull();
    });
  });
});
