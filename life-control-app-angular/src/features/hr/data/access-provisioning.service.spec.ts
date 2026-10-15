import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { AccessProvisioningService } from './access-provisioning.service';
import { ConfigService } from '@app/services/config.service';
import {
  AccessProvisioningOverview,
  AccessProvisioningQueueEntry,
  AccessProvisioningTaskView,
} from '../models/access-provisioning.models';

describe('AccessProvisioningService', () => {
  let service: AccessProvisioningService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const employeeId = 'employee-456';
  const taskId = 'task-789';
  const baseUrl = 'http://localhost:9000/api';
  const accessUrl = `${baseUrl}/companies/${companyId}/employees/${employeeId}/access`;
  const queueUrl = `${baseUrl}/companies/${companyId}/access/requests`;

  const openTask: AccessProvisioningTaskView = {
    id: taskId,
    kind: 'ACTIVATE',
    status: 'APPROVAL_PENDING',
    attempts: 0,
    maxAttempts: 3,
    lastError: null,
    nextAttemptAt: null,
    requestedBy: 'requester-sub',
    requestedByName: 'Ada Lovelace',
    requestedAt: '2026-10-14T10:00:00',
    decidedBy: null,
    decidedAt: null,
    enabled: true,
  };

  const mockOverview: AccessProvisioningOverview = {
    keycloakUserId: 'keycloak-user-1',
    accountLinked: true,
    requiredRoles: ['lc-sales'],
    currentRoles: ['lc-hr-read'],
    roleDiff: { added: ['lc-sales'], removed: ['lc-hr-read'] },
    frozenRoleDiff: { added: ['lc-sales'], removed: [] },
    openTask,
    history: [openTask],
    claims: {
      companyId: [companyId],
      companyCountryId: ['country-1'],
      companyRegionId: ['region-1'],
      companyZoneId: ['zone-1'],
      companyStoreId: ['store-1'],
    },
  };

  const queueEntry: AccessProvisioningQueueEntry = {
    taskId,
    kind: 'ACTIVATE',
    status: 'APPROVAL_PENDING',
    requestedBy: 'requester-sub',
    requestedByName: 'Ada Lovelace',
    requestedAt: '2026-10-14T10:00:00',
    employeeId,
    employeeNumber: 'E-001',
    employeeFirstName: 'Grace',
    employeePaternalLastName: 'Hopper',
    employeeMaternalLastName: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        AccessProvisioningService,
        { provide: ConfigService, useValue: { apiUrl: baseUrl } },
      ],
    });
    service = TestBed.inject(AccessProvisioningService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getOverview', () => {
    it('should GET the employee access path and expose the overview on the signal', async () => {
      const promise = firstValueFrom(service.getOverview(companyId, employeeId));

      const req = httpMock.expectOne((r) => r.url === accessUrl && r.method === 'GET');
      expect(req.request.method).toBe('GET');
      req.flush(mockOverview);

      await expect(promise).resolves.toEqual(mockOverview);
      expect(service.overview()).toEqual(mockOverview);
    });

    it('should set loading true during the fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const promise = firstValueFrom(service.getOverview(companyId, employeeId));
      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      httpMock.expectOne((r) => r.url === accessUrl).flush(mockOverview);
      await promise;
      expect(service.loading()).toBe(false);
    });

    it('should map a 403 to the missing-permission message', async () => {
      const promise = firstValueFrom(service.getOverview(companyId, employeeId));
      httpMock
        .expectOne((r) => r.url === accessUrl && r.method === 'GET')
        .flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });

      await expect(promise).rejects.toMatchObject({ status: 403 });
      expect(service.error()).toBe('No tenés permisos para ver el acceso');
    });

    it('should map a 404 to the employee-not-found message', async () => {
      const promise = firstValueFrom(service.getOverview(companyId, employeeId));
      httpMock
        .expectOne((r) => r.url === accessUrl && r.method === 'GET')
        .flush({ status: 404 }, { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toMatchObject({ status: 404 });
      expect(service.error()).toBe('No se encontró el empleado');
    });

    it('should clear the error signal on a successful read after a failure', async () => {
      const failing = firstValueFrom(service.getOverview(companyId, employeeId));
      httpMock
        .expectOne((r) => r.url === accessUrl && r.method === 'GET')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(failing).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar el acceso');

      const succeeding = firstValueFrom(service.getOverview(companyId, employeeId));
      httpMock.expectOne((r) => r.url === accessUrl && r.method === 'GET').flush(mockOverview);
      await succeeding;

      expect(service.error()).toBeNull();
    });
  });

  describe('apply', () => {
    it('should POST the apply path with no body and expose the reloaded overview', async () => {
      const promise = firstValueFrom(service.apply(companyId, employeeId));

      const req = httpMock.expectOne((r) => r.url === `${accessUrl}/apply` && r.method === 'POST');
      expect(req.request.body).toBeNull();
      req.flush(mockOverview);

      await promise;
      expect(service.overview()).toEqual(mockOverview);
    });

    it('should map a 409 to the open-request-or-not-deliverable message', async () => {
      const promise = firstValueFrom(service.apply(companyId, employeeId));
      httpMock
        .expectOne(`${accessUrl}/apply`)
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toMatchObject({ status: 409 });
      expect(service.error()).toBe(
        'El empleado ya tiene una solicitud abierta o no puede recibir el acceso',
      );
    });
  });

  describe('deactivate', () => {
    it('should POST the deactivate path with no body and expose the reloaded overview', async () => {
      const promise = firstValueFrom(service.deactivate(companyId, employeeId));

      const req = httpMock.expectOne(
        (r) => r.url === `${accessUrl}/deactivate` && r.method === 'POST',
      );
      expect(req.request.body).toBeNull();
      req.flush(mockOverview);

      await promise;
      expect(service.overview()).toEqual(mockOverview);
    });

    it('should map a 409 to the open-request message', async () => {
      const promise = firstValueFrom(service.deactivate(companyId, employeeId));
      httpMock
        .expectOne(`${accessUrl}/deactivate`)
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toMatchObject({ status: 409 });
      expect(service.error()).toBe('El empleado ya tiene una solicitud abierta');
    });
  });

  describe('retry', () => {
    it('should POST the retry path with no body and expose the reloaded overview', async () => {
      const promise = firstValueFrom(service.retry(companyId, employeeId));

      const req = httpMock.expectOne((r) => r.url === `${accessUrl}/retry` && r.method === 'POST');
      expect(req.request.body).toBeNull();
      req.flush(mockOverview);

      await promise;
      expect(service.overview()).toEqual(mockOverview);
    });

    it('should map a 409 to the not-failed message', async () => {
      const promise = firstValueFrom(service.retry(companyId, employeeId));
      httpMock
        .expectOne(`${accessUrl}/retry`)
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toMatchObject({ status: 409 });
      expect(service.error()).toBe('La solicitud abierta no está fallada');
    });
  });

  describe('resend', () => {
    it('should POST the resend path with no body and expose the reloaded overview', async () => {
      const promise = firstValueFrom(service.resend(companyId, employeeId));

      const req = httpMock.expectOne((r) => r.url === `${accessUrl}/resend` && r.method === 'POST');
      expect(req.request.body).toBeNull();
      req.flush(mockOverview);

      await promise;
      expect(service.overview()).toEqual(mockOverview);
    });

    it('should map a 409 to the no-account-or-no-domain message', async () => {
      const promise = firstValueFrom(service.resend(companyId, employeeId));
      httpMock
        .expectOne(`${accessUrl}/resend`)
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toMatchObject({ status: 409 });
      expect(service.error()).toBe(
        'No hay una cuenta vinculada o la empresa no tiene dominio de correo',
      );
    });
  });

  describe('approve', () => {
    it('should POST the approve path with no body and expose the reloaded overview', async () => {
      const promise = firstValueFrom(service.approve(companyId, employeeId, taskId));

      const req = httpMock.expectOne(
        (r) => r.url === `${accessUrl}/requests/${taskId}/approve` && r.method === 'POST',
      );
      expect(req.request.body).toBeNull();
      req.flush(mockOverview);

      await promise;
      expect(service.overview()).toEqual(mockOverview);
    });

    it('should map a 409 to the self-approval-or-not-awaiting message', async () => {
      const promise = firstValueFrom(service.approve(companyId, employeeId, taskId));
      httpMock
        .expectOne(`${accessUrl}/requests/${taskId}/approve`)
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toMatchObject({ status: 409 });
      expect(service.error()).toBe(
        'No podés aprobar tu propia solicitud o la solicitud no espera aprobación',
      );
    });
  });

  describe('reject', () => {
    it('should POST the reject path with the reason as its body', async () => {
      const promise = firstValueFrom(
        service.reject(companyId, employeeId, taskId, { reason: 'No corresponde' }),
      );

      const req = httpMock.expectOne(
        (r) => r.url === `${accessUrl}/requests/${taskId}/reject` && r.method === 'POST',
      );
      // The wire payload is exactly `{"reason": "..."}` (AccessProvisioningRejectionRequest).
      expect(req.request.body).toEqual({ reason: 'No corresponde' });
      req.flush(mockOverview);

      await promise;
      expect(service.overview()).toEqual(mockOverview);
    });

    it('should map a 400 to the missing-actor-or-reason message', async () => {
      const promise = firstValueFrom(service.reject(companyId, employeeId, taskId, { reason: '' }));
      httpMock
        .expectOne(`${accessUrl}/requests/${taskId}/reject`)
        .flush({ status: 400 }, { status: 400, statusText: 'Bad Request' });

      await expect(promise).rejects.toMatchObject({ status: 400 });
      expect(service.error()).toBe(
        'La decisión no tiene un usuario que la tome o falta el motivo',
      );
    });

    it('should map a 409 to the not-awaiting message', async () => {
      const promise = firstValueFrom(
        service.reject(companyId, employeeId, taskId, { reason: 'No corresponde' }),
      );
      httpMock
        .expectOne(`${accessUrl}/requests/${taskId}/reject`)
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toMatchObject({ status: 409 });
      expect(service.error()).toBe('La solicitud no espera aprobación');
    });
  });

  describe('getDecisionQueue', () => {
    it('should GET the company queue path and return the entries', async () => {
      const promise = firstValueFrom(service.getDecisionQueue(companyId));

      const req = httpMock.expectOne((r) => r.url === queueUrl && r.method === 'GET');
      expect(req.request.method).toBe('GET');
      req.flush([queueEntry]);

      await expect(promise).resolves.toEqual([queueEntry]);
    });

    it('should be a pure read that leaves the overview signal untouched', async () => {
      const promise = firstValueFrom(service.getDecisionQueue(companyId));
      httpMock.expectOne((r) => r.url === queueUrl && r.method === 'GET').flush([queueEntry]);
      await promise;

      expect(service.overview()).toBeNull();
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });

  it('clearError should reset the error signal', async () => {
    const promise = firstValueFrom(service.getOverview(companyId, employeeId));
    httpMock
      .expectOne((r) => r.url === accessUrl)
      .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
    await expect(promise).rejects.toBeTruthy();

    service.clearError();
    expect(service.error()).toBeNull();
  });
});
