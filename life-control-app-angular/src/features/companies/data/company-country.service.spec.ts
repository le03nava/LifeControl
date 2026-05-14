import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CompanyCountryService } from './company-country.service';
import { CompanyCountry, CompanyCountryRequest } from '../models/company.models';

describe('CompanyCountryService', () => {
  let service: CompanyCountryService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const baseUrl = 'http://localhost:9000/api';

  const mockCountries: CompanyCountry[] = [
    {
      id: 'cc-1', companyId: 'company-123', countryId: '1',
      countryCode: 'MX', countryName: 'Mexico', localAlias: 'Sucursal CDMX',
      createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'cc-2', companyId: 'company-123', countryId: '2',
      countryCode: 'US', countryName: 'United States', localAlias: null,
      createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CompanyCountryService],
    });
    service = TestBed.inject(CompanyCountryService);
    httpMock = TestBed.inject(HttpTestingController);
    // Mock ConfigService apiUrl
    (service as any).configService = { apiUrl: baseUrl };
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getCountries', () => {
    it('should fetch assigned countries for a company', (done) => {
      service.getCountries(companyId).subscribe(countries => {
        expect(countries).toEqual(mockCountries);
        expect(countries.length).toBe(2);
        done();
      });

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries` && r.method === 'GET'
      );
      req.flush(mockCountries);
    });

    it('should populate the assignedCountries signal', (done) => {
      service.getCountries(companyId).subscribe(() => {
        expect(service.assignedCountries()).toEqual(mockCountries);
        expect(service.assignedCountries().length).toBe(2);
        done();
      });

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush(mockCountries);
    });

    it('should set loading true during fetch and false after', (done) => {
      expect(service.loading()).toBe(false);

      service.getCountries(companyId).subscribe(() => {
        expect(service.loading()).toBe(false);
        done();
      });

      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush(mockCountries);
    });

    it('should set error signal on HTTP failure', (done) => {
      service.getCountries(companyId).subscribe({
        error: () => {
          expect(service.error()).toBe('Error al cargar los países de la empresa');
          done();
        },
      });

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });
    });
  });

  describe('addCountry', () => {
    const request: CompanyCountryRequest = {
      countryCode: 'CO',
      localAlias: 'Sucursal Bogotá',
    };

    const response: CompanyCountry = {
      id: 'cc-3', companyId: 'company-123', countryId: '3',
      countryCode: 'CO', countryName: 'Colombia', localAlias: 'Sucursal Bogotá',
      createdAt: '2024-01-01', updatedAt: '2024-01-01',
    };

    it('should POST and push the new country to the signal', (done) => {
      // Pre-seed the signal with existing data
      (service as any)._assignedCountries.set([mockCountries[0]]);
      expect(service.assignedCountries().length).toBe(1);

      service.addCountry(companyId, request).subscribe(cc => {
        expect(cc).toEqual(response);
        expect(service.assignedCountries().length).toBe(2);
        expect(service.assignedCountries()[1].countryCode).toBe('CO');
        expect(service.assignedCountries()[1].localAlias).toBe('Sucursal Bogotá');
        done();
      });

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries` && r.method === 'POST'
      );
      expect(req.request.body).toEqual(request);
      req.flush(response);
    });

    it('should add country without localAlias', (done) => {
      const requestNoAlias: CompanyCountryRequest = { countryCode: 'BR' };
      const responseNoAlias: CompanyCountry = {
        id: 'cc-4', companyId: 'company-123', countryId: '4',
        countryCode: 'BR', countryName: 'Brazil', localAlias: null,
        createdAt: '2024-01-01', updatedAt: '2024-01-01',
      };

      service.addCountry(companyId, requestNoAlias).subscribe(cc => {
        expect(cc.countryCode).toBe('BR');
        done();
      });

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      expect(req.request.body).toEqual(requestNoAlias);
      req.flush(responseNoAlias);
    });

    it('should set specific error message on 409 Conflict', (done) => {
      service.addCountry(companyId, request).subscribe({
        error: () => {
          expect(service.error()).toBe('Este país ya está asignado a esta empresa');
          done();
        },
      });

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush({ message: 'Conflict' }, { status: 409, statusText: 'Conflict' });
    });

    it('should NOT modify signal on error', (done) => {
      (service as any)._assignedCountries.set([mockCountries[0]]);

      service.addCountry(companyId, request).subscribe({
        error: () => {
          // Signal should still have only the original entry
          expect(service.assignedCountries().length).toBe(1);
          done();
        },
      });

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });
    });

    it('should set generic error on non-409 failure', (done) => {
      service.addCountry(companyId, request).subscribe({
        error: () => {
          expect(service.error()).toBe('Error al agregar país');
          done();
        },
      });

      const req = httpMock.expectOne(`${baseUrl}/companies/${companyId}/countries`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });
    });
  });

  describe('removeCountry', () => {
    const companyCountryId = 'cc-1';

    it('should DELETE and filter out the removed country', (done) => {
      (service as any)._assignedCountries.set(mockCountries);
      expect(service.assignedCountries().length).toBe(2);

      service.removeCountry(companyId, companyCountryId).subscribe(() => {
        expect(service.assignedCountries().length).toBe(1);
        expect(service.assignedCountries()[0].id).toBe('cc-2');
        done();
      });

      const req = httpMock.expectOne(
        r => r.url === `${baseUrl}/companies/${companyId}/countries/${companyCountryId}`
          && r.method === 'DELETE'
      );
      req.flush(null);
    });

    it('should set error on remove failure', (done) => {
      service.removeCountry(companyId, companyCountryId).subscribe({
        error: () => {
          expect(service.error()).toBe('Error al eliminar país');
          done();
        },
      });

      const req = httpMock.expectOne(
        `${baseUrl}/companies/${companyId}/countries/${companyCountryId}`
      );
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });
    });
  });

  describe('clearError', () => {
    it('should reset the error signal to null', () => {
      (service as any)._error.set('Some error');
      expect(service.error()).toBe('Some error');

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });

  describe('signal exposure', () => {
    it('should expose readonly signals for assignedCountries, loading, and error', () => {
      expect(service.assignedCountries).toBeDefined();
      expect(service.loading).toBeDefined();
      expect(service.error).toBeDefined();

      expect(service.assignedCountries()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
