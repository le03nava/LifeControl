import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { SchedulingActivityService } from './scheduling-activity.service';
import {
  Page,
  SchedulingActivity,
  SchedulingActivityRequest,
} from '../models/scheduling-activity.models';

describe('SchedulingActivityService', () => {
  let service: SchedulingActivityService;
  let httpMock: HttpTestingController;

  const base = 'http://api.test/api/scheduling/activities';

  const mockActivity: SchedulingActivity = {
    id: 'activity-1',
    companyStoreId: 'store-1',
    userId: 'user-1',
    activityName: 'Yoga',
    description: 'Clase de yoga',
    durationMinutes: 60,
    capacityPerSlot: 8,
    enabled: true,
    version: 0,
    createdAt: '2026-09-28T12:34:56.789',
    updatedAt: '2026-09-28T12:34:56.789',
  };

  const mockPage: Page<SchedulingActivity> = {
    content: [mockActivity],
    totalElements: 1,
    totalPages: 1,
    size: 12,
    number: 0,
    first: true,
    last: true,
    empty: false,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        SchedulingActivityService,
        { provide: ConfigService, useValue: { apiUrl: 'http://api.test/api' } },
      ],
    });
    service = TestBed.inject(SchedulingActivityService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('listActivities', () => {
    it('should GET the store-scoped list with exactly the documented query params', async () => {
      const promise = firstValueFrom(service.listActivities('store-1'));

      const req = httpMock.expectOne((r) => r.url === base && r.method === 'GET');
      expect(req.request.params.get('storeId')).toBe('store-1');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('12');
      // The endpoint has no `search`, and no client `sort` behaviour is relied on.
      expect(req.request.params.has('search')).toBe(false);
      expect(req.request.params.has('sort')).toBe(false);
      req.flush(mockPage);

      const page = await promise;
      expect(page).toEqual(mockPage);
      expect(page.content[0].activityName).toBe('Yoga');
    });

    it('should forward page, size and includeDisabled', async () => {
      const promise = firstValueFrom(service.listActivities('store-2', 2, 24, true));

      const req = httpMock.expectOne((r) => r.url === base);
      expect(req.request.params.get('storeId')).toBe('store-2');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('24');
      req.flush({ ...mockPage, number: 2, size: 24 });

      const page = await promise;
      expect(page.number).toBe(2);
    });
  });

  describe('getActivityById', () => {
    it('should GET a single activity by id', async () => {
      const promise = firstValueFrom(service.getActivityById('activity-1'));

      const req = httpMock.expectOne(`${base}/activity-1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockActivity);

      const activity = await promise;
      expect(activity).toEqual(mockActivity);
    });
  });

  describe('createActivity', () => {
    it('should POST the request body and clear the loading flag', async () => {
      const request: SchedulingActivityRequest = {
        companyStoreId: 'store-1',
        activityName: 'Yoga',
        description: null,
        durationMinutes: 60,
        capacityPerSlot: 8,
        userId: null,
      };

      const promise = firstValueFrom(service.createActivity(request));

      const req = httpMock.expectOne(base);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      expect(service.loading()).toBe(true);
      req.flush(mockActivity);

      await promise;
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });

    it('should record a friendly error and rethrow on failure', () => {
      let caught: unknown;
      service
        .createActivity({ activityName: 'Yoga', durationMinutes: 60, capacityPerSlot: 8 })
        .subscribe({
          error: (err: unknown) => {
            caught = err;
          },
        });

      const req = httpMock.expectOne(base);
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(caught).toBeTruthy();
      expect(service.error()).toBe('Error al crear la actividad');
      expect(service.loading()).toBe(false);
    });
  });

  describe('updateActivity', () => {
    it('should PUT the request body to the id URL', async () => {
      const request: SchedulingActivityRequest = {
        activityName: 'Yoga avanzado',
        description: null,
        durationMinutes: 90,
        capacityPerSlot: 10,
        userId: null,
        version: 3,
      };

      const promise = firstValueFrom(service.updateActivity('activity-1', request));

      const req = httpMock.expectOne(`${base}/activity-1`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush({ ...mockActivity, activityName: 'Yoga avanzado', version: 4 });

      const updated = await promise;
      expect(updated.version).toBe(4);
    });

    it('should record a friendly error on update failure', () => {
      service
        .updateActivity('activity-1', {
          activityName: 'Yoga',
          durationMinutes: 60,
          capacityPerSlot: 8,
        })
        .subscribe({
          error: () => undefined,
        });

      httpMock
        .expectOne(`${base}/activity-1`)
        .flush({}, { status: 412, statusText: 'Precondition Failed' });

      expect(service.error()).toBe('Error al actualizar la actividad');
    });
  });

  describe('enableActivity', () => {
    it('should PATCH the enable suffix with no body', async () => {
      const promise = firstValueFrom(service.enableActivity('activity-1'));

      const req = httpMock.expectOne(`${base}/activity-1/enable`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toBeNull();
      req.flush({ ...mockActivity, enabled: true });

      const enabled = await promise;
      expect(enabled.enabled).toBe(true);
    });
  });

  describe('disableActivity', () => {
    it('should DELETE the activity (soft delete)', async () => {
      const promise = firstValueFrom(service.disableActivity('activity-1'));

      const req = httpMock.expectOne(`${base}/activity-1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null, { status: 204, statusText: 'No Content' });

      await promise;
    });

    it('should record a friendly error on disable failure', () => {
      service.disableActivity('activity-1').subscribe({ error: () => undefined });

      httpMock.expectOne(`${base}/activity-1`).flush({}, { status: 403, statusText: 'Forbidden' });

      expect(service.error()).toBe('Error al deshabilitar la actividad');
    });
  });

  describe('clearError', () => {
    it('should clear the error signal', () => {
      service.disableActivity('activity-1').subscribe({ error: () => undefined });
      httpMock.expectOne(`${base}/activity-1`).flush({}, { status: 500, statusText: 'Error' });
      expect(service.error()).not.toBeNull();

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });
});
