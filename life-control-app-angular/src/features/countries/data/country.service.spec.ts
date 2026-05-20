import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { CountryService } from './country.service';
import { Country } from '../../companies/models/company.models';

describe('CountryService', () => {
  let service: CountryService;
  let httpMock: HttpTestingController;

  const mockCountries: Country[] = [
    {
      id: '1', countryCode: 'MX', countryName: 'Mexico',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: '2', countryCode: 'US', countryName: 'United States',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: '3', countryCode: 'CO', countryName: 'Colombia',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CountryService],
    });
    service = TestBed.inject(CountryService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getCountries', () => {
    it('should fetch enabled countries from the API', async () => {
      const countriesPromise = firstValueFrom(service.getCountries());

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.url).toContain('/countries');
      req.flush(mockCountries);

      const countries = await countriesPromise;
      expect(countries).toEqual(mockCountries);
      expect(countries.length).toBe(3);
      expect(countries[0].countryName).toBe('Mexico');
    });

    it('should set the countries signal after fetch', async () => {
      const fetchPromise = firstValueFrom(service.getCountries());

      const req = httpMock.expectOne(service.apiUrl);
      req.flush(mockCountries);

      await fetchPromise;

      expect(service.countries()).toEqual(mockCountries);
      expect(service.countries().length).toBe(3);
    });

    it('should cache the result and NOT make a second HTTP call', async () => {
      // First call
      const firstPromise = firstValueFrom(service.getCountries());

      const req = httpMock.expectOne(service.apiUrl);
      req.flush(mockCountries);

      await firstPromise;

      // Second call should return cached data immediately (no new HTTP)
      const secondPromise = firstValueFrom(service.getCountries());

      // Verify no additional HTTP call was made
      httpMock.expectNone(service.apiUrl);

      const countries = await secondPromise;
      expect(countries).toEqual(mockCountries);
    });

    it('should set loading true during fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const countriesPromise = firstValueFrom(service.getCountries());

      // After subscribe triggers, loading should be true
      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne(service.apiUrl);
      req.flush(mockCountries);

      await countriesPromise;

      // After completion
      expect(service.loading()).toBe(false);
    });

    it('should set error signal on HTTP failure', async () => {
      // Ensure we're starting fresh
      service.clearError();
      expect(service.loading()).toBe(false);

      const errorPromise = firstValueFrom(service.getCountries());

      const req = httpMock.expectOne(service.apiUrl);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(errorPromise).rejects.toThrow();

      expect(service.error()).toBe('Error al cargar los países');
      expect(service.loading()).toBe(false);
    });
  });

  describe('clearError', () => {
    it('should reset the error signal to null', () => {
      // Simulate setting an error by internal mechanism
      (service as any)._error.set('Some error');
      expect(service.error()).toBe('Some error');

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });

  describe('signal exposure', () => {
    it('should expose readonly signals for countries, loading, and error', () => {
      expect(service.countries).toBeDefined();
      expect(service.loading).toBeDefined();
      expect(service.error).toBeDefined();

      expect(service.countries()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
