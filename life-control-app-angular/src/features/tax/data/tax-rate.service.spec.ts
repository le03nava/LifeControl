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
