import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { StoreAreasEdit } from './store-areas-edit';
import { StoreAreaForm } from '../../components/store-area-form/store-area-form';
import { StoreAreaService } from '../../data/store-area.service';
import { CreateStoreAreaRequest, StoreArea } from '../../models/store-area.models';

describe('StoreAreasEdit', () => {
  let component: StoreAreasEdit;
  let fixture: ComponentFixture<StoreAreasEdit>;

  const mockArea: StoreArea = {
    id: 'area-1',
    companyStoreId: 'store-1',
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    areaCode: 'ALMACEN',
    areaName: 'Almacén Central',
    description: 'Depósito principal',
    displayOrder: 2,
    enabled: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-15T00:00:00Z',
  };

  class MockStoreAreaService {
    getAreaById = vi.fn().mockReturnValue(of(mockArea));
    createArea = vi.fn().mockReturnValue(of(mockArea));
    updateArea = vi.fn().mockReturnValue(of(mockArea));
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
      imports: [StoreAreasEdit, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: StoreAreaService, useClass: MockStoreAreaService },
        { provide: Router, useValue: routerMock },
        { provide: ActivatedRoute, useValue: activatedRouteWith(id, queryParams) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StoreAreasEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function areaForm(): StoreAreaForm {
    return fixture.debugElement.query(By.directive(StoreAreaForm))
      .componentInstance as StoreAreaForm;
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
    };

    beforeEach(async () => {
      await setup(null, createParams);
    });

    it('should create', () => {
      expect(component).toBeTruthy();
    });

    it('should not be in edit mode', () => {
      expect(component.isEditMode()).toBe(false);
      expect(component.areaId()).toBeNull();
    });

    it('should build the chain from the query params', () => {
      expect(component.chain()).toEqual({
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
      });
    });

    it('should NOT hit the flat lookup in create mode', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      expect(areaService.getAreaById).not.toHaveBeenCalled();
    });

    it('should call createArea with the chain and navigate with the same chain', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      const request: CreateStoreAreaRequest = { areaCode: 'DEPOSITO', areaName: 'Depósito' };

      component.onSave(request);

      expect(areaService.createArea).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        request,
      );
      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
        },
      });
    });

    it('should navigate back with the chain on cancel', () => {
      component.onCancel();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas'], {
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

  describe('create mode with missing query params', () => {
    it.each([
      ['companyId', { countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1', storeId: 'store-1' }],
      [
        'countryId',
        { companyId: 'company-1', regionId: 'reg-1', zoneId: 'zone-1', storeId: 'store-1' },
      ],
      [
        'regionId',
        { companyId: 'company-1', countryId: 'cc-1', zoneId: 'zone-1', storeId: 'store-1' },
      ],
      [
        'zoneId',
        { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', storeId: 'store-1' },
      ],
      [
        'storeId',
        { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1' },
      ],
    ])('should redirect to the list when %s is missing', async (_missing, params) => {
      await setup(null, params);

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas']);
      expect(component.chain()).toBeNull();
    });

    it('should redirect when no query params are present at all', async () => {
      await setup();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas']);
    });

    it('should not save without a chain', async () => {
      await setup();
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;

      component.onSave({ areaCode: 'X', areaName: 'Y' });

      expect(areaService.createArea).not.toHaveBeenCalled();
      expect(areaService.updateArea).not.toHaveBeenCalled();
    });
  });

  // ─── Edit mode ──────────────────────────────────────────────

  describe('edit mode', () => {
    beforeEach(async () => {
      await setup('area-1');
    });

    it('should be in edit mode', () => {
      expect(component.isEditMode()).toBe(true);
      expect(component.areaId()).toBe('area-1');
    });

    it('should load the area through the FLAT lookup', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      expect(areaService.getAreaById).toHaveBeenCalledWith('area-1');
      expect(component.area()).toEqual(mockArea);
    });

    it('should build the chain from the flat lookup response', () => {
      expect(component.chain()).toEqual({
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
      });
    });

    it('should pass the loaded area down to the form', () => {
      expect(areaForm().area()).toEqual(mockArea);
      expect(areaForm().isEditMode()).toBe(true);
    });

    it('should navigate back to the list with the chain after saving', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      expect(areaService.updateArea).toBeDefined();

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén Nuevo' });

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas'], {
        queryParams: {
          companyId: 'company-1',
          countryId: 'cc-1',
          regionId: 'reg-1',
          zoneId: 'zone-1',
          storeId: 'store-1',
        },
      });
    });

    it('should navigate back with the chain on cancel', () => {
      component.onCancel();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas'], {
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

  describe('edit mode chain authority', () => {
    // The query params deliberately disagree with the authoritative flat response.
    beforeEach(async () => {
      await setup('area-1', {
        companyId: 'other-company',
        countryId: 'other-cc',
        regionId: 'other-region',
        zoneId: 'other-zone',
        storeId: 'other-store',
      });
    });

    it('should ignore query params when building the chain', () => {
      expect(component.chain()).toEqual({
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
      });
    });

    it('should update using the chain from the response', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      const request = { areaCode: 'ALMACEN', areaName: 'Almacén Nuevo' };

      component.onSave(request);

      expect(areaService.updateArea).toHaveBeenCalledWith(
        'company-1',
        'cc-1',
        'reg-1',
        'zone-1',
        'store-1',
        'area-1',
        request,
      );
    });

    it('should navigate back with the response chain after saving', () => {
      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén Nuevo' });

      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas'], {
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

  describe('edit mode history.state seeding', () => {
    const seededArea: StoreArea = {
      ...mockArea,
      areaName: 'Nombre desde el state',
      areaCode: 'SEED',
    };

    beforeEach(() => {
      globalThis.history = { ...globalThis.history, state: { area: seededArea } };
    });

    afterEach(() => {
      delete (globalThis.history as { state?: unknown }).state;
    });

    it('should paint from history.state before the flat lookup resolves', async () => {
      const pending = new Subject<StoreArea>();

      await TestBed.configureTestingModule({
        imports: [StoreAreasEdit, NoopAnimationsModule, HttpClientTestingModule],
        providers: [
          { provide: StoreAreaService, useClass: MockStoreAreaService },
          { provide: Router, useValue: routerMock },
          { provide: ActivatedRoute, useValue: activatedRouteWith('area-1') },
        ],
      }).compileComponents();

      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.getAreaById.mockReturnValue(pending.asObservable());

      fixture = TestBed.createComponent(StoreAreasEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();

      // Seeded immediately, while the authoritative fetch is still in flight.
      expect(component.area()?.areaName).toBe('Nombre desde el state');
      expect(component.chain()).toBeNull();

      pending.next(mockArea);
      pending.complete();
      fixture.detectChanges();

      // The authoritative response wins and supplies the chain.
      expect(component.area()).toEqual(mockArea);
      expect(component.chain()?.storeId).toBe('store-1');
    });
  });

  describe('edit mode fetch failure', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [StoreAreasEdit, NoopAnimationsModule, HttpClientTestingModule],
        providers: [
          { provide: StoreAreaService, useClass: MockStoreAreaService },
          { provide: Router, useValue: routerMock },
          { provide: ActivatedRoute, useValue: activatedRouteWith('area-404') },
        ],
      }).compileComponents();

      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.getAreaById.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { status: 404, message: 'Not found' },
              status: 404,
              statusText: 'Not Found',
            }),
        ),
      );

      fixture = TestBed.createComponent(StoreAreasEdit);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should redirect to the list when the area cannot be fetched', () => {
      expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/store-areas']);
      expect(component.chain()).toBeNull();
    });
  });

  // ─── Validation (delegated to StoreAreaForm) ────────────────

  describe('form validation', () => {
    beforeEach(async () => {
      await setup(null, {
        companyId: 'company-1',
        countryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        storeId: 'store-1',
      });
    });

    it('should require areaCode and areaName', () => {
      const group = areaForm().formGroup;
      expect(group.controls.areaCode.errors?.['required']).toBeTruthy();
      expect(group.controls.areaName.errors?.['required']).toBeTruthy();
    });

    it('should enforce maxLength on areaCode (10), areaName (100), and description (255)', () => {
      const group = areaForm().formGroup;

      group.controls.areaCode.setValue('A'.repeat(11));
      group.controls.areaName.setValue('A'.repeat(101));
      group.controls.description.setValue('A'.repeat(256));

      expect(group.controls.areaCode.errors?.['maxlength']).toBeTruthy();
      expect(group.controls.areaName.errors?.['maxlength']).toBeTruthy();
      expect(group.controls.description.errors?.['maxlength']).toBeTruthy();
    });

    it('should enforce min 0 on displayOrder', () => {
      const group = areaForm().formGroup;
      group.controls.displayOrder.setValue(-1);

      expect(group.controls.displayOrder.errors?.['min']).toBeTruthy();
    });

    it('should not send a request when the form submits while invalid', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;

      areaForm().onSave();

      expect(areaService.createArea).not.toHaveBeenCalled();
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
    };

    async function setupWithCreateError(error: unknown): Promise<void> {
      await setup(null, createParams);
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.createArea.mockReturnValue(
        throwError(() => new HttpErrorResponse({ error, status: 400, statusText: 'Bad Request' })),
      );
    }

    it('should map ApiError.errors onto serverErrors', async () => {
      await setupWithCreateError({
        status: 400,
        message: 'Validation failed',
        errors: { areaCode: 'Código duplicado' },
      });

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén' });
      fixture.detectChanges();

      expect(component.serverErrors()).toEqual({ areaCode: 'Código duplicado' });
      expect(component.generalError()).toBeNull();
      expect(areaForm().serverErrors()).toEqual({ areaCode: 'Código duplicado' });
      expect(routerMock.navigate).not.toHaveBeenCalled();
    });

    it('should fall back to ApiError.message when there is no errors map', async () => {
      await setupWithCreateError({ status: 409, message: 'Conflicto de dominio' });

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén' });

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toBe('Conflicto de dominio');
    });

    it('should fall back to the generic message when the error body is empty', async () => {
      await setupWithCreateError('');

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén' });

      expect(component.generalError()).toBe('Error inesperado. Intente de nuevo más tarde.');
    });

    it('should render the general error through the ErrorBanner', async () => {
      await setupWithCreateError('');

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén' });
      fixture.detectChanges();

      const banner = fixture.nativeElement.querySelector('app-error-banner');
      expect(banner).toBeTruthy();
      expect(banner.textContent).toContain('Error inesperado. Intente de nuevo más tarde.');
    });
  });
});
