import { TestBed, fakeAsync, tick } from '@angular/core/testing';
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
    it('should fetch companies and update signal', fakeAsync(() => {
      const mockCompanies: Company[] = [
        { id: '1', companyId: 1, companyName: 'Company A', tipoPersonaId: 1, razonSocial: 'Razon A', rfc: 'RFC123456789', email: 'test@a.com', phone: '5551234567', enabled: true, createdAt: '', updatedAt: '' }
      ];

      expect(service.loading()).toBe(false);
      expect(service.companies().length).toBe(0);

      service.getCompanies();

      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne(`${service.apiUrl}`);
      expect(req.request.method).toBe('GET');
      req.flush(mockCompanies);
      tick();

      expect(service.loading()).toBe(false);
      expect(service.companies().length).toBe(1);
      expect(service.companies()[0].companyName).toBe('Company A');
    }));

    it('should set error on HTTP error', fakeAsync(() => {
      expect(service.error()).toBeNull();

      service.getCompanies();

      const req = httpMock.expectOne(`${service.apiUrl}`);
      req.flush('Error', { status: 500, statusText: 'Server Error' });
      tick();

      expect(service.error()).toBe('Error al cargar las empresas');
      expect(service.companies().length).toBe(0);
    }));
  });

  describe('createCompany', () => {
    it('should create a new company', (done) => {
      const newCompany: Company = {
        companyName: 'New Company',
        companyId: 99,
        tipoPersonaId: 1,
        razonSocial: 'New Razon',
        rfc: 'RFC1234567890',
        email: 'new@test.com',
        phone: '5559876543',
        enabled: true,
        createdAt: '',
        updatedAt: ''
      } as Company;

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
        companyId: 1,
        companyName: 'Updated Company',
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
    it('should set loading to true during getCompanies', fakeAsync(() => {
      expect(service.loading()).toBe(false);

      service.getCompanies();

      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne(`${service.apiUrl}`);
      req.flush([]);
      tick();

      expect(service.loading()).toBe(false);
    }));
  });

  describe('clearError', () => {
    it('should clear error signal', fakeAsync(() => {
      // Primero generamos un error
      service.getCompanies();
      const req = httpMock.expectOne(`${service.apiUrl}`);
      req.flush('Error', { status: 500, statusText: 'Server Error' });
      tick();

      expect(service.error()).toBe('Error al cargar las empresas');

      // Ahora lo limpiamos
      service.clearError();

      expect(service.error()).toBeNull();
    }));
  });
});
