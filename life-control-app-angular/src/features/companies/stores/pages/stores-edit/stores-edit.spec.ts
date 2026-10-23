import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatTabGroup } from '@angular/material/tabs';
import Keycloak from 'keycloak-js';
import { StoresEdit } from './stores-edit';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { StoreInventorySettingsPanel } from '../../components/store-inventory-settings-panel/store-inventory-settings-panel';
import { StoreInventorySettingsService } from '@features/inventory/data/store-inventory-settings.service';
import { StoreLocationLookupService } from '@features/inventory/data/store-location-lookup.service';
import type { StoreLocationSummary } from '@features/inventory/models/store-location-summary.models';
import { CompanyStore, StoreSaveEvent } from '../../models/store.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';
import { CompanyZone } from '../../../zones/models/zone.models';

describe('StoresEdit', () => {
  let component: StoresEdit;
  let fixture: ComponentFixture<StoresEdit>;

  const mockCompanies: Company[] = [
    {
      id: 'company-1',
      companyKey: 'COMP001',
      companyName: 'Test Company',
      tipoPersonaId: 1,
      razonSocial: 'Test Company SA',
      rfc: 'RFC123',
      email: 'test@company.com',
      phone: '+525512345678',
      enabled: true,
      createdAt: '',
      updatedAt: '',
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

  const mockCountries: CompanyCountry[] = [
    {
      id: 'cc-1',
      companyId: 'company-1',
      countryId: '1',
      countryCode: 'MX',
      countryName: 'Mexico',
      localAlias: null,
      createdAt: '',
      updatedAt: '',
    },
  ];

  const mockRegions: CompanyRegion[] = [
    {
      id: 'reg-1',
      companyCountryId: 'cc-1',
      companyId: 'company-1',
      countryId: '1',
      regionCode: 'CENTRO',
      regionName: 'Centro',
      enabled: true,
      createdAt: '',
      updatedAt: '',
    },
  ];

  const mockZones: CompanyZone[] = [
    {
      id: 'zone-1',
      companyRegionId: 'reg-1',
      companyCountryId: 'cc-1',
      companyId: 'company-1',
      countryId: '1',
      zoneCode: 'DT',
      zoneName: 'Downtown',
      displayOrder: 1,
      enabled: true,
      createdAt: '',
      updatedAt: '',
    },
  ];

  const mockStore: CompanyStore = {
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
      city: 'CDMX',
      state: 'CDMX',
      countryId: 'MX',
    },
    enabled: true,
    createdAt: '',
    updatedAt: '',
    version: 3,
  };

  class MockCompanyService {
    getCompanySelector = vi.fn().mockReturnValue(of(mockCompaniesPage));
  }

  class MockCompanyCountryService {
    private readonly _assignedCountries = signal<CompanyCountry[]>([]);
    readonly assignedCountries = this._assignedCountries.asReadonly();
    readonly loading = signal(false).asReadonly();
    readonly error = signal<string | null>(null).asReadonly();
    getCountries = vi.fn().mockImplementation(() => {
      this._assignedCountries.set(mockCountries);
      return of(mockCountries);
    });
  }

  class MockCompanyRegionService {
    _regions = signal<CompanyRegion[]>([]);
    readonly regions = this._regions.asReadonly();
    getRegions = vi.fn().mockImplementation(() => {
      this._regions.set(mockRegions);
      return of(mockRegions);
    });
  }

  class MockCompanyZoneService {
    _zones = signal<CompanyZone[]>([]);
    readonly zones = this._zones.asReadonly();
    getZones = vi.fn().mockReturnValue(of(mockZones));
  }

  class MockCompanyStoreService {
    addStore = vi.fn().mockReturnValue(of(mockStore));
    updateStore = vi.fn().mockReturnValue(of(mockStore));
  }

  const mockStoreLocations: StoreLocationSummary[] = [
    {
      id: 'loc-1',
      locationCode: 'REC-01',
      locationName: 'Recepción',
      storeZoneId: 'zone-a',
      zoneCode: 'Z-A',
      zoneName: 'Zona A',
      storeAreaId: 'area-1',
      areaCode: 'A-1',
      areaName: 'Área Uno',
    },
    {
      id: 'loc-2',
      locationCode: 'VEN-01',
      locationName: 'Venta',
      storeZoneId: 'zone-b',
      zoneCode: 'Z-B',
      zoneName: 'Zona B',
      storeAreaId: 'area-2',
      areaCode: 'A-2',
      areaName: 'Área Dos',
    },
  ];

  class MockStoreInventorySettingsService {
    getSettings = vi.fn().mockReturnValue(of(null));
    upsertSettings = vi.fn().mockReturnValue(of(null));
  }

  class MockStoreLocationLookupService {
    getStoreLocations = vi.fn().mockReturnValue(of(mockStoreLocations));
  }

  /** The chain the edit page derives from the store it is editing. */
  const editChain = {
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    storeId: 'store-1',
  };

  function createActivatedRoute(params: { id?: string; queryParams?: Record<string, string> }) {
    return {
      snapshot: {
        paramMap: { get: vi.fn().mockReturnValue(params.id ?? null) },
        queryParamMap: {
          get: vi.fn().mockImplementation((key: string) => params.queryParams?.[key] ?? null),
        },
      },
    };
  }

  // ─── Default: create mode ───────────────────────────────────

  describe('create mode', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [StoresEdit, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: CompanyZoneService, useClass: MockCompanyZoneService },
          { provide: CompanyStoreService, useClass: MockCompanyStoreService },
          { provide: Router, useValue: { navigate: vi.fn() } },
          {
            provide: ActivatedRoute,
            useValue: createActivatedRoute({
              queryParams: {
                companyId: 'company-1',
                countryId: 'cc-1',
                regionId: 'reg-1',
                zoneId: 'zone-1',
              },
            }),
          },
        ],
      }).compileComponents();

      fixture = TestBed.createComponent(StoresEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should create', () => {
      expect(component).toBeTruthy();
    });

    it('should be in create mode (isEditMode = false)', () => {
      expect(component.isEditMode()).toBe(false);
    });

    it('should not render the tab bar before the store exists', () => {
      expect(fixture.nativeElement.querySelectorAll('.mat-mdc-tab').length).toBe(0);
      expect(fixture.nativeElement.querySelector('app-stores-form')).toBeTruthy();
    });

    it('should set initialCompanyId from query params', () => {
      expect(component.initialCompanyId()).toBe('company-1');
    });

    it('should set initialCountryId from query params', () => {
      expect(component.initialCountryId()).toBe('cc-1');
    });

    it('should set initialRegionId from query params', () => {
      expect(component.initialRegionId()).toBe('reg-1');
    });

    it('should set initialZoneId from query params', () => {
      expect(component.initialZoneId()).toBe('zone-1');
    });

    it('should load countries on init when companyId is present', () => {
      const countryService = TestBed.inject(
        CompanyCountryService,
      ) as unknown as MockCompanyCountryService;
      expect(countryService.getCountries).toHaveBeenCalledWith('company-1');
    });

    it('should load regions on init when countryId is present', () => {
      const regionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      expect(regionService.getRegions).toHaveBeenCalledWith('company-1', 'cc-1');
    });

    it('should call addStore on save when not edit mode', () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'New Store' },
      };
      component.onSaveStore(event);

      expect(storeService.addStore).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        event.request,
      );
      expect(router.navigate).toHaveBeenCalledWith(['/companies/stores'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
        },
      });
    });

    it('should not include the version key in the create request body', () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'New Store' },
      };
      component.onSaveStore(event);

      const requestBody = storeService.addStore.mock.calls[0][4] as Record<string, unknown>;
      // Create has no prior version: the key must be absent, not merely undefined.
      expect('version' in requestBody).toBe(false);
    });

    it('should handle addStore errors via serverErrors', () => {
      const apiError = { status: 400, message: 'Error', errors: { storeName: 'Already exists' } };
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      storeService.addStore = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: apiError,
              status: 400,
              statusText: 'Bad Request',
            }),
        ),
      );

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'Duplicate Store' },
      };
      component.onSaveStore(event);

      expect(component.serverErrors()).toEqual({ storeName: 'Already exists' });
    });

    it('should surface a 409 without an errors map as a visible general message', () => {
      const apiError = {
        status: 409,
        message: 'Store already exists in this zone',
        path: '/api/companies/company-1/stores',
        timestamp: '2024-01-01',
        correlationId: 'abc',
      };
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      storeService.addStore = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: apiError,
              status: 409,
              statusText: 'Conflict',
            }),
        ),
      );

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'Duplicate Store' },
      };
      component.onSaveStore(event);
      fixture.detectChanges();

      expect(component.generalError()).toBe('Store already exists in this zone');

      const banner = fixture.nativeElement.querySelector('app-error-banner');
      expect(banner).toBeTruthy();
      expect(banner.textContent).toContain('Store already exists in this zone');
    });

    it('should scroll the error banner into view when a save fails', () => {
      // Same geometry as the areas, zones and locations pages: the banner sits above a long form
      // whose actions are at the bottom, so the page opts it into scrolling itself into view.
      const scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView');
      const apiError = {
        status: 409,
        message: 'Store already exists in this zone',
        path: '/api/companies/company-1/stores',
        timestamp: '2024-01-01',
        correlationId: 'abc',
      };
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      storeService.addStore = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: apiError,
              status: 409,
              statusText: 'Conflict',
            }),
        ),
      );

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'Duplicate Store' },
      };
      component.onSaveStore(event);
      fixture.detectChanges();

      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
    });

    it('should fall back to a generic message when a non-field failure carries none', () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      storeService.addStore = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { status: 404 },
              status: 404,
              statusText: 'Not Found',
            }),
        ),
      );

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'Missing Store' },
      };
      component.onSaveStore(event);

      expect(component.generalError()).toBe('No se pudo guardar la tienda.');
    });
  });

  // ─── Create mode without query params ───────────────────────

  describe('create mode without query params', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [StoresEdit, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: CompanyZoneService, useClass: MockCompanyZoneService },
          { provide: CompanyStoreService, useClass: MockCompanyStoreService },
          { provide: Router, useValue: { navigate: vi.fn() } },
          {
            provide: ActivatedRoute,
            useValue: createActivatedRoute({}),
          },
        ],
      }).compileComponents();

      fixture = TestBed.createComponent(StoresEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should have null initial values', () => {
      expect(component.initialCompanyId()).toBeNull();
      expect(component.initialCountryId()).toBeNull();
      expect(component.initialRegionId()).toBeNull();
      expect(component.initialZoneId()).toBeNull();
    });
  });

  // ─── Edit mode ──────────────────────────────────────────────

  describe('edit mode', () => {
    beforeEach(async () => {
      globalThis.history = {
        ...globalThis.history,
        state: { store: mockStore },
      };

      await TestBed.configureTestingModule({
        imports: [StoresEdit, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: CompanyZoneService, useClass: MockCompanyZoneService },
          { provide: CompanyStoreService, useClass: MockCompanyStoreService },
          // Edit mode renders the tab group, and Material mounts both tab bodies eagerly, so the
          // configuration panel is constructed even while `Datos` is selected: its collaborators
          // have to be provided here too.
          {
            provide: StoreInventorySettingsService,
            useValue: {
              getSettings: vi.fn().mockReturnValue(of(null)),
              upsertSettings: vi.fn().mockReturnValue(of(null)),
            },
          },
          {
            provide: StoreLocationLookupService,
            useValue: { getStoreLocations: vi.fn().mockReturnValue(of(mockStoreLocations)) },
          },
          {
            provide: Keycloak,
            useValue: {
              tokenParsed: {
                resource_access: { 'life-control-client': { roles: ['lc-company-store'] } },
              },
            },
          },
          { provide: Router, useValue: { navigate: vi.fn() } },
          {
            provide: ActivatedRoute,
            useValue: createActivatedRoute({ id: 'store-1' }),
          },
        ],
      }).compileComponents();

      fixture = TestBed.createComponent(StoresEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    afterEach(() => {
      delete (globalThis.history as { state?: unknown }).state;
    });

    it('should be in edit mode (isEditMode = true)', () => {
      expect(component.isEditMode()).toBe(true);
    });

    it('should have storeId set from paramMap', () => {
      expect(component.storeId()).toBe('store-1');
    });

    it('should load store from history.state', () => {
      expect(component.storeToEdit()).toEqual(mockStore);
    });

    it('should load regions for the store country', () => {
      const regionService = TestBed.inject(
        CompanyRegionService,
      ) as unknown as MockCompanyRegionService;
      expect(regionService.getRegions).toHaveBeenCalledWith('company-1', 'cc-1');
    });

    it('should call updateStore on save when in edit mode', () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'Updated Store' },
        storeId: 'store-1',
      };
      component.onSaveStore(event);

      expect(storeService.updateStore).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        { ...event.request, version: 3 },
      );
      expect(router.navigate).toHaveBeenCalledWith(['/companies/stores'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
        },
      });
    });

    it('should handle updateStore errors via serverErrors', () => {
      const apiError = { status: 400, message: 'Error', errors: { storeName: 'Name taken' } };
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      storeService.updateStore = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: apiError,
              status: 400,
              statusText: 'Bad Request',
            }),
        ),
      );

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'Duplicate' },
        storeId: 'store-1',
      };
      component.onSaveStore(event);

      expect(component.serverErrors()).toEqual({ storeName: 'Name taken' });
    });

    it('should send the version read from the entity in the update request body', () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'Updated Store' },
        storeId: 'store-1',
      };
      component.onSaveStore(event);

      const requestBody = storeService.updateStore.mock.calls[0][5] as Record<string, unknown>;
      expect(requestBody['version']).toBe(3);
    });

    it('should show the conflict copy and stay on the page when the update is rejected with 412', () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
      storeService.updateStore = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: {
                status: 412,
                message:
                  'The company store conflicts with the current server state; reload and try again',
              },
              status: 412,
              statusText: 'Precondition Failed',
            }),
        ),
      );

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'Updated Store' },
        storeId: 'store-1',
      };
      component.onSaveStore(event);
      fixture.detectChanges();

      // history.state still holds the stale copy, so the copy names the real recovery instead of
      // suggesting a page reload that would only re-seed the same stale version.
      expect(component.generalError()).toBe(
        'Otra sesión modificó esta tienda mientras la editabas. Volvé a la lista y abrí la tienda de nuevo.',
      );
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('should show the server duplicate message and neither reload nor navigate on 409', () => {
      const storeService = TestBed.inject(
        CompanyStoreService,
      ) as unknown as MockCompanyStoreService;
      const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
      storeService.updateStore = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: {
                status: 409,
                message: "Store with name 'Tienda Existente' already exists in this zone",
              },
              status: 409,
              statusText: 'Conflict',
            }),
        ),
      );

      const event: StoreSaveEvent = {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        request: { storeName: 'Updated Store' },
        storeId: 'store-1',
      };
      const storedBefore = component.storeToEdit();
      component.onSaveStore(event);
      fixture.detectChanges();

      // A 409 is a duplicate name, not a lost update: the server's real message wins and the draft
      // is kept, so the page neither re-seeds the entity nor navigates away.
      expect(component.generalError()).toBe(
        "Store with name 'Tienda Existente' already exists in this zone",
      );
      expect(component.storeToEdit()).toEqual(storedBefore);
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  // ─── Configuración tab (inventory settings) ─────────────────

  describe('Configuración tab', () => {
    let settingsService: MockStoreInventorySettingsService;
    let locationService: MockStoreLocationLookupService;
    let router: { navigate: ReturnType<typeof vi.fn> };

    /** Mounts the page in edit mode over the store in `history.state`. */
    async function setupEdit(queryParams: Record<string, string> = {}): Promise<void> {
      globalThis.history = {
        ...globalThis.history,
        state: { store: mockStore },
      };
      settingsService = new MockStoreInventorySettingsService();
      locationService = new MockStoreLocationLookupService();
      router = { navigate: vi.fn() };

      await TestBed.configureTestingModule({
        imports: [StoresEdit, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: CompanyZoneService, useClass: MockCompanyZoneService },
          { provide: CompanyStoreService, useClass: MockCompanyStoreService },
          { provide: StoreInventorySettingsService, useValue: settingsService },
          { provide: StoreLocationLookupService, useValue: locationService },
          {
            provide: Keycloak,
            useValue: {
              tokenParsed: {
                resource_access: { 'life-control-client': { roles: ['lc-company-store'] } },
              },
            },
          },
          { provide: Router, useValue: router },
          {
            provide: ActivatedRoute,
            useValue: createActivatedRoute({ id: 'store-1', queryParams }),
          },
        ],
      }).compileComponents();

      fixture = TestBed.createComponent(StoresEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    }

    afterEach(() => {
      delete (globalThis.history as { state?: unknown }).state;
    });

    function panel(): StoreInventorySettingsPanel | null {
      const debug = fixture.debugElement.query(By.directive(StoreInventorySettingsPanel));
      return debug ? (debug.componentInstance as StoreInventorySettingsPanel) : null;
    }

    function tabLabels(): string[] {
      return Array.from(
        fixture.nativeElement.querySelectorAll('.mat-mdc-tab') as NodeListOf<HTMLElement>,
      ).map((node) => (node.textContent ?? '').trim());
    }

    function tabGroup(): MatTabGroup {
      return fixture.debugElement.query(By.directive(MatTabGroup)).componentInstance as MatTabGroup;
    }

    /** The rendered tab header of the given index — what the operator actually clicks. */
    function tabHeader(index: number): HTMLElement {
      const headers = Array.from(
        fixture.nativeElement.querySelectorAll('.mat-mdc-tab') as NodeListOf<HTMLElement>,
      );
      return headers[index];
    }

    it('should offer the store data and its inventory configuration as tabs', async () => {
      await setupEdit();

      expect(tabLabels()).toEqual(['Datos', 'Configuración']);
    });

    it('should open on the store data tab with the panel already holding the chain', async () => {
      await setupEdit();

      expect(component.activeTab()).toBe('datos');
      expect(component.selectedTabIndex()).toBe(0);
      // Material mounts both tab bodies eagerly, so the panel is constructed — and reads with the
      // chain the page derived from the store — while the operator is still looking at `Datos`.
      // That is what lets the page answer the guard without a first visit to the tab.
      expect(settingsService.getSettings).toHaveBeenCalledWith(editChain);
      expect(locationService.getStoreLocations).toHaveBeenCalledWith(editChain);
    });

    it('should open the Configuración tab when ?tab=configuracion', async () => {
      await setupEdit({ tab: 'configuracion' });

      expect(component.activeTab()).toBe('configuracion');
      expect(component.selectedTabIndex()).toBe(1);
      expect(panel()).toBeTruthy();
    });

    it('should fall back to the data tab for an unknown ?tab value', async () => {
      await setupEdit({ tab: 'inventario' });

      expect(component.activeTab()).toBe('datos');
      expect(component.selectedTabIndex()).toBe(0);
    });

    it('should hand the edited store chain to the panel', async () => {
      await setupEdit({ tab: 'configuracion' });

      expect(panel()?.chain()).toEqual(editChain);
      expect(settingsService.getSettings).toHaveBeenCalledWith(editChain);
      expect(locationService.getStoreLocations).toHaveBeenCalledWith(editChain);
    });

    it('should write the selected tab into the URL, replacing the history entry', async () => {
      await setupEdit();

      component.onTabChange(1);

      expect(router.navigate).toHaveBeenCalledWith([], {
        relativeTo: TestBed.inject(ActivatedRoute),
        queryParams: { tab: 'configuracion' },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
      expect(component.activeTab()).toBe('configuracion');
    });

    it('should not navigate when the tab does not change', async () => {
      await setupEdit();

      component.onTabChange(0);

      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('should keep the panel and its selection when the operator returns to the data tab', async () => {
      await setupEdit({ tab: 'configuracion' });
      const mounted = panel();
      mounted?.onSelectReceiving('loc-1');

      tabHeader(0).click();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(component.activeTab()).toBe('datos');
      // `preserveContent` is load-bearing, not cosmetic: without it Material detaches a tab body
      // when it stops being centred, which would destroy the panel's selections.
      expect(tabGroup().preserveContent).toBe(true);
      expect(panel()).toBe(mounted);
      expect(panel()?.receivingLocationId()).toBe('loc-1');
    });

    it('should report the panel unsaved changes through the page', async () => {
      await setupEdit({ tab: 'configuracion' });

      expect(component.hasUnsavedChanges()).toBe(false);

      panel()?.onSelectReceiving('loc-1');

      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should report no unsaved changes before the operator edits anything', async () => {
      await setupEdit();

      expect(component.hasUnsavedChanges()).toBe(false);
    });
  });

  // ─── Cancel navigation ──────────────────────────────────────

  describe('onCancelForm', () => {
    it('should navigate back with query params from storeToEdit when in edit mode', () => {
      TestBed.configureTestingModule({
        imports: [StoresEdit, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: CompanyZoneService, useClass: MockCompanyZoneService },
          { provide: CompanyStoreService, useClass: MockCompanyStoreService },
          { provide: Router, useValue: { navigate: vi.fn() } },
          { provide: ActivatedRoute, useValue: createActivatedRoute({ id: 'store-1' }) },
        ],
      });
      const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
      const edit = TestBed.createComponent(StoresEdit);
      component = edit.componentInstance;

      // Manually set storeToEdit to simulate edit mode
      component.storeToEdit.set(mockStore);
      component.isEditMode.set(true);

      component.onCancelForm();

      expect(router.navigate).toHaveBeenCalledWith(['/companies/stores'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
        },
      });
    });

    it('should navigate back with initial query params when in create mode', () => {
      TestBed.configureTestingModule({
        imports: [StoresEdit, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: CompanyZoneService, useClass: MockCompanyZoneService },
          { provide: CompanyStoreService, useClass: MockCompanyStoreService },
          { provide: Router, useValue: { navigate: vi.fn() } },
          { provide: ActivatedRoute, useValue: createActivatedRoute({}) },
        ],
      });
      const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
      const edit = TestBed.createComponent(StoresEdit);
      component = edit.componentInstance;

      // Manually set initial values to simulate create with query params
      component.initialCompanyId.set('company-1');
      component.initialCountryId.set('cc-1');
      component.initialRegionId.set('reg-1');
      component.initialZoneId.set('zone-1');

      component.onCancelForm();

      expect(router.navigate).toHaveBeenCalledWith(['/companies/stores'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
        },
      });
    });

    it('should navigate without query params when no context exists', () => {
      TestBed.configureTestingModule({
        imports: [StoresEdit, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: CompanyZoneService, useClass: MockCompanyZoneService },
          { provide: CompanyStoreService, useClass: MockCompanyStoreService },
          { provide: Router, useValue: { navigate: vi.fn() } },
          { provide: ActivatedRoute, useValue: createActivatedRoute({}) },
        ],
      });
      const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };
      const edit = TestBed.createComponent(StoresEdit);
      component = edit.componentInstance;

      component.onCancelForm();

      expect(router.navigate).toHaveBeenCalledWith(['/companies/stores']);
    });
  });
});
