import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { TaxRateService } from './tax-rate.service';
import { TaxRate } from './tax-rate.models';

const COMPANY_ID = '4d1b0e8e-0000-4000-8000-000000000001';

function taxRate(overrides: Partial<TaxRate> = {}): TaxRate {
  return {
    id: 'rate-1',
    companyId: COMPANY_ID,
    taxCode: 'IVA',
    rate: 0.16,
    effectiveFrom: '2026-11-10',
    enabled: true,
    regionId: null,
    regionName: null,
    ...overrides,
  };
}

describe('TaxRateService', () => {
  let service: TaxRateService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [TaxRateService],
    });
    service = TestBed.inject(TaxRateService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('addresses the rates as a company sub-resource', async () => {
    const read = firstValueFrom(service.getTaxRates(COMPANY_ID));

    const request = httpMock.expectOne(
      (candidate) =>
        candidate.url === `${service.apiUrl}/${COMPANY_ID}/tax-rates` && candidate.method === 'GET',
    );
    request.flush([taxRate()]);

    await expect(read).resolves.toEqual([taxRate()]);
  });

  it('states includeDisabled as false unless the caller asks for retired rows', async () => {
    const first = firstValueFrom(service.getTaxRates(COMPANY_ID));
    const withoutFlag = httpMock.expectOne(
      (candidate) => candidate.url === `${service.apiUrl}/${COMPANY_ID}/tax-rates`,
    );
    expect(withoutFlag.request.params.get('includeDisabled')).toBe('false');
    withoutFlag.flush([]);
    await first;

    const second = firstValueFrom(service.getTaxRates(COMPANY_ID, true));
    const withFlag = httpMock.expectOne(
      (candidate) => candidate.url === `${service.apiUrl}/${COMPANY_ID}/tax-rates`,
    );
    expect(withFlag.request.params.get('includeDisabled')).toBe('true');
    withFlag.flush([]);
    await second;
  });

  it('keeps a readable message beside the rethrown error, so the page never invents one', async () => {
    const read = firstValueFrom(service.getTaxRates(COMPANY_ID));

    httpMock
      .expectOne((candidate) => candidate.url === `${service.apiUrl}/${COMPANY_ID}/tax-rates`)
      .flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });

    await expect(read).rejects.toBeTruthy();
    expect(service.error()).toBe('Error al cargar las tasas de impuesto');
  });

  it('supersedes through a PUT on the named row, sending only what the server cannot inherit', async () => {
    const written = firstValueFrom(
      service.supersedeTaxRate(COMPANY_ID, 'rate-1', {
        rate: 0.2,
        effectiveFrom: '2027-01-01',
        enabled: true,
      }),
    );

    const request = httpMock.expectOne(
      (candidate) =>
        candidate.url === `${service.apiUrl}/${COMPANY_ID}/tax-rates/rate-1` &&
        candidate.method === 'PUT',
    );
    // `toEqual` on the whole body, not on its fields: the code and the scope are the row's, so a body that
    // carried them would be a form offering a second source of truth for the half of the key the server owns.
    expect(request.request.body).toEqual({
      rate: 0.2,
      effectiveFrom: '2027-01-01',
      enabled: true,
    });

    const successor = taxRate({ id: 'rate-2', rate: 0.2, effectiveFrom: '2027-01-01' });
    request.flush(successor);

    await expect(written).resolves.toEqual(successor);
  });

  it('retires through the enable endpoint, sending the flag the row does not have', async () => {
    const written = firstValueFrom(service.setTaxRateEnabled(COMPANY_ID, 'rate-1', false));

    const request = httpMock.expectOne(
      (candidate) =>
        candidate.url === `${service.apiUrl}/${COMPANY_ID}/tax-rates/rate-1/enable` &&
        candidate.method === 'PATCH',
    );
    // The server refuses a stated `null`, so the flag is always a boolean and never omitted.
    expect(request.request.body).toEqual({ enabled: false });

    const retired = taxRate({ enabled: false });
    request.flush(retired);

    await expect(written).resolves.toEqual(retired);
  });

  it('reinstates by stating the opposite flag on the same endpoint', async () => {
    const written = firstValueFrom(service.setTaxRateEnabled(COMPANY_ID, 'rate-1', true));

    const request = httpMock.expectOne(
      (candidate) => candidate.url === `${service.apiUrl}/${COMPANY_ID}/tax-rates/rate-1/enable`,
    );
    expect(request.request.body).toEqual({ enabled: true });

    request.flush(taxRate({ enabled: true }));

    await expect(written).resolves.toEqual(taxRate({ enabled: true }));
  });

  it('keeps a readable message beside a refused supersede', async () => {
    const written = firstValueFrom(
      service.supersedeTaxRate(COMPANY_ID, 'rate-1', {
        rate: 0.2,
        effectiveFrom: '2026-11-10',
        enabled: true,
      }),
    );

    httpMock
      .expectOne((candidate) => candidate.method === 'PUT')
      .flush({ message: 'gap' }, { status: 400, statusText: 'Bad Request' });

    await expect(written).rejects.toBeTruthy();
    expect(service.error()).toBe('No se pudo suceder la tasa de impuesto');
  });

  it('keeps a readable message beside a refused retire, in the words of the direction that failed', async () => {
    const written = firstValueFrom(service.setTaxRateEnabled(COMPANY_ID, 'rate-1', false));

    httpMock
      .expectOne((candidate) => candidate.method === 'PATCH')
      .flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });

    await expect(written).rejects.toBeTruthy();
    expect(service.error()).toBe('No se pudo retirar la tasa de impuesto');
  });

  it('reads fresh on every call: a rate is configuration an operator edits and must see back', async () => {
    const first = firstValueFrom(service.getTaxRates(COMPANY_ID));
    httpMock
      .expectOne((candidate) => candidate.url === `${service.apiUrl}/${COMPANY_ID}/tax-rates`)
      .flush([taxRate()]);
    await first;

    // A second GET is the assertion: `expectOne` throws if the service answered from a cache, and the
    // changed body proves the caller sees the operator's own write and not the earlier answer.
    const second = firstValueFrom(service.getTaxRates(COMPANY_ID));
    httpMock
      .expectOne((candidate) => candidate.url === `${service.apiUrl}/${COMPANY_ID}/tax-rates`)
      .flush([taxRate({ rate: 0.2 })]);

    await expect(second).resolves.toEqual([taxRate({ rate: 0.2 })]);
  });
});
