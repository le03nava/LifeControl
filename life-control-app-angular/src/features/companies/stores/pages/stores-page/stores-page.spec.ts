import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { signal, WritableSignal } from '@angular/core';
import { NEVER, of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import Keycloak from 'keycloak-js';
import { StoresPage } from './stores-page';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { CompanyStore } from '../../models/store.models';
import { CompanyZone } from '../../../zones/models/zone.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';

describe('StoresPage', () => {
  let component: StoresPage;
  let fixture: ComponentFixture<StoresPage>;

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
      enabled: false,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  const mockStores: CompanyStore[] = [
    {
      id: 'store-1',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'zone-1',
      storeName: 'Tienda Central',
      email: 'central@store.com',
      phoneNumber: '+525512345678',
      address: {
        street: 'Av. Reforma',
        streetNumber: '222',
        neighborhood: 'Juárez',
        zipCode: '06600',
        city: 'Ciudad de México',
        state: 'CDMX',
        countryId: 'MX',
      },
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-15',
      version: 0,
    },
    {
      id: 'store-2',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'zone-1',
      storeName: 'Tienda Norte',
      email: 'norte@store.com',
      enabled: false,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-15',
      version: 0,
    },
  ];

  class MockCompanyCountryService {
    private readonly _assignedCountries = signal<CompanyCountry[]>([]);
    readonly assignedCountries = this._assignedCountries.asReadonly();
    readonly loading = signal(false).asReadonly();
    readonly error = signal<string | null>(null).asReadonly();
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
  }

  class MockCompanyStoreService {
    private readonly _stores = signal<CompanyStore[]>([]);
    private readonly _loading = signal(false);
    private readonly _error = signal<string | null>(null);
    readonly stores = this._stores.asReadonly();
    readonly loading = this._loading.asReadonly();
    readonly error = this._error.asReadonly();
    getStores = vi.fn().mockImplementation(() => {
      this._stores.set(mockStores);
      this._loading.set(false);
      return of(mockStores);
    });
    removeStore = vi.fn().mockReturnValue(of(undefined));
    enableStore = vi.fn().mockReturnValue(of(mockStores[0]));
    addStore = vi.fn().mockReturnValue(of(mockStores[0]));
  }

  class MockCompanyService {
    getCompanies = vi.fn().mockReturnValue(of(mockCompaniesPage));
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StoresPage, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: CompanyService, useClass: MockCompanyService },
        { provide: CompanyCountryService, useClass: MockCompanyCountryService },
        { provide: CompanyRegionService, useClass: MockCompanyRegionService },
        { provide: CompanyZoneService, useClass: MockCompanyZoneService },
        { provide: CompanyStoreService, useClass: MockCompanyStoreService },
        { provide: Router, useValue: { navigate: vi.fn() } },
        {
          provide: Keycloak,
          useValue: {
            tokenParsed: {
              resource_access: { 'life-control-client': { roles: ['lc-admin'] } },
            },
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: { get: vi.fn().mockReturnValue(null) },
              paramMap: { get: vi.fn().mockReturnValue(null) },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StoresPage);
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

  it('should have null selectedRegion initially', () => {
    expect(component.selectedRegion()).toBeNull();
  });

  it('should have null selectedZone initially', () => {
    expect(component.selectedZone()).toBeNull();
  });

  it('should not filter by default (showDisabled = false)', () => {
    expect(component.showDisabled()).toBe(false);
  });

  // ─── onCompanyChange ─────────────────────────────────────────

  it('should set selectedCompanyId when onCompanyChange is called', () => {
    component.onCompanyChange('company-1');
    expect(component.selectedCompanyId()).toBe('company-1');
  });

  it('should clear selectedCountry, selectedRegion, and selectedZone when company changes', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    expect(component.selectedCountry()).toBe(mockAssignedCountries[0]);
    expect(component.selectedRegion()).toBe(mockRegions[0]);
    expect(component.selectedZone()).toBe(mockZones[0]);

    component.onCompanyChange('company-2');
    expect(component.selectedCountry()).toBeNull();
    expect(component.selectedRegion()).toBeNull();
    expect(component.selectedZone()).toBeNull();
  });

  it('should load assigned countries when onCompanyChange is called with valid id', () => {
    const countryService = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;
    component.onCompanyChange('company-1');
    fixture.detectChanges();
    expect(countryService.getCountries).toHaveBeenCalledWith('company-1');
  });

  it('should NOT load countries when onCompanyChange is called with empty string', () => {
    const countryService = TestBed.inject(
      CompanyCountryService,
    ) as unknown as MockCompanyCountryService;
    component.onCompanyChange('');
    fixture.detectChanges();
    expect(countryService.getCountries).not.toHaveBeenCalled();
  });

  // ─── onSelectCountry ─────────────────────────────────────────

  it('should set selectedCountry when onSelectCountry is called', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    expect(component.selectedCountry()).toBe(mockAssignedCountries[0]);
  });

  it('should load regions for the selected country', () => {
    const regionService = TestBed.inject(
      CompanyRegionService,
    ) as unknown as MockCompanyRegionService;
    component.onSelectCountry(mockAssignedCountries[0]);
    fixture.detectChanges();
    expect(regionService.getRegions).toHaveBeenCalledWith('company-1', 'cc-1');
  });

  it('should clear selectedRegion and selectedZone when country changes', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    expect(component.selectedRegion()).toBe(mockRegions[0]);
    expect(component.selectedZone()).toBe(mockZones[0]);

    component.onSelectCountry(mockAssignedCountries[0]);
    expect(component.selectedRegion()).toBeNull();
    expect(component.selectedZone()).toBeNull();
  });

  // ─── onSelectRegion ──────────────────────────────────────────

  it('should set selectedRegion when onSelectRegion is called', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    expect(component.selectedRegion()).toBe(mockRegions[0]);
  });

  it('should load zones for the selected region', () => {
    const zoneService = TestBed.inject(CompanyZoneService) as unknown as MockCompanyZoneService;
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    fixture.detectChanges();
    expect(zoneService.getZones).toHaveBeenCalledWith('company-1', 'cc-1', 'reg-1');
  });

  it('should clear selectedZone when region changes', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    expect(component.selectedZone()).toBe(mockZones[0]);

    component.onSelectRegion(mockRegions[1]);
    expect(component.selectedZone()).toBeNull();
  });

  // ─── onSelectZone ────────────────────────────────────────────

  it('should set selectedZone when onSelectZone is called', () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    expect(component.selectedZone()).toBe(mockZones[0]);
  });

  it('should load stores when zone is selected', () => {
    const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    fixture.detectChanges();

    expect(storeService.getStores).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'reg-1',
      'zone-1',
      false,
    );
  });

  it('should NOT load stores when no region is selected on zone select', () => {
    const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
    component.onSelectCountry(mockAssignedCountries[0]);
    // No region selected
    component.onSelectZone(mockZones[0]);
    fixture.detectChanges();
    expect(storeService.getStores).not.toHaveBeenCalled();
  });

  // ─── onCreateStore ───────────────────────────────────────────

  it('should navigate to create with query params when zone is selected', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    component.onCreateStore();

    expect(router.navigate).toHaveBeenCalledWith(['/companies/stores/create'], {
      queryParams: {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
      },
    });
  });

  it('should NOT navigate when no zone is selected', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
    component.onCreateStore();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  // ─── onEditStore ─────────────────────────────────────────────

  it('should navigate to edit with store id and state', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
    component.onEditStore(mockStores[0]);
    expect(router.navigate).toHaveBeenCalledWith(['/companies/stores/edit', 'store-1'], {
      state: { store: mockStores[0] },
    });
  });

  // ─── onCardEditStore ─────────────────────────────────────────

  it('should navigate to edit when onCardEditStore finds the store', async () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onCardEditStore('store-1');
    expect(router.navigate).toHaveBeenCalledWith(['/companies/stores/edit', 'store-1'], {
      state: { store: mockStores[0] },
    });
  });

  it('should NOT navigate when onCardEditStore does not find the store', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
    component.onCardEditStore('non-existent');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  // ─── onCardManageLocations ──────────────────────────────────

  it('should navigate to store-areas with the cascade and store id', async () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onCardManageLocations('store-1');

    expect(router.navigate).toHaveBeenCalledWith(['/companies/store-areas'], {
      queryParams: {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
      },
    });
  });

  it('should NOT navigate to store-areas when the store is unknown', async () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onCardManageLocations('non-existent');

    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('should NOT navigate to store-areas without a full cascade', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onCardManageLocations('store-1');

    expect(router.navigate).not.toHaveBeenCalled();
  });

  // ─── onCardConfigureInventory ───────────────────────────────

  it('should navigate to store-inventory-settings with the cascade and store id', async () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onCardConfigureInventory('store-1');

    expect(router.navigate).toHaveBeenCalledWith(['/companies/store-inventory-settings'], {
      queryParams: {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
      },
    });
  });

  it('should NOT navigate to store-inventory-settings when the store is unknown', async () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onCardConfigureInventory('non-existent');

    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('should NOT navigate to store-inventory-settings without a full cascade', () => {
    const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

    component.onCardConfigureInventory('store-1');

    expect(router.navigate).not.toHaveBeenCalled();
  });

  // ─── onToggleStore ───────────────────────────────────────────

  it('should call removeStore when toggling an enabled store OFF', async () => {
    const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onToggleStore('store-1'); // enabled: true
    expect(storeService.removeStore).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'reg-1',
      'zone-1',
      'store-1',
    );
  });

  it('should call enableStore when toggling a disabled store ON', async () => {
    const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onToggleStore('store-2'); // enabled: false
    expect(storeService.enableStore).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'reg-1',
      'zone-1',
      'store-2',
    );
  });

  it('should NOT call service when no country is selected for toggle', () => {
    const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
    component.onToggleStore('store-1');
    expect(storeService.removeStore).not.toHaveBeenCalled();
    expect(storeService.enableStore).not.toHaveBeenCalled();
  });

  it('should surface the backend message when disabling fails', async () => {
    const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
    storeService.removeStore.mockReturnValue(
      throwError(() => ({ error: { message: 'La zona está deshabilitada' } })),
    );

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onToggleStore('store-1'); // enabled: true
    await settle();

    expect(component.actionError()).toBe('La zona está deshabilitada');

    const errorEl = fixture.nativeElement.querySelector('.error-state');
    expect(errorEl).toBeTruthy();
    expect(errorEl.textContent).toContain('La zona está deshabilitada');
  });

  it('should surface the backend message when re-enabling fails', async () => {
    const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
    storeService.enableStore.mockReturnValue(
      throwError(() => ({ error: { message: 'La zona está deshabilitada' } })),
    );

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onToggleStore('store-2'); // enabled: false
    await settle();

    expect(component.actionError()).toBe('La zona está deshabilitada');
  });

  it('should fall back to a generic message when the failure carries none', async () => {
    const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
    storeService.enableStore.mockReturnValue(throwError(() => ({ error: {} })));

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onToggleStore('store-2');
    await settle();

    expect(component.actionError()).toBe('No se pudo actualizar la tienda.');
  });

  it('should clear a previous action error on the next toggle attempt', async () => {
    const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
    storeService.enableStore.mockReturnValue(
      throwError(() => ({ error: { message: 'La zona está deshabilitada' } })),
    );

    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.onToggleStore('store-2');
    await settle();
    expect(component.actionError()).toBe('La zona está deshabilitada');

    storeService.enableStore.mockReturnValue(of(mockStores[1]));
    component.onToggleStore('store-2');
    await settle();

    expect(component.actionError()).toBeNull();
  });

  // ─── filteredStores ────────────────────────────────────────

  it('should show only enabled stores by default', async () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    expect(component.filteredStores().length).toBe(1);
    expect(component.filteredStores().every((s) => s.enabled)).toBe(true);
  });

  it('should show all stores when showDisabled is true', async () => {
    component.onSelectCountry(mockAssignedCountries[0]);
    component.onSelectRegion(mockRegions[0]);
    component.onSelectZone(mockZones[0]);
    await settle();

    component.showDisabled.set(true);
    expect(component.filteredStores().length).toBe(2);
  });

  // ─── Rendering ───────────────────────────────────────────────

  describe('rendering', () => {
    it('should show empty prompt when no company is selected', () => {
      const emptyPrompt = fixture.nativeElement.querySelector('.empty-prompt');
      expect(emptyPrompt).toBeTruthy();
      expect(emptyPrompt.textContent).toContain('Seleccioná una empresa');
    });

    it('should render an app-stores-card for each enabled store', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      component.onSelectZone(mockZones[0]);
      await settle();

      const cards = fixture.nativeElement.querySelectorAll('app-stores-card');
      expect(cards.length).toBe(1); // Only enabled stores by default
    });

    it('should render cards for all stores when showDisabled is on', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      component.onSelectZone(mockZones[0]);
      await settle();

      component.showDisabled.set(true);
      fixture.detectChanges();

      const cards = fixture.nativeElement.querySelectorAll('app-stores-card');
      expect(cards.length).toBe(2);
    });

    it('should display error message when error is set', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      component.onSelectZone(mockZones[0]);
      await settle();

      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      (storeService as unknown as { _error: WritableSignal<string | null> })._error.set(
        'Error al cargar las tiendas',
      );
      fixture.detectChanges();

      const errorEl = fixture.nativeElement.querySelector('.error-state');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Error al cargar las tiendas');
    });

    it('should display loading text when loading', () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      storeService.getStores = vi.fn().mockReturnValue(NEVER);

      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      component.onSelectZone(mockZones[0]);
      fixture.detectChanges();

      const loadingEl = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingEl).toBeTruthy();
      expect(loadingEl.textContent).toContain('Cargando tiendas');
    });

    it('should show empty message "No stores found for this zone" when no stores and not loading', async () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      storeService.getStores = vi.fn().mockReturnValue(of([]));

      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      component.onSelectZone(mockZones[0]);
      await settle();

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeTruthy();
      expect(emptyEl.textContent).toContain('No stores found for this zone');
    });

    it('should NOT show empty message when loading', () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      storeService.getStores = vi.fn().mockReturnValue(NEVER);

      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      component.onSelectZone(mockZones[0]);
      fixture.detectChanges();

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeNull();
    });

    it('should have stores-grid container', async () => {
      component.onCompanyChange('company-1');
      component.onSelectCountry(mockAssignedCountries[0]);
      component.onSelectRegion(mockRegions[0]);
      component.onSelectZone(mockZones[0]);
      await settle();

      const grid = fixture.nativeElement.querySelector('.stores-grid');
      expect(grid).toBeTruthy();
    });
  });

  // ─── Resource hardening ──────────────────────────────────────

  describe('resource hardening', () => {
    it('should return empty values without throwing when resources have no value', () => {
      expect(() => component.countries()).not.toThrow();
      expect(() => component.regions()).not.toThrow();
      expect(() => component.zones()).not.toThrow();
      expect(() => component.stores()).not.toThrow();

      expect(component.countries()).toEqual([]);
      expect(component.regions()).toEqual([]);
      expect(component.zones()).toEqual([]);
      expect(component.stores()).toEqual([]);
      expect(component.filteredStores()).toEqual([]);
    });
  });

  // ─── Create-button role gating ───────────────────────────────

  describe('create button gating', () => {
    /** Rebuilds the page with a specific set of Keycloak client roles. */
    async function setupWithRoles(roles: string[]): Promise<void> {
      fixture.destroy();
      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [StoresPage, NoopAnimationsModule, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: CompanyZoneService, useClass: MockCompanyZoneService },
          { provide: CompanyStoreService, useClass: MockCompanyStoreService },
          { provide: Router, useValue: { navigate: vi.fn() } },
          {
            provide: Keycloak,
            useValue: {
              tokenParsed: { resource_access: { 'life-control-client': { roles } } },
            },
          },
          {
            provide: ActivatedRoute,
            useValue: {
              snapshot: {
                queryParamMap: { get: vi.fn().mockReturnValue(null) },
                paramMap: { get: vi.fn().mockReturnValue(null) },
              },
            },
          },
        ],
      }).compileComponents();

      fixture = TestBed.createComponent(StoresPage);
      component = fixture.componentInstance;
      fixture.detectChanges();
    }

    it('should show "Nueva Tienda" for roles that can create stores', async () => {
      await setupWithRoles(['lc-company']);

      expect(fixture.nativeElement.textContent).toContain('Nueva Tienda');
    });

    it('should hide "Nueva Tienda" for store-scoped users', async () => {
      await setupWithRoles(['lc-company-store']);

      expect(fixture.nativeElement.textContent).not.toContain('Nueva Tienda');
    });

    it('should hide "Nueva Tienda" for read-only store users', async () => {
      await setupWithRoles(['lc-company-store-read']);

      expect(fixture.nativeElement.textContent).not.toContain('Nueva Tienda');
    });
  });
});
