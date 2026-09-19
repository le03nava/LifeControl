/// <reference types="vitest/globals" />
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
import { NotificationService } from '@shared/data/notification';
import Keycloak from 'keycloak-js';

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
    roles: string[] = ['lc-admin'],
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [StoreAreasEdit, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: StoreAreaService, useClass: MockStoreAreaService },
        { provide: Router, useValue: routerMock },
        {
          provide: NotificationService,
          useValue: { showSuccess: vi.fn(), showError: vi.fn(), showWarning: vi.fn() },
        },
        {
          provide: Keycloak,
          useValue: { tokenParsed: { resource_access: { 'life-control-client': { roles } } } },
        },
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
          {
            provide: NotificationService,
            useValue: { showSuccess: vi.fn(), showError: vi.fn(), showWarning: vi.fn() },
          },
          {
            provide: Keycloak,
            useValue: {
              tokenParsed: { resource_access: { 'life-control-client': { roles: ['lc-admin'] } } },
            },
          },
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
          {
            provide: NotificationService,
            useValue: { showSuccess: vi.fn(), showError: vi.fn(), showWarning: vi.fn() },
          },
          {
            provide: Keycloak,
            useValue: {
              tokenParsed: { resource_access: { 'life-control-client': { roles: ['lc-admin'] } } },
            },
          },
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

  // ─── Save lifecycle, feedback and route guard ───────────────

  describe('save lifecycle (create mode)', () => {
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

    it('should start with no save in flight', () => {
      expect(component.saving()).toBe(false);
    });

    it('should notify a successful create', () => {
      const notifications = TestBed.inject(NotificationService) as unknown as {
        showSuccess: ReturnType<typeof vi.fn>;
      };

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén' });

      expect(notifications.showSuccess).toHaveBeenCalledWith('Área creada correctamente.');
    });

    it('should not fire a second request while the first is still in flight', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.createArea.mockReturnValue(new Subject<StoreArea>().asObservable());
      const request = { areaCode: 'ALMACEN', areaName: 'Almacén' };

      component.onSave(request);
      expect(component.saving()).toBe(true);

      component.onSave(request);

      expect(areaService.createArea).toHaveBeenCalledTimes(1);
    });

    it('should clear the in-flight flag when the request completes', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      const pending = new Subject<StoreArea>();
      areaService.createArea.mockReturnValue(pending.asObservable());

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén' });
      pending.next(mockArea);
      pending.complete();

      expect(component.saving()).toBe(false);
    });

    it('should clear the in-flight flag when the request fails', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.createArea.mockReturnValue(
        throwError(() => new HttpErrorResponse({ error: {}, status: 400 })),
      );

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén' });

      expect(component.saving()).toBe(false);
    });

    it('should pass the in-flight flag down to the form', () => {
      const areaService = TestBed.inject(StoreAreaService) as unknown as MockStoreAreaService;
      areaService.createArea.mockReturnValue(new Subject<StoreArea>().asObservable());

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén' });
      fixture.detectChanges();

      expect(areaForm().saving()).toBe(true);
    });
  });

  describe('save lifecycle (edit mode)', () => {
    beforeEach(async () => {
      await setup('area-1');
    });

    it('should notify a successful update', () => {
      const notifications = TestBed.inject(NotificationService) as unknown as {
        showSuccess: ReturnType<typeof vi.fn>;
      };

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén Nuevo' });

      expect(notifications.showSuccess).toHaveBeenCalledWith('Área actualizada correctamente.');
    });

    it('should leave the form pristine so the route guard lets the post-save navigation through', () => {
      areaForm().formGroup.markAsDirty();
      expect(component.hasUnsavedChanges()).toBe(true);

      component.onSave({ areaCode: 'ALMACEN', areaName: 'Almacén Nuevo' });

      expect(component.hasUnsavedChanges()).toBe(false);
      expect(routerMock.navigate).toHaveBeenCalled();
    });
  });

  describe('unsaved changes', () => {
    const createParams = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'zone-1',
      storeId: 'store-1',
    };

    it('should report no unsaved changes right after loading an entity', async () => {
      await setup('area-1');

      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should report unsaved changes once the form is edited', async () => {
      await setup('area-1');

      areaForm().formGroup.markAsDirty();

      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should report no unsaved changes in a fresh create form', async () => {
      await setup(null, createParams);

      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should report unsaved changes after editing a create form', async () => {
      await setup(null, createParams);

      areaForm().formGroup.markAsDirty();

      expect(component.hasUnsavedChanges()).toBe(true);
    });
  });

  describe('role gating', () => {
    const createParams = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'zone-1',
      storeId: 'store-1',
    };

    it('should not render the submit control for a read-only user', async () => {
      await setup(null, createParams, ['lc-company-store-read']);

      expect(fixture.nativeElement.querySelector('button[type="submit"]')).toBeNull();
    });

    it('should render the submit control for a store writer', async () => {
      await setup(null, createParams, ['lc-company-store']);

      expect(fixture.nativeElement.querySelector('button[type="submit"]')).toBeTruthy();
    });
  });
});
