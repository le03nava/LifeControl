import { TestBed } from '@angular/core/testing';
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

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getAreas', () => {
    it('should GET the nested URL with includeDisabled=false and emit the areas', async () => {
      const request$ = firstValueFrom(
        service.getAreas(companyId, countryId, regionId, zoneId, storeId),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockAreas);

      await expect(request$).resolves.toEqual(mockAreas);
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

    it('should keep the request in flight until the response arrives', async () => {
      const request$ = firstValueFrom(
        service.getAreas(companyId, countryId, regionId, zoneId, storeId),
      );
      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');

      let settled = false;
      void request$.then(() => (settled = true));
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(settled).toBe(false);

      req.flush(mockAreas);

      await expect(request$).resolves.toEqual(mockAreas);
      expect(settled).toBe(true);
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

    it('should issue only the flat request, never a nested list read', async () => {
      const request$ = firstValueFrom(service.getAreaById('area-1'));

      httpMock.expectOne(`${baseUrl}/store-areas/area-1`).flush(mockAreas[0]);
      await request$;

      expect(httpMock.match((r) => r.url === nestedUrl).length).toBe(0);
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

    it('should POST the body to the nested URL and emit the created area', async () => {
      const request$ = firstValueFrom(
        service.createArea(companyId, countryId, regionId, zoneId, storeId, request),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'POST');
      expect(req.request.body).toEqual(request);
      req.flush(response);

      await expect(request$).resolves.toEqual(response);
    });

    it('should set the create error message and rethrow', async () => {
      const request$ = firstValueFrom(
        service.createArea(companyId, countryId, regionId, zoneId, storeId, request),
      );

      httpMock
        .expectOne(nestedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al crear el área');
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

    it('should PUT the body to the nested /{areaId} URL and emit the updated area', async () => {
      const request$ = firstValueFrom(
        service.updateArea(companyId, countryId, regionId, zoneId, storeId, areaId, request),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'PUT');
      expect(req.request.body).toEqual(request);
      req.flush(updated);

      await expect(request$).resolves.toEqual(updated);
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

    it('should DELETE the nested /{areaId} URL and complete', async () => {
      const request$ = firstValueFrom(
        service.removeArea(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'DELETE');
      req.flush(null);

      await request$;

      // Soft delete: the backend flips `enabled`; the client issues no follow-up read or write.
      expect(httpMock.match((r) => r.method !== 'DELETE').length).toBe(0);
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

    it('should PATCH /{areaId}/enable and emit the re-enabled area', async () => {
      const request$ = firstValueFrom(
        service.enableArea(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'PATCH');
      req.flush(enabled);

      await expect(request$).resolves.toEqual(enabled);
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

  describe('error signal', () => {
    it('should start with no error', () => {
      expect(service.error()).toBeNull();
    });

    it('should clear a previous error when a new request starts', async () => {
      const failed$ = firstValueFrom(
        service.getAreas(companyId, countryId, regionId, zoneId, storeId),
      );
      httpMock
        .expectOne((r) => r.url === nestedUrl && r.method === 'GET')
        .flush('Server error', { status: 500, statusText: 'Internal Server Error' });
      await expect(failed$).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar las áreas');

      const retry$ = firstValueFrom(
        service.getAreas(companyId, countryId, regionId, zoneId, storeId),
      );
      expect(service.error()).toBeNull();

      httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET').flush(mockAreas);
      await retry$;
    });
  });
});
