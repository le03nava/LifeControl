/// <reference types="vitest/globals" />
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { PositionService } from './position.service';
import { ConfigService } from '@app/services/config.service';
import { Position, PositionRequest } from '../models/position.models';

describe('PositionService', () => {
  let service: PositionService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const baseUrl = 'http://localhost:9000/api';
  const positionsUrl = `${baseUrl}/companies/${companyId}/positions`;

  const mockPositions: Position[] = [
    {
      id: 'pos-1',
      companyId,
      departmentId: 'dep-1',
      positionCode: 'OPR',
      positionName: 'Operario',
      description: null,
      reportsToPositionId: null,
      displayOrder: 1,
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
    {
      id: 'pos-2',
      companyId,
      departmentId: 'dep-2',
      positionCode: 'SUP',
      positionName: 'Supervisor',
      description: 'Jefe de turno',
      reportsToPositionId: 'pos-1',
      displayOrder: 2,
      enabled: false,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
  ];

  const request: PositionRequest = {
    departmentId: 'dep-1',
    positionCode: 'JEF',
    positionName: 'Jefe de almacén',
    description: null,
    reportsToPositionId: null,
    displayOrder: 3,
    enabled: true,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [PositionService, { provide: ConfigService, useValue: { apiUrl: baseUrl } }],
    });
    service = TestBed.inject(PositionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getPositions', () => {
    it('should fetch positions with includeDisabled false by default and no department filter', async () => {
      const promise = firstValueFrom(service.getPositions(companyId));

      const req = httpMock.expectOne((r) => r.url === positionsUrl && r.method === 'GET');
      // The toggle reaches the request instead of filtering an already-enabled list.
      expect(req.request.params.get('includeDisabled')).toBe('false');
      // An absent filter must not be sent as the string "undefined".
      expect(req.request.params.has('departmentId')).toBe(false);
      req.flush(mockPositions);

      const positions = await promise;
      expect(positions).toEqual(mockPositions);
      expect(service.positions()).toEqual(mockPositions);
    });

    it('should forward includeDisabled true to the request', async () => {
      const promise = firstValueFrom(service.getPositions(companyId, undefined, true));

      const req = httpMock.expectOne((r) => r.url === positionsUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(mockPositions);

      await promise;
    });

    it('should forward the department filter when one is given', async () => {
      const promise = firstValueFrom(service.getPositions(companyId, 'dep-1'));

      const req = httpMock.expectOne((r) => r.url === positionsUrl && r.method === 'GET');
      expect(req.request.params.get('departmentId')).toBe('dep-1');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockPositions);

      await promise;
    });

    it('should set loading true during the fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const promise = firstValueFrom(service.getPositions(companyId));
      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      httpMock.expectOne((r) => r.url === positionsUrl).flush(mockPositions);
      await promise;
      expect(service.loading()).toBe(false);
    });

    it('should set the error signal on failure', async () => {
      const promise = firstValueFrom(service.getPositions(companyId));
      httpMock
        .expectOne((r) => r.url === positionsUrl)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar los puestos');
    });

    it('should clear the error signal on a successful read after a failure', async () => {
      const failing = firstValueFrom(service.getPositions(companyId));
      httpMock
        .expectOne((r) => r.url === positionsUrl && r.method === 'GET')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(failing).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar los puestos');

      const succeeding = firstValueFrom(service.getPositions(companyId));
      httpMock.expectOne((r) => r.url === positionsUrl && r.method === 'GET').flush(mockPositions);
      await succeeding;
      expect(service.error()).toBeNull();
    });
  });

  describe('getPosition', () => {
    it('should fetch one position by id', async () => {
      const promise = firstValueFrom(service.getPosition(companyId, 'pos-1'));

      httpMock
        .expectOne((r) => r.url === `${positionsUrl}/pos-1` && r.method === 'GET')
        .flush(mockPositions[0]);

      expect(await promise).toEqual(mockPositions[0]);
    });

    it('should not replace the list signal with the single row', async () => {
      const list = firstValueFrom(service.getPositions(companyId));
      httpMock.expectOne((r) => r.url === positionsUrl).flush(mockPositions);
      await list;

      const single = firstValueFrom(service.getPosition(companyId, 'pos-1'));
      httpMock.expectOne((r) => r.url === `${positionsUrl}/pos-1`).flush(mockPositions[0]);
      await single;

      // Editing one row must not collapse the collection the list page is showing.
      expect(service.positions()).toEqual(mockPositions);
    });

    it('should set the error signal on failure', async () => {
      const promise = firstValueFrom(service.getPosition(companyId, 'pos-9'));
      httpMock
        .expectOne((r) => r.url === `${positionsUrl}/pos-9`)
        .flush({ status: 404 }, { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar el puesto');
    });
  });

  describe('addPosition', () => {
    it('should post the request and append the created position to the list', async () => {
      const seeded = firstValueFrom(service.getPositions(companyId));
      httpMock.expectOne((r) => r.url === positionsUrl).flush([mockPositions[0]]);
      await seeded;

      const created: Position = { ...mockPositions[1], id: 'pos-3' };
      const promise = firstValueFrom(service.addPosition(companyId, request));

      const req = httpMock.expectOne((r) => r.url === positionsUrl && r.method === 'POST');
      expect(req.request.body).toEqual(request);
      req.flush(created);

      expect(await promise).toEqual(created);
      expect(service.positions().map((p) => p.id)).toEqual(['pos-1', 'pos-3']);
    });

    it('should name the duplicate conflict instead of a generic failure', async () => {
      const promise = firstValueFrom(service.addPosition(companyId, request));
      httpMock
        .expectOne((r) => r.url === positionsUrl && r.method === 'POST')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Ya existe un puesto con ese código en el departamento');
    });

    it('should set a generic message for a non-conflict failure', async () => {
      const promise = firstValueFrom(service.addPosition(companyId, request));
      httpMock
        .expectOne((r) => r.url === positionsUrl && r.method === 'POST')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al crear el puesto');
    });
  });

  describe('updatePosition', () => {
    it('should put the request and replace the row in the list', async () => {
      const seeded = firstValueFrom(service.getPositions(companyId));
      httpMock.expectOne((r) => r.url === positionsUrl).flush(mockPositions);
      await seeded;

      const updated: Position = { ...mockPositions[0], positionName: 'Operario senior' };
      const promise = firstValueFrom(service.updatePosition(companyId, 'pos-1', request));

      const req = httpMock.expectOne((r) => r.url === `${positionsUrl}/pos-1` && r.method === 'PUT');
      expect(req.request.body).toEqual(request);
      req.flush(updated);

      expect(await promise).toEqual(updated);
      expect(service.positions()[0].positionName).toBe('Operario senior');
      expect(service.positions()).toHaveLength(2);
    });

    it('should name the duplicate conflict instead of a generic failure', async () => {
      const promise = firstValueFrom(service.updatePosition(companyId, 'pos-1', request));
      httpMock
        .expectOne((r) => r.method === 'PUT')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Ya existe un puesto con ese código en el departamento');
    });

    it('should set a generic message for a non-conflict failure', async () => {
      const promise = firstValueFrom(service.updatePosition(companyId, 'pos-1', request));
      httpMock
        .expectOne((r) => r.method === 'PUT')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al actualizar el puesto');
    });
  });

  describe('removePosition', () => {
    it('should delete the row and drop it from the list', async () => {
      const seeded = firstValueFrom(service.getPositions(companyId));
      httpMock.expectOne((r) => r.url === positionsUrl).flush(mockPositions);
      await seeded;

      const promise = firstValueFrom(service.removePosition(companyId, 'pos-2'));
      httpMock
        .expectOne((r) => r.url === `${positionsUrl}/pos-2` && r.method === 'DELETE')
        .flush(null, { status: 204, statusText: 'No Content' });
      await promise;

      // Disable is a soft delete on the server; the list drops the row it no longer shows.
      expect(service.positions().map((p) => p.id)).toEqual(['pos-1']);
    });

    it('should set the error signal on failure', async () => {
      const promise = firstValueFrom(service.removePosition(companyId, 'pos-2'));
      httpMock
        .expectOne((r) => r.method === 'DELETE')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al deshabilitar el puesto');
    });
  });

  describe('enablePosition', () => {
    it('should patch enabled true and replace the row in the list', async () => {
      const seeded = firstValueFrom(service.getPositions(companyId));
      httpMock.expectOne((r) => r.url === positionsUrl).flush(mockPositions);
      await seeded;

      const reEnabled: Position = { ...mockPositions[1], enabled: true };
      const promise = firstValueFrom(service.enablePosition(companyId, 'pos-2'));

      const req = httpMock.expectOne(
        (r) => r.url === `${positionsUrl}/pos-2/enable` && r.method === 'PATCH',
      );
      expect(req.request.body).toEqual({ enabled: true });
      req.flush(reEnabled);

      expect(await promise).toEqual(reEnabled);
      expect(service.positions()[1].enabled).toBe(true);
    });

    it('should set the error signal on failure', async () => {
      const promise = firstValueFrom(service.enablePosition(companyId, 'pos-2'));
      httpMock
        .expectOne((r) => r.method === 'PATCH')
        .flush({ status: 404 }, { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al reactivar el puesto');
    });
  });

  describe('clearError', () => {
    it('should clear the error signal', async () => {
      const promise = firstValueFrom(service.getPositions(companyId));
      httpMock
        .expectOne((r) => r.url === positionsUrl)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(promise).rejects.toBeTruthy();

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });
});
