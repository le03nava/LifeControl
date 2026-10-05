import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { StoreAssignmentService } from './store-assignment.service';
import { ConfigService } from '@app/services/config.service';
import { StoreAssignment, StoreAssignmentRequest } from '../models/store-assignment.models';

describe('StoreAssignmentService', () => {
  let service: StoreAssignmentService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const employeeId = 'employee-456';
  const baseUrl = 'http://localhost:9000/api';
  const assignmentsUrl = `${baseUrl}/companies/${companyId}/employees/${employeeId}/store-assignments`;

  const mockAssignments: StoreAssignment[] = [
    {
      id: 'assignment-2',
      companyStoreId: 'store-1',
      companyStoreName: 'Tienda Centro',
      validFrom: '2026-01-01',
      validTo: null,
      enabled: true,
      derived: {
        companyId,
        companyName: 'Acme Corp',
        companyCountryId: 'company-country-1',
        companyCountryName: 'México',
        companyRegionId: 'region-1',
        companyRegionName: 'Centro',
        companyZoneId: 'zone-1',
        companyZoneName: 'Zona Norte',
      },
    },
    {
      id: 'assignment-1',
      companyStoreId: 'store-2',
      companyStoreName: 'Tienda Sur',
      validFrom: '2024-01-01',
      validTo: '2025-12-31',
      enabled: false,
      derived: {
        companyId,
        companyName: 'Acme Corp',
        companyCountryId: 'company-country-1',
        companyCountryName: 'México',
        companyRegionId: 'region-2',
        companyRegionName: 'Sur',
        companyZoneId: 'zone-2',
        companyZoneName: 'Zona Sur',
      },
    },
  ];

  const request: StoreAssignmentRequest = {
    companyStoreId: 'store-1',
    validFrom: '2026-01-01',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        StoreAssignmentService,
        { provide: ConfigService, useValue: { apiUrl: baseUrl } },
      ],
    });
    service = TestBed.inject(StoreAssignmentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  /** Seeds the assignments signal through a real list read. */
  async function seedAssignments(): Promise<void> {
    const listPromise = firstValueFrom(service.getAssignments(companyId, employeeId));
    httpMock
      .expectOne((r) => r.url === assignmentsUrl && r.method === 'GET')
      .flush(mockAssignments);
    await listPromise;
  }

  describe('getAssignments', () => {
    it('should GET the nested assignment path and expose the rows on the signal', async () => {
      const promise = firstValueFrom(service.getAssignments(companyId, employeeId));

      const req = httpMock.expectOne((r) => r.url === assignmentsUrl && r.method === 'GET');
      expect(req.request.method).toBe('GET');
      req.flush(mockAssignments);

      await expect(promise).resolves.toEqual(mockAssignments);
      expect(service.assignments()).toEqual(mockAssignments);
    });

    it('should ask for the endpoint default when includeDisabled is omitted', async () => {
      const promise = firstValueFrom(service.getAssignments(companyId, employeeId));

      const req = httpMock.expectOne((r) => r.url === assignmentsUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockAssignments);
      await promise;
    });

    it('should forward includeDisabled=true when the soft-deleted rows are asked for', async () => {
      const promise = firstValueFrom(service.getAssignments(companyId, employeeId, true));

      const req = httpMock.expectOne((r) => r.url === assignmentsUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(mockAssignments);
      await promise;
    });

    it('should set loading true during the fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const promise = firstValueFrom(service.getAssignments(companyId, employeeId));
      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      httpMock.expectOne((r) => r.url === assignmentsUrl).flush(mockAssignments);
      await promise;
      expect(service.loading()).toBe(false);
    });

    it('should set the error signal on failure and re-throw', async () => {
      const promise = firstValueFrom(service.getAssignments(companyId, employeeId));
      httpMock
        .expectOne((r) => r.url === assignmentsUrl)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toMatchObject({ status: 500 });
      expect(service.error()).toBe('Error al cargar las tiendas asignadas');
    });

    it('should clear the error signal on a successful read after a failure', async () => {
      const failing = firstValueFrom(service.getAssignments(companyId, employeeId));
      httpMock
        .expectOne((r) => r.url === assignmentsUrl && r.method === 'GET')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(failing).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar las tiendas asignadas');

      const succeeding = firstValueFrom(service.getAssignments(companyId, employeeId));
      httpMock
        .expectOne((r) => r.url === assignmentsUrl && r.method === 'GET')
        .flush(mockAssignments);
      await succeeding;

      expect(service.error()).toBeNull();
    });
  });

  describe('createAssignment', () => {
    it('should POST the request and prepend the created assignment', async () => {
      await seedAssignments();

      const created: StoreAssignment = {
        ...mockAssignments[0],
        id: 'assignment-3',
        validFrom: '2026-07-01',
      };
      const promise = firstValueFrom(service.createAssignment(companyId, employeeId, request));

      const req = httpMock.expectOne((r) => r.url === assignmentsUrl && r.method === 'POST');
      expect(req.request.method).toBe('POST');
      // T13: the create payload carries the store and the first day covered, and no end date.
      expect(req.request.body).toEqual({ companyStoreId: 'store-1', validFrom: '2026-01-01' });
      req.flush(created, { status: 201, statusText: 'Created' });

      await expect(promise).resolves.toEqual(created);
      // The API orders newest first, so a freshly created assignment goes to the front.
      expect(service.assignments()).toEqual([created, ...mockAssignments]);
    });

    it('should map a 400 to the invalid-payload message', async () => {
      const promise = firstValueFrom(service.createAssignment(companyId, employeeId, request));
      httpMock
        .expectOne((r) => r.url === assignmentsUrl && r.method === 'POST')
        .flush({ status: 400 }, { status: 400, statusText: 'Bad Request' });

      await expect(promise).rejects.toMatchObject({ status: 400 });
      expect(service.error()).toBe('Los datos de la asignación no son válidos');
    });

    it('should map a 403 to the missing-permission message', async () => {
      const promise = firstValueFrom(service.createAssignment(companyId, employeeId, request));
      httpMock
        .expectOne((r) => r.url === assignmentsUrl && r.method === 'POST')
        .flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });

      await expect(promise).rejects.toMatchObject({ status: 403 });
      expect(service.error()).toBe('No tenés permisos para asignar tiendas');
    });

    it('should map a 404 to the not-found message', async () => {
      const promise = firstValueFrom(service.createAssignment(companyId, employeeId, request));
      httpMock
        .expectOne((r) => r.url === assignmentsUrl && r.method === 'POST')
        .flush({ status: 404 }, { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toMatchObject({ status: 404 });
      expect(service.error()).toBe('No se encontró el empleado o la tienda');
    });

    it('should map a 409 to the overlap message, the status this path actually produces', async () => {
      const promise = firstValueFrom(service.createAssignment(companyId, employeeId, request));
      httpMock
        .expectOne((r) => r.url === assignmentsUrl && r.method === 'POST')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toMatchObject({ status: 409 });
      expect(service.error()).toBe('Ya existe una asignación en esa tienda que se superpone');
    });

    it('should fall back to the generic message for any other status', async () => {
      const promise = firstValueFrom(service.createAssignment(companyId, employeeId, request));
      httpMock
        .expectOne((r) => r.url === assignmentsUrl && r.method === 'POST')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al crear la asignación');
    });
  });

  describe('closeAssignment', () => {
    it('should PATCH close with an inclusive endDate and replace the row in place', async () => {
      await seedAssignments();

      const closed: StoreAssignment = { ...mockAssignments[0], validTo: '2026-06-15' };
      const promise = firstValueFrom(
        service.closeAssignment(companyId, employeeId, 'assignment-2', { endDate: '2026-06-15' }),
      );

      const req = httpMock.expectOne(`${assignmentsUrl}/assignment-2/close`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual({ endDate: '2026-06-15' });
      req.flush(closed);

      await promise;
      expect(service.assignments().find((a) => a.id === 'assignment-2')).toEqual(closed);
    });

    it('should send no body when the request is omitted, so the API closes today', async () => {
      await seedAssignments();

      const closed: StoreAssignment = { ...mockAssignments[0], validTo: '2026-06-30' };
      const promise = firstValueFrom(
        service.closeAssignment(companyId, employeeId, 'assignment-2'),
      );

      const req = httpMock.expectOne(`${assignmentsUrl}/assignment-2/close`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toBeNull();
      req.flush(closed);

      await promise;
      expect(service.assignments().find((a) => a.id === 'assignment-2')).toEqual(closed);
    });

    it('should map a 409 to the already-closed message', async () => {
      const promise = firstValueFrom(
        service.closeAssignment(companyId, employeeId, 'assignment-2'),
      );
      httpMock
        .expectOne(`${assignmentsUrl}/assignment-2/close`)
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toMatchObject({ status: 409 });
      expect(service.error()).toBe('La asignación ya está cerrada');
    });

    it('should map a 400 to the inverted-date message', async () => {
      const promise = firstValueFrom(
        service.closeAssignment(companyId, employeeId, 'assignment-2', { endDate: '2025-01-01' }),
      );
      httpMock
        .expectOne(`${assignmentsUrl}/assignment-2/close`)
        .flush({ status: 400 }, { status: 400, statusText: 'Bad Request' });

      await expect(promise).rejects.toMatchObject({ status: 400 });
      expect(service.error()).toBe('La fecha de cierre no puede ser anterior al inicio');
    });

    it('should map a 404 to the assignment-not-found message', async () => {
      const promise = firstValueFrom(
        service.closeAssignment(companyId, employeeId, 'assignment-2'),
      );
      httpMock
        .expectOne(`${assignmentsUrl}/assignment-2/close`)
        .flush({ status: 404 }, { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toMatchObject({ status: 404 });
      expect(service.error()).toBe('No se encontró la asignación');
    });

    it('should map a 403 to the missing-permission message', async () => {
      const promise = firstValueFrom(
        service.closeAssignment(companyId, employeeId, 'assignment-2'),
      );
      httpMock
        .expectOne(`${assignmentsUrl}/assignment-2/close`)
        .flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });

      await expect(promise).rejects.toMatchObject({ status: 403 });
      expect(service.error()).toBe('No tenés permisos para cerrar asignaciones');
    });

    it('should fall back to the generic message for any other status', async () => {
      const promise = firstValueFrom(
        service.closeAssignment(companyId, employeeId, 'assignment-2'),
      );
      httpMock
        .expectOne(`${assignmentsUrl}/assignment-2/close`)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cerrar la asignación');
    });
  });

  it('clearError should reset the error signal', async () => {
    const promise = firstValueFrom(service.getAssignments(companyId, employeeId));
    httpMock
      .expectOne((r) => r.url === assignmentsUrl)
      .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
    await expect(promise).rejects.toBeTruthy();

    service.clearError();
    expect(service.error()).toBeNull();
  });
});
