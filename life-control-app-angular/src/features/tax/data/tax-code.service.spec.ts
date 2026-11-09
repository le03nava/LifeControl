import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { TaxCodeService } from './tax-code.service';
import { TaxCode } from './tax-code.models';

describe('TaxCodeService', () => {
  let service: TaxCodeService;
  let httpMock: HttpTestingController;

  /** The four codes `V82` seeds; the read serves the enabled ones. */
  const mockTaxCodes: TaxCode[] = [
    { code: 'NA', label: 'No aplica', enabled: true },
    { code: 'IVA', label: 'IVA 16%', enabled: true },
    { code: 'TASA_0', label: 'Tasa 0%', enabled: true },
    { code: 'EXENTO', label: 'Exento', enabled: true },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [TaxCodeService],
    });
    service = TestBed.inject(TaxCodeService);
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
      // The catalog is global reference data served next to `/api/currencies` and `/api/countries`;
      // the singular `/tax-code` path belongs to no endpoint at all.
      expect(service.apiUrl).toMatch(/\/api\/tax-codes$/);
    });
  });

  describe('getTaxCodes', () => {
    it('should fetch the flat catalog from the API', async () => {
      const taxCodesPromise = firstValueFrom(service.getTaxCodes());

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      req.flush(mockTaxCodes);

      const taxCodes = await taxCodesPromise;
      expect(taxCodes).toEqual(mockTaxCodes);
      expect(taxCodes.length).toBe(4);
      expect(taxCodes[0]).toEqual({ code: 'NA', label: 'No aplica', enabled: true });
    });

    it('should ask for enabled codes only, never for the retired ones', async () => {
      // S4: the form offers only what the endpoint returns by default. `?includeDisabled=true` exists
      // for an administrative future, and sending it here would offer a code `fk_products_tax_code`
      // still accepts but the catalog has retired.
      const taxCodesPromise = firstValueFrom(service.getTaxCodes());

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.has('includeDisabled')).toBe(false);
      req.flush(mockTaxCodes);

      await taxCodesPromise;
    });

    it('should set the taxCodes signal after fetch', async () => {
      const fetchPromise = firstValueFrom(service.getTaxCodes());

      const req = httpMock.expectOne(service.apiUrl);
      req.flush(mockTaxCodes);

      await fetchPromise;

      expect(service.taxCodes()).toEqual(mockTaxCodes);
      expect(service.loaded()).toBe(true);
    });

    it('should cache the result and NOT make a second HTTP call', async () => {
      const firstPromise = firstValueFrom(service.getTaxCodes());

      const req = httpMock.expectOne(service.apiUrl);
      req.flush(mockTaxCodes);

      await firstPromise;

      const secondPromise = firstValueFrom(service.getTaxCodes());

      httpMock.expectNone(service.apiUrl);

      const taxCodes = await secondPromise;
      expect(taxCodes).toEqual(mockTaxCodes);
    });

    it('should fetch again when force is true', async () => {
      const firstPromise = firstValueFrom(service.getTaxCodes());
      httpMock.expectOne(service.apiUrl).flush(mockTaxCodes);
      await firstPromise;

      const forcedPromise = firstValueFrom(service.getTaxCodes(true));

      const req = httpMock.expectOne(service.apiUrl);
      req.flush([mockTaxCodes[0]]);

      const taxCodes = await forcedPromise;
      expect(taxCodes).toEqual([mockTaxCodes[0]]);
      expect(service.taxCodes()).toEqual([mockTaxCodes[0]]);
    });

    it('should set loading true during fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const taxCodesPromise = firstValueFrom(service.getTaxCodes());

      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne(service.apiUrl);
      req.flush(mockTaxCodes);

      await taxCodesPromise;

      expect(service.loading()).toBe(false);
    });

    it('should set error signal on HTTP failure', async () => {
      service.clearError();
      expect(service.loading()).toBe(false);

      const errorPromise = firstValueFrom(service.getTaxCodes());

      const req = httpMock.expectOne(service.apiUrl);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(errorPromise).rejects.toThrow();

      expect(service.error()).toBe('Error al cargar los códigos de impuesto');
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
    it('should expose readonly signals for taxCodes, loading, loaded and error', () => {
      expect(service.taxCodes).toBeDefined();
      expect(service.loading).toBeDefined();
      expect(service.loaded).toBeDefined();
      expect(service.error).toBeDefined();

      expect(service.taxCodes()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.loaded()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
