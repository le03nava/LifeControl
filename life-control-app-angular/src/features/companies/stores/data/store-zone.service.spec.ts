import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { StoreZoneService } from './store-zone.service';
import {
  CreateStoreZoneRequest,
  StoreZone,
  UpdateStoreZoneRequest,
} from '../models/store-zone.models';

describe('StoreZoneService', () => {
  let service: StoreZoneService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const countryId = 'cc-1';
  const regionId = 'region-1';
  const zoneId = 'zone-1';
  const storeId = 'store-1';
  const areaId = 'area-1';
  const baseUrl = 'http://localhost:9000/api';
  const nestedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}/areas/${areaId}/store-zones`;

  const mockZones: StoreZone[] = [
    {
      id: 'store-zone-1',
      storeAreaId: areaId,
      companyStoreId: storeId,
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      zoneCode: 'SECO',
      zoneName: 'Depósito Seco',
      description: 'Zona de almacenamiento seco',
      displayOrder: 1,
      enabled: true,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-15T00:00:00Z',
      version: 1,
    },
    {
      id: 'store-zone-2',
      storeAreaId: areaId,
      companyStoreId: storeId,
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      zoneCode: 'FRIO',
      zoneName: 'Cámara Fría',
      description: null,
      displayOrder: null,
      enabled: false,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-15T00:00:00Z',
      version: 2,
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [StoreZoneService, { provide: ConfigService, useValue: { apiUrl: baseUrl } }],
    });
    service = TestBed.inject(StoreZoneService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getStoreZones', () => {
    it('should GET the nested URL with includeDisabled=false and emit the store zones', async () => {
      const request$ = firstValueFrom(
        service.getStoreZones(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockZones);

      await expect(request$).resolves.toEqual(mockZones);
    });

    it('should send includeDisabled=true when requested', async () => {
      const request$ = firstValueFrom(
        service.getStoreZones(companyId, countryId, regionId, zoneId, storeId, areaId, true),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(mockZones);

      await request$;
    });

    it('should keep the request in flight until the response arrives', async () => {
      const request$ = firstValueFrom(
        service.getStoreZones(companyId, countryId, regionId, zoneId, storeId, areaId),
      );
      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');

      let settled = false;
      void request$.then(() => (settled = true));
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(settled).toBe(false);

      req.flush(mockZones);

      await expect(request$).resolves.toEqual(mockZones);
      expect(settled).toBe(true);
    });

    it('should set the store zones error message on failure', async () => {
      const request$ = firstValueFrom(
        service.getStoreZones(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      httpMock
        .expectOne((r) => r.url === nestedUrl && r.method === 'GET')
        .flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar las zonas de la tienda');
    });
  });

  describe('getZoneById (flat lookup)', () => {
    it('should GET the flat /store-zones/{storeZoneId} URL', async () => {
      const request$ = firstValueFrom(service.getZoneById('store-zone-1'));

      const req = httpMock.expectOne(
        (r) => r.url === `${baseUrl}/store-zones/store-zone-1` && r.method === 'GET',
      );
      req.flush(mockZones[0]);

      const storeZone = await request$;
      expect(storeZone).toEqual(mockZones[0]);
      expect(storeZone.storeAreaId).toBe(areaId);
    });

    it('should issue only the flat request, never a nested list read', async () => {
      const request$ = firstValueFrom(service.getZoneById('store-zone-1'));

      httpMock.expectOne(`${baseUrl}/store-zones/store-zone-1`).flush(mockZones[0]);
      await request$;

      expect(httpMock.match((r) => r.url === nestedUrl).length).toBe(0);
    });

    it('should set the store zone error message on failure', async () => {
      const request$ = firstValueFrom(service.getZoneById('missing'));

      httpMock
        .expectOne(`${baseUrl}/store-zones/missing`)
        .flush('Not found', { status: 404, statusText: 'Not Found' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar la zona de la tienda');
    });
  });

  describe('createZone', () => {
    const request: CreateStoreZoneRequest = {
      zoneCode: 'NUEVA',
      zoneName: 'Zona Nueva',
      description: 'Descripción',
      displayOrder: 3,
    };

    const response: StoreZone = {
      id: 'store-zone-3',
      storeAreaId: areaId,
      companyStoreId: storeId,
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      zoneCode: 'NUEVA',
      zoneName: 'Zona Nueva',
      description: 'Descripción',
      displayOrder: 3,
      enabled: true,
      createdAt: '2024-02-01T00:00:00Z',
      updatedAt: '2024-02-01T00:00:00Z',
      version: 0,
    };

    it('should POST the body to the nested URL and emit the created store zone', async () => {
      const request$ = firstValueFrom(
        service.createZone(companyId, countryId, regionId, zoneId, storeId, areaId, request),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'POST');
      expect(req.request.body).toEqual(request);
      req.flush(response);

      await expect(request$).resolves.toEqual(response);
    });

    it('should set the create error message and rethrow', async () => {
      const request$ = firstValueFrom(
        service.createZone(companyId, countryId, regionId, zoneId, storeId, areaId, request),
      );

      httpMock
        .expectOne(nestedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al crear la zona de la tienda');
    });
  });

  describe('updateZone', () => {
    const storeZoneId = 'store-zone-1';
    const expectedUrl = `${nestedUrl}/${storeZoneId}`;
    const request: UpdateStoreZoneRequest = { zoneCode: 'SECO', zoneName: 'Depósito Nuevo' };

    const updated: StoreZone = {
      ...mockZones[0],
      zoneName: 'Depósito Nuevo',
      updatedAt: '2024-02-01T00:00:00Z',
    };

    it('should PUT the body to the nested /{storeZoneId} URL and emit the updated store zone', async () => {
      const request$ = firstValueFrom(
        service.updateZone(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
          request,
        ),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'PUT');
      expect(req.request.body).toEqual(request);
      req.flush(updated);

      await expect(request$).resolves.toEqual(updated);
    });

    it('should set the update error message on failure', async () => {
      const request$ = firstValueFrom(
        service.updateZone(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
          request,
        ),
      );

      httpMock
        .expectOne(expectedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al actualizar la zona de la tienda');
    });
  });

  describe('removeZone (soft delete)', () => {
    const storeZoneId = 'store-zone-1';
    const expectedUrl = `${nestedUrl}/${storeZoneId}`;

    it('should DELETE the nested /{storeZoneId} URL and complete', async () => {
      const request$ = firstValueFrom(
        service.removeZone(companyId, countryId, regionId, zoneId, storeId, areaId, storeZoneId),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'DELETE');
      req.flush(null);

      await request$;

      // Soft delete: the backend flips `enabled`; the client issues no follow-up read or write.
      expect(httpMock.match((r) => r.method !== 'DELETE').length).toBe(0);
    });

    it('should set the disable error message on failure', async () => {
      const request$ = firstValueFrom(
        service.removeZone(companyId, countryId, regionId, zoneId, storeId, areaId, storeZoneId),
      );

      httpMock
        .expectOne(expectedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al deshabilitar la zona de la tienda');
    });
  });

  describe('enableZone', () => {
    const storeZoneId = 'store-zone-2';
    // The store zones backend requires the /{storeZoneId}/enable suffix (like areas).
    const expectedUrl = `${nestedUrl}/${storeZoneId}/enable`;

    const enabled: StoreZone = { ...mockZones[1], enabled: true };

    it('should PATCH /{storeZoneId}/enable and emit the re-enabled store zone', async () => {
      const request$ = firstValueFrom(
        service.enableZone(companyId, countryId, regionId, zoneId, storeId, areaId, storeZoneId),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'PATCH');
      req.flush(enabled);

      await expect(request$).resolves.toEqual(enabled);
    });

    it('should set the enable error message on failure', async () => {
      const request$ = firstValueFrom(
        service.enableZone(companyId, countryId, regionId, zoneId, storeId, areaId, storeZoneId),
      );

      httpMock
        .expectOne(expectedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al reactivar la zona de la tienda');
    });
  });

  describe('error signal', () => {
    it('should start with no error', () => {
      expect(service.error()).toBeNull();
    });

    it('should clear a previous error when a new request starts', async () => {
      const failed$ = firstValueFrom(
        service.getStoreZones(companyId, countryId, regionId, zoneId, storeId, areaId),
      );
      httpMock
        .expectOne((r) => r.url === nestedUrl && r.method === 'GET')
        .flush('Server error', { status: 500, statusText: 'Internal Server Error' });
      await expect(failed$).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar las zonas de la tienda');

      const retry$ = firstValueFrom(
        service.getStoreZones(companyId, countryId, regionId, zoneId, storeId, areaId),
      );
      expect(service.error()).toBeNull();

      httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET').flush(mockZones);
      await retry$;
    });
  });
});
