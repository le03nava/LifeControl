import { TestBed } from '@angular/core/testing';
import { WritableSignal } from '@angular/core';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { StoreAreaService } from './store-area.service';
import {
  CreateStoreAreaRequest,
  StoreArea,
  UpdateStoreAreaRequest,
} from '../models/store-area.models';

describe('StoreAreaService', () => {
  let service: StoreAreaService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const countryId = 'cc-1';
  const regionId = 'region-1';
  const zoneId = 'zone-1';
  const storeId = 'store-1';
  const baseUrl = 'http://localhost:9000/api';
  const nestedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}/areas`;

  const mockAreas: StoreArea[] = [
    {
      id: 'area-1',
      companyStoreId: storeId,
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      areaCode: 'ALMACEN',
      areaName: 'Almacén Central',
      description: 'Depósito principal',
      displayOrder: 1,
      enabled: true,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-15T00:00:00Z',
    },
    {
      id: 'area-2',
      companyStoreId: storeId,
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      areaCode: 'MOSTRADOR',
      areaName: 'Mostrador',
      description: null,
      displayOrder: null,
      enabled: false,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-15T00:00:00Z',
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [StoreAreaService, { provide: ConfigService, useValue: { apiUrl: baseUrl } }],
    });
    service = TestBed.inject(StoreAreaService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function setAreas(areas: StoreArea[]): void {
    (service as unknown as { _areas: WritableSignal<StoreArea[]> })._areas.set(areas);
  }

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getAreas', () => {
    it('should GET the nested URL and populate the areas signal', async () => {
      const request$ = firstValueFrom(
        service.getAreas(companyId, countryId, regionId, zoneId, storeId),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockAreas);

      const areas = await request$;
      expect(areas).toEqual(mockAreas);
      expect(service.areas()).toEqual(mockAreas);
    });

    it('should send includeDisabled=true when requested', async () => {
      const request$ = firstValueFrom(
        service.getAreas(companyId, countryId, regionId, zoneId, storeId, true),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(mockAreas);

      await request$;
    });

    it('should toggle loading around the request', async () => {
      expect(service.loading()).toBe(false);

      const request$ = firstValueFrom(
        service.getAreas(companyId, countryId, regionId, zoneId, storeId),
      );

      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET').flush(mockAreas);
      await request$;

      expect(service.loading()).toBe(false);
    });

    it('should set the areas error message on failure', async () => {
      const request$ = firstValueFrom(
        service.getAreas(companyId, countryId, regionId, zoneId, storeId),
      );

      httpMock
        .expectOne((r) => r.url === nestedUrl && r.method === 'GET')
        .flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar las áreas');
    });
  });

  describe('getAreaById (flat lookup)', () => {
    it('should GET the flat /store-areas/{areaId} URL', async () => {
      const request$ = firstValueFrom(service.getAreaById('area-1'));

      const req = httpMock.expectOne(
        (r) => r.url === `${baseUrl}/store-areas/area-1` && r.method === 'GET',
      );
      req.flush(mockAreas[0]);

      const area = await request$;
      expect(area).toEqual(mockAreas[0]);
      expect(area.companyStoreId).toBe(storeId);
    });

    it('should NOT touch the areas signal', async () => {
      setAreas([mockAreas[1]]);
      expect(service.areas()).toEqual([mockAreas[1]]);

      const request$ = firstValueFrom(service.getAreaById('area-1'));
      httpMock.expectOne(`${baseUrl}/store-areas/area-1`).flush(mockAreas[0]);
      await request$;

      expect(service.areas()).toEqual([mockAreas[1]]);
    });

    it('should set the area error message on failure', async () => {
      const request$ = firstValueFrom(service.getAreaById('missing'));

      httpMock
        .expectOne(`${baseUrl}/store-areas/missing`)
        .flush('Not found', { status: 404, statusText: 'Not Found' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar el área');
    });
  });

  describe('createArea', () => {
    const request: CreateStoreAreaRequest = {
      areaCode: 'NUEVA',
      areaName: 'Área Nueva',
      description: 'Descripción',
      displayOrder: 3,
    };

    const response: StoreArea = {
      id: 'area-3',
      companyStoreId: storeId,
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      areaCode: 'NUEVA',
      areaName: 'Área Nueva',
      description: 'Descripción',
      displayOrder: 3,
      enabled: true,
      createdAt: '2024-02-01T00:00:00Z',
      updatedAt: '2024-02-01T00:00:00Z',
    };

    it('should POST to the nested URL and append to the signal', async () => {
      setAreas([mockAreas[0]]);

      const request$ = firstValueFrom(
        service.createArea(companyId, countryId, regionId, zoneId, storeId, request),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'POST');
      expect(req.request.body).toEqual(request);
      req.flush(response);

      const created = await request$;
      expect(created).toEqual(response);
      expect(service.areas().length).toBe(2);
      expect(service.areas()[1].areaCode).toBe('NUEVA');
    });

    it('should set the create error message and leave the signal untouched', async () => {
      setAreas([mockAreas[0]]);

      const request$ = firstValueFrom(
        service.createArea(companyId, countryId, regionId, zoneId, storeId, request),
      );

      httpMock
        .expectOne(nestedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al crear el área');
      expect(service.areas().length).toBe(1);
    });
  });

  describe('updateArea', () => {
    const areaId = 'area-1';
    const expectedUrl = `${nestedUrl}/${areaId}`;
    const request: UpdateStoreAreaRequest = { areaCode: 'ALMACEN', areaName: 'Almacén Nuevo' };

    const updated: StoreArea = {
      ...mockAreas[0],
      areaName: 'Almacén Nuevo',
      updatedAt: '2024-02-01T00:00:00Z',
    };

    it('should PUT to the nested /{areaId} URL and map-replace in the signal', async () => {
      setAreas(mockAreas);

      const request$ = firstValueFrom(
        service.updateArea(companyId, countryId, regionId, zoneId, storeId, areaId, request),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'PUT');
      expect(req.request.body).toEqual(request);
      req.flush(updated);

      const result = await request$;
      expect(result).toEqual(updated);
      expect(service.areas().length).toBe(2);
      expect(service.areas()[0].areaName).toBe('Almacén Nuevo');
      expect(service.areas()[1]).toEqual(mockAreas[1]);
    });

    it('should set the update error message on failure', async () => {
      const request$ = firstValueFrom(
        service.updateArea(companyId, countryId, regionId, zoneId, storeId, areaId, request),
      );

      httpMock
        .expectOne(expectedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al actualizar el área');
    });
  });

  describe('removeArea (soft delete)', () => {
    const areaId = 'area-1';
    const expectedUrl = `${nestedUrl}/${areaId}`;

    it('should DELETE and flag the row enabled=false without removing it', async () => {
      setAreas(mockAreas);

      const request$ = firstValueFrom(
        service.removeArea(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'DELETE');
      req.flush(null);

      await request$;

      expect(service.areas().length).toBe(2);
      const area = service.areas().find((a) => a.id === areaId);
      expect(area).toBeDefined();
      expect(area!.enabled).toBe(false);
    });

    it('should set the disable error message on failure', async () => {
      const request$ = firstValueFrom(
        service.removeArea(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      httpMock
        .expectOne(expectedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al deshabilitar el área');
    });
  });

  describe('enableArea', () => {
    const areaId = 'area-2';
    // The areas backend requires the /{areaId}/enable suffix (unlike stores).
    const expectedUrl = `${nestedUrl}/${areaId}/enable`;

    const enabled: StoreArea = { ...mockAreas[1], enabled: true };

    it('should PATCH /{areaId}/enable and map-replace in the signal', async () => {
      setAreas(mockAreas);

      const request$ = firstValueFrom(
        service.enableArea(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'PATCH');
      req.flush(enabled);

      const result = await request$;
      expect(result).toEqual(enabled);
      expect(service.areas().find((a) => a.id === areaId)?.enabled).toBe(true);
    });

    it('should set the enable error message on failure', async () => {
      const request$ = firstValueFrom(
        service.enableArea(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      httpMock
        .expectOne(expectedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al reactivar el área');
    });
  });

  describe('clearError', () => {
    it('should reset the error signal to null', () => {
      (service as unknown as { _error: WritableSignal<string | null> })._error.set('Some error');
      expect(service.error()).toBe('Some error');

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });

  describe('signal exposure', () => {
    it('should expose readonly signals for areas, loading, and error', () => {
      expect(service.areas()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
