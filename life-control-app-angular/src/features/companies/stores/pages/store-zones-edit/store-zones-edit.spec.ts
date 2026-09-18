import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { StoreZonesEdit } from './store-zones-edit';
import { StoreZoneForm } from '../../components/store-zone-form/store-zone-form';
import { StoreZoneService } from '../../data/store-zone.service';
import { CreateStoreZoneRequest, StoreZone } from '../../models/store-zone.models';

describe('StoreZonesEdit', () => {
  let component: StoreZonesEdit;
  let fixture: ComponentFixture<StoreZonesEdit>;

  const mockZone: StoreZone = {
    id: 'store-zone-1',
    storeAreaId: 'area-1',
    companyStoreId: 'store-1',
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    zoneCode: 'SECO',
    zoneName: 'Zona Seca',
    description: 'Depósito seco',
    displayOrder: 2,
    enabled: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-15T00:00:00Z',
  };

  class MockStoreZoneService {
    getZoneById = vi.fn().mockReturnValue(of(mockZone));
    createZone = vi.fn().mockReturnValue(of(mockZone));
    updateZone = vi.fn().mockReturnValue(of(mockZone));
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
      imports: [StoreZonesEdit, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: StoreZoneService, useClass: MockStoreZoneService },
        { provide: Router, useValue: routerMock },
        { provide: ActivatedRoute, useValue: activatedRouteWith(id, queryParams) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StoreZonesEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function zoneForm(): StoreZoneForm {
    return fixture.debugElement.query(By.directive(StoreZoneForm))
      .componentInstance as StoreZoneForm;
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
    };

    beforeEach(async () => {
      await setup(null, createParams);
    });

    it('should create', () => {
      expect(component).toBeTruthy();
    });

    it('should not be in edit mode', () => {
      expect(component.isEditMode()).toBe(false);
      expect(component.storeZoneId()).toBeNull();
    });

    it('should build the chain from the six query params', () => {
      expect(component.chain()).toEqual({
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
        areaId: 'area-1',
      });
    });

    it('should NOT hit the flat lookup in create mode', () => {
      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      expect(zoneService.getZoneById).not.toHaveBeenCalled();
    });

    it('should call createZone with the chain and navigate with the same chain', () => {
      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      const request: CreateStoreZoneRequest = { zoneCode: 'SECO', zoneName: 'Zona Seca' };

      component.onSave(request);

      expect(zoneService.createZone).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        'area-1',
        request,
      );
      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
        },
      });
    });

    it('should navigate back with the chain on cancel', () => {
      component.onCancel();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
        },
      });
    });

    it('should route the form save output through the page', () => {
      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;

      zoneForm().formGroup.controls.zoneCode.setValue('SECO');
      zoneForm().formGroup.controls.zoneName.setValue('Zona Seca');
      zoneForm().onSave();

      expect(zoneService.createZone).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        'area-1',
        { zoneCode: 'SECO', zoneName: 'Zona Seca' },
      );
    });

    it('should route the form cancelForm output through the page', () => {
      zoneForm().onCancel();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
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
        },
      ],
    ])('should redirect to the list when %s is missing', async (_missing, params) => {
      await setup(null, params as Record<string, string>);

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones']);
      expect(component.chain()).toBeNull();
    });

    it('should redirect when no query params are present at all', async () => {
      await setup();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones']);
    });

    it('should not save without a chain', async () => {
      await setup();
      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;

      component.onSave({ zoneCode: 'X', zoneName: 'Y' });

      expect(zoneService.createZone).not.toHaveBeenCalled();
      expect(zoneService.updateZone).not.toHaveBeenCalled();
    });
  });

  // ─── Edit mode ──────────────────────────────────────────────

  describe('edit mode', () => {
    beforeEach(async () => {
      await setup('store-zone-1');
    });

    it('should be in edit mode', () => {
      expect(component.isEditMode()).toBe(true);
      expect(component.storeZoneId()).toBe('store-zone-1');
    });

    it('should load the store zone through the FLAT lookup', () => {
      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      expect(zoneService.getZoneById).toHaveBeenCalledWith('store-zone-1');
      expect(component.storeZone()).toEqual(mockZone);
    });

    it('should build the chain from the flat lookup response', () => {
      expect(component.chain()).toEqual({
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
        areaId: 'area-1',
      });
    });

    it('should resolve areaId from the response storeAreaId, not from the route', () => {
      expect(component.chain()?.areaId).toBe(mockZone.storeAreaId);
      expect(component.chain()?.zoneId).toBe(mockZone.zoneId);
    });

    it('should pass the loaded store zone down to the form', () => {
      expect(zoneForm().storeZone()).toEqual(mockZone);
      expect(zoneForm().isEditMode()).toBe(true);
    });

    it('should navigate back to the list with the chain after saving', () => {
      component.onSave({ zoneCode: 'SECO', zoneName: 'Zona Seca Nueva' });

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
        },
      });
    });

    it('should navigate back with the chain on cancel', () => {
      component.onCancel();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
        },
      });
    });
  });

  describe('edit mode chain authority', () => {
    // The query params deliberately disagree with the authoritative flat response.
    beforeEach(async () => {
      await setup('store-zone-1', {
        companyId: 'other-company',
        countryId: 'other-cc',
        regionId: 'other-region',
        zoneId: 'other-zone',
        storeId: 'other-store',
        areaId: 'other-area',
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
      });
    });

    it('should update using the chain from the response', () => {
      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      const request = { zoneCode: 'SECO', zoneName: 'Zona Seca Nueva' };

      component.onSave(request);

      expect(zoneService.updateZone).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        'area-1',
        'store-zone-1',
        request,
      );
    });

    it('should navigate back with the response chain after saving', () => {
      component.onSave({ zoneCode: 'SECO', zoneName: 'Zona Seca Nueva' });

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
          areaId: 'area-1',
        },
      });
    });
  });

  describe('edit mode history.state seeding', () => {
    const seededZone: StoreZone = {
      ...mockZone,
      zoneName: 'Nombre desde el state',
      zoneCode: 'SEED',
    };

    beforeEach(() => {
      globalThis.history = { ...globalThis.history, state: { storeZone: seededZone } };
    });

    afterEach(() => {
      delete (globalThis.history as { state?: unknown }).state;
    });

    it('should paint from history.state before the flat lookup resolves', async () => {
      const pending = new Subject<StoreZone>();

      await TestBed.configureTestingModule({
        imports: [StoreZonesEdit, NoopAnimationsModule, HttpClientTestingModule],
        providers: [
          { provide: StoreZoneService, useClass: MockStoreZoneService },
          { provide: Router, useValue: routerMock },
          { provide: ActivatedRoute, useValue: activatedRouteWith('store-zone-1') },
        ],
      }).compileComponents();

      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      zoneService.getZoneById.mockReturnValue(pending.asObservable());

      fixture = TestBed.createComponent(StoreZonesEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();

      // Seeded immediately, while the authoritative fetch is still in flight.
      expect(component.storeZone()?.zoneName).toBe('Nombre desde el state');
      expect(component.chain()).toBeNull();

      pending.next(mockZone);
      pending.complete();
      fixture.detectChanges();

      // The authoritative response wins and supplies the chain.
      expect(component.storeZone()).toEqual(mockZone);
      expect(component.chain()?.areaId).toBe('area-1');
      expect(component.chain()?.storeId).toBe('store-1');
    });
  });

  describe('edit mode fetch failure', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [StoreZonesEdit, NoopAnimationsModule, HttpClientTestingModule],
        providers: [
          { provide: StoreZoneService, useClass: MockStoreZoneService },
          { provide: Router, useValue: routerMock },
          { provide: ActivatedRoute, useValue: activatedRouteWith('store-zone-404') },
        ],
      }).compileComponents();

      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      zoneService.getZoneById.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { status: 404, message: 'Not found' },
              status: 404,
              statusText: 'Not Found',
            }),
        ),
      );

      fixture = TestBed.createComponent(StoreZonesEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should redirect to the list when the store zone cannot be fetched', () => {
      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-zones']);
      expect(component.chain()).toBeNull();
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
    };

    async function setupWithCreateError(error: unknown): Promise<void> {
      await setup(null, createParams);
      const zoneService = TestBed.inject(StoreZoneService) as unknown as MockStoreZoneService;
      zoneService.createZone.mockReturnValue(
        throwError(() => new HttpErrorResponse({ error, status: 400, statusText: 'Bad Request' })),
      );
    }

    it('should map ApiError.errors onto serverErrors', async () => {
      await setupWithCreateError({
        status: 400,
        message: 'Validation failed',
        errors: { zoneCode: 'Código duplicado' },
      });

      component.onSave({ zoneCode: 'SECO', zoneName: 'Zona Seca' });
      fixture.detectChanges();

      expect(component.serverErrors()).toEqual({ zoneCode: 'Código duplicado' });
      expect(component.generalError()).toBeNull();
      expect(zoneForm().serverErrors()).toEqual({ zoneCode: 'Código duplicado' });
      expect(routerMock.navigate).not.toHaveBeenCalled();
    });

    it('should fall back to ApiError.message when there is no errors map', async () => {
      await setupWithCreateError({ status: 409, message: 'Conflicto de dominio' });

      component.onSave({ zoneCode: 'SECO', zoneName: 'Zona Seca' });

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toBe('Conflicto de dominio');
    });

    it('should fall back to the generic message when the error body is empty', async () => {
      await setupWithCreateError('');

      component.onSave({ zoneCode: 'SECO', zoneName: 'Zona Seca' });

      expect(component.generalError()).toBe('Error inesperado. Intente de nuevo más tarde.');
    });

    it('should render the general error through the ErrorBanner', async () => {
      await setupWithCreateError('');

      component.onSave({ zoneCode: 'SECO', zoneName: 'Zona Seca' });
      fixture.detectChanges();

      const banner = fixture.nativeElement.querySelector('app-error-banner');
      expect(banner).toBeTruthy();
      expect(banner.textContent).toContain('Error inesperado. Intente de nuevo más tarde.');
    });
  });
});
