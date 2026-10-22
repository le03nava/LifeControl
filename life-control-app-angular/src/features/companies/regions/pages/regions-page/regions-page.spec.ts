import { By } from '@angular/platform-browser';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { NEVER, Observable, of } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { RegionsPage } from './regions-page';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../data/company-region.service';
import { CompanyRegion } from '../../models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';
import { RegionsCard } from '../../components/regions-card/regions-card';
import { LocationChain, ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';

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
    private readonly _assignedCountries = signal<CompanyCountry[]>([]);

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

    getRegions = vi
      .fn()
      .mockImplementation((_companyId: string, _countryId: string, includeDisabled = false) => {
        // Models the API: `includeDisabled` decides which rows come back
        // (`CompanyRegionService` filters disabled rows out server-side).
        const rows = includeDisabled ? mockRegions : mockRegions.filter((region) => region.enabled);
        this._regions.set(rows);
        return of(rows);
      });
    addRegion = vi.fn().mockReturnValue(of(mockNewRegion));
    updateRegion = vi.fn().mockReturnValue(of(mockNewRegion));
    removeRegion = vi.fn().mockReturnValue(of(undefined));
    enableRegion = vi.fn().mockReturnValue(of(mockNewRegion));
  }

  class MockCompanyService {
    getCompanySelector = vi.fn().mockReturnValue(of(mockCompaniesPage));
  }

  /** A location chain as `GET /api/profile` derives it (S1). */
  const locationChain = (overrides: Partial<LocationChain> = {}): LocationChain => ({
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    companyRegionId: 'r-1',
    companyZoneId: 'zone-1',
    companyStoreId: 'store-1',
    companyStoreName: 'Store One',
    ...overrides,
  });

  const profileResponse = (location: LocationChain | null): ProfileResponse => ({
    keycloakUserId: 'user-1',
    username: 'operator',
    email: 'operator@lifecontrol.test',
    firstName: 'Oper',
    lastName: 'Ator',
    companyStoreId: location?.companyStoreId ?? null,
    assignedStores: null,
    location,
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RegionsPage, HttpClientTestingModule],
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
        {
          // The page resolves its preselection through the shared location preference (T3), which reads
          // the profile. These tests expect nothing preselected, so the default reports no chain.
          provide: ProfileService,
          useValue: { getProfile: () => of(profileResponse(null)) },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RegionsPage);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // Flush the reactive resource pipeline: trigger CD, await async resolution, re-render.
  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

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
    fixture.detectChanges();

    expect(companyCountryService.getCountries).toHaveBeenCalledWith('company-1');
  });

  it('should NOT load countries when onCompanyChange is called with empty string', () => {
    const companyCountryService = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;

    component.onCompanyChange('');
    fixture.detectChanges();

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
    fixture.detectChanges();

    expect(companyRegionService.getRegions).toHaveBeenCalledWith(
      mockCC.companyId,
      mockCC.id,
      false,
    );
  });

  it('should ask the API for disabled regions when the toggle is turned on', async () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;

    component.onSelectCountry(mockAssignedCountries[0]);
    await settle();
    expect(companyRegionService.getRegions).toHaveBeenLastCalledWith('company-1', 'cc-1', false);

    component.showDisabled.set(true);
    await settle();

    expect(companyRegionService.getRegions).toHaveBeenLastCalledWith('company-1', 'cc-1', true);
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
    fixture.detectChanges();
    expect(component.selectedCountry()).toBe(secondCC);
    expect(companyRegionService.getRegions).toHaveBeenCalledWith(
      secondCC.companyId,
      secondCC.id,
      false,
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

  it('should navigate to edit when onCardEditRegion finds the region', async () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    await settle();
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

    expect(companyRegionService.removeRegion).toHaveBeenCalledWith('company-1', 'cc-1', 'r-1');
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

    expect(companyRegionService.enableRegion).toHaveBeenCalledWith('company-1', 'cc-1', 'r-1');
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

    expect(companyRegionService.removeRegion).toHaveBeenCalledWith('company-1', 'cc-1', 'r-1');
  });

  it('should call onEnableRegion when toggling a disabled region ON', () => {
    const companyRegionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onToggleRegion(mockRegions[2]); // enabled: false

    expect(companyRegionService.enableRegion).toHaveBeenCalledWith('company-1', 'cc-1', 'r-3');
  });

  // ─── filteredRegions ─────────────────────────────────────────

  it('should show only enabled regions by default', async () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    await settle();
    expect(component.filteredRegions().length).toBe(2);
    expect(component.filteredRegions().every((r) => r.enabled)).toBe(true);
  });

  it('should show all regions when showDisabled is true', async () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    await settle();
    component.showDisabled.set(true);
    await settle();
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
      await settle();

      const headerRow = fixture.nativeElement.querySelector('app-page-header');
      expect(headerRow).toBeTruthy();
      expect(headerRow.textContent).toContain('Nueva Región');

      const toggle = fixture.nativeElement.querySelector('.filter-toggle');
      expect(toggle).toBeTruthy();
      expect(toggle.textContent).toContain('Mostrar deshabilitadas');
    });

    it('should render an app-regions-card for each enabled region', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();

      const cards = fixture.nativeElement.querySelectorAll('app-regions-card');
      expect(cards.length).toBe(2);
    });

    it('should render cards for all regions when showDisabled is on', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();
      component.showDisabled.set(true);
      await settle();

      const cards = fixture.nativeElement.querySelectorAll('app-regions-card');
      expect(cards.length).toBe(3);
    });

    it('should pass the correct region data to each card', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();

      const cards = fixture.debugElement.queryAll(By.directive(RegionsCard));
      expect(cards.length).toBe(2);
      expect(cards[0].componentInstance.region()).toEqual(mockRegions[0]);
      expect(cards[1].componentInstance.region()).toEqual(mockRegions[1]);
    });

    it('should have a CSS grid container wrapping the cards', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();

      const grid = fixture.nativeElement.querySelector('.regions-grid');
      expect(grid).toBeTruthy();
      const cards = grid!.querySelectorAll('app-regions-card');
      expect(cards.length).toBe(2);
    });

    it('should display error message when error is set', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();
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
      const companyRegionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      companyRegionService.getRegions = vi.fn().mockReturnValue(NEVER);

      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      fixture.detectChanges();

      const loadingEl = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingEl).toBeTruthy();
      expect(loadingEl.textContent).toContain('Cargando regiones');
    });

    it('should show empty message when no regions and not loading', async () => {
      const companyRegionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      companyRegionService.getRegions = vi.fn().mockReturnValue(of([]));

      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeTruthy();
      expect(emptyEl.textContent).toContain('No hay regiones registradas');
    });

    it('should NOT show empty message when loading', () => {
      const companyRegionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      companyRegionService.getRegions = vi.fn().mockReturnValue(NEVER);

      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      fixture.detectChanges();

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeNull();
    });
  });

  // ─── Resource hardening ──────────────────────────────────────

  describe('resource hardening', () => {
    it('should return empty values without throwing when resources have no value', () => {
      expect(() => component.countries()).not.toThrow();
      expect(() => component.regions()).not.toThrow();

      expect(component.countries()).toEqual([]);
      expect(component.regions()).toEqual([]);
      expect(component.filteredRegions()).toEqual([]);
    });
  });

  // ─── Location preselection (S7) and its fail-closed counterpart (S8) ───

  describe('location preselection', () => {
    /**
     * Rebuilds the module with the two inputs this rule reads: the route's own ids and the profile's
     * location chain. Company, country and region services stay the shared mocks of this spec.
     */
    async function setup(
      options: { query?: Record<string, string>; location?: LocationChain | null } = {},
    ): Promise<{
      component: RegionsPage;
      fixture: ComponentFixture<RegionsPage>;
      getProfile: ReturnType<typeof vi.fn>;
    }> {
      const query = options.query ?? {};
      const getProfile = vi.fn((): Observable<ProfileResponse> =>
        of(profileResponse(options.location ?? null)),
      );

      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [RegionsPage, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: Router, useValue: { navigate: vi.fn() } },
          {
            provide: ActivatedRoute,
            useValue: {
              snapshot: {
                queryParamMap: { get: (key: string) => query[key] ?? null },
              },
            },
          },
          { provide: ProfileService, useValue: { getProfile } },
        ],
      }).compileComponents();

      const regionalFixture = TestBed.createComponent(RegionsPage);
      regionalFixture.detectChanges();
      return { component: regionalFixture.componentInstance, fixture: regionalFixture, getProfile };
    }

    async function settle(fixture: ComponentFixture<RegionsPage>): Promise<void> {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    }

    it('should preselect Company and Country from the location chain when the route carries no ids (S7)', async () => {
      const { component: page, fixture: pageFixture } = await setup({
        location: locationChain({ companyId: 'company-1', companyCountryId: 'cc-2' }),
      });

      await settle(pageFixture);

      expect(page.selectedCompanyId()).toBe('company-1');
      expect(page.selectedCountry()?.id).toBe('cc-2');
      // And the preselection reaches the read that hangs off it, not only the controls.
      const regions = TestBed.inject(CompanyRegionService) as unknown as MockCompanyRegionService;
      expect(regions.getRegions).toHaveBeenCalledWith('company-1', 'cc-2', false);
    });

    it('should keep the route ids and never consult the preference when the route carries them (S7)', async () => {
      const {
        component: page,
        fixture: pageFixture,
        getProfile,
      } = await setup({
        query: { companyId: 'company-1', countryId: 'cc-1' },
        location: locationChain({ companyId: 'company-2', companyCountryId: 'cc-2' }),
      });

      await settle(pageFixture);

      expect(page.selectedCompanyId()).toBe('company-1');
      expect(page.selectedCountry()?.id).toBe('cc-1');
      expect(getProfile).not.toHaveBeenCalled();
    });

    it("should keep the route's company and leave the country empty when the route names only one (S7)", async () => {
      const { component: page, fixture: pageFixture } = await setup({
        query: { companyId: 'company-1' },
        location: locationChain({ companyId: 'company-1', companyCountryId: 'cc-2' }),
      });

      await settle(pageFixture);

      expect(page.selectedCompanyId()).toBe('company-1');
      // The route answered the company question, so the preference is not consulted for the rest.
      expect(page.selectedCountry()).toBeNull();
    });

    it('should start with empty selectors and issue no country or region read when location is null (S8)', async () => {
      const { component: page, fixture: pageFixture } = await setup({ location: null });

      await settle(pageFixture);

      expect(page.selectedCompanyId()).toBeNull();
      expect(page.selectedCountry()).toBeNull();
      expect(page.countries()).toEqual([]);
      expect(page.filteredRegions()).toEqual([]);

      const countries = TestBed.inject(
        CompanyCountryService,
      ) as unknown as MockCompanyCountryService;
      const regions = TestBed.inject(CompanyRegionService) as unknown as MockCompanyRegionService;
      expect(countries.getCountries).not.toHaveBeenCalled();
      expect(regions.getRegions).not.toHaveBeenCalled();
      expect(pageFixture.nativeElement.querySelector('.empty-prompt')).toBeTruthy();
    });
  });
});
