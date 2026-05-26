import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { CompanyRegionService } from './company-region.service';
import { CompanyRegion, CompanyRegionRequest } from '../models/company.models';

describe('CompanyRegionService', () => {
  let service: CompanyRegionService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const countryId = 'country-1';
  const baseUrl = 'http://localhost:9000/api';

  const mockRegions: CompanyRegion[] = [
    {
      id: 'reg-1', companyCountryId: 'cc-1', companyId: 'company-123', countryId: '1',
      regionCode: 'US-CA', regionName: 'California',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'reg-2', companyCountryId: 'cc-1', companyId: 'company-123', countryId: '1',
      regionCode: 'US-TX', regionName: 'Texas',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CompanyRegionService],
    });
    service = TestBed.inject(CompanyRegionService);
    httpMock = TestBed.inject(HttpTestingController);
    // Mock ConfigService apiUrl
    (service as any).configService = { apiUrl: baseUrl };
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getRegions', () => {
    it('should fetch regions for a company country', async () => {
      const regionsPromise = firstValueFrom(service.getRegions(companyId, countryId));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${countryId}/regions`
          && r.method === 'GET'
      );
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockRegions);

      const regions = await regionsPromise;
      expect(regions).toEqual(mockRegions);
      expect(regions.length).toBe(2);
    });

    it('should populate the regions signal', async () => {
      const fetchPromise = firstValueFrom(service.getRegions(companyId, countryId));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${countryId}/regions`
          && r.method === 'GET'
      );
      req.flush(mockRegions);

      await fetchPromise;

      expect(service.regions()).toEqual(mockRegions);
      expect(service.regions().length).toBe(2);
    });

    it('should set loading true during fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const fetchPromise = firstValueFrom(service.getRegions(companyId, countryId));

      await new Promise(resolve => setTimeout(resolve, 10));

      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${countryId}/regions`
          && r.method === 'GET'
      );
      req.flush(mockRegions);

      await fetchPromise;

      expect(service.loading()).toBe(false);
    });

    it('should set error signal on HTTP failure', async () => {
      const errorPromise = firstValueFrom(service.getRegions(companyId, countryId));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${countryId}/regions`
          && r.method === 'GET'
      );
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(errorPromise).rejects.toThrow();

      expect(service.error()).toBe('Error al cargar las regiones');
    });
  });

  describe('addRegion', () => {
    const request: CompanyRegionRequest = {
      regionCode: 'US-NY',
      regionName: 'New York',
    };

    const response: CompanyRegion = {
      id: 'reg-3', companyCountryId: 'cc-1', companyId: 'company-123', countryId: '1',
      regionCode: 'US-NY', regionName: 'New York',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    };

    it('should POST and push the new region to the signal', async () => {
      (service as any)._regions.set([mockRegions[0]]);
      expect(service.regions().length).toBe(1);

      const addPromise = firstValueFrom(service.addRegion(companyId, countryId, request));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${countryId}/regions`
          && r.method === 'POST'
      );
      expect(req.request.body).toEqual(request);
      req.flush(response);

      const region = await addPromise;
      expect(region).toEqual(response);
      expect(service.regions().length).toBe(2);
      expect(service.regions()[1].regionCode).toBe('US-NY');
      expect(service.regions()[1].regionName).toBe('New York');
    });

    it('should set specific error message on 409 Conflict', async () => {
      const addPromise = firstValueFrom(service.addRegion(companyId, countryId, request));

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries/${countryId}/regions`);
      req.flush({ message: 'Conflict' }, { status: 409, statusText: 'Conflict' });

      await expect(addPromise).rejects.toThrow();

      expect(service.error()).toBe('Ya existe una región con ese código');
    });

    it('should NOT modify signal on error', async () => {
      (service as any)._regions.set([mockRegions[0]]);

      const addPromise = firstValueFrom(service.addRegion(companyId, countryId, request));

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries/${countryId}/regions`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(addPromise).rejects.toThrow();

      expect(service.regions().length).toBe(1);
    });

    it('should set generic error on non-409 failure', async () => {
      const addPromise = firstValueFrom(service.addRegion(companyId, countryId, request));

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries/${countryId}/regions`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(addPromise).rejects.toThrow();

      expect(service.error()).toBe('Error al crear la región');
    });
  });

  describe('updateRegion', () => {
    const regionId = 'reg-1';
    const request: CompanyRegionRequest = {
      regionCode: 'US-CA',
      regionName: 'California Updated',
    };

    const updatedRegion: CompanyRegion = {
      id: 'reg-1', companyCountryId: 'cc-1', companyId: 'company-123', countryId: '1',
      regionCode: 'US-CA', regionName: 'California Updated',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-02',
    };

    it('should PUT and replace the region in the signal', async () => {
      (service as any)._regions.set(mockRegions);
      expect(service.regions().length).toBe(2);

      const updatePromise = firstValueFrom(service.updateRegion(companyId, countryId, regionId, request));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}`
          && r.method === 'PUT'
      );
      expect(req.request.body).toEqual(request);
      req.flush(updatedRegion);

      const region = await updatePromise;
      expect(region).toEqual(updatedRegion);
      expect(service.regions().length).toBe(2);
      expect(service.regions()[0].regionName).toBe('California Updated');
    });

    it('should set error on update failure', async () => {
      const updatePromise = firstValueFrom(service.updateRegion(companyId, countryId, regionId, request));

      const req = httpMock.expectOne(
        `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}`
      );
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(updatePromise).rejects.toThrow();

      expect(service.error()).toBe('Error al actualizar la región');
    });
  });

  describe('removeRegion', () => {
    const regionId = 'reg-1';

    it('should DELETE and filter out the removed region', async () => {
      (service as any)._regions.set(mockRegions);
      expect(service.regions().length).toBe(2);

      const removePromise = firstValueFrom(service.removeRegion(companyId, countryId, regionId));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}`
          && r.method === 'DELETE'
      );
      req.flush(null);

      await removePromise;

      expect(service.regions().length).toBe(1);
      expect(service.regions()[0].id).toBe('reg-2');
    });

    it('should set error on remove failure', async () => {
      const removePromise = firstValueFrom(service.removeRegion(companyId, countryId, regionId));

      const req = httpMock.expectOne(
        `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}`
      );
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(removePromise).rejects.toThrow();

      expect(service.error()).toBe('Error al eliminar la región');
    });
  });

  describe('enableRegion', () => {
    const regionId = 'reg-3';
    const enabledRegion: CompanyRegion = {
      id: 'reg-3', companyCountryId: 'cc-1', companyId: 'company-123', countryId: '1',
      regionCode: 'US-DC', regionName: 'Distrito de Columbia',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-02',
    };

    it('should PATCH and update region enabled state in signal', async () => {
      (service as any)._regions.set([
        ...mockRegions,
        { ...mockRegions[0], id: 'reg-3', regionCode: 'US-DC', enabled: false },
      ]);
      expect(service.regions().length).toBe(3);

      const enablePromise = firstValueFrom(service.enableRegion(companyId, countryId, regionId));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}`
          && r.method === 'PATCH'
      );
      req.flush(enabledRegion);

      const region = await enablePromise;
      expect(region).toEqual(enabledRegion);
      expect(region.enabled).toBe(true);
      expect(service.regions().find(r => r.id === regionId)?.enabled).toBe(true);
    });

    it('should set error on enable failure', async () => {
      const enablePromise = firstValueFrom(service.enableRegion(companyId, countryId, regionId));

      const req = httpMock.expectOne(
        `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}`
      );
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(enablePromise).rejects.toThrow();

      expect(service.error()).toBe('Error al reactivar la región');
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
    it('should expose readonly signals for regions, loading, and error', () => {
      expect(service.regions).toBeDefined();
      expect(service.loading).toBeDefined();
      expect(service.error).toBeDefined();

      expect(service.regions()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
