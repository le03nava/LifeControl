import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { EmployeeStatusService } from './employee-status.service';

describe('EmployeeStatusService', () => {
  let service: EmployeeStatusService;
  let httpMock: HttpTestingController;

  const apiUrl = 'http://api.test/api';
  const statusTypesUrl = `${apiUrl}/status-types`;
  const statusesUrl = `${apiUrl}/statuses`;

  const employeeType = { id: 'type-employee', statusTypeName: 'EMPLOYEE_STATUS' };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [EmployeeStatusService, { provide: ConfigService, useValue: { apiUrl } }],
    });
    service = TestBed.inject(EmployeeStatusService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('loadEmployeeStatusIds', () => {
    it('should request the EMPLOYEE_STATUS type with search=EMPLOYEE_STATUS and the max page size', () => {
      service.loadEmployeeStatusIds().subscribe({ error: () => undefined });

      const req = httpMock.expectOne((r) => r.url === statusTypesUrl);
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('search')).toBe('EMPLOYEE_STATUS');
      // `search=` is a substring filter, so `size=1` could return an unrelated type
      // whose name merely contains EMPLOYEE_STATUS. Ask for the app-wide cap.
      expect(req.request.params.get('size')).toBe('100');

      req.flush({ content: [employeeType] });
      httpMock.expectOne((r) => r.url === statusesUrl).flush([]);
    });

    it('should carry the matched type id as statusTypeId on the second request', () => {
      service.loadEmployeeStatusIds().subscribe({ error: () => undefined });

      httpMock.expectOne((r) => r.url === statusTypesUrl).flush({ content: [employeeType] });

      const statusesReq = httpMock.expectOne((r) => r.url === statusesUrl);
      expect(statusesReq.request.method).toBe('GET');
      expect(statusesReq.request.params.get('statusTypeId')).toBe('type-employee');
      statusesReq.flush([]);
    });

    it('should match the type name case-insensitively', async () => {
      const promise = firstValueFrom(service.loadEmployeeStatusIds());

      httpMock
        .expectOne((r) => r.url === statusTypesUrl)
        .flush({ content: [{ id: 'type-lowercase', statusTypeName: 'employee_status' }] });

      const statusesReq = httpMock.expectOne((r) => r.url === statusesUrl);
      expect(statusesReq.request.params.get('statusTypeId')).toBe('type-lowercase');
      statusesReq.flush([]);

      await expect(promise).resolves.toEqual(new Map());
    });

    it('should map statusName to id for every returned status, enabled or not', async () => {
      const promise = firstValueFrom(service.loadEmployeeStatusIds());

      httpMock.expectOne((r) => r.url === statusTypesUrl).flush({ content: [employeeType] });

      httpMock
        .expectOne((r) => r.url === statusesUrl)
        .flush([
          { id: 'status-active', statusName: 'Active', enabled: true },
          { id: 'status-inactive', statusName: 'Inactive', enabled: true },
          { id: 'status-onleave', statusName: 'OnLeave', enabled: true },
          { id: 'status-terminated', statusName: 'Terminated', enabled: false },
        ]);

      const map = await promise;
      expect(map.size).toBe(4);
      expect(map.get('Active')).toBe('status-active');
      expect(map.get('Inactive')).toBe('status-inactive');
      expect(map.get('OnLeave')).toBe('status-onleave');
      // `GET /statuses` answers disabled rows too, so the client must not filter on `enabled`.
      expect(map.get('Terminated')).toBe('status-terminated');
    });

    it('should fail and never resolve an empty map when no returned type matches exactly', async () => {
      const promise = firstValueFrom(service.loadEmployeeStatusIds());

      httpMock
        .expectOne((r) => r.url === statusTypesUrl)
        .flush({ content: [{ id: 'type-other', statusTypeName: 'EMPLOYEE_STATUS_ARCHIVE' }] });

      await expect(promise).rejects.toThrow('EMPLOYEE_STATUS status type not found');
    });

    it('should issue no second request when the first response does not match', async () => {
      const promise = firstValueFrom(service.loadEmployeeStatusIds());

      httpMock
        .expectOne((r) => r.url === statusTypesUrl)
        .flush({ content: [{ id: 'type-other', statusTypeName: 'EMPLOYEE_STATUS_ARCHIVE' }] });

      await expect(promise).rejects.toThrow();
      expect(httpMock.match((r) => r.url === statusesUrl)).toHaveLength(0);
    });

    it('should fail when the first response carries no type at all', async () => {
      const promise = firstValueFrom(service.loadEmployeeStatusIds());

      httpMock.expectOne((r) => r.url === statusTypesUrl).flush({ content: [] });

      await expect(promise).rejects.toThrow('EMPLOYEE_STATUS status type not found');
      expect(httpMock.match((r) => r.url === statusesUrl)).toHaveLength(0);
    });
  });
});
