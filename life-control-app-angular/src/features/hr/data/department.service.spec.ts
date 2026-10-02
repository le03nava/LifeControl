import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { DepartmentService } from './department.service';
import { ConfigService } from '@app/services/config.service';
import { Department, DepartmentRequest } from '../models/department.models';

describe('DepartmentService', () => {
  let service: DepartmentService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const baseUrl = 'http://localhost:9000/api';
  const departmentsUrl = `${baseUrl}/companies/${companyId}/departments`;

  const mockDepartments: Department[] = [
    {
      id: 'dep-1',
      companyId,
      departmentCode: 'OPS',
      departmentName: 'Operaciones',
      description: null,
      displayOrder: 1,
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
    {
      id: 'dep-2',
      companyId,
      departmentCode: 'FIN',
      departmentName: 'Finanzas',
      description: 'Administración',
      displayOrder: 2,
      enabled: false,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
  ];

  const request: DepartmentRequest = {
    departmentCode: 'RRHH',
    departmentName: 'Recursos Humanos',
    description: null,
    displayOrder: 3,
    enabled: true,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [DepartmentService, { provide: ConfigService, useValue: { apiUrl: baseUrl } }],
    });
    service = TestBed.inject(DepartmentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getDepartments', () => {
    it('should fetch departments for a company with includeDisabled false by default', async () => {
      const promise = firstValueFrom(service.getDepartments(companyId));

      const req = httpMock.expectOne((r) => r.url === departmentsUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockDepartments);

      const departments = await promise;
      expect(departments).toEqual(mockDepartments);
      expect(service.departments()).toEqual(mockDepartments);
    });

    it('should forward includeDisabled true to the request', async () => {
      const promise = firstValueFrom(service.getDepartments(companyId, true));

      const req = httpMock.expectOne((r) => r.url === departmentsUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(mockDepartments);

      await promise;
    });

    it('should set loading true during the fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const promise = firstValueFrom(service.getDepartments(companyId));
      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      httpMock.expectOne((r) => r.url === departmentsUrl).flush(mockDepartments);
      await promise;
      expect(service.loading()).toBe(false);
    });

    it('should set the error signal on failure', async () => {
      const promise = firstValueFrom(service.getDepartments(companyId));
      httpMock
        .expectOne((r) => r.url === departmentsUrl)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar los departamentos');
    });

    it('should clear the error signal on a successful read after a failure', async () => {
      const failing = firstValueFrom(service.getDepartments(companyId));
      httpMock
        .expectOne((r) => r.url === departmentsUrl && r.method === 'GET')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(failing).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar los departamentos');

      const succeeding = firstValueFrom(service.getDepartments(companyId));
      httpMock
        .expectOne((r) => r.url === departmentsUrl && r.method === 'GET')
        .flush(mockDepartments);
      await succeeding;

      expect(service.error()).toBeNull();
    });
  });

  describe('getDepartment', () => {
    it('should fetch a single department by id and not touch the list signal', async () => {
      const promise = firstValueFrom(service.getDepartment(companyId, 'dep-1'));

      const req = httpMock.expectOne(`${departmentsUrl}/dep-1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockDepartments[0]);

      await expect(promise).resolves.toEqual(mockDepartments[0]);
      expect(service.departments()).toEqual([]);
    });
  });

  describe('addDepartment', () => {
    it('should POST the request and append the created department', async () => {
      const created: Department = { ...mockDepartments[0], id: 'dep-new', departmentCode: 'RRHH' };
      const promise = firstValueFrom(service.addDepartment(companyId, request));

      const req = httpMock.expectOne((r) => r.url === departmentsUrl && r.method === 'POST');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(created, { status: 201, statusText: 'Created' });

      await expect(promise).resolves.toEqual(created);
      expect(service.departments()).toEqual([created]);
    });

    it('should map a 409 to the duplicate message', async () => {
      const promise = firstValueFrom(service.addDepartment(companyId, request));
      httpMock
        .expectOne((r) => r.url === departmentsUrl && r.method === 'POST')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Ya existe un departamento con ese código o nombre');
    });
  });

  describe('updateDepartment', () => {
    it('should PUT the request and replace the row in the signal', async () => {
      // Seed the signal through a list read first.
      const listPromise = firstValueFrom(service.getDepartments(companyId));
      httpMock
        .expectOne((r) => r.url === departmentsUrl && r.method === 'GET')
        .flush(mockDepartments);
      await listPromise;

      const updated: Department = { ...mockDepartments[0], departmentName: 'Operaciones 2' };
      const promise = firstValueFrom(service.updateDepartment(companyId, 'dep-1', request));

      const req = httpMock.expectOne(`${departmentsUrl}/dep-1`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush(updated);

      await promise;
      expect(service.departments()[0]).toEqual(updated);
    });
  });

  describe('removeDepartment', () => {
    it('should DELETE the row and drop it from the signal', async () => {
      const listPromise = firstValueFrom(service.getDepartments(companyId));
      httpMock
        .expectOne((r) => r.url === departmentsUrl && r.method === 'GET')
        .flush(mockDepartments);
      await listPromise;

      const promise = firstValueFrom(service.removeDepartment(companyId, 'dep-1'));
      const req = httpMock.expectOne(`${departmentsUrl}/dep-1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null, { status: 204, statusText: 'No Content' });

      await promise;
      expect(service.departments().map((d) => d.id)).toEqual(['dep-2']);
    });
  });

  describe('enableDepartment', () => {
    it('should PATCH enable with { enabled: true } and replace the row', async () => {
      const listPromise = firstValueFrom(service.getDepartments(companyId));
      httpMock
        .expectOne((r) => r.url === departmentsUrl && r.method === 'GET')
        .flush(mockDepartments);
      await listPromise;

      const enabled: Department = { ...mockDepartments[1], enabled: true };
      const promise = firstValueFrom(service.enableDepartment(companyId, 'dep-2'));

      const req = httpMock.expectOne(`${departmentsUrl}/dep-2/enable`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual({ enabled: true });
      req.flush(enabled);

      await promise;
      expect(service.departments().find((d) => d.id === 'dep-2')?.enabled).toBe(true);
    });
  });

  it('clearError should reset the error signal', async () => {
    const promise = firstValueFrom(service.getDepartments(companyId));
    httpMock
      .expectOne((r) => r.url === departmentsUrl)
      .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
    await expect(promise).rejects.toBeTruthy();

    service.clearError();
    expect(service.error()).toBeNull();
  });
});
