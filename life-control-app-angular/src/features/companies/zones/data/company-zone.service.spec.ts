import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { CompanyZoneService } from './company-zone.service';
import { CompanyZone, CompanyZoneRequest } from '../models/zone.models';

describe('CompanyZoneService', () => {
  let service: CompanyZoneService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const countryId = 'cc-1';
  const regionId = 'region-1';
  const baseUrl = 'http://localhost:9000/api';

  const mockZones: CompanyZone[] = [
    {
      id: 'zone-1',
      companyRegionId: 'region-1',
      companyCountryId: 'cc-1',
      companyId: 'company-123',
      countryId: '1',
      zoneCode: 'US-CA-DT',
      zoneName: 'Downtown',
      description: 'Urban center district',
      displayOrder: 1,
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
    {
      id: 'zone-2',
      companyRegionId: 'region-1',
      companyCountryId: 'cc-1',
      companyId: 'company-123',
      countryId: '1',
      zoneCode: 'US-CA-SF',
      zoneName: 'San Fernando',
      description: 'Suburban area',
      displayOrder: 2,
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CompanyZoneService],
    });
    service = TestBed.inject(CompanyZoneService);
    httpMock = TestBed.inject(HttpTestingController);
    (service as any).configService = { apiUrl: baseUrl };
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getZones', () => {
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones`;

    it('should fetch zones for a company country region', async () => {
      const zonesPromise = firstValueFrom(service.getZones(companyId, countryId, regionId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockZones);

      const zones = await zonesPromise;
      expect(zones).toEqual(mockZones);
      expect(zones.length).toBe(2);
    });

    it('should populate the zones signal', async () => {
      const fetchPromise = firstValueFrom(service.getZones(companyId, countryId, regionId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      req.flush(mockZones);

      await fetchPromise;

      expect(service.zones()).toEqual(mockZones);
      expect(service.zones().length).toBe(2);
    });

    it('should set loading true during fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const fetchPromise = firstValueFrom(service.getZones(companyId, countryId, regionId));

      await new Promise(resolve => setTimeout(resolve, 10));

      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      req.flush(mockZones);

      await fetchPromise;

      expect(service.loading()).toBe(false);
    });

    it('should set error signal on HTTP failure', async () => {
      const errorPromise = firstValueFrom(service.getZones(companyId, countryId, regionId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(errorPromise).rejects.toThrow();

      expect(service.error()).toBe('Error al cargar las zonas');
    });
  });

  describe('getZone', () => {
    const zoneId = 'zone-1';
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}`;

    it('should fetch a single zone by id', async () => {
      const zonePromise = firstValueFrom(service.getZone(companyId, countryId, regionId, zoneId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      req.flush(mockZones[0]);

      const zone = await zonePromise;
      expect(zone).toEqual(mockZones[0]);
      expect(zone.id).toBe(zoneId);
    });

    it('should set error on fetch failure', async () => {
      const zonePromise = firstValueFrom(service.getZone(companyId, countryId, regionId, zoneId));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Not found', { status: 404, statusText: 'Not Found' });

      await expect(zonePromise).rejects.toThrow();

      expect(service.error()).toBe('Error al cargar la zona');
    });
  });

  describe('addZone', () => {
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones`;
    const request: CompanyZoneRequest = {
      zoneCode: 'US-CA-WL',
      zoneName: 'West LA',
      description: 'Western area',
      displayOrder: 3,
    };

    const response: CompanyZone = {
      id: 'zone-3',
      companyRegionId: 'region-1',
      companyCountryId: 'cc-1',
      companyId: 'company-123',
      countryId: '1',
      zoneCode: 'US-CA-WL',
      zoneName: 'West LA',
      description: 'Western area',
      displayOrder: 3,
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    };

    it('should POST and push the new zone to the signal', async () => {
      (service as any)._zones.set([mockZones[0]]);
      expect(service.zones().length).toBe(1);

      const addPromise = firstValueFrom(service.addZone(companyId, countryId, regionId, request));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'POST'
      );
      expect(req.request.body).toEqual(request);
      req.flush(response);

      const zone = await addPromise;
      expect(zone).toEqual(response);
      expect(service.zones().length).toBe(2);
      expect(service.zones()[1].zoneCode).toBe('US-CA-WL');
      expect(service.zones()[1].zoneName).toBe('West LA');
    });

    it('should set specific error message on 409 Conflict', async () => {
      const addPromise = firstValueFrom(service.addZone(companyId, countryId, regionId, request));

      const req = httpMock.expectOne(expectedUrl);
      req.flush({ message: 'Conflict' }, { status: 409, statusText: 'Conflict' });

      await expect(addPromise).rejects.toThrow();

      expect(service.error()).toBe('Ya existe una zona con ese código');
    });

    it('should NOT modify signal on error', async () => {
      (service as any)._zones.set([mockZones[0]]);

      const addPromise = firstValueFrom(service.addZone(companyId, countryId, regionId, request));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(addPromise).rejects.toThrow();

      expect(service.zones().length).toBe(1);
    });

    it('should set generic error on non-409 failure', async () => {
      const addPromise = firstValueFrom(service.addZone(companyId, countryId, regionId, request));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(addPromise).rejects.toThrow();

      expect(service.error()).toBe('Error al crear la zona');
    });
  });

  describe('updateZone', () => {
    const zoneId = 'zone-1';
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}`;
    const request: CompanyZoneRequest = {
      zoneCode: 'US-CA-DT',
      zoneName: 'Downtown Updated',
    };

    const updatedZone: CompanyZone = {
      id: 'zone-1',
      companyRegionId: 'region-1',
      companyCountryId: 'cc-1',
      companyId: 'company-123',
      countryId: '1',
      zoneCode: 'US-CA-DT',
      zoneName: 'Downtown Updated',
      description: 'Urban center district',
      displayOrder: 1,
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-02',
    };

    it('should PUT and replace the zone in the signal', async () => {
      (service as any)._zones.set(mockZones);
      expect(service.zones().length).toBe(2);

      const updatePromise = firstValueFrom(service.updateZone(companyId, countryId, regionId, zoneId, request));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'PUT'
      );
      expect(req.request.body).toEqual(request);
      req.flush(updatedZone);

      const zone = await updatePromise;
      expect(zone).toEqual(updatedZone);
      expect(service.zones().length).toBe(2);
      expect(service.zones()[0].zoneName).toBe('Downtown Updated');
    });

    it('should set error on update failure', async () => {
      const updatePromise = firstValueFrom(service.updateZone(companyId, countryId, regionId, zoneId, request));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(updatePromise).rejects.toThrow();

      expect(service.error()).toBe('Error al actualizar la zona');
    });
  });

  describe('removeZone', () => {
    const zoneId = 'zone-1';
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}`;

    it('should DELETE and filter out the removed zone', async () => {
      (service as any)._zones.set(mockZones);
      expect(service.zones().length).toBe(2);

      const removePromise = firstValueFrom(service.removeZone(companyId, countryId, regionId, zoneId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'DELETE'
      );
      req.flush(null);

      await removePromise;

      expect(service.zones().length).toBe(1);
      expect(service.zones()[0].id).toBe('zone-2');
    });

    it('should set error on remove failure', async () => {
      const removePromise = firstValueFrom(service.removeZone(companyId, countryId, regionId, zoneId));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(removePromise).rejects.toThrow();

      expect(service.error()).toBe('Error al eliminar la zona');
    });
  });

  describe('enableZone', () => {
    const zoneId = 'zone-3';
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}`;
    const enabledZone: CompanyZone = {
      id: 'zone-3',
      companyRegionId: 'region-1',
      companyCountryId: 'cc-1',
      companyId: 'company-123',
      countryId: '1',
      zoneCode: 'US-CA-NB',
      zoneName: 'North Bay',
      description: 'Northern district',
      displayOrder: 4,
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-02',
    };

    it('should PATCH and update zone enabled state in signal', async () => {
      (service as any)._zones.set([
        ...mockZones,
        { ...mockZones[0], id: 'zone-3', zoneCode: 'US-CA-NB', enabled: false },
      ]);
      expect(service.zones().length).toBe(3);

      const enablePromise = firstValueFrom(service.enableZone(companyId, countryId, regionId, zoneId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'PATCH'
      );
      req.flush(enabledZone);

      const zone = await enablePromise;
      expect(zone).toEqual(enabledZone);
      expect(zone.enabled).toBe(true);
      expect(service.zones().find(z => z.id === zoneId)?.enabled).toBe(true);
    });

    it('should set error on enable failure', async () => {
      const enablePromise = firstValueFrom(service.enableZone(companyId, countryId, regionId, zoneId));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(enablePromise).rejects.toThrow();

      expect(service.error()).toBe('Error al reactivar la zona');
    });
  });

  describe('clearError', () => {
    it('should reset the error signal to null', () => {
      (service as any)._error.set('Some error');
      expect(service.error()).toBe('Some error');

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });

  describe('signal exposure', () => {
    it('should expose readonly signals for zones, loading, and error', () => {
      expect(service.zones).toBeDefined();
      expect(service.loading).toBeDefined();
      expect(service.error).toBeDefined();

      expect(service.zones()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
