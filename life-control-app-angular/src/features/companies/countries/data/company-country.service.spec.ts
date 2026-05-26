import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { CompanyCountryService } from './company-country.service';
import { CompanyCountry, CompanyCountryRequest } from '../models/country.models';

describe('CompanyCountryService', () => {
  let service: CompanyCountryService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const baseUrl = 'http://localhost:9000/api';

  const mockCountries: CompanyCountry[] = [
    {
      id: 'cc-1', companyId: 'company-123', countryId: '1',
      countryCode: 'MX', countryName: 'Mexico', localAlias: 'Sucursal CDMX',
      createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'cc-2', companyId: 'company-123', countryId: '2',
      countryCode: 'US', countryName: 'United States', localAlias: null,
      createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CompanyCountryService],
    });
    service = TestBed.inject(CompanyCountryService);
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

  describe('getCountries', () => {
    it('should fetch assigned countries for a company', async () => {
      const countriesPromise = firstValueFrom(service.getCountries(companyId));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries` && r.method === 'GET'
      );
      req.flush(mockCountries);

      const countries = await countriesPromise;
      expect(countries).toEqual(mockCountries);
      expect(countries.length).toBe(2);
    });

    it('should populate the assignedCountries signal', async () => {
      const fetchPromise = firstValueFrom(service.getCountries(companyId));

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush(mockCountries);

      await fetchPromise;

      expect(service.assignedCountries()).toEqual(mockCountries);
      expect(service.assignedCountries().length).toBe(2);
    });

    it('should set loading true during fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const fetchPromise = firstValueFrom(service.getCountries(companyId));

      // Give time for the subscription to trigger
      await new Promise(resolve => setTimeout(resolve, 10));

      // After subscribe triggers, loading should be true
      expect(service.loading()).toBe(true);

      // Flush the HTTP response
      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush(mockCountries);

      await fetchPromise;

      // After completion, loading should be false
      expect(service.loading()).toBe(false);
    });

    it('should set error signal on HTTP failure', async () => {
      const errorPromise = firstValueFrom(service.getCountries(companyId));

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(errorPromise).rejects.toThrow();

      expect(service.error()).toBe('Error al cargar los países de la empresa');
    });
  });

  describe('addCountry', () => {
    const request: CompanyCountryRequest = {
      countryCode: 'CO',
      localAlias: 'Sucursal Bogotá',
    };

    const response: CompanyCountry = {
      id: 'cc-3', companyId: 'company-123', countryId: '3',
      countryCode: 'CO', countryName: 'Colombia', localAlias: 'Sucursal Bogotá',
      createdAt: '2024-01-01', updatedAt: '2024-01-01',
    };

    it('should POST and push the new country to the signal', async () => {
      // Pre-seed the signal with existing data
      (service as any)._assignedCountries.set([mockCountries[0]]);
      expect(service.assignedCountries().length).toBe(1);

      const addPromise = firstValueFrom(service.addCountry(companyId, request));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries` && r.method === 'POST'
      );
      expect(req.request.body).toEqual(request);
      req.flush(response);

      const cc = await addPromise;
      expect(cc).toEqual(response);
      expect(service.assignedCountries().length).toBe(2);
      expect(service.assignedCountries()[1].countryCode).toBe('CO');
      expect(service.assignedCountries()[1].localAlias).toBe('Sucursal Bogotá');
    });

    it('should add country without localAlias', async () => {
      const requestNoAlias: CompanyCountryRequest = { countryCode: 'BR' };
      const responseNoAlias: CompanyCountry = {
        id: 'cc-4', companyId: 'company-123', countryId: '4',
        countryCode: 'BR', countryName: 'Brazil', localAlias: null,
        createdAt: '2024-01-01', updatedAt: '2024-01-01',
      };

      const addPromise = firstValueFrom(service.addCountry(companyId, requestNoAlias));

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      expect(req.request.body).toEqual(requestNoAlias);
      req.flush(responseNoAlias);

      const cc = await addPromise;
      expect(cc.countryCode).toBe('BR');
    });

    it('should set specific error message on 409 Conflict', async () => {
      const addPromise = firstValueFrom(service.addCountry(companyId, request));

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush({ message: 'Conflict' }, { status: 409, statusText: 'Conflict' });

      await expect(addPromise).rejects.toThrow();

      expect(service.error()).toBe('Este país ya está asignado a esta empresa');
    });

    it('should NOT modify signal on error', async () => {
      (service as any)._assignedCountries.set([mockCountries[0]]);

      const addPromise = firstValueFrom(service.addCountry(companyId, request));

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(addPromise).rejects.toThrow();

      // Signal should still have only the original entry
      expect(service.assignedCountries().length).toBe(1);
    });

    it('should set generic error on non-409 failure', async () => {
      const addPromise = firstValueFrom(service.addCountry(companyId, request));

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(addPromise).rejects.toThrow();

      expect(service.error()).toBe('Error al agregar país');
    });
  });

  describe('removeCountry', () => {
    const companyCountryId = 'cc-1';

    it('should DELETE and filter out the removed country', async () => {
      (service as any)._assignedCountries.set(mockCountries);
      expect(service.assignedCountries().length).toBe(2);

      const removePromise = firstValueFrom(service.removeCountry(companyId, companyCountryId));

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${companyCountryId}`
          && r.method === 'DELETE'
      );
      req.flush(null);

      await removePromise;

      expect(service.assignedCountries().length).toBe(1);
      expect(service.assignedCountries()[0].id).toBe('cc-2');
    });

    it('should set error on remove failure', async () => {
      const removePromise = firstValueFrom(service.removeCountry(companyId, companyCountryId));

      const req = httpMock.expectOne(
        `${baseUrl}/companies/${companyId}/countries/${companyCountryId}`
      );
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(removePromise).rejects.toThrow();

      expect(service.error()).toBe('Error al eliminar país');
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
    it('should expose readonly signals for assignedCountries, loading, and error', () => {
      expect(service.assignedCountries).toBeDefined();
      expect(service.loading).toBeDefined();
      expect(service.error).toBeDefined();

      expect(service.assignedCountries()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
