import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { CurrencyService } from './currency.service';
import { Currency } from './currency.models';

describe('CurrencyService', () => {
  let service: CurrencyService;
  let httpMock: HttpTestingController;

  const mockCurrencies: Currency[] = [
    { code: 'MXN', minorUnits: 2, symbol: '$', enabled: true },
    { code: 'USD', minorUnits: 2, symbol: 'US$', enabled: true },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CurrencyService],
    });
    service = TestBed.inject(CurrencyService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('apiUrl', () => {
    it('should point at the plural platform catalog under /api', () => {
      // The catalog is global reference data served next to `/api/countries`; the singular
      // `/currency` path belongs to no endpoint at all.
      expect(service.apiUrl).toMatch(/\/api\/currencies$/);
    });
  });

  describe('getCurrencies', () => {
    it('should fetch the flat catalog from the API', async () => {
      const currenciesPromise = firstValueFrom(service.getCurrencies());

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      req.flush(mockCurrencies);

      const currencies = await currenciesPromise;
      expect(currencies).toEqual(mockCurrencies);
      expect(currencies.length).toBe(2);
      expect(currencies[0]).toEqual({
        code: 'MXN',
        minorUnits: 2,
        symbol: '$',
        enabled: true,
      });
    });

    it('should ask for enabled codes only, never for the retired ones', async () => {
      // D2: the form offers only what the endpoint returns by default. `?includeDisabled=true`
      // exists for an administrative future, and sending it here would offer a code the API
      // refuses on save.
      const currenciesPromise = firstValueFrom(service.getCurrencies());

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.has('includeDisabled')).toBe(false);
      req.flush(mockCurrencies);

      await currenciesPromise;
    });

    it('should set the currencies signal after fetch', async () => {
      const fetchPromise = firstValueFrom(service.getCurrencies());

      const req = httpMock.expectOne(service.apiUrl);
      req.flush(mockCurrencies);

      await fetchPromise;

      expect(service.currencies()).toEqual(mockCurrencies);
      expect(service.loaded()).toBe(true);
    });

    it('should cache the result and NOT make a second HTTP call', async () => {
      const firstPromise = firstValueFrom(service.getCurrencies());

      const req = httpMock.expectOne(service.apiUrl);
      req.flush(mockCurrencies);

      await firstPromise;

      const secondPromise = firstValueFrom(service.getCurrencies());

      httpMock.expectNone(service.apiUrl);

      const currencies = await secondPromise;
      expect(currencies).toEqual(mockCurrencies);
    });

    it('should fetch again when force is true', async () => {
      const firstPromise = firstValueFrom(service.getCurrencies());
      httpMock.expectOne(service.apiUrl).flush(mockCurrencies);
      await firstPromise;

      const forcedPromise = firstValueFrom(service.getCurrencies(true));

      const req = httpMock.expectOne(service.apiUrl);
      req.flush([mockCurrencies[0]]);

      const currencies = await forcedPromise;
      expect(currencies).toEqual([mockCurrencies[0]]);
      expect(service.currencies()).toEqual([mockCurrencies[0]]);
    });

    it('should set loading true during fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const currenciesPromise = firstValueFrom(service.getCurrencies());

      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne(service.apiUrl);
      req.flush(mockCurrencies);

      await currenciesPromise;

      expect(service.loading()).toBe(false);
    });

    it('should set error signal on HTTP failure', async () => {
      service.clearError();
      expect(service.loading()).toBe(false);

      const errorPromise = firstValueFrom(service.getCurrencies());

      const req = httpMock.expectOne(service.apiUrl);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(errorPromise).rejects.toThrow();

      expect(service.error()).toBe('Error al cargar las monedas');
      expect(service.loaded()).toBe(false);
      expect(service.loading()).toBe(false);
    });
  });

  describe('clearError', () => {
    it('should reset the error signal to null', () => {
      (service as unknown as { _error: { set: (value: string | null) => void } })._error.set(
        'Some error',
      );
      expect(service.error()).toBe('Some error');

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });

  describe('signal exposure', () => {
    it('should expose readonly signals for currencies, loading, loaded and error', () => {
      expect(service.currencies).toBeDefined();
      expect(service.loading).toBeDefined();
      expect(service.loaded).toBeDefined();
      expect(service.error).toBeDefined();

      expect(service.currencies()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.loaded()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
