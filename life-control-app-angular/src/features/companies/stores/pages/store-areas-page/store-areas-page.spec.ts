import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { signal, Type, WritableSignal } from '@angular/core';
import { throwError, of } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSelect } from '@angular/material/select';
import { StoreAreasPage } from './store-areas-page';
import { ConfirmDialog } from '@shared/ui';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { StoreAreaService } from '../../data/store-area.service';
import { CompanyStore } from '../../models/store.models';
import { StoreArea } from '../../models/store-area.models';
import { CompanyZone } from '../../../zones/models/zone.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';

describe('StoreAreasPage', () => {
  let component: StoreAreasPage;
  let fixture: ComponentFixture<StoreAreasPage>;

  /**
   * The listing pages provide their leaf service themselves — a page-scoped instance so the
   * service's `error` signal cannot leak in from another route (slice 3, step 2d). The module
   * level mock therefore cannot reach them; the override lives in the component injector.
   */
  function pageService<T>(type: Type<T>): T {
    return fixture.debugElement.injector.get(type);
  }

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
  ];

  const mockStores: CompanyStore[] = [
    {
      id: 'store-1',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'zone-1',
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
      zoneId: 'zone-1',
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
      zoneId: 'zone-1',
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
      zoneId: 'zone-1',
      areaCode: 'MOSTRADOR',
      areaName: 'Mostrador',
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
    readonly companyZones = this._zones.asReadonly();
    readonly loading = signal(false).asReadonly();
    readonly error = signal<string | null>(null).asReadonly();
    getZones = vi.fn().mockImplementation(() => {
      this._zones.set(mockZones);
      return of(mockZones);
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
    private readonly _error = signal<string | null>(null);
    readonly areas = this._areas.asReadonly();
    readonly loading = signal(false).asReadonly();
    readonly error = this._error.asReadonly();
    getAreas = vi.fn().mockImplementation(() => {
      this._areas.set(mockAreas);
      return of(mockAreas);
    });
    removeArea = vi.fn().mockReturnValue(of(undefined));
    enableArea = vi.fn().mockReturnValue(of(mockAreas[1]));
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
    TestBed.configureTestingModule({
      imports: [StoreAreasPage, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: CompanyService, useClass: MockCompanyService },
        { provide: CompanyCountryService, useClass: MockCompanyCountryService },
        { provide: CompanyRegionService, useClass: MockCompanyRegionService },
        { provide: CompanyZoneService, useClass: MockCompanyZoneService },
        { provide: CompanyStoreService, useClass: MockCompanyStoreService },
        { provide: Router, useValue: routerMock },
        { provide: MatDialog, useValue: dialogMock },
        { provide: ActivatedRoute, useValue: activatedRouteWith(queryParams) },
      ],
    });
    // The page-scoped leaf service (2d): replace the component-level provider with the mock.
    TestBed.overrideComponent(StoreAreasPage, {
      set: { providers: [{ provide: StoreAreaService, useClass: MockStoreAreaService }] },
    });
    await TestBed.compileComponents();

    fixture = TestBed.createComponent(StoreAreasPage);
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
   * The query-param cascade resolves level by level (country → region → zone → store),
   * each one triggering the next resource load, so a single settle is not enough.
   */
  async function settleCascade(rounds = 6): Promise<void> {
    for (let i = 0; i < rounds; i++) {
      await settle();
    }
  }

  /** Drives the cascade down to a selected store and waits for the areas load. */
  async function selectStore(store: CompanyStore = mockStores[0]): Promise<void> {
    component.onCompanyChange('company-1');
    await settle();
    component.onSelectCountry(mockAssignedCountries[0]);
    await settle();
    component.onSelectRegion(mockRegions[0]);
    await settle();
    component.onSelectCompanyZone(mockZones[0]);
    await settle();
    component.onSelectStore(store);
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
      expect(component.showDisabled()).toBe(false);
    });

    it('should render the no-store prompt', () => {
      const prompt = fixture.nativeElement.querySelector('.empty-prompt');
      expect(prompt).toBeTruthy();
      expect(prompt.textContent).toContain('Seleccioná una tienda para ver sus áreas');
    });

    it('should disable "Nueva área" while no store is selected', () => {
      const button = fixture.nativeElement.querySelector('.header-actions button');
      expect(button).toBeTruthy();
      expect((button as HTMLButtonElement).disabled).toBe(true);
    });

    it('should not request areas while no store is selected', () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;
      expect(areaService.getAreas).not.toHaveBeenCalled();
    });

    it('should NOT navigate on create without a selected store', () => {
      component.onCreateArea();
      expect(routerMock.navigate).not.toHaveBeenCalled();
    });
  });

  describe('cascade resets', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should reset every lower level when the company changes', async () => {
      await selectStore();

      component.onCompanyChange('company-2');

      expect(component.selectedCompanyId()).toBe('company-2');
      expect(component.selectedCountry()).toBeNull();
      expect(component.selectedRegion()).toBeNull();
      expect(component.selectedCompanyZone()).toBeNull();
      expect(component.selectedStore()).toBeNull();
    });

    it('should reset region, zone, and store when the country changes', async () => {
      await selectStore();

      component.onSelectCountry(mockAssignedCountries[0]);

      expect(component.selectedRegion()).toBeNull();
      expect(component.selectedCompanyZone()).toBeNull();
      expect(component.selectedStore()).toBeNull();
    });

    it('should reset zone and store when the region changes', async () => {
      await selectStore();

      component.onSelectRegion(mockRegions[0]);

      expect(component.selectedCompanyZone()).toBeNull();
      expect(component.selectedStore()).toBeNull();
    });

    it('should reset the store when the zone changes', async () => {
      await selectStore();

      component.onSelectCompanyZone(mockZones[0]);

      expect(component.selectedStore()).toBeNull();
    });
  });

  describe('areas loading', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should request stores including disabled ones', async () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;

      await selectStore();

      // includeDisabled must be true so a pre-selected disabled store still resolves.
      expect(storeService.getStores).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        true,
      );
    });

    it('should load the selected store areas with the full nested chain', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;

      await selectStore();

      expect(areaService.getAreas).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        false,
      );
      expect(component.areas().length).toBe(2);
    });

    it('should request disabled areas when the toggle is on', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;

      await selectStore();
      component.showDisabled.set(true);
      await settle();

      expect(areaService.getAreas).toHaveBeenLastCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        true,
      );
    });

    it('should render one card per area with its status badge', async () => {
      await selectStore();

      const cards = fixture.nativeElement.querySelectorAll('mat-card.area-card');
      expect(cards.length).toBe(2);

      const text = fixture.nativeElement.textContent;
      expect(text).toContain('Almacén Central');
      expect(text).toContain('ALMACEN');
      expect(text).toContain('Activa');
      expect(text).toContain('Inactiva');
    });

    it('should mark disabled stores in the store selector', async () => {
      await selectStore(mockStores[1]);

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

    it('should expose empty guarded reads before anything resolves', () => {
      expect(component.countries()).toEqual([]);
      expect(component.regions()).toEqual([]);
      expect(component.companyZones()).toEqual([]);
      expect(component.stores()).toEqual([]);
      expect(component.areas()).toEqual([]);
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
      const zoneService = TestBed.inject(CompanyZoneService) as unknown as MockCompanyZoneService;
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
      component.onSelectCompanyZone(mockZones[0]);
      await settle();

      expect(component.stores()).toEqual([]);
      expect(component.storesError()).toBe('Tiendas caídas');
      expect(fixture.nativeElement.querySelector('.cascade-error')?.textContent).toContain(
        'Tiendas caídas',
      );
    });
  });

  describe('onCreateArea', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should enable "Nueva área" once a store is selected', async () => {
      await selectStore();

      const button = fixture.nativeElement.querySelector('.header-actions button');
      expect((button as HTMLButtonElement).disabled).toBe(false);
    });

    it('should navigate to create carrying the whole chain', async () => {
      await selectStore();

      component.onCreateArea();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas/create'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
        },
      });
    });
  });

  describe('onEditArea', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should navigate to the edit route with the area as state', async () => {
      await selectStore();

      component.onEditArea(mockAreas[0]);

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas/edit', 'area-1'], {
        state: { area: mockAreas[0] },
      });
    });
  });

  describe('onToggleArea', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should open a destructive confirm dialog when disabling', async () => {
      await selectStore();

      component.onToggleArea(mockAreas[0]);

      expect(dialogMock.open).toHaveBeenCalledWith(
        ConfirmDialog,
        expect.objectContaining({
          data: expect.objectContaining({
            title: 'Deshabilitar área',
            confirmLabel: 'Deshabilitar',
            destructive: true,
          }),
        }),
      );
    });

    it('should call removeArea and reload when the dialog is confirmed', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;
      dialogMock.open.mockReturnValue({ afterClosed: () => of(true) });

      await selectStore();
      expect(component.reload()).toBe(0);

      component.onToggleArea(mockAreas[0]);
      await settle();

      expect(areaService.removeArea).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        'area-1',
      );
      expect(component.reload()).toBe(1);
    });

    it('should NOT call removeArea when the dialog is dismissed', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;
      dialogMock.open.mockReturnValue({ afterClosed: () => of(false) });

      await selectStore();

      component.onToggleArea(mockAreas[0]);
      await settle();

      expect(areaService.removeArea).not.toHaveBeenCalled();
      expect(component.reload()).toBe(0);
    });

    it('should call enableArea for a disabled area and reload', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;

      await selectStore();

      component.onToggleArea(mockAreas[1]); // enabled: false
      await settle();

      expect(areaService.enableArea).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        'area-2',
      );
      expect(dialogMock.open).not.toHaveBeenCalled();
      expect(component.reload()).toBe(1);
    });

    it('should render "Reactivar" for a disabled area', async () => {
      await selectStore();

      const labels = Array.from(
        fixture.nativeElement.querySelectorAll('button[aria-label]') as NodeListOf<Element>,
      ).map((b) => b.getAttribute('aria-label'));
      expect(labels).toContain('Reactivar área');
      expect(labels).toContain('Deshabilitar área');
    });

    it('should surface the backend message when disabling fails', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;
      dialogMock.open.mockReturnValue({ afterClosed: () => of(true) });
      areaService.removeArea.mockReturnValue(
        throwError(() => ({ error: { message: 'La tienda está deshabilitada' } })),
      );

      await selectStore();

      component.onToggleArea(mockAreas[0]);
      await settle();

      expect(component.actionError()).toBe('La tienda está deshabilitada');
      expect(component.reload()).toBe(0);

      const errorEl = fixture.nativeElement.querySelector('.error-state');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('La tienda está deshabilitada');
    });

    it('should surface the backend message when re-enabling fails', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.enableArea.mockReturnValue(
        throwError(() => ({ error: { message: 'La tienda está deshabilitada' } })),
      );

      await selectStore();

      component.onToggleArea(mockAreas[1]); // enabled: false
      await settle();

      expect(component.actionError()).toBe('La tienda está deshabilitada');
      expect(component.reload()).toBe(0);

      const errorEl = fixture.nativeElement.querySelector('.error-state');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('La tienda está deshabilitada');
    });

    it('should fall back to a generic message when the failure carries none', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.enableArea.mockReturnValue(throwError(() => ({ error: {} })));

      await selectStore();

      component.onToggleArea(mockAreas[1]);
      await settle();

      expect(component.actionError()).toBe('No se pudo actualizar el área de la tienda.');
    });

    it('should clear a previous action error on the next toggle attempt', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.enableArea.mockReturnValue(
        throwError(() => ({ error: { message: 'La tienda está deshabilitada' } })),
      );

      await selectStore();

      component.onToggleArea(mockAreas[1]);
      await settle();
      expect(component.actionError()).toBe('La tienda está deshabilitada');

      areaService.enableArea.mockReturnValue(of(mockAreas[1]));
      component.onToggleArea(mockAreas[1]);
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
        zoneId: 'zone-1',
        storeId: 'store-2',
      });
      await settleCascade();
    });

    it('should seed the company from the query params', () => {
      expect(component.selectedCompanyId()).toBe('company-1');
    });

    it('should resolve the whole cascade including the store', async () => {
      await settleCascade();

      expect(component.selectedCountry()?.id).toBe('cc-1');
      expect(component.selectedRegion()?.id).toBe('reg-1');
      expect(component.selectedCompanyZone()?.id).toBe('zone-1');
      expect(component.selectedStore()?.id).toBe('store-2');
    });

    it('should load areas for the pre-selected (disabled) store', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;

      await settleCascade();

      expect(areaService.getAreas).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-2',
        false,
      );
    });
  });

  describe('error state', () => {
    beforeEach(async () => {
      await setup();
    });

    it('should render the service error message', async () => {
      const areaService = pageService(StoreAreaService) as unknown as MockStoreAreaService;
      (areaService as unknown as { _error: WritableSignal<string | null> })._error.set(
        'Error al cargar las áreas',
      );

      await selectStore();

      const errorEl = fixture.nativeElement.querySelector('.error-state');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Error al cargar las áreas');
    });
  });
});
