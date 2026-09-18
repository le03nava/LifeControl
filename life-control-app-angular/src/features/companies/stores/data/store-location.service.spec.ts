import { TestBed } from '@angular/core/testing';
import { WritableSignal } from '@angular/core';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { StoreLocationService } from './store-location.service';
import {
  CreateStoreLocationRequest,
  StoreLocation,
  UpdateStoreLocationRequest,
} from '../models/store-location.models';

describe('StoreLocationService', () => {
  let service: StoreLocationService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const countryId = 'cc-1';
  const regionId = 'region-1';
  const zoneId = 'zone-1';
  const storeId = 'store-1';
  const areaId = 'area-1';
  const storeZoneId = 'store-zone-1';
  const baseUrl = 'http://localhost:9000/api';
  const nestedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}/areas/${areaId}/store-zones/${storeZoneId}/store-locations`;

  const mockLocations: StoreLocation[] = [
    {
      id: 'store-location-1',
      storeZoneId,
      storeAreaId: areaId,
      companyStoreId: storeId,
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      locationCode: 'A-01',
      locationName: 'Estante A-01',
      description: 'Ubicación de almacenamiento seco',
      displayOrder: 1,
      enabled: true,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-15T00:00:00Z',
    },
    {
      id: 'store-location-2',
      storeZoneId,
      storeAreaId: areaId,
      companyStoreId: storeId,
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      locationCode: 'A-02',
      locationName: 'Estante A-02',
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
      providers: [StoreLocationService, { provide: ConfigService, useValue: { apiUrl: baseUrl } }],
    });
    service = TestBed.inject(StoreLocationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function setStoreLocations(storeLocations: StoreLocation[]): void {
    (
      service as unknown as { _storeLocations: WritableSignal<StoreLocation[]> }
    )._storeLocations.set(storeLocations);
  }

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getStoreLocations', () => {
    it('should GET the nested URL and populate the store locations signal', async () => {
      const request$ = firstValueFrom(
        service.getStoreLocations(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
        ),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockLocations);

      const storeLocations = await request$;
      expect(storeLocations).toEqual(mockLocations);
      expect(service.storeLocations()).toEqual(mockLocations);
    });

    it('should send includeDisabled=true when requested', async () => {
      const request$ = firstValueFrom(
        service.getStoreLocations(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
          true,
        ),
      );

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(mockLocations);

      await request$;
    });

    it('should toggle loading around the request', async () => {
      expect(service.loading()).toBe(false);

      const request$ = firstValueFrom(
        service.getStoreLocations(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
        ),
      );

      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'GET').flush(mockLocations);
      await request$;

      expect(service.loading()).toBe(false);
    });

    it('should set the store locations error message on failure', async () => {
      const request$ = firstValueFrom(
        service.getStoreLocations(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
        ),
      );

      httpMock
        .expectOne((r) => r.url === nestedUrl && r.method === 'GET')
        .flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar las ubicaciones');
    });
  });

  describe('getLocationById (flat lookup)', () => {
    it('should GET the flat /store-locations/{storeLocationId} URL', async () => {
      const request$ = firstValueFrom(service.getLocationById('store-location-1'));

      const req = httpMock.expectOne(
        (r) => r.url === `${baseUrl}/store-locations/store-location-1` && r.method === 'GET',
      );
      req.flush(mockLocations[0]);

      const storeLocation = await request$;
      expect(storeLocation).toEqual(mockLocations[0]);
      expect(storeLocation.storeZoneId).toBe(storeZoneId);
    });

    it('should NOT touch the store locations signal', async () => {
      setStoreLocations([mockLocations[1]]);
      expect(service.storeLocations()).toEqual([mockLocations[1]]);

      const request$ = firstValueFrom(service.getLocationById('store-location-1'));
      httpMock.expectOne(`${baseUrl}/store-locations/store-location-1`).flush(mockLocations[0]);
      await request$;

      expect(service.storeLocations()).toEqual([mockLocations[1]]);
    });

    it('should set the store location error message on failure', async () => {
      const request$ = firstValueFrom(service.getLocationById('missing'));

      httpMock
        .expectOne(`${baseUrl}/store-locations/missing`)
        .flush('Not found', { status: 404, statusText: 'Not Found' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar la ubicación');
    });
  });

  describe('createLocation', () => {
    const request: CreateStoreLocationRequest = {
      locationCode: 'A-03',
      locationName: 'Estante A-03',
      description: 'Descripción',
      displayOrder: 3,
    };

    const response: StoreLocation = {
      id: 'store-location-3',
      storeZoneId,
      storeAreaId: areaId,
      companyStoreId: storeId,
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      locationCode: 'A-03',
      locationName: 'Estante A-03',
      description: 'Descripción',
      displayOrder: 3,
      enabled: true,
      createdAt: '2024-02-01T00:00:00Z',
      updatedAt: '2024-02-01T00:00:00Z',
    };

    it('should POST to the nested URL and append to the signal', async () => {
      setStoreLocations([mockLocations[0]]);

      const request$ = firstValueFrom(
        service.createLocation(
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

      const req = httpMock.expectOne((r) => r.url === nestedUrl && r.method === 'POST');
      expect(req.request.body).toEqual(request);
      req.flush(response);

      const created = await request$;
      expect(created).toEqual(response);
      expect(service.storeLocations().length).toBe(2);
      expect(service.storeLocations()[1].locationCode).toBe('A-03');
    });

    it('should set the create error message and leave the signal untouched', async () => {
      setStoreLocations([mockLocations[0]]);

      const request$ = firstValueFrom(
        service.createLocation(
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
        .expectOne(nestedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al crear la ubicación');
      expect(service.storeLocations().length).toBe(1);
    });
  });

  describe('updateLocation', () => {
    const storeLocationId = 'store-location-1';
    const expectedUrl = `${nestedUrl}/${storeLocationId}`;
    const request: UpdateStoreLocationRequest = {
      locationCode: 'A-01',
      locationName: 'Estante A-01 Nuevo',
    };

    const updated: StoreLocation = {
      ...mockLocations[0],
      locationName: 'Estante A-01 Nuevo',
      updatedAt: '2024-02-01T00:00:00Z',
    };

    it('should PUT to the nested /{storeLocationId} URL and map-replace in the signal', async () => {
      setStoreLocations(mockLocations);

      const request$ = firstValueFrom(
        service.updateLocation(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
          storeLocationId,
          request,
        ),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'PUT');
      expect(req.request.body).toEqual(request);
      req.flush(updated);

      const result = await request$;
      expect(result).toEqual(updated);
      expect(service.storeLocations().length).toBe(2);
      expect(service.storeLocations()[0].locationName).toBe('Estante A-01 Nuevo');
      expect(service.storeLocations()[1]).toEqual(mockLocations[1]);
    });

    it('should set the update error message on failure', async () => {
      const request$ = firstValueFrom(
        service.updateLocation(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
          storeLocationId,
          request,
        ),
      );

      httpMock
        .expectOne(expectedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al actualizar la ubicación');
    });
  });

  describe('removeLocation (soft delete)', () => {
    const storeLocationId = 'store-location-1';
    const expectedUrl = `${nestedUrl}/${storeLocationId}`;

    it('should DELETE and flag the row enabled=false without removing it', async () => {
      setStoreLocations(mockLocations);

      const request$ = firstValueFrom(
        service.removeLocation(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
          storeLocationId,
        ),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'DELETE');
      req.flush(null);

      await request$;

      expect(service.storeLocations().length).toBe(2);
      const storeLocation = service.storeLocations().find((l) => l.id === storeLocationId);
      expect(storeLocation).toBeDefined();
      expect(storeLocation!.enabled).toBe(false);
    });

    it('should set the disable error message on failure', async () => {
      const request$ = firstValueFrom(
        service.removeLocation(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
          storeLocationId,
        ),
      );

      httpMock
        .expectOne(expectedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al deshabilitar la ubicación');
    });
  });

  describe('enableLocation', () => {
    const storeLocationId = 'store-location-2';
    // The store locations backend requires the /{storeLocationId}/enable suffix (like zones).
    const expectedUrl = `${nestedUrl}/${storeLocationId}/enable`;

    const enabled: StoreLocation = { ...mockLocations[1], enabled: true };

    it('should PATCH /{storeLocationId}/enable and map-replace in the signal', async () => {
      setStoreLocations(mockLocations);

      const request$ = firstValueFrom(
        service.enableLocation(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
          storeLocationId,
        ),
      );

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'PATCH');
      req.flush(enabled);

      const result = await request$;
      expect(result).toEqual(enabled);
      expect(service.storeLocations().find((l) => l.id === storeLocationId)?.enabled).toBe(true);
    });

    it('should set the enable error message on failure', async () => {
      const request$ = firstValueFrom(
        service.enableLocation(
          companyId,
          countryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
          storeLocationId,
        ),
      );

      httpMock
        .expectOne(expectedUrl)
        .flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(request$).rejects.toThrow();
      expect(service.error()).toBe('Error al reactivar la ubicación');
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
    it('should expose readonly signals for store locations, loading, and error', () => {
      expect(service.storeLocations()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
