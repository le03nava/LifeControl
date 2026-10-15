/// <reference types="vitest/globals" />
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { SeniorityLevelService } from './seniority-level.service';
import { ConfigService } from '@app/services/config.service';
import { SeniorityLevel, SeniorityLevelRequest } from '../models/seniority-level.models';

describe('SeniorityLevelService', () => {
  let service: SeniorityLevelService;
  let httpMock: HttpTestingController;

  const baseUrl = 'http://localhost:9000/api';
  const levelsUrl = `${baseUrl}/seniority-levels`;

  const mockLevels: SeniorityLevel[] = [
    {
      id: 'lvl-1',
      levelCode: 'JUN',
      levelName: 'Junior',
      rank: 1,
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
    {
      id: 'lvl-2',
      levelCode: 'SEN',
      levelName: 'Senior',
      rank: 2,
      enabled: false,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
  ];

  const request: SeniorityLevelRequest = {
    levelCode: 'LID',
    levelName: 'Líder',
    rank: 3,
    enabled: true,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [SeniorityLevelService, { provide: ConfigService, useValue: { apiUrl: baseUrl } }],
    });
    service = TestBed.inject(SeniorityLevelService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getSeniorityLevels', () => {
    it('should read the global catalog with includeDisabled false by default', async () => {
      const promise = firstValueFrom(service.getSeniorityLevels());

      const req = httpMock.expectOne((r) => r.url === levelsUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockLevels);

      expect(await promise).toEqual(mockLevels);
      expect(service.levels()).toEqual(mockLevels);
    });

    it('should forward includeDisabled true to the request', async () => {
      const promise = firstValueFrom(service.getSeniorityLevels(true));

      const req = httpMock.expectOne((r) => r.url === levelsUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(mockLevels);

      await promise;
    });

    it('should set the error signal on failure', async () => {
      const promise = firstValueFrom(service.getSeniorityLevels());
      httpMock
        .expectOne((r) => r.url === levelsUrl)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar los niveles de antigüedad');
    });

    it('should clear the error signal on a successful read after a failure', async () => {
      const failing = firstValueFrom(service.getSeniorityLevels());
      httpMock
        .expectOne((r) => r.url === levelsUrl)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(failing).rejects.toBeTruthy();

      const succeeding = firstValueFrom(service.getSeniorityLevels());
      httpMock.expectOne((r) => r.url === levelsUrl).flush(mockLevels);
      await succeeding;

      expect(service.error()).toBeNull();
    });
  });

  describe('getSeniorityLevel', () => {
    it('should not replace the list signal with the single row', async () => {
      const list = firstValueFrom(service.getSeniorityLevels());
      httpMock.expectOne((r) => r.url === levelsUrl).flush(mockLevels);
      await list;

      const single = firstValueFrom(service.getSeniorityLevel('lvl-1'));
      httpMock.expectOne((r) => r.url === `${levelsUrl}/lvl-1`).flush(mockLevels[0]);
      await single;

      expect(service.levels()).toEqual(mockLevels);
    });

    it('should set the error signal on failure', async () => {
      const promise = firstValueFrom(service.getSeniorityLevel('lvl-9'));
      httpMock
        .expectOne((r) => r.url === `${levelsUrl}/lvl-9`)
        .flush({ status: 404 }, { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar el nivel de antigüedad');
    });
  });

  describe('writes', () => {
    it('should post the request and append the created level', async () => {
      const seeded = firstValueFrom(service.getSeniorityLevels());
      httpMock.expectOne((r) => r.url === levelsUrl).flush([mockLevels[0]]);
      await seeded;

      const created: SeniorityLevel = { ...mockLevels[1], id: 'lvl-3' };
      const promise = firstValueFrom(service.addSeniorityLevel(request));

      const req = httpMock.expectOne((r) => r.url === levelsUrl && r.method === 'POST');
      expect(req.request.body).toEqual(request);
      req.flush(created);

      expect(await promise).toEqual(created);
      expect(service.levels().map((l) => l.id)).toEqual(['lvl-1', 'lvl-3']);
    });

    it('should name the duplicate conflict instead of a generic failure', async () => {
      const promise = firstValueFrom(service.addSeniorityLevel(request));
      httpMock
        .expectOne((r) => r.url === levelsUrl && r.method === 'POST')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Ya existe un nivel de antigüedad con ese código, nombre o rango');
    });

    it('should put the request and replace the row in the list', async () => {
      const seeded = firstValueFrom(service.getSeniorityLevels());
      httpMock.expectOne((r) => r.url === levelsUrl).flush(mockLevels);
      await seeded;

      const updated: SeniorityLevel = { ...mockLevels[0], levelName: 'Junior avanzado' };
      const promise = firstValueFrom(service.updateSeniorityLevel('lvl-1', request));

      const req = httpMock.expectOne((r) => r.url === `${levelsUrl}/lvl-1` && r.method === 'PUT');
      expect(req.request.body).toEqual(request);
      req.flush(updated);

      expect(await promise).toEqual(updated);
      expect(service.levels()[0].levelName).toBe('Junior avanzado');
      expect(service.levels()).toHaveLength(2);
    });

    it('should soft-delete the row and drop it from the list', async () => {
      const seeded = firstValueFrom(service.getSeniorityLevels());
      httpMock.expectOne((r) => r.url === levelsUrl).flush(mockLevels);
      await seeded;

      const promise = firstValueFrom(service.removeSeniorityLevel('lvl-2'));
      httpMock
        .expectOne((r) => r.url === `${levelsUrl}/lvl-2` && r.method === 'DELETE')
        .flush(null, { status: 204, statusText: 'No Content' });
      await promise;

      expect(service.levels().map((l) => l.id)).toEqual(['lvl-1']);
    });

    it('should patch enabled true and replace the row in the list', async () => {
      const seeded = firstValueFrom(service.getSeniorityLevels());
      httpMock.expectOne((r) => r.url === levelsUrl).flush(mockLevels);
      await seeded;

      const reEnabled: SeniorityLevel = { ...mockLevels[1], enabled: true };
      const promise = firstValueFrom(service.enableSeniorityLevel('lvl-2'));

      const req = httpMock.expectOne(
        (r) => r.url === `${levelsUrl}/lvl-2/enable` && r.method === 'PATCH',
      );
      expect(req.request.body).toEqual({ enabled: true });
      req.flush(reEnabled);

      expect(await promise).toEqual(reEnabled);
      expect(service.levels()[1].enabled).toBe(true);
    });

    it('should set the error signal on a failed remove', async () => {
      const promise = firstValueFrom(service.removeSeniorityLevel('lvl-2'));
      httpMock
        .expectOne((r) => r.method === 'DELETE')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al deshabilitar el nivel de antigüedad');
    });
  });

  describe('clearError', () => {
    it('should clear the error signal', async () => {
      const promise = firstValueFrom(service.getSeniorityLevels());
      httpMock
        .expectOne((r) => r.url === levelsUrl)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(promise).rejects.toBeTruthy();

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });
});
