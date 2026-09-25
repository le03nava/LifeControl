/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { MatSelect } from '@angular/material/select';
import Keycloak from 'keycloak-js';
import { of, ReplaySubject, throwError } from 'rxjs';
import { StoreInventorySettings } from './store-inventory-settings';
import { StoreInventorySettingsService } from '@features/inventory/data/store-inventory-settings.service';
import { StoreLocationLookupService } from '@features/inventory/data/store-location-lookup.service';
import { NotificationService } from '@shared/data/notification';
import type {
  StoreInventorySettings as StoreInventorySettingsModel,
  StoreLocationSummary,
} from '@features/inventory/models/store-location-summary.models';

describe('StoreInventorySettings', () => {
  let component: StoreInventorySettings;
  let fixture: ComponentFixture<StoreInventorySettings>;
  let settingsService: MockStoreInventorySettingsService;
  let locationService: MockStoreLocationLookupService;

  const chainQuery: Record<string, string> = {
    companyId: 'company-1',
    countryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    storeId: 'store-1',
  };

  const expectedChain = {
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    storeId: 'store-1',
  };

  const mockSettings: StoreInventorySettingsModel = {
    companyStoreId: 'store-1',
    receivingLocationId: 'loc-1',
    salesLocationId: 'loc-2',
    version: 3,
  };

  const mockLocations: StoreLocationSummary[] = [
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
    {
      id: 'loc-3',
      locationCode: 'REC-02',
      locationName: 'Recepción 2',
      storeZoneId: 'zone-a',
      zoneCode: 'Z-A',
      zoneName: 'Zona A',
      storeAreaId: 'area-1',
      areaCode: 'A-1',
      areaName: 'Área Uno',
    },
  ];

  class MockStoreInventorySettingsService {
    getSettings = vi.fn().mockReturnValue(of(mockSettings));
    upsertSettings = vi.fn().mockReturnValue(of(mockSettings));
  }

  class MockStoreLocationLookupService {
    getStoreLocations = vi.fn().mockReturnValue(of(mockLocations));
  }

  /**
   * A settings read the test controls explicitly: the reload can be held in flight
   * (`resolve` is called on demand) instead of resolving synchronously like `of`.
   */
  class DeferredSettingsRead {
    private readonly subject = new ReplaySubject<StoreInventorySettingsModel | null>(1);
    readonly stream = this.subject.asObservable();

    resolve(value: StoreInventorySettingsModel | null): void {
      this.subject.next(value);
      this.subject.complete();
    }
  }

  const notificationsMock = {
    showSuccess: vi.fn(),
    showError: vi.fn(),
    showWarning: vi.fn(),
  };

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

  async function setup(
    queryParams: Record<string, string> = chainQuery,
    configure?: (mocks: {
      settings: MockStoreInventorySettingsService;
      locations: MockStoreLocationLookupService;
    }) => void,
    roles: string[] = ['lc-company-store'],
  ): Promise<void> {
    settingsService = new MockStoreInventorySettingsService();
    locationService = new MockStoreLocationLookupService();
    configure?.({ settings: settingsService, locations: locationService });

    await TestBed.configureTestingModule({
      imports: [StoreInventorySettings, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        provideRouter([]),
        { provide: StoreInventorySettingsService, useValue: settingsService },
        { provide: StoreLocationLookupService, useValue: locationService },
        { provide: NotificationService, useValue: notificationsMock },
        {
          provide: Keycloak,
          useValue: { tokenParsed: { resource_access: { 'life-control-client': { roles } } } },
        },
        { provide: ActivatedRoute, useValue: activatedRouteWith(queryParams) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StoreInventorySettings);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  // Flush the reactive resource pipeline: trigger CD, await async resolution, re-render.
  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function submitButton(): HTMLButtonElement | null {
    return fixture.nativeElement.querySelector('button[type="button"]');
  }

  function selectValues(): (string | null)[] {
    return fixture.debugElement
      .queryAll(By.directive(MatSelect))
      .map((debug) => (debug.componentInstance as MatSelect).value as string | null);
  }

  beforeEach(() => {
    notificationsMock.showSuccess.mockClear();
    notificationsMock.showError.mockClear();
    notificationsMock.showWarning.mockClear();
  });

  // ─── Missing chain: fail closed ─────────────────────────────

  describe('missing store chain', () => {
    it('should not request anything when no query param is present', async () => {
      await setup({});
      await settle();

      expect(settingsService.getSettings).not.toHaveBeenCalled();
      expect(locationService.getStoreLocations).not.toHaveBeenCalled();
      expect(component.chain).toBeNull();
    });

    it.each(['companyId', 'countryId', 'regionId', 'zoneId', 'storeId'])(
      'should not request anything when %s is missing',
      async (missing) => {
        const params = { ...chainQuery };
        delete params[missing];

        await setup(params);
        await settle();

        expect(settingsService.getSettings).not.toHaveBeenCalled();
        expect(locationService.getStoreLocations).not.toHaveBeenCalled();
        expect(component.chain).toBeNull();
      },
    );

    it('should render the blocking state with a link back to the store list', async () => {
      await setup({});
      await settle();

      const blocking = fixture.nativeElement.querySelector('.blocking-state');
      expect(blocking).toBeTruthy();
      expect(blocking.textContent).toContain('No se pudo determinar la tienda');

      const link = blocking.querySelector('a');
      expect(link).toBeTruthy();
      expect(link.getAttribute('href')).toBe('/companies/stores');
    });
  });

  // ─── Unconfigured store (404 → null) ────────────────────────

  describe('unconfigured store', () => {
    beforeEach(async () => {
      await setup(chainQuery, ({ settings }) => settings.getSettings.mockReturnValue(of(null)));
      await settle();
    });

    it('should render an empty form instead of an error', () => {
      expect(fixture.nativeElement.querySelector('.form-card')).toBeTruthy();
      expect(fixture.nativeElement.querySelector('.error-state')).toBeNull();
    });

    it('should start with neither location selected', () => {
      expect(component.receivingLocationId()).toBeNull();
      expect(component.salesLocationId()).toBeNull();
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should keep the submit disabled until both locations are chosen', () => {
      expect(component.canSubmit()).toBe(false);
      expect(submitButton()?.disabled).toBe(true);

      component.onSelectReceiving('loc-1');
      fixture.detectChanges();
      expect(component.canSubmit()).toBe(false);

      component.onSelectSales('loc-2');
      fixture.detectChanges();
      expect(component.canSubmit()).toBe(true);
      expect(submitButton()?.disabled).toBe(false);
    });

    it('should omit the version precondition when the store was never configured', () => {
      component.onSelectReceiving('loc-1');
      component.onSelectSales('loc-2');
      fixture.detectChanges();
      expect(component.canSubmit()).toBe(true);

      component.onSave();

      expect(settingsService.upsertSettings).toHaveBeenCalledWith(expectedChain, {
        receivingLocationId: 'loc-1',
        salesLocationId: 'loc-2',
      });
      // No `version` key must be serialized at all: the backend rejects any version on create.
      const requestBody = settingsService.upsertSettings.mock.calls[0][1] as Record<
        string,
        unknown
      >;
      expect('version' in requestBody).toBe(false);
    });
  });

  // ─── Configured store ───────────────────────────────────────

  describe('configured store', () => {
    beforeEach(async () => {
      await setup();
      await settle();
    });

    it('should request the settings and the locations with the full chain', () => {
      expect(settingsService.getSettings).toHaveBeenCalledWith(expectedChain);
      expect(locationService.getStoreLocations).toHaveBeenCalledWith(expectedChain);
    });

    it('should pre-select the configured receiving and sales locations', () => {
      expect(component.receivingLocationId()).toBe('loc-1');
      expect(component.salesLocationId()).toBe('loc-2');
      expect(selectValues()).toEqual(['loc-1', 'loc-2']);
    });

    it('should not mark the programmatic load as an unsaved change', () => {
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should disambiguate each option with its zone, area and code', async () => {
      const receivingSelect = fixture.debugElement.query(By.css('.receiving-location-select'))
        .componentInstance as MatSelect;
      receivingSelect.open();
      fixture.detectChanges();

      const options = Array.from(
        document.querySelectorAll('mat-option') as NodeListOf<Element>,
      ).map((option) => (option.textContent ?? '').replace(/\s+/g, ' ').trim());

      expect(options).toContain('Recepción (Zona A · Área Uno) — REC-01');

      receivingSelect.close();
      fixture.detectChanges();
    });

    it('should allow the same location for both sides', () => {
      component.onSelectReceiving('loc-3');
      component.onSelectSales('loc-3');
      fixture.detectChanges();

      expect(component.canSubmit()).toBe(true);
    });
  });

  // ─── Empty locations ────────────────────────────────────────

  describe('store without enabled locations', () => {
    beforeEach(async () => {
      await setup(chainQuery, ({ locations }) =>
        locations.getStoreLocations.mockReturnValue(of([])),
      );
      await settle();
    });

    it('should render the empty state without a select', () => {
      const empty = fixture.nativeElement.querySelector('.empty-locations');
      expect(empty).toBeTruthy();
      expect(empty.textContent).toContain('Esta tienda no tiene ubicaciones habilitadas');
      expect(fixture.nativeElement.querySelectorAll('mat-select').length).toBe(0);
    });

    it('should keep the submit disabled', () => {
      expect(component.hasLocations()).toBe(false);
      expect(component.canSubmit()).toBe(false);
    });
  });

  // ─── Stale configured location ──────────────────────────────

  describe('stale configured location', () => {
    beforeEach(async () => {
      await setup(chainQuery, ({ settings }) =>
        settings.getSettings.mockReturnValue(
          of({
            companyStoreId: 'store-1',
            receivingLocationId: 'loc-gone',
            salesLocationId: 'loc-1',
            version: 3,
          }),
        ),
      );
      await settle();
    });

    it('should warn instead of leaving the select silently blank', () => {
      expect(component.receivingIsStale()).toBe(true);
      const warning = fixture.nativeElement.querySelector('.stale-warning');
      expect(warning).toBeTruthy();
      expect(warning.textContent).toContain('La ubicación configurada ya no está habilitada');
    });

    it('should block the submit until the stale side is re-picked', () => {
      expect(component.canSubmit()).toBe(false);
      expect(submitButton()?.disabled).toBe(true);

      component.onSelectReceiving('loc-3');
      fixture.detectChanges();

      expect(component.receivingIsStale()).toBe(false);
      expect(component.canSubmit()).toBe(true);
      expect(fixture.nativeElement.querySelector('.stale-warning')).toBeNull();
    });
  });

  // ─── Save ───────────────────────────────────────────────────

  describe('save', () => {
    beforeEach(async () => {
      await setup();
      await settle();
    });

    it('should PUT the whole request object with the chain and the loaded version', () => {
      component.onSave();

      expect(settingsService.upsertSettings).toHaveBeenCalledWith(expectedChain, {
        receivingLocationId: 'loc-1',
        salesLocationId: 'loc-2',
        version: 3,
      });
    });

    it('should not write while the submit is blocked', () => {
      component.onSelectReceiving(null);
      fixture.detectChanges();

      component.onSave();

      expect(settingsService.upsertSettings).not.toHaveBeenCalled();
    });

    it('should notify success, reload and reset the dirty flag', async () => {
      component.onSelectReceiving('loc-3');
      expect(component.hasUnsavedChanges()).toBe(true);

      component.onSave();
      await settle();

      expect(notificationsMock.showSuccess).toHaveBeenCalledWith(
        'Configuración de inventario guardada.',
      );
      expect(component.hasUnsavedChanges()).toBe(false);
      expect(settingsService.getSettings).toHaveBeenCalledTimes(2);
    });

    it('should clear the dirty flag even before the reload resolves', () => {
      const reload = new DeferredSettingsRead();
      settingsService.getSettings.mockReturnValue(reload.stream);

      component.onSelectReceiving('loc-3');

      component.onSave();

      // The reload is still pending: the dirty flag is already cleared.
      expect(component.hasUnsavedChanges()).toBe(false);

      reload.resolve(mockSettings);
    });

    it('should keep an operator edit made while the post-save reload is in flight', async () => {
      const reload = new DeferredSettingsRead();
      settingsService.getSettings.mockReturnValue(reload.stream);

      component.onSave();

      // The PUT answered and released the form while the reload is still pending.
      component.onSelectReceiving('loc-3');
      expect(component.hasUnsavedChanges()).toBe(true);

      reload.resolve({ ...mockSettings });
      await settle();

      expect(component.receivingLocationId()).toBe('loc-3');
      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it.each([
      [400, 'Revisá los datos: el servidor rechazó la configuración.'],
      [404, 'La tienda o la ubicación elegida no es válida para esta tienda.'],
      [403, 'No tenés permisos para configurar el inventario de esta tienda.'],
    ])('should map a %s to its operator-facing copy', async (status, copy) => {
      settingsService.upsertSettings.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { message: `raw ${status}` },
              status,
              statusText: 'Error',
            }),
        ),
      );

      component.onSave();
      fixture.detectChanges();

      expect(component.saveError()).toBe(copy);
      expect(component.saveErrorDetail()).toBe(`raw ${status}`);

      const detail = fixture.nativeElement.querySelector('.server-detail');
      expect(detail?.textContent).toContain(`raw ${status}`);
    });

    it('should reload and clear the guard when the write is rejected with a 409', async () => {
      settingsService.upsertSettings.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { message: 'raw 409' },
              status: 409,
              statusText: 'Conflict',
            }),
        ),
      );

      component.onSelectReceiving('loc-3');
      expect(component.hasUnsavedChanges()).toBe(true);

      component.onSave();
      await settle();

      expect(component.saveError()).toBe(
        'Otra sesión modificó esta configuración mientras la editabas. Se recargaron los valores actuales: revisalos y volvé a guardar.',
      );
      expect(component.saveErrorDetail()).toBe('raw 409');
      expect(component.hasUnsavedChanges()).toBe(false);
      expect(settingsService.getSettings).toHaveBeenCalledTimes(2);
      // The reload re-seeds the form from the server's current state, not the discarded edit.
      expect(component.receivingLocationId()).toBe('loc-1');
    });

    it('should fall back to the shared HTTP copy for an unmapped status', () => {
      settingsService.upsertSettings.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { message: 'boom' },
              status: 500,
              statusText: 'Server Error',
            }),
        ),
      );

      component.onSave();
      fixture.detectChanges();

      expect(component.saveError()).toBe(
        'Ocurrió un error en el servidor. Intentá de nuevo más tarde.',
      );
      expect(component.saveErrorDetail()).toBe('boom');
    });
  });

  // ─── Dirty tracking ─────────────────────────────────────────

  describe('hasUnsavedChanges', () => {
    beforeEach(async () => {
      await setup();
      await settle();
    });

    it('should stay false after the programmatic load', () => {
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should flip to true once the operator changes the receiving select', () => {
      component.onSelectReceiving('loc-3');
      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should flip to true once the operator changes the sales select', () => {
      component.onSelectSales('loc-3');
      expect(component.hasUnsavedChanges()).toBe(true);
    });
  });

  // ─── Read failures ──────────────────────────────────────────

  describe('read failure', () => {
    it('should render an error with retry when the settings read fails', async () => {
      await setup(chainQuery, ({ settings }) =>
        settings.getSettings.mockReturnValue(
          throwError(
            () =>
              new HttpErrorResponse({
                error: { message: 'settings down' },
                status: 503,
                statusText: 'Service Unavailable',
              }),
          ),
        ),
      );
      await settle();

      const errorState = fixture.nativeElement.querySelector('.error-state');
      expect(errorState).toBeTruthy();
      expect(errorState.querySelector('button')).toBeTruthy();

      settingsService.getSettings.mockReturnValue(of(mockSettings));
      errorState.querySelector('button').click();
      await settle();

      expect(settingsService.getSettings).toHaveBeenCalledTimes(2);
      expect(fixture.nativeElement.querySelector('.form-card')).toBeTruthy();
    });

    it('should render an error when the locations read fails', async () => {
      await setup(chainQuery, ({ locations }) =>
        locations.getStoreLocations.mockReturnValue(
          throwError(
            () =>
              new HttpErrorResponse({
                error: { message: 'locations down' },
                status: 503,
                statusText: 'Service Unavailable',
              }),
          ),
        ),
      );
      await settle();

      expect(fixture.nativeElement.querySelector('.error-state')).toBeTruthy();
      expect(fixture.nativeElement.querySelector('.form-card')).toBeNull();
    });
  });

  // ─── Role gating ────────────────────────────────────────────

  describe('role gating', () => {
    describe('read-only user (lc-company-store-read)', () => {
      beforeEach(async () => {
        await setup(chainQuery, undefined, ['lc-company-store-read']);
        await settle();
      });

      it('should keep showing both configured location values', () => {
        expect(component.receivingLocationId()).toBe('loc-1');
        expect(component.salesLocationId()).toBe('loc-2');
        expect(selectValues()).toEqual(['loc-1', 'loc-2']);
      });

      it('should not render the save control', () => {
        expect(submitButton()).toBeNull();
        expect(fixture.nativeElement.querySelector('.form-actions')).toBeNull();
      });

      it('should disable both selectors', () => {
        const selects = fixture.debugElement.queryAll(By.directive(MatSelect));
        expect(selects.length).toBe(2);
        expect(selects.every((select) => (select.componentInstance as MatSelect).disabled)).toBe(
          true,
        );
      });

      it('should keep the unsaved-changes guard inert', () => {
        expect(component.hasUnsavedChanges()).toBe(false);
      });

      it('should refuse to write even with a complete and valid chain', () => {
        expect(component.canSubmit()).toBe(false);

        component.onSave();

        expect(settingsService.upsertSettings).not.toHaveBeenCalled();
      });
    });

    describe('store writer (lc-company-store)', () => {
      beforeEach(async () => {
        await setup(chainQuery, undefined, ['lc-company-store']);
        await settle();
      });

      it('should render the save control', () => {
        expect(submitButton()).toBeTruthy();
        expect(fixture.nativeElement.querySelector('.form-actions')).toBeTruthy();
      });

      it('should keep both selectors editable', () => {
        const selects = fixture.debugElement.queryAll(By.directive(MatSelect));
        expect(selects.length).toBe(2);
        expect(selects.some((select) => (select.componentInstance as MatSelect).disabled)).toBe(
          false,
        );
      });
    });

    describe('read-only user with a stale configured location', () => {
      beforeEach(async () => {
        await setup(
          chainQuery,
          ({ settings }) =>
            settings.getSettings.mockReturnValue(
              of({
                companyStoreId: 'store-1',
                receivingLocationId: 'loc-gone',
                salesLocationId: 'loc-1',
                version: 3,
              }),
            ),
          ['lc-company-store-read'],
        );
        await settle();
      });

      it('should still render the stale-location notice, because it is information', () => {
        expect(component.receivingIsStale()).toBe(true);
        expect(component.canSubmit()).toBe(false);

        const warning = fixture.nativeElement.querySelector('.stale-warning');
        expect(warning).toBeTruthy();
        expect(warning.textContent).toContain('La ubicación configurada ya no está habilitada');
      });
    });
  });
});
