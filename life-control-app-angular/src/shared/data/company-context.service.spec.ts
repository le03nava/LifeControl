import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CompanyContextService } from './company-context.service';
import { Company, Page } from '@features/companies/companies/models/company.models';
import { ConfigService } from '@app/services/config.service';

describe('CompanyContextService', () => {
  let service: CompanyContextService;
  let configService: ConfigService;
  let httpMock: HttpTestingController;

  const mockCompany: Company = {
    id: '1',
    companyKey: '1',
    companyName: 'Company A',
    tipoPersonaId: 1,
    razonSocial: 'Razon A',
    rfc: 'RFC123456789',
    email: 'test@a.com',
    phone: '5551234567',
    enabled: true,
    createdAt: '',
    updatedAt: '',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ConfigService, CompanyContextService],
    });
    service = TestBed.inject(CompanyContextService);
    configService = TestBed.inject(ConfigService);
    httpMock = TestBed.inject(HttpTestingController);

    delete window.env;
  });

  afterEach(() => {
    httpMock.verify();
    delete window.env;
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should start with empty companies, null current company and loading false', () => {
    expect(service.companies()).toEqual([]);
    expect(service.currentCompany()).toBeNull();
    expect(service.loading()).toBe(false);
  });

  describe('loadCompanies', () => {
    it('should make an HTTP GET to the correct URL and load companies', () => {
      const page: Page<Company> = {
        content: [mockCompany],
        totalElements: 1,
        totalPages: 1,
        size: 1000,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };
      const expectedUrl = `${configService.apiUrl}/companies`;

      service.loadCompanies();

      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne((r) => r.url === expectedUrl && r.method === 'GET');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('1000');
      req.flush(page);

      expect(service.companies()).toEqual([mockCompany]);
      expect(service.loading()).toBe(false);
    });

    it('should auto-select the first company when none is selected', () => {
      const page: Page<Company> = {
        content: [mockCompany],
        totalElements: 1,
        totalPages: 1,
        size: 1000,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };

      service.loadCompanies();
      httpMock.expectOne((r) => r.url === `${configService.apiUrl}/companies`).flush(page);

      expect(service.currentCompany()).toEqual(mockCompany);
    });

    it('should not override an existing current company selection', () => {
      const anotherCompany: Company = {
        ...mockCompany,
        id: '2',
        companyKey: '2',
        companyName: 'Company B',
      };
      const page: Page<Company> = {
        content: [mockCompany, anotherCompany],
        totalElements: 2,
        totalPages: 1,
        size: 1000,
        number: 0,
        first: true,
        last: true,
        empty: false,
      };
      service.setCurrentCompany(anotherCompany);

      service.loadCompanies();
      httpMock.expectOne((r) => r.url === `${configService.apiUrl}/companies`).flush(page);

      expect(service.currentCompany()).toEqual(anotherCompany);
    });

    it('should set loading false on error', () => {
      service.loadCompanies();

      const req = httpMock.expectOne((r) => r.url === `${configService.apiUrl}/companies`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      expect(service.loading()).toBe(false);
    });
  });

  describe('setCurrentCompany', () => {
    it('should update the currentCompany signal', () => {
      service.setCurrentCompany(mockCompany);

      expect(service.currentCompany()).toEqual(mockCompany);
    });
  });

  describe('clearSelection', () => {
    it('should reset currentCompany to null', () => {
      service.setCurrentCompany(mockCompany);
      expect(service.currentCompany()).toEqual(mockCompany);

      service.clearSelection();

      expect(service.currentCompany()).toBeNull();
    });
  });
});
