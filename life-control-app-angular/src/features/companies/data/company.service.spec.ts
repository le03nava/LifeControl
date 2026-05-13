import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CompanyService } from './company.service';
import { Company, Page } from '../models/company.models';

describe('CompanyService', () => {
  let service: CompanyService;
  let httpMock: HttpTestingController;

  const mockCompany: Company = {
    id: '1', companyId: 1, companyName: 'Company A', tipoPersonaId: 1,
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
    it('should fetch paginated companies with default params', (done) => {
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

      service.getCompanies(0, 12).subscribe(page => {
        expect(page).toEqual(mockPage);
        expect(page.content.length).toBe(1);
        expect(page.content[0].companyName).toBe('Company A');
        done();
      });

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      expect(req.request.params.has('search')).toBeFalse();
      req.flush(mockPage);
    });

    it('should include search param when provided', (done) => {
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

      service.getCompanies(0, 12, 'Test').subscribe(page => {
        expect(page.empty).toBeTrue();
        done();
      });

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('search')).toBe('Test');
      req.flush(mockPage);
    });

    it('should handle pagination params correctly', (done) => {
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

      service.getCompanies(2, 24).subscribe(page => {
        expect(page.number).toBe(2);
        expect(page.size).toBe(24);
        done();
      });

      const req = httpMock.expectOne(r => r.url === service.apiUrl && r.method === 'GET');
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('24');
      req.flush(mockPage);
    });
  });

  describe('getCompanyById', () => {
    it('should fetch a single company by ID', (done) => {
      service.getCompanyById('1').subscribe(company => {
        expect(company).toEqual(mockCompany);
        done();
      });

      const req = httpMock.expectOne(`${service.apiUrl}/1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockCompany);
    });
  });

  describe('createCompany', () => {
    it('should create a new company', (done) => {
      const mockResponse: Company = { ...mockCompany, id: 'new-id' };

      service.createCompany(mockCompany).subscribe(response => {
        expect(response.id).toBe('new-id');
        done();
      });

      const req = httpMock.expectOne(`${service.apiUrl}`);
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });
  });

  describe('updateCompany', () => {
    it('should update an existing company', (done) => {
      service.updateCompany(mockCompany).subscribe(response => {
        expect(response.companyName).toBe('Company A');
        done();
      });

      const req = httpMock.expectOne(`${service.apiUrl}`);
      expect(req.request.method).toBe('PUT');
      req.flush(mockCompany);
    });
  });

  describe('deleteCompany', () => {
    it('should delete a company', (done) => {
      service.deleteCompany('1').subscribe(() => {
        done();
      });

      const req = httpMock.expectOne(`${service.apiUrl}/1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });
  });

  describe('clearError', () => {
    it('should clear error signal', () => {
      service.clearError();
      expect(service.error()).toBeNull();
    });
  });
});
