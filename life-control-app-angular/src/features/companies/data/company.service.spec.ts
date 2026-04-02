import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CompanyService } from './company.service';
import { Company } from '../models/company.models';

describe('CompanyService', () => {
  let service: CompanyService;
  let httpMock: HttpTestingController;

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
    it('should fetch companies and update signal', (done) => {
      const mockCompanies: Company[] = [
        { id: '1', companyKey: 'C001', companyName: 'Company A', tipoPersonaId: 1, razonSocial: 'Razon A', rfc: 'RFC123456789', email: 'test@a.com', phone: '5551234567', enabled: true, createdAt: '', updatedAt: '' }
      ];

      service.getCompanies();

      const req = httpMock.expectOne(`${service.apiUrl}`);
      expect(req.request.method).toBe('GET');
      req.flush(mockCompanies);

      service.companies().subscribe(companies => {
        expect(companies.length).toBe(1);
        expect(companies[0].companyName).toBe('Company A');
        done();
      });
    });

    it('should set error on HTTP error', (done) => {
      service.getCompanies();

      const req = httpMock.expectOne(`${service.apiUrl}`);
      req.flush('Error', { status: 500, statusText: 'Server Error' });

      service.error().subscribe(error => {
        expect(error).toBe('Error al cargar las empresas');
        done();
      });
    });
  });

  describe('createCompany', () => {
    it('should create a new company', (done) => {
      const newCompany: Company = {
        companyName: 'New Company',
        companyKey: 'NC001',
        tipoPersonaId: 1,
        razonSocial: 'New Razon',
        rfc: 'RFC1234567890',
        email: 'new@test.com',
        phone: '5559876543',
        enabled: true,
        createdAt: '',
        updatedAt: ''
      };

      const mockResponse: Company = { ...newCompany, id: 'new-id' };

      service.createCompany(newCompany).subscribe(response => {
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
      const company: Company = {
        id: '1',
        companyName: 'Updated Company',
        companyKey: 'C001',
        tipoPersonaId: 1,
        razonSocial: 'Updated Razon',
        rfc: 'RFC123456789',
        email: 'updated@test.com',
        phone: '5551234567',
        enabled: true,
        createdAt: '',
        updatedAt: ''
      };

      service.updateCompany(company).subscribe(response => {
        expect(response.companyName).toBe('Updated Company');
        done();
      });

      const req = httpMock.expectOne(`${service.apiUrl}`);
      expect(req.request.method).toBe('PUT');
      req.flush(company);
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

  describe('loading state', () => {
    it('should set loading to true during getCompanies', (done) => {
      service.loading().subscribe(loading => {
        if (loading) {
          done();
        }
      });
      service.getCompanies();
      const req = httpMock.expectOne(`${service.apiUrl}`);
      req.flush([]);
    });
  });

  describe('clearError', () => {
    it('should clear error signal', (done) => {
      service.getCompanies();
      
      const req = httpMock.expectOne(`${service.apiUrl}`);
      req.flush('Error', { status: 500, statusText: 'Server Error' });

      service.clearError();
      
      service.error().subscribe(error => {
        expect(error).toBeNull();
        done();
      });
    });
  });
});
