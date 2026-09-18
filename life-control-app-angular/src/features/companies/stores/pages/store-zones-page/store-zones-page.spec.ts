import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { signal, WritableSignal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSelect } from '@angular/material/select';
import { StoreZonesPage } from './store-zones-page';
import { ConfirmDialog } from '@shared/ui';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { StoreAreaService } from '../../data/store-area.service';
import { StoreZoneService } from '../../data/store-zone.service';
import { CompanyStore } from '../../models/store.models';
import { StoreArea } from '../../models/store-area.models';
import { StoreZone } from '../../models/store-zone.models';
import { CompanyZone } from '../../../zones/models/zone.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';

describe('StoreZonesPage', () => {
  let component: StoreZonesPage;
  let fixture: ComponentFixture<StoreZonesPage>;

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
  ];

  /** Company zones: the third cascade level (Empresa → País → Región → Zona). */
  const mockCompanyZones: CompanyZone[] = [
    {
      id: 'czone-1',
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
  ];

  const mockStores: CompanyStore[] = [
    {
      id: 'store-1',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'czone-1',
      storeName: 'Tienda Central',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-15',
    },
    {
      id: 'store-2',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'czone-1',
      storeName: 'Tienda Cerrada',
      enabled: false,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-15',
    },
  ];

  const mockAreas: StoreArea[] = [
    {
      id: 'area-1',
      companyStoreId: 'store-1',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'czone-1',
      areaCode: 'ALMACEN',
      areaName: 'Almacén Central',
      description: 'Depósito principal',
      displayOrder: 1,
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-15',
    },
    {
      id: 'area-2',
      companyStoreId: 'store-1',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'czone-1',
      areaCode: 'MOSTRADOR',
      areaName: 'Mostrador',
      description: null,
      displayOrder: null,
      enabled: false,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-15',
    },
    {
      id: 'area-3',
      companyStoreId: 'store-2',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'czone-1',
      areaCode: 'DEPOSITO',
      areaName: 'Depósito Cerrado',
      description: null,
      displayOrder: 2,
      enabled: false,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-15',
    },
  ];

  /** Store zones: the managed list of the page. */
  const mockStoreZones: StoreZone[] = [
    {
      id: 'szone-1',
      storeAreaId: 'area-1',
      companyStoreId: 'store-1',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'czone-1',
      zoneCode: 'ESTANTERIA-A',
      zoneName: 'Estantería A',
      description: 'Pasillo norte',
      displayOrder: 1,
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-15',
    },
    {
      id: 'szone-2',
      storeAreaId: 'area-1',
      companyStoreId: 'store-1',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'czone-1',
      zoneCode: 'REFRIGERADO',
      zoneName: 'Refrigerado',
      description: null,
      displayOrder: null,
      enabled: false,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-15',
    },
  ];

  class MockCompanyService {
    getCompanies = vi.fn().mockReturnValue(of(mockCompaniesPage));
  }

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
    private readonly _regions = signal<CompanyRegion[]>([]);
    readonly regions = this._regions.asReadonly();
    readonly loading = signal(false).asReadonly();
    readonly error = signal<string | null>(null).asReadonly();
    getRegions = vi.fn().mockImplementation(() => {
      this._regions.set(mockRegions);
      return of(mockRegions);
    });
  }

  class MockCompanyZoneService {
    private readonly _zones = signal<CompanyZone[]>([]);
    readonly zones = this._zones.asReadonly();
    readonly loading = signal(false).asReadonly();
    readonly error = signal<string | null>(null).asReadonly();
    getZones = vi.fn().mockImplementation(() => {
      this._zones.set(mockCompanyZones);
      return of(mockCompanyZones);
    });
  }

  class MockCompanyStoreService {
    private readonly _stores = signal<CompanyStore[]>([]);
    readonly stores = this._stores.asReadonly();
    readonly loading = signal(false).asReadonly();
    readonly error = signal<string | null>(null).asReadonly();
    getStores = vi.fn().mockImplementation(() => {
      this._stores.set(mockStores);
      return of(mockStores);
    });
  }

  class MockStoreAreaService {
    private readonly _areas = signal<StoreArea[]>([]);
    readonly areas = this._areas.asReadonly();
    readonly loading = signal(false).asReadonly();
    readonly error = signal<string | null>(null).asReadonly();
    getAreas = vi.fn().mockImplementation(() => {
      this._areas.set(mockAreas);
      return of(mockAreas);
    });
    clearError = vi.fn();
  }

  class MockStoreZoneService {
    private readonly _storeZones = signal<StoreZone[]>([]);
    private readonly _error = signal<string | null>(null);
    readonly storeZones = this._storeZones.asReadonly();
    readonly loading = signal(false).asReadonly();
    readonly error = this._error.asReadonly();
    getStoreZones = vi.fn().mockImplementation(() => {
      this._storeZones.set(mockStoreZones);
      return of(mockStoreZones);
    });
    removeZone = vi.fn().mockReturnValue(of(undefined));
    enableZone = vi.fn().mockReturnValue(of(mockStoreZones[1]));
    clearError = vi.fn();
  }

  const routerMock = { navigate: vi.fn() };
  const dialogMock = { open: vi.fn() };

  function activatedRouteWith(queryParams: Record<string, string>): unknown {
    return {
      snapshot: {
        paramMap: { get: vi.fn().mockReturnValue(null) },
        queryParamMap: {
          get: vi.fn().mockImplementation((key: string) => queryParams[key] ?? null),
        },
      },
    };
  }

  /** Backend failure simulating the real HTTP envelope (wrapped by rxResource). */
  function httpFailure(message: string): HttpErrorResponse {
    return new HttpErrorResponse({
      error: { message },
      status: 503,
      statusText: 'Service Unavailable',
    });
  }

  async function setup(queryParams: Record<string, string> = {}): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [StoreZonesPage, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: CompanyService, useClass: MockCompanyService },
        { provide: CompanyCountryService, useClass: MockCompanyCountryService },
        { provide: CompanyRegionService, useClass: MockCompanyRegionService },
        { provide: CompanyZoneService, useClass: MockCompanyZoneService },
        { provide: CompanyStoreService, useClass: MockCompanyStoreService },
        { provide: StoreAreaService, useClass: MockStoreAreaService },
        { provide: StoreZoneService, useClass: MockStoreZoneService },
        { provide: Router, useValue: routerMock },
        { provide: MatDialog, useValue: dialogMock },
        { provide: ActivatedRoute, useValue: activatedRouteWith(queryParams) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StoreZonesPage);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  // Flush the reactive resource pipeline: trigger CD, await async resolution, re-render.
  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /**
   * The query-param cascade resolves level by level
   * (country → region → company zone → store → area), each one triggering the next
   * resource load, so a single settle is not enough.
   */
  async function settleCascade(rounds = 8): Promise<void> {
    for (let i = 0; i < rounds; i++) {
      await settle();
    }
  }

  /** Drives the cascade down to a selected area and waits for the store-zones load. */
  async function selectArea(area: StoreArea = mockAreas[0]): Promise<void> {
    component.onCompanyChange('company-1');
    await settle();
    component.onSelectCountry(mockAssignedCountries[0]);
    await settle();
    component.onSelectRegion(mockRegions[0]);
    await settle();
    component.onSelectCompanyZone(mockCompanyZones[0]);
    await settle();
    component.onSelectStore(mockStores[0]);
    await settle();
    component.onSelectArea(area);
    await settle();
  }

  beforeEach(() => {
    routerMock.navigate.mockClear();
    dialogMock.open.mockReset();
    dialogMock.open.mockReturnValue({ afterClosed: () => of(undefined) });
  });

  describe('initial state', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should create', () => {
      expect(component).toBeTruthy();
    });

    it('should start with no selection', () => {
      expect(component.selectedCompanyId()).toBeNull();
      expect(component.selectedCountry()).toBeNull();
      expect(component.selectedRegion()).toBeNull();
      expect(component.selectedCompanyZone()).toBeNull();
      expect(component.selectedStore()).toBeNull();
      expect(component.selectedArea()).toBeNull();
      expect(component.showDisabled()).toBe(false);
    });

    it('should render the no-area prompt', () => {
      const prompt = fixture.nativeElement.querySelector('.empty-prompt');
      expect(prompt).toBeTruthy();
      expect(prompt.textContent).toContain('Seleccioná un área para ver sus zonas');
    });

    it('should disable "Nueva zona" while no area is selected', () => {
      const button = fixture.nativeElement.querySelector('.header-actions button');
      expect(button).toBeTruthy();
      expect((button as HTMLButtonElement).disabled).toBe(true);
    });

    it('should render no action error before any write', () => {
      expect(component.actionError()).toBeNull();
      expect(fixture.nativeElement.querySelector('.error-state')).toBeNull();
    });

    it('should not request store zones while no area is selected', () => {
      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      expect(zoneService.getStoreZones).not.toHaveBeenCalled();
    });

    it('should NOT navigate on create without a selected area', () => {
      component.onCreateStoreZone();
      expect(routerMock.navigate).not.toHaveBeenCalled();
    });
  });

  describe('cascade wiring', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should issue each level request only once its parent is selected', async () => {
      const countryService = TestBed.inject(
        CompanyCountryService,
      ) as unknown as MockCompanyCountryService;
      const regionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      const companyZoneService = TestBed.inject(
        CompanyZoneService,
      ) as unknown as MockCompanyZoneService;
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;

      component.onCompanyChange('company-1');
      await settle();
      expect(countryService.getCountries).toHaveBeenCalledWith('company-1');
      expect(regionService.getRegions).not.toHaveBeenCalled();

      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();
      expect(regionService.getRegions).toHaveBeenCalledWith('company-1', 'cc-1');
      expect(companyZoneService.getZones).not.toHaveBeenCalled();

      component.onSelectRegion(mockRegions[0]);
      await settle();
      expect(companyZoneService.getZones).toHaveBeenCalledWith('company-1', 'cc-1', 'reg-1');
      expect(storeService.getStores).not.toHaveBeenCalled();

      component.onSelectCompanyZone(mockCompanyZones[0]);
      await settle();
      expect(storeService.getStores).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        true,
      );
      expect(areaService.getAreas).not.toHaveBeenCalled();

      component.onSelectStore(mockStores[0]);
      await settle();
      expect(areaService.getAreas).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-1',
        true,
      );
      expect(storeZoneService.getStoreZones).not.toHaveBeenCalled();

      component.onSelectArea(mockAreas[0]);
      await settle();
      expect(storeZoneService.getStoreZones).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-1',
        'area-1',
        false,
      );
    });

    it('should expose empty guarded reads before anything resolves', () => {
      expect(component.countries()).toEqual([]);
      expect(component.regions()).toEqual([]);
      expect(component.companyZones()).toEqual([]);
      expect(component.stores()).toEqual([]);
      expect(component.areas()).toEqual([]);
      expect(component.storeZones()).toEqual([]);
    });
  });

  describe('cascade resets', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should reset every lower level when the company changes', async () => {
      await selectArea();

      component.onCompanyChange('company-2');

      expect(component.selectedCompanyId()).toBe('company-2');
      expect(component.selectedCountry()).toBeNull();
      expect(component.selectedRegion()).toBeNull();
      expect(component.selectedCompanyZone()).toBeNull();
      expect(component.selectedStore()).toBeNull();
      expect(component.selectedArea()).toBeNull();
    });

    it('should reset region, company zone, store, and area when the country changes', async () => {
      await selectArea();

      component.onSelectCountry(mockAssignedCountries[0]);

      expect(component.selectedRegion()).toBeNull();
      expect(component.selectedCompanyZone()).toBeNull();
      expect(component.selectedStore()).toBeNull();
      expect(component.selectedArea()).toBeNull();
    });

    it('should reset company zone, store, and area when the region changes', async () => {
      await selectArea();

      component.onSelectRegion(mockRegions[0]);

      expect(component.selectedCompanyZone()).toBeNull();
      expect(component.selectedStore()).toBeNull();
      expect(component.selectedArea()).toBeNull();
    });

    it('should reset store and area when the company zone changes', async () => {
      await selectArea();

      component.onSelectCompanyZone(mockCompanyZones[0]);

      expect(component.selectedStore()).toBeNull();
      expect(component.selectedArea()).toBeNull();
    });

    it('should reset the area when the store changes', async () => {
      await selectArea();

      component.onSelectStore(mockStores[0]);

      expect(component.selectedArea()).toBeNull();
    });
  });

  describe('cascade load failures', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should surface a countries failure instead of an empty list', async () => {
      const countryService = TestBed.inject(
        CompanyCountryService,
      ) as unknown as MockCompanyCountryService;
      countryService.getCountries.mockReturnValue(throwError(() => httpFailure('Países caídos')));

      component.onCompanyChange('company-1');
      await settle();

      expect(component.countries()).toEqual([]);
      expect(component.countriesError()).toBe('Países caídos');
      expect(fixture.nativeElement.querySelector('.cascade-error')?.textContent).toContain(
        'Países caídos',
      );
    });

    it('should surface a regions failure instead of an empty list', async () => {
      const regionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      regionService.getRegions.mockReturnValue(throwError(() => httpFailure('Regiones caídas')));

      component.onCompanyChange('company-1');
      await settle();
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();

      expect(component.regions()).toEqual([]);
      expect(component.regionsError()).toBe('Regiones caídas');
      expect(fixture.nativeElement.querySelector('.cascade-error')?.textContent).toContain(
        'Regiones caídas',
      );
    });

    it('should surface a company-zones failure instead of an empty list', async () => {
      const zoneService = TestBed.inject(
        CompanyZoneService,
      ) as unknown as MockCompanyZoneService;
      zoneService.getZones.mockReturnValue(throwError(() => httpFailure('Zonas caídas')));

      component.onCompanyChange('company-1');
      await settle();
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();
      component.onSelectRegion(mockRegions[0]);
      await settle();

      expect(component.companyZones()).toEqual([]);
      expect(component.companyZonesError()).toBe('Zonas caídas');
      expect(fixture.nativeElement.querySelector('.cascade-error')?.textContent).toContain(
        'Zonas caídas',
      );
    });

    it('should surface a stores failure instead of an empty list', async () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      storeService.getStores.mockReturnValue(throwError(() => httpFailure('Tiendas caídas')));

      component.onCompanyChange('company-1');
      await settle();
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();
      component.onSelectRegion(mockRegions[0]);
      await settle();
      component.onSelectCompanyZone(mockCompanyZones[0]);
      await settle();

      expect(component.stores()).toEqual([]);
      expect(component.storesError()).toBe('Tiendas caídas');
      expect(fixture.nativeElement.querySelector('.cascade-error')?.textContent).toContain(
        'Tiendas caídas',
      );
    });

    it('should surface an areas failure instead of an empty list', async () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.getAreas.mockReturnValue(throwError(() => httpFailure('Áreas caídas')));

      component.onCompanyChange('company-1');
      await settle();
      component.onSelectCountry(mockAssignedCountries[0]);
      await settle();
      component.onSelectRegion(mockRegions[0]);
      await settle();
      component.onSelectCompanyZone(mockCompanyZones[0]);
      await settle();
      component.onSelectStore(mockStores[0]);
      await settle();

      expect(component.areas()).toEqual([]);
      expect(component.areasError()).toBe('Áreas caídas');
      expect(fixture.nativeElement.querySelector('.cascade-error')?.textContent).toContain(
        'Áreas caídas',
      );
    });
  });

  describe('store zones loading', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should request stores and areas including disabled ones', async () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;

      await selectArea();

      // includeDisabled must be true so a pre-selected disabled ancestor still resolves.
      expect(storeService.getStores).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        true,
      );
      expect(areaService.getAreas).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-1',
        true,
      );
    });

    it('should load the selected area store zones with the full nested chain', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;

      await selectArea();

      expect(storeZoneService.getStoreZones).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-1',
        'area-1',
        false,
      );
      expect(component.storeZones().length).toBe(2);
    });

    it('should re-request store zones when the selected area changes', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;

      await selectArea();
      component.onSelectArea(mockAreas[1]);
      await settle();

      expect(storeZoneService.getStoreZones).toHaveBeenLastCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-1',
        'area-2',
        false,
      );
    });

    it('should request disabled store zones when the toggle is on', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;

      await selectArea();
      component.showDisabled.set(true);
      await settle();

      expect(storeZoneService.getStoreZones).toHaveBeenLastCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-1',
        'area-1',
        true,
      );
    });

    it('should render one card per store zone with its code and status badge', async () => {
      await selectArea();

      const cards = fixture.nativeElement.querySelectorAll('mat-card.store-zone-card');
      expect(cards.length).toBe(2);

      const text = fixture.nativeElement.textContent;
      expect(text).toContain('Estantería A');
      expect(text).toContain('ESTANTERIA-A');
      expect(text).toContain('Activa');
      expect(text).toContain('Inactiva');
      expect(text).toContain('Ord. 1');
      expect(text).toContain('Pasillo norte');
    });

    it('should mark disabled stores in the store selector', async () => {
      await selectArea();

      // Material renders the options lazily, so open the panel to inspect the labels.
      const selectDebug = fixture.debugElement.query(
        By.css('mat-form-field.store-selector mat-select'),
      );
      const select = selectDebug.componentInstance as MatSelect;
      select.open();
      fixture.detectChanges();

      const options = Array.from(document.querySelectorAll('mat-option') as NodeListOf<Element>);
      expect(options.some((o) => o.textContent?.includes('(deshabilitada)'))).toBe(true);

      select.close();
      fixture.detectChanges();
    });

    it('should render the empty state when the selected area has no zones', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      storeZoneService.getStoreZones.mockImplementation(() => {
        return of([] as StoreZone[]);
      });

      await selectArea();

      const empty = fixture.nativeElement.querySelector('.empty-state');
      expect(empty).toBeTruthy();
      expect(empty.textContent).toContain('No hay zonas registradas para esta área');
    });
  });

  describe('onCreateStoreZone', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should enable "Nueva zona" once an area is selected', async () => {
      await selectArea();

      const button = fixture.nativeElement.querySelector('.header-actions button');
      expect((button as HTMLButtonElement).disabled).toBe(false);
    });

    it('should navigate to create carrying the whole six-level chain', async () => {
      await selectArea();

      component.onCreateStoreZone();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones/create'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'czone-1',
          storeId: 'store-1',
          areaId: 'area-1',
        },
      });
    });
  });

  describe('onEditStoreZone', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should navigate to the edit route with the store zone as state', async () => {
      await selectArea();

      component.onEditStoreZone(mockStoreZones[0]);

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones/edit', 'szone-1'], {
        state: { storeZone: mockStoreZones[0] },
      });
    });
  });

  describe('onToggleStoreZone', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should open a destructive confirm dialog when disabling', async () => {
      await selectArea();

      component.onToggleStoreZone(mockStoreZones[0]);

      expect(dialogMock.open).toHaveBeenCalledWith(
        ConfirmDialog,
        expect.objectContaining({
          data: expect.objectContaining({
            title: 'Deshabilitar zona de tienda',
            message:
              '¿Confirmás que querés deshabilitar la zona "Estantería A"? La información se conserva y podés reactivarla más adelante.',
            confirmLabel: 'Deshabilitar',
            destructive: true,
          }),
        }),
      );
    });

    it('should call removeZone and reload when the dialog is confirmed', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      dialogMock.open.mockReturnValue({ afterClosed: () => of(true) });

      await selectArea();
      expect(component.reload()).toBe(0);

      component.onToggleStoreZone(mockStoreZones[0]);
      await settle();

      expect(storeZoneService.removeZone).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-1',
        'area-1',
        'szone-1',
      );
      expect(component.reload()).toBe(1);
      expect(component.actionError()).toBeNull();
    });

    it('should NOT call removeZone when the dialog is dismissed', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      dialogMock.open.mockReturnValue({ afterClosed: () => of(false) });

      await selectArea();

      component.onToggleStoreZone(mockStoreZones[0]);
      await settle();

      expect(storeZoneService.removeZone).not.toHaveBeenCalled();
      expect(component.reload()).toBe(0);
    });

    it('should call enableZone for a disabled store zone and reload', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;

      await selectArea();

      component.onToggleStoreZone(mockStoreZones[1]); // enabled: false
      await settle();

      expect(storeZoneService.enableZone).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-1',
        'area-1',
        'szone-2',
      );
      expect(dialogMock.open).not.toHaveBeenCalled();
      expect(component.reload()).toBe(1);
    });

    it('should render "Reactivar" for a disabled store zone', async () => {
      await selectArea();

      const labels = Array.from(
        fixture.nativeElement.querySelectorAll('button[aria-label]') as NodeListOf<Element>,
      ).map((b) => b.getAttribute('aria-label'));
      expect(labels).toContain('Reactivar zona');
      expect(labels).toContain('Deshabilitar zona');
    });

    it('should surface the backend message when disabling fails', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      dialogMock.open.mockReturnValue({ afterClosed: () => of(true) });
      storeZoneService.removeZone.mockReturnValue(
        throwError(() => ({ error: { message: 'El área está deshabilitada' } })),
      );

      await selectArea();

      component.onToggleStoreZone(mockStoreZones[0]);
      await settle();

      expect(component.actionError()).toBe('El área está deshabilitada');
      expect(component.reload()).toBe(0);

      const errorEl = fixture.nativeElement.querySelector('.error-state');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('El área está deshabilitada');
    });

    it('should surface the backend message when re-enabling fails', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      storeZoneService.enableZone.mockReturnValue(
        throwError(() => ({ error: { message: 'La tienda está deshabilitada' } })),
      );

      await selectArea();

      component.onToggleStoreZone(mockStoreZones[1]); // enabled: false
      await settle();

      expect(component.actionError()).toBe('La tienda está deshabilitada');
      expect(component.reload()).toBe(0);

      const errorEl = fixture.nativeElement.querySelector('.error-state');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('La tienda está deshabilitada');
    });

    it('should fall back to a generic message when the failure carries none', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      storeZoneService.enableZone.mockReturnValue(throwError(() => ({ error: {} })));

      await selectArea();

      component.onToggleStoreZone(mockStoreZones[1]);
      await settle();

      expect(component.actionError()).toBe('No se pudo actualizar la zona de la tienda.');
    });

    it('should clear a previous action error on the next toggle attempt', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      storeZoneService.enableZone.mockReturnValue(
        throwError(() => ({ error: { message: 'La tienda está deshabilitada' } })),
      );

      await selectArea();

      component.onToggleStoreZone(mockStoreZones[1]);
      await settle();
      expect(component.actionError()).toBe('La tienda está deshabilitada');

      storeZoneService.enableZone.mockReturnValue(of(mockStoreZones[1]));
      component.onToggleStoreZone(mockStoreZones[1]);
      await settle();

      expect(component.actionError()).toBeNull();
      expect(component.reload()).toBe(1);
    });
  });

  describe('query-param pre-selection', () => {
    beforeEach(async () => {
      await setup({
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'czone-1',
        storeId: 'store-2',
        areaId: 'area-3',
      });
      await settleCascade();
    });

    it('should seed the company from the query params', () => {
      expect(component.selectedCompanyId()).toBe('company-1');
    });

    it('should resolve the whole six-level cascade', async () => {
      await settleCascade();

      expect(component.selectedCountry()?.id).toBe('cc-1');
      expect(component.selectedRegion()?.id).toBe('reg-1');
      expect(component.selectedCompanyZone()?.id).toBe('czone-1');
      expect(component.selectedStore()?.id).toBe('store-2');
      expect(component.selectedArea()?.id).toBe('area-3');
    });

    it('should request areas of the pre-selected (disabled) store including disabled ones', async () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;

      await settleCascade();

      // includeDisabled must be true so the pre-selected disabled area resolves.
      expect(areaService.getAreas).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-2',
        true,
      );
    });

    it('should load store zones for the pre-selected disabled area', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;

      await settleCascade();

      // The nested chain is rebuilt from the area's own fields.
      expect(storeZoneService.getStoreZones).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'czone-1',
        'store-2',
        'area-3',
        false,
      );
    });

    it('should enable "Nueva zona" after the cascade resolves', async () => {
      await settleCascade();

      const button = fixture.nativeElement.querySelector('.header-actions button');
      expect((button as HTMLButtonElement).disabled).toBe(false);
    });
  });

  describe('error state', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should render the service error message', async () => {
      const storeZoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      (storeZoneService as unknown as { _error: WritableSignal<string | null> })._error.set(
        'Error al cargar las zonas de la tienda',
      );

      await selectArea();

      const errorEl = fixture.nativeElement.querySelector('.error-state');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Error al cargar las zonas de la tienda');
    });
  });
});
