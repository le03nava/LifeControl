import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { CompanyService } from './company.service';
import { Company, Page } from '../models/company.models';

describe('CompanyService', () => {
  let service: CompanyService;
  let httpMock: HttpTestingController;

  const mockCompany: Company = {
    id: '1', companyKey: '1', companyName: 'Company A', tipoPersonaId: 1,
    razonSocial: 'Razon A', rfc: 'RFC123456789', email: 'test@a.com',
    phone: '5551234567', enabled: true, createdAt: '', updatedAt: '',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CompanyService]
    });
    service = TestBed.inject(CompanyService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getCompanies', () => {
    it('should fetch paginated companies with default params', async () => {
      const mockPage: Page<Company> = {
        content: [mockCompany],
        totalElements: 1,
        totalPages: 1,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };

      const pagePromise = firstValueFrom(service.getCompanies(0, 12));

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      expect(req.request.params.has('search')).toBe(false);
      req.flush(mockPage);

      const page = await pagePromise;
      expect(page).toEqual(mockPage);
      expect(page.content.length).toBe(1);
      expect(page.content[0].companyName).toBe('Company A');
    });

    it('should include search param when provided', async () => {
      const mockPage: Page<Company> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 12,
        number: 0,
        first: true,
        last: true,
        empty: true,
      };

      const pagePromise = firstValueFrom(service.getCompanies(0, 12, 'Test'));

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('search')).toBe('Test');
      req.flush(mockPage);

      const page = await pagePromise;
      expect(page.empty).toBe(true);
    });

    it('should handle pagination params correctly', async () => {
      const mockPage: Page<Company> = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 24,
        number: 2,
        first: false,
        last: true,
        empty: true,
      };

      const pagePromise = firstValueFrom(service.getCompanies(2, 24));

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('24');
      req.flush(mockPage);

      const page = await pagePromise;
      expect(page.number).toBe(2);
      expect(page.size).toBe(24);
    });
  });

  describe('getCompanyById', () => {
    it('should fetch a single company by ID', async () => {
      const companyPromise = firstValueFrom(service.getCompanyById('1'));

      const req = httpMock.expectOne(`${service.apiUrl}/1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockCompany);

      const company = await companyPromise;
      expect(company).toEqual(mockCompany);
    });
  });

  describe('createCompany', () => {
    it('should create a new company', async () => {
      const mockResponse: Company = { ...mockCompany, id: 'new-id' };

      const responsePromise = firstValueFrom(service.createCompany(mockCompany));

      const req = httpMock.expectOne(`${service.apiUrl}`);
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);

      const response = await responsePromise;
      expect(response.id).toBe('new-id');
    });
  });

  describe('updateCompany', () => {
    it('should update an existing company', async () => {
      const responsePromise = firstValueFrom(service.updateCompany('1', mockCompany));

      const req = httpMock.expectOne(`${service.apiUrl}/1`);
      expect(req.request.method).toBe('PUT');
      req.flush(mockCompany);

      const response = await responsePromise;
      expect(response.companyName).toBe('Company A');
    });
  });

  describe('deleteCompany', () => {
    it('should delete a company', async () => {
      const deletePromise = firstValueFrom(service.deleteCompany('1'));

      const req = httpMock.expectOne(`${service.apiUrl}/1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await deletePromise;
    });
  });

  describe('clearError', () => {
    it('should clear error signal', () => {
      service.clearError();
      expect(service.error()).toBeNull();
    });
  });
});
