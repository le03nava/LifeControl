import { TestBed } from '@angular/core/testing';
import { WritableSignal } from '@angular/core';
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

  function setStoreZones(storeZones: StoreZone[]): void {
    (service as unknown as { _storeZones: WritableSignal<StoreZone[]> })._storeZones.set(
      storeZones,
    );
  }

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getStoreZones', () => {
    it('should GET the nested URL and populate the store zones signal', async () => {
      const request$ = firstValueFrom(
        service.getStoreZones(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockZones);

      const storeZones = await request$;
      expect(storeZones).toEqual(mockZones);
      expect(service.storeZones()).toEqual(mockZones);
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

    it('should toggle loading around the request', async () => {
      expect(service.loading()).toBe(false);

      const request$ = firstValueFrom(
        service.getStoreZones(companyId, countryId, regionId, zoneId, storeId, areaId),
      );

      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET').flush(mockZones);
      await request$;

      expect(service.loading()).toBe(false);
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

    it('should NOT touch the store zones signal', async () => {
      setStoreZones([mockZones[1]]);
      expect(service.storeZones()).toEqual([mockZones[1]]);

      const request$ = firstValueFrom(service.getZoneById('store-zone-1'));
      httpMock.expectOne(`${baseUrl}/store-zones/store-zone-1`).flush(mockZones[0]);
      await request$;

      expect(service.storeZones()).toEqual([mockZones[1]]);
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
    };

    it('should POST to the nested URL and append to the signal', async () => {
      setStoreZones([mockZones[0]]);

      const request$ = firstValueFrom(
        service.createZone(companyId, countryId, regionId, zoneId, storeId, areaId, request),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'POST');
      expect(req.request.body).toEqual(request);
      req.flush(response);

      const created = await request$;
      expect(created).toEqual(response);
      expect(service.storeZones().length).toBe(2);
      expect(service.storeZones()[1].zoneCode).toBe('NUEVA');
    });

    it('should set the create error message and leave the signal untouched', async () => {
      setStoreZones([mockZones[0]]);

      const request$ = firstValueFrom(
        service.createZone(companyId, countryId, regionId, zoneId, storeId, areaId, request),
      );

      httpMock
        .expectOne(nestedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al crear la zona de la tienda');
      expect(service.storeZones().length).toBe(1);
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

    it('should PUT to the nested /{storeZoneId} URL and map-replace in the signal', async () => {
      setStoreZones(mockZones);

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

      const result = await request$;
      expect(result).toEqual(updated);
      expect(service.storeZones().length).toBe(2);
      expect(service.storeZones()[0].zoneName).toBe('Depósito Nuevo');
      expect(service.storeZones()[1]).toEqual(mockZones[1]);
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

    it('should DELETE and flag the row enabled=false without removing it', async () => {
      setStoreZones(mockZones);

      const request$ = firstValueFrom(
        service.removeZone(companyId, countryId, regionId, zoneId, storeId, areaId, storeZoneId),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'DELETE');
      req.flush(null);

      await request$;

      expect(service.storeZones().length).toBe(2);
      const storeZone = service.storeZones().find((z) => z.id === storeZoneId);
      expect(storeZone).toBeDefined();
      expect(storeZone!.enabled).toBe(false);
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

    it('should PATCH /{storeZoneId}/enable and map-replace in the signal', async () => {
      setStoreZones(mockZones);

      const request$ = firstValueFrom(
        service.enableZone(companyId, countryId, regionId, zoneId, storeId, areaId, storeZoneId),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'PATCH');
      req.flush(enabled);

      const result = await request$;
      expect(result).toEqual(enabled);
      expect(service.storeZones().find((z) => z.id === storeZoneId)?.enabled).toBe(true);
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

  describe('clearError', () => {
    it('should reset the error signal to null', () => {
      (service as unknown as { _error: WritableSignal<string | null> })._error.set('Some error');
      expect(service.error()).toBe('Some error');

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });

  describe('signal exposure', () => {
    it('should expose readonly signals for store zones, loading, and error', () => {
      expect(service.storeZones()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
