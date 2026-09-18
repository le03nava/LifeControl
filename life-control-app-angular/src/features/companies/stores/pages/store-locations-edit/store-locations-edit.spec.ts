import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { StoreLocationsEdit } from './store-locations-edit';
import { StoreLocationForm } from '../../components/store-location-form/store-location-form';
import { StoreLocationService } from '../../data/store-location.service';
import { CreateStoreLocationRequest, StoreLocation } from '../../models/store-location.models';

describe('StoreLocationsEdit', () => {
  let component: StoreLocationsEdit;
  let fixture: ComponentFixture<StoreLocationsEdit>;

  const mockStoreLocation: StoreLocation = {
    id: 'store-location-1',
    storeZoneId: 'store-zone-1',
    storeAreaId: 'area-1',
    companyStoreId: 'store-1',
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    locationCode: 'EST-01',
    locationName: 'Estante 01',
    description: 'Estante de almacenamiento seco',
    displayOrder: 2,
    enabled: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-15T00:00:00Z',
  };

  class MockStoreLocationService {
    getLocationById = vi.fn().mockReturnValue(of(mockStoreLocation));
    createLocation = vi.fn().mockReturnValue(of(mockStoreLocation));
    updateLocation = vi.fn().mockReturnValue(of(mockStoreLocation));
  }

  const routerMock = { navigate: vi.fn() };

  function activatedRouteWith(
    id: string | null,
    queryParams: Record<string, string> = {},
  ): unknown {
    return {
      snapshot: {
        paramMap: { get: vi.fn().mockReturnValue(id) },
        queryParamMap: {
          get: vi.fn().mockImplementation((key: string) => queryParams[key] ?? null),
        },
      },
    };
  }

  async function setup(
    id: string | null = null,
    queryParams: Record<string, string> = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [StoreLocationsEdit, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: StoreLocationService, useClass: MockStoreLocationService },
        { provide: Router, useValue: routerMock },
        { provide: ActivatedRoute, useValue: activatedRouteWith(id, queryParams) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StoreLocationsEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function storeLocationForm(): StoreLocationForm {
    return fixture.debugElement.query(By.directive(StoreLocationForm))
      .componentInstance as StoreLocationForm;
  }

  beforeEach(() => {
    routerMock.navigate.mockClear();
  });

  // ─── Create mode ────────────────────────────────────────────

  describe('create mode', () => {
    const createParams = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'zone-1',
      storeId: 'store-1',
      areaId: 'area-1',
      storeZoneId: 'store-zone-1',
    };

    beforeEach(async () => {
      await setup(null, createParams);
    });

    it('should create', () => {
      expect(component).toBeTruthy();
    });

    it('should not be in edit mode', () => {
      expect(component.isEditMode()).toBe(false);
      expect(component.storeLocationId()).toBeNull();
    });

    it('should build the chain from the seven query params', () => {
      expect(component.chain()).toEqual({
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
        areaId: 'area-1',
        storeZoneId: 'store-zone-1',
      });
    });

    it('should NOT hit the flat lookup in create mode', () => {
      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;
      expect(storeLocationService.getLocationById).not.toHaveBeenCalled();
    });

    it('should call createLocation with the chain and navigate with the same chain', () => {
      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;
      const request: CreateStoreLocationRequest = {
        locationCode: 'EST-01',
        locationName: 'Estante 01',
      };

      component.onSave(request);

      expect(storeLocationService.createLocation).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        'area-1',
        'store-zone-1',
        request,
      );
      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-locations'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      });
    });

    it('should navigate back with the chain on cancel', () => {
      component.onCancel();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-locations'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      });
    });

    it('should route the form save output through the page', () => {
      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;

      storeLocationForm().formGroup.controls.locationCode.setValue('EST-01');
      storeLocationForm().formGroup.controls.locationName.setValue('Estante 01');
      storeLocationForm().onSave();

      expect(storeLocationService.createLocation).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        'area-1',
        'store-zone-1',
        { locationCode: 'EST-01', locationName: 'Estante 01' },
      );
    });

    it('should route the form cancelForm output through the page', () => {
      storeLocationForm().onCancel();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-locations'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      });
    });
  });

  describe('create mode with missing query params', () => {
    it.each([
      [
        'companyId',
        {
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      ],
      [
        'countryId',
        {
          companyId: 'company-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      ],
      [
        'regionId',
        {
          companyId: 'company-1',
          countryId: 'cc-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      ],
      [
        'zoneId',
        {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      ],
      [
        'storeId',
        {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      ],
      [
        'areaId',
        {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          storeZoneId: 'store-zone-1',
        },
      ],
      [
        'storeZoneId',
        {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
        },
      ],
    ])('should redirect to the list when %s is missing', async (_missing, params) => {
      await setup(null, params as Record<string, string>);

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-locations']);
      expect(component.chain()).toBeNull();
    });

    it('should redirect when no query params are present at all', async () => {
      await setup();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-locations']);
    });

    it('should not save without a chain', async () => {
      await setup();
      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;

      component.onSave({ locationCode: 'X', locationName: 'Y' });

      expect(storeLocationService.createLocation).not.toHaveBeenCalled();
      expect(storeLocationService.updateLocation).not.toHaveBeenCalled();
    });
  });

  // ─── Edit mode ──────────────────────────────────────────────

  describe('edit mode', () => {
    beforeEach(async () => {
      await setup('store-location-1');
    });

    it('should be in edit mode', () => {
      expect(component.isEditMode()).toBe(true);
      expect(component.storeLocationId()).toBe('store-location-1');
    });

    it('should load the store location through the FLAT lookup', () => {
      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;
      expect(storeLocationService.getLocationById).toHaveBeenCalledWith('store-location-1');
      expect(component.storeLocation()).toEqual(mockStoreLocation);
    });

    it('should build the chain from the flat lookup response', () => {
      expect(component.chain()).toEqual({
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
        areaId: 'area-1',
        storeZoneId: 'store-zone-1',
      });
    });

    it('should resolve storeZoneId from the response, not from the route', () => {
      expect(component.chain()?.areaId).toBe(mockStoreLocation.storeAreaId);
      expect(component.chain()?.storeZoneId).toBe(mockStoreLocation.storeZoneId);
    });

    it('should pass the loaded store location down to the form', () => {
      expect(storeLocationForm().storeLocation()).toEqual(mockStoreLocation);
      expect(storeLocationForm().isEditMode()).toBe(true);
    });

    it('should navigate back to the list with the chain after saving', () => {
      component.onSave({ locationCode: 'EST-01', locationName: 'Estante 01 Nuevo' });

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-locations'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      });
    });

    it('should navigate back with the chain on cancel', () => {
      component.onCancel();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-locations'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      });
    });
  });

  describe('edit mode chain authority', () => {
    // The query params deliberately disagree with the authoritative flat response.
    beforeEach(async () => {
      await setup('store-location-1', {
        companyId: 'other-company',
        countryId: 'other-cc',
        regionId: 'other-region',
        zoneId: 'other-zone',
        storeId: 'other-store',
        areaId: 'other-area',
        storeZoneId: 'other-store-zone',
      });
    });

    it('should ignore query params when building the chain', () => {
      expect(component.chain()).toEqual({
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
        areaId: 'area-1',
        storeZoneId: 'store-zone-1',
      });
    });

    it('should update using the chain from the response', () => {
      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;
      const request = { locationCode: 'EST-01', locationName: 'Estante 01 Nuevo' };

      component.onSave(request);

      expect(storeLocationService.updateLocation).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        'area-1',
        'store-zone-1',
        'store-location-1',
        request,
      );
    });

    it('should navigate back with the response chain after saving', () => {
      component.onSave({ locationCode: 'EST-01', locationName: 'Estante 01 Nuevo' });

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-locations'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
          storeZoneId: 'store-zone-1',
        },
      });
    });
  });

  describe('edit mode history.state seeding', () => {
    const seededStoreLocation: StoreLocation = {
      ...mockStoreLocation,
      locationName: 'Nombre desde el state',
      locationCode: 'SEED',
    };

    beforeEach(() => {
      globalThis.history = { ...globalThis.history, state: { storeLocation: seededStoreLocation } };
    });

    afterEach(() => {
      delete (globalThis.history as { state?: unknown }).state;
    });

    it('should paint from history.state before the flat lookup resolves', async () => {
      const pending = new Subject<StoreLocation>();

      await TestBed.configureTestingModule({
        imports: [StoreLocationsEdit, NoopAnimationsModule, HttpClientTestingModule],
        providers: [
          { provide: StoreLocationService, useClass: MockStoreLocationService },
          { provide: Router, useValue: routerMock },
          { provide: ActivatedRoute, useValue: activatedRouteWith('store-location-1') },
        ],
      }).compileComponents();

      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;
      storeLocationService.getLocationById.mockReturnValue(pending.asObservable());

      fixture = TestBed.createComponent(StoreLocationsEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();

      // Seeded immediately, while the authoritative fetch is still in flight.
      expect(component.storeLocation()?.locationName).toBe('Nombre desde el state');
      expect(component.chain()).toBeNull();

      pending.next(mockStoreLocation);
      pending.complete();
      fixture.detectChanges();

      // The authoritative response wins and supplies the chain.
      expect(component.storeLocation()).toEqual(mockStoreLocation);
      expect(component.chain()?.areaId).toBe('area-1');
      expect(component.chain()?.storeId).toBe('store-1');
      expect(component.chain()?.storeZoneId).toBe('store-zone-1');
    });
  });

  describe('edit mode fetch failure', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [StoreLocationsEdit, NoopAnimationsModule, HttpClientTestingModule],
        providers: [
          { provide: StoreLocationService, useClass: MockStoreLocationService },
          { provide: Router, useValue: routerMock },
          { provide: ActivatedRoute, useValue: activatedRouteWith('store-location-404') },
        ],
      }).compileComponents();

      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;
      storeLocationService.getLocationById.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { status: 404, message: 'Not found' },
              status: 404,
              statusText: 'Not Found',
            }),
        ),
      );

      fixture = TestBed.createComponent(StoreLocationsEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should redirect to the list when the store location cannot be fetched', () => {
      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-locations']);
      expect(component.chain()).toBeNull();
    });
  });

  // ─── Validation (delegated to StoreLocationForm) ────────────

  describe('form validation', () => {
    beforeEach(async () => {
      await setup(null, {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
        areaId: 'area-1',
        storeZoneId: 'store-zone-1',
      });
    });

    it('should require locationCode and locationName', () => {
      const group = storeLocationForm().formGroup;
      expect(group.controls.locationCode.errors?.['required']).toBeTruthy();
      expect(group.controls.locationName.errors?.['required']).toBeTruthy();
    });

    it('should enforce maxLength on locationCode (10), locationName (100), and description (255)', () => {
      const group = storeLocationForm().formGroup;

      group.controls.locationCode.setValue('A'.repeat(11));
      group.controls.locationName.setValue('A'.repeat(101));
      group.controls.description.setValue('A'.repeat(256));

      expect(group.controls.locationCode.errors?.['maxlength']).toBeTruthy();
      expect(group.controls.locationName.errors?.['maxlength']).toBeTruthy();
      expect(group.controls.description.errors?.['maxlength']).toBeTruthy();
    });

    it('should enforce min 0 on displayOrder', () => {
      const group = storeLocationForm().formGroup;
      group.controls.displayOrder.setValue(-1);

      expect(group.controls.displayOrder.errors?.['min']).toBeTruthy();
    });

    it('should not send a request when the form submits while invalid', () => {
      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;

      storeLocationForm().onSave();

      expect(storeLocationService.createLocation).not.toHaveBeenCalled();
    });
  });

  // ─── Error handling ─────────────────────────────────────────

  describe('handleError', () => {
    const createParams = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'zone-1',
      storeId: 'store-1',
      areaId: 'area-1',
      storeZoneId: 'store-zone-1',
    };

    async function setupWithCreateError(error: unknown): Promise<void> {
      await setup(null, createParams);
      const storeLocationService = TestBed.inject(
        StoreLocationService,
      ) as unknown as MockStoreLocationService;
      storeLocationService.createLocation.mockReturnValue(
        throwError(() => new HttpErrorResponse({ error, status: 400, statusText: 'Bad Request' })),
      );
    }

    it('should map ApiError.errors onto serverErrors', async () => {
      await setupWithCreateError({
        status: 400,
        message: 'Validation failed',
        errors: { locationCode: 'Código duplicado' },
      });

      component.onSave({ locationCode: 'EST-01', locationName: 'Estante 01' });
      fixture.detectChanges();

      expect(component.serverErrors()).toEqual({ locationCode: 'Código duplicado' });
      expect(component.generalError()).toBeNull();
      expect(storeLocationForm().serverErrors()).toEqual({ locationCode: 'Código duplicado' });
      expect(routerMock.navigate).not.toHaveBeenCalled();
    });

    it('should fall back to ApiError.message when there is no errors map', async () => {
      await setupWithCreateError({ status: 409, message: 'Conflicto de dominio' });

      component.onSave({ locationCode: 'EST-01', locationName: 'Estante 01' });

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toBe('Conflicto de dominio');
    });

    it('should fall back to the generic message when the error body is empty', async () => {
      await setupWithCreateError('');

      component.onSave({ locationCode: 'EST-01', locationName: 'Estante 01' });

      expect(component.generalError()).toBe('Error inesperado. Intente de nuevo más tarde.');
    });

    it('should render the general error through the ErrorBanner', async () => {
      await setupWithCreateError('');

      component.onSave({ locationCode: 'EST-01', locationName: 'Estante 01' });
      fixture.detectChanges();

      const banner = fixture.nativeElement.querySelector('app-error-banner');
      expect(banner).toBeTruthy();
      expect(banner.textContent).toContain('Error inesperado. Intente de nuevo más tarde.');
    });
  });
});
