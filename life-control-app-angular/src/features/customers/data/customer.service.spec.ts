import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { CustomerService } from './customer.service';
import { Customer, Page } from '../models/customer.models';

/**
 * The list reads one endpoint and nothing else (`S5`), so this spec pins the two halves of that read:
 * the address and the two query parameters the API's own signature declares
 * (`CustomerController.java:49-51`: `@PageableDefault(size = 12) Pageable` + `@RequestParam(required =
 * false) String search`), and the sixteen row fields the service must hand over untruncated
 * (`CustomerResponse.java:19-34`).
 */
describe('CustomerService', () => {
  let service: CustomerService;
  let httpMock: HttpTestingController;

  /**
   * A row with **every** field the endpoint answers stated, fiscal ones included: a fixture that omits
   * them cannot tell "the service dropped the column" from "the row never had it", which is the whole
   * failure mode the model exists to prevent.
   */
  const mockCustomer: Customer = {
    id: 'c-1',
    name: 'Cliente Uno',
    email: 'uno@test.com',
    phone: '5551234567',
    rfc: 'RFC010101AB1',
    salesChannel: 'TODOS',
    enabled: true,
    legalName: 'Cliente Uno SA de CV',
    regimenFiscal: '601',
    fiscalStreet: 'Av. Reforma 100',
    fiscalZipCode: '06600',
    fiscalState: 'Ciudad de México',
    fiscalCountryId: 'country-1',
    defaultUsoCfdi: 'G03',
    createdAt: '2026-11-17T10:00:00',
    updatedAt: '2026-11-17T10:00:00',
  };

  const emptyPage = (): Page<Customer> => ({
    content: [],
    totalElements: 0,
    totalPages: 0,
    size: 12,
    number: 0,
    first: true,
    last: true,
    empty: true,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CustomerService],
    });
    service = TestBed.inject(CustomerService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getCustomers', () => {
    it('should read the customer collection with the API default page and size', async () => {
      const mockPage: Page<Customer> = {
        content: [mockCustomer],
        totalElements: 1,
        totalPages: 1,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };

      const pagePromise = firstValueFrom(service.getCustomers());

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      req.flush(mockPage);

      const page = await pagePromise;
      expect(page).toEqual(mockPage);
    });

    it('should hand over every field the endpoint answers, fiscal profile included', async () => {
      const pagePromise = firstValueFrom(service.getCustomers());

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      req.flush({ ...emptyPage(), content: [mockCustomer], totalElements: 1, totalPages: 1 });

      const row = (await pagePromise).content[0];
      // Sixteen fields, compared as a whole so a column the model forgets fails here instead of
      // disappearing from a screen nobody re-reads.
      expect(row).toEqual(mockCustomer);
      expect(Object.keys(row)).toHaveLength(16);
    });

    it('should omit the search parameter when no term is given', async () => {
      const pagePromise = firstValueFrom(service.getCustomers(0, 12));

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.has('search')).toBe(false);
      req.flush(emptyPage());

      await pagePromise;
    });

    it('should send the search term the API matches on name or email', async () => {
      const pagePromise = firstValueFrom(service.getCustomers(0, 12, 'uno@test.com'));

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('search')).toBe('uno@test.com');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      req.flush(emptyPage());

      await pagePromise;
    });

    it('should carry the requested page and size through unchanged', async () => {
      const pagePromise = firstValueFrom(service.getCustomers(2, 24));

      const req = httpMock.expectOne((r) => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('24');
      req.flush({ ...emptyPage(), number: 2, size: 24, first: false });

      const page = await pagePromise;
      expect(page.number).toBe(2);
      expect(page.size).toBe(24);
    });
  });
});
