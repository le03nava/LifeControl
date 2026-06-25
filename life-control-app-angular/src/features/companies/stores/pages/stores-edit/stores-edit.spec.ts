import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { StoresEdit } from './stores-edit';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
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
      id: 'company-1', companyKey: 'COMP001', companyName: 'Test Company',
      tipoPersonaId: 1, razonSocial: 'Test Company SA',
      rfc: 'RFC123', email: 'test@company.com', phone: '+525512345678',
      enabled: true, createdAt: '', updatedAt: '',
    },
  ];

  const mockCompaniesPage: Page<Company> = {
    content: mockCompanies, totalElements: 1, totalPages: 1, size: 1000, number: 0,
    first: true, last: true, empty: false,
  };

  const mockCountries: CompanyCountry[] = [
    { id: 'cc-1', companyId: 'company-1', countryId: '1', countryCode: 'MX', countryName: 'Mexico', localAlias: null, createdAt: '', updatedAt: '' },
  ];

  const mockRegions: CompanyRegion[] = [
    { id: 'reg-1', companyCountryId: 'cc-1', companyId: 'company-1', countryId: '1', regionCode: 'CENTRO', regionName: 'Centro', enabled: true, createdAt: '', updatedAt: '' },
  ];

  const mockZones: CompanyZone[] = [
    { id: 'zone-1', companyRegionId: 'reg-1', companyCountryId: 'cc-1', companyId: 'company-1', countryId: '1', zoneCode: 'DT', zoneName: 'Downtown', displayOrder: 1, enabled: true, createdAt: '', updatedAt: '' },
  ];

  const mockStore: CompanyStore = {
    id: 'store-1', companyId: 'company-1', companyCountryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1',
    storeName: 'Tienda Central', email: 'central@store.com', phoneNumber: '+525512345678',
    address: {
      street: 'Av. Reforma', streetNumber: '222', neighborhood: 'Juárez',
      city: 'CDMX', state: 'CDMX', countryId: 'MX',
    },
    enabled: true, createdAt: '', updatedAt: '',
  };

  class MockCompanyService {
    getCompanies = vi.fn().mockReturnValue(of(mockCompaniesPage));
  }

  class MockCompanyCountryService {
    private _assignedCountries = signal<CompanyCountry[]>([]);
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
        imports: [StoresEdit, NoopAnimationsModule, HttpClientTestingModule],
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
              queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1' },
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
      const countryService = TestBed.inject(CompanyCountryService) as unknown as MockCompanyCountryService;
      expect(countryService.getCountries).toHaveBeenCalledWith('company-1');
    });

    it('should load regions on init when countryId is present', () => {
      const regionService = TestBed.inject(CompanyRegionService) as unknown as MockCompanyRegionService;
      expect(regionService.getRegions).toHaveBeenCalledWith('company-1', 'cc-1');
    });

    it('should call addStore on save when not edit mode', () => {
      const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
      const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

      const event: StoreSaveEvent = {
        companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1',
        request: { storeName: 'New Store' },
      };
      component.onSaveStore(event);

      expect(storeService.addStore).toHaveBeenCalledWith('company-1', 'cc-1', 'reg-1', 'zone-1', event.request);
      expect(router.navigate).toHaveBeenCalledWith(['/companies/stores'], {
        queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1' },
      });
    });

    it('should handle addStore errors via serverErrors', () => {
      const apiError = { status: 400, message: 'Error', errors: { storeName: 'Already exists' } };
      const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
      storeService.addStore = vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({
        error: apiError, status: 400, statusText: 'Bad Request',
      })));

      const event: StoreSaveEvent = {
        companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1',
        request: { storeName: 'Duplicate Store' },
      };
      component.onSaveStore(event);

      expect(component.serverErrors()).toEqual({ storeName: 'Already exists' });
    });
  });

  // ─── Create mode without query params ───────────────────────

  describe('create mode without query params', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [StoresEdit, NoopAnimationsModule, HttpClientTestingModule],
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
        imports: [StoresEdit, NoopAnimationsModule, HttpClientTestingModule],
        providers: [
          { provide: CompanyService, useClass: MockCompanyService },
          { provide: CompanyCountryService, useClass: MockCompanyCountryService },
          { provide: CompanyRegionService, useClass: MockCompanyRegionService },
          { provide: CompanyZoneService, useClass: MockCompanyZoneService },
          { provide: CompanyStoreService, useClass: MockCompanyStoreService },
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
      delete (globalThis as any).history?.state;
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
      const regionService = TestBed.inject(CompanyRegionService) as unknown as MockCompanyRegionService;
      expect(regionService.getRegions).toHaveBeenCalledWith('company-1', 'cc-1');
    });

    it('should call updateStore on save when in edit mode', () => {
      const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
      const router = TestBed.inject(Router) as unknown as { navigate: ReturnType<typeof vi.fn> };

      const event: StoreSaveEvent = {
        companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1',
        request: { storeName: 'Updated Store' }, storeId: 'store-1',
      };
      component.onSaveStore(event);

      expect(storeService.updateStore).toHaveBeenCalledWith('company-1', 'cc-1', 'reg-1', 'zone-1', 'store-1', event.request);
      expect(router.navigate).toHaveBeenCalledWith(['/companies/stores'], {
        queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1' },
      });
    });

    it('should handle updateStore errors via serverErrors', () => {
      const apiError = { status: 400, message: 'Error', errors: { storeName: 'Name taken' } };
      const storeService = TestBed.inject(CompanyStoreService) as unknown as MockCompanyStoreService;
      storeService.updateStore = vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({
        error: apiError, status: 400, statusText: 'Bad Request',
      })));

      const event: StoreSaveEvent = {
        companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1',
        request: { storeName: 'Duplicate' }, storeId: 'store-1',
      };
      component.onSaveStore(event);

      expect(component.serverErrors()).toEqual({ storeName: 'Name taken' });
    });
  });

  // ─── Cancel navigation ──────────────────────────────────────

  describe('onCancelForm', () => {
    it('should navigate back with query params from storeToEdit when in edit mode', () => {
      TestBed.configureTestingModule({
        imports: [StoresEdit, NoopAnimationsModule, HttpClientTestingModule],
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
      (component as any).storeToEdit.set(mockStore);
      (component as any).isEditMode.set(true);

      component.onCancelForm();

      expect(router.navigate).toHaveBeenCalledWith(['/companies/stores'], {
        queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1' },
      });
    });

    it('should navigate back with initial query params when in create mode', () => {
      TestBed.configureTestingModule({
        imports: [StoresEdit, NoopAnimationsModule, HttpClientTestingModule],
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
      (component as any).initialCompanyId.set('company-1');
      (component as any).initialCountryId.set('cc-1');
      (component as any).initialRegionId.set('reg-1');
      (component as any).initialZoneId.set('zone-1');

      component.onCancelForm();

      expect(router.navigate).toHaveBeenCalledWith(['/companies/stores'], {
        queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1' },
      });
    });

    it('should navigate without query params when no context exists', () => {
      TestBed.configureTestingModule({
        imports: [StoresEdit, NoopAnimationsModule, HttpClientTestingModule],
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
