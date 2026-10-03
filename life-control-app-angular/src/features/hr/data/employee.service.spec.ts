import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { EmployeeService } from './employee.service';
import { ConfigService } from '@app/services/config.service';
import { Employee, EmployeeRequest } from '../models/employee.models';

describe('EmployeeService', () => {
  let service: EmployeeService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const baseUrl = 'http://localhost:9000/api';
  const employeesUrl = `${baseUrl}/companies/${companyId}/employees`;

  const mockEmployees: Employee[] = [
    {
      id: 'emp-1',
      companyId,
      employeeNumber: 'EMP-1',
      firstName: 'Ana',
      paternalLastName: 'García',
      maternalLastName: 'López',
      email: 'ana.garcia@lifecontrol.test',
      phoneNumber: '555-0101',
      birthDate: '1990-01-15',
      hireDate: '2020-03-01',
      terminationDate: null,
      addressId: null,
      statusId: 'status-active',
      statusName: 'Active',
      keycloakUserId: null,
      enabled: true,
      version: 0,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
    {
      id: 'emp-2',
      companyId,
      employeeNumber: 'EMP-2',
      firstName: 'Luis',
      paternalLastName: 'Pérez',
      maternalLastName: null,
      email: 'luis.perez@lifecontrol.test',
      phoneNumber: null,
      birthDate: '1985-07-20',
      hireDate: '2019-06-15',
      terminationDate: '2025-12-31',
      addressId: 'address-9',
      statusId: 'status-terminated',
      statusName: 'Terminated',
      keycloakUserId: 'kc-9',
      enabled: false,
      version: 3,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
  ];

  const request: EmployeeRequest = {
    employeeNumber: 'EMP-3',
    firstName: 'Marta',
    paternalLastName: 'Ruiz',
    maternalLastName: null,
    email: 'marta.ruiz@lifecontrol.test',
    phoneNumber: null,
    birthDate: '1992-05-05',
    hireDate: '2021-09-01',
    terminationDate: null,
    addressId: null,
    statusId: 'status-active',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [EmployeeService, { provide: ConfigService, useValue: { apiUrl: baseUrl } }],
    });
    service = TestBed.inject(EmployeeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getEmployees', () => {
    it('should always send includeDisabled and omit search and statusId when absent', async () => {
      const promise = firstValueFrom(service.getEmployees(companyId));

      const req = httpMock.expectOne((r) => r.url === employeesUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      expect(req.request.params.has('search')).toBe(false);
      expect(req.request.params.has('statusId')).toBe(false);
      req.flush(mockEmployees);

      const employees = await promise;
      expect(employees).toEqual(mockEmployees);
      expect(service.employees()).toEqual(mockEmployees);
    });

    it('should forward search and statusId when present', async () => {
      const promise = firstValueFrom(service.getEmployees(companyId, 'ana', 'status-active'));

      const req = httpMock.expectOne((r) => r.url === employeesUrl && r.method === 'GET');
      expect(req.request.params.get('search')).toBe('ana');
      expect(req.request.params.get('statusId')).toBe('status-active');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockEmployees);

      await promise;
    });

    it('should forward includeDisabled true to the request', async () => {
      const promise = firstValueFrom(service.getEmployees(companyId, undefined, undefined, true));

      const req = httpMock.expectOne((r) => r.url === employeesUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      expect(req.request.params.has('search')).toBe(false);
      expect(req.request.params.has('statusId')).toBe(false);
      req.flush(mockEmployees);

      await promise;
    });

    it('should set loading true during the fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const promise = firstValueFrom(service.getEmployees(companyId));
      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      httpMock.expectOne((r) => r.url === employeesUrl).flush(mockEmployees);
      await promise;
      expect(service.loading()).toBe(false);
    });

    it('should set the error signal on failure', async () => {
      const promise = firstValueFrom(service.getEmployees(companyId));
      httpMock
        .expectOne((r) => r.url === employeesUrl)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar los empleados');
    });

    it('should clear the error signal on a successful read after a failure', async () => {
      const failing = firstValueFrom(service.getEmployees(companyId));
      httpMock
        .expectOne((r) => r.url === employeesUrl && r.method === 'GET')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(failing).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar los empleados');

      const succeeding = firstValueFrom(service.getEmployees(companyId));
      httpMock.expectOne((r) => r.url === employeesUrl && r.method === 'GET').flush(mockEmployees);
      await succeeding;

      expect(service.error()).toBeNull();
    });
  });

  describe('getEmployee', () => {
    it('should fetch a single employee by id and not touch the list signal', async () => {
      // Seed the list first: with an unseeded signal, a `getEmployee` that wrote
      // `[]` would be indistinguishable from one that wrote nothing at all.
      const listPromise = firstValueFrom(service.getEmployees(companyId));
      httpMock.expectOne((r) => r.url === employeesUrl && r.method === 'GET').flush(mockEmployees);
      await listPromise;

      const promise = firstValueFrom(service.getEmployee(companyId, 'emp-1'));

      const req = httpMock.expectOne(`${employeesUrl}/emp-1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockEmployees[0]);

      await expect(promise).resolves.toEqual(mockEmployees[0]);
      expect(service.employees()).toEqual(mockEmployees);
    });

    it('should set the error signal on failure and re-throw to the caller', async () => {
      const promise = firstValueFrom(service.getEmployee(companyId, 'emp-1'));
      httpMock
        .expectOne(`${employeesUrl}/emp-1`)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toMatchObject({ status: 500 });
      expect(service.error()).toBe('Error al cargar el empleado');
    });
  });

  describe('suggestEmail', () => {
    it('should GET the suggestion with both names and mutate no state', async () => {
      // Seed the list so "no state" is observable: a pure read must not clear it.
      const listPromise = firstValueFrom(service.getEmployees(companyId));
      httpMock.expectOne((r) => r.url === employeesUrl).flush(mockEmployees);
      await listPromise;

      const suggestion = { email: 'nueva.persona@lifecontrol.test', reason: null };
      const promise = firstValueFrom(service.suggestEmail(companyId, 'Nueva', 'Persona'));

      const req = httpMock.expectOne((r) => r.url === `${employeesUrl}/suggest-email`);
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('firstName')).toBe('Nueva');
      expect(req.request.params.get('paternalLastName')).toBe('Persona');
      req.flush(suggestion);

      await expect(promise).resolves.toEqual(suggestion);
      expect(service.employees()).toEqual(mockEmployees);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });

    it('should surface a reason when no address is free', async () => {
      const reason = { email: null, reason: 'NO_FREE_CANDIDATE' };
      const promise = firstValueFrom(service.suggestEmail(companyId, 'Nueva', 'Persona'));

      httpMock.expectOne((r) => r.url === `${employeesUrl}/suggest-email`).flush(reason);

      await expect(promise).resolves.toEqual(reason);
    });
  });

  describe('addEmployee', () => {
    it('should POST the request and append the created employee', async () => {
      const created: Employee = { ...mockEmployees[0], id: 'emp-new', employeeNumber: 'EMP-3' };
      const promise = firstValueFrom(service.addEmployee(companyId, request));

      const req = httpMock.expectOne((r) => r.url === employeesUrl && r.method === 'POST');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(created, { status: 201, statusText: 'Created' });

      await expect(promise).resolves.toEqual(created);
      expect(service.employees()).toEqual([created]);
    });

    it('should map a 409 to the duplicate message', async () => {
      const promise = firstValueFrom(service.addEmployee(companyId, request));
      httpMock
        .expectOne((r) => r.url === employeesUrl && r.method === 'POST')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Ya existe un empleado con ese número o correo');
    });

    it('should map a non-409 failure to the create message and re-throw', async () => {
      const promise = firstValueFrom(service.addEmployee(companyId, request));
      httpMock
        .expectOne((r) => r.url === employeesUrl && r.method === 'POST')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toMatchObject({ status: 500 });
      expect(service.error()).toBe('Error al crear el empleado');
    });
  });

  describe('updateEmployee', () => {
    it('should PUT the request and replace the row in the signal', async () => {
      // Seed the signal through a list read first.
      const listPromise = firstValueFrom(service.getEmployees(companyId));
      httpMock.expectOne((r) => r.url === employeesUrl && r.method === 'GET').flush(mockEmployees);
      await listPromise;

      const updated: Employee = { ...mockEmployees[0], firstName: 'Ana María' };
      const promise = firstValueFrom(service.updateEmployee(companyId, 'emp-1', request));

      const req = httpMock.expectOne(`${employeesUrl}/emp-1`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush(updated);

      await promise;
      expect(service.employees()[0]).toEqual(updated);
    });

    it('should map a 409 to the duplicate message', async () => {
      const promise = firstValueFrom(service.updateEmployee(companyId, 'emp-1', request));
      httpMock
        .expectOne((r) => r.url === `${employeesUrl}/emp-1` && r.method === 'PUT')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Ya existe un empleado con ese número o correo');
    });

    it('should map a non-409 failure to the update message and re-throw', async () => {
      const promise = firstValueFrom(service.updateEmployee(companyId, 'emp-1', request));
      httpMock
        .expectOne((r) => r.url === `${employeesUrl}/emp-1` && r.method === 'PUT')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toMatchObject({ status: 500 });
      expect(service.error()).toBe('Error al actualizar el empleado');
    });
  });

  describe('removeEmployee', () => {
    it('should DELETE the row and drop it from the signal', async () => {
      const listPromise = firstValueFrom(service.getEmployees(companyId));
      httpMock.expectOne((r) => r.url === employeesUrl && r.method === 'GET').flush(mockEmployees);
      await listPromise;

      const promise = firstValueFrom(service.removeEmployee(companyId, 'emp-1'));
      const req = httpMock.expectOne(`${employeesUrl}/emp-1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null, { status: 204, statusText: 'No Content' });

      await promise;
      expect(service.employees().map((e) => e.id)).toEqual(['emp-2']);
    });

    it('should set the error signal on failure and re-throw', async () => {
      const promise = firstValueFrom(service.removeEmployee(companyId, 'emp-1'));
      httpMock
        .expectOne(`${employeesUrl}/emp-1`)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toMatchObject({ status: 500 });
      expect(service.error()).toBe('Error al deshabilitar el empleado');
    });

    it('should clear a stale error on a successful remove after a failure', async () => {
      const failing = firstValueFrom(service.removeEmployee(companyId, 'emp-1'));
      httpMock
        .expectOne(`${employeesUrl}/emp-1`)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(failing).rejects.toMatchObject({ status: 500 });
      expect(service.error()).toBe('Error al deshabilitar el empleado');

      const succeeding = firstValueFrom(service.removeEmployee(companyId, 'emp-1'));
      httpMock
        .expectOne(`${employeesUrl}/emp-1`)
        .flush(null, { status: 204, statusText: 'No Content' });
      await succeeding;

      expect(service.error()).toBeNull();
    });
  });

  describe('enableEmployee', () => {
    it('should PATCH enable with { enabled: true } and replace the row', async () => {
      const listPromise = firstValueFrom(service.getEmployees(companyId));
      httpMock.expectOne((r) => r.url === employeesUrl && r.method === 'GET').flush(mockEmployees);
      await listPromise;

      const enabled: Employee = { ...mockEmployees[1], enabled: true };
      const promise = firstValueFrom(service.enableEmployee(companyId, 'emp-2'));

      const req = httpMock.expectOne(`${employeesUrl}/emp-2/enable`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual({ enabled: true });
      req.flush(enabled);

      await promise;
      expect(service.employees().find((e) => e.id === 'emp-2')?.enabled).toBe(true);
    });

    it('should set the error signal on failure and re-throw', async () => {
      const promise = firstValueFrom(service.enableEmployee(companyId, 'emp-2'));
      httpMock
        .expectOne(`${employeesUrl}/emp-2/enable`)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toMatchObject({ status: 500 });
      expect(service.error()).toBe('Error al reactivar el empleado');
    });
  });

  it('clearError should reset the error signal', async () => {
    const promise = firstValueFrom(service.getEmployees(companyId));
    httpMock
      .expectOne((r) => r.url === employeesUrl)
      .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
    await expect(promise).rejects.toBeTruthy();

    service.clearError();
    expect(service.error()).toBeNull();
  });
});
