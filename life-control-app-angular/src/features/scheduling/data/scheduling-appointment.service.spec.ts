import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { SKIP_ERROR_NOTIFICATION } from '@shared/data';
import { SchedulingAppointmentService } from './scheduling-appointment.service';
import {
  SchedulingAppointment,
  SchedulingAppointmentBookingRequest,
  SchedulingAppointmentRescheduleRequest,
  SchedulingAppointmentStatusRequest,
} from '../models/scheduling-appointment.models';

describe('SchedulingAppointmentService', () => {
  let service: SchedulingAppointmentService;
  let httpMock: HttpTestingController;

  const base = 'http://api.test/api/scheduling/appointments';

  const mockAppointment: SchedulingAppointment = {
    id: 'appointment-1',
    slotId: 'slot-1',
    startAt: '2026-09-28T09:00:00',
    endAt: '2026-09-28T10:00:00',
    activityId: 'activity-1',
    companyStoreId: 'store-1',
    userId: 'user-1',
    customerId: null,
    statusId: 'status-1',
    statusName: 'Scheduled',
    notes: 'Primera visita',
    enabled: true,
    version: 0,
    createdAt: '2026-09-28T08:00:00',
    updatedAt: '2026-09-28T08:00:00',
  };

  const bookingRequest: SchedulingAppointmentBookingRequest = {
    slotId: 'slot-1',
    userId: 'user-1',
    notes: 'Primera visita',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        SchedulingAppointmentService,
        { provide: ConfigService, useValue: { apiUrl: 'http://api.test/api' } },
      ],
    });
    service = TestBed.inject(SchedulingAppointmentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('bookAppointment', () => {
    it('should POST to the appointments endpoint', () => {
      service.bookAppointment(bookingRequest).subscribe({ error: () => undefined });

      const req = httpMock.expectOne(base);
      expect(req.request.method).toBe('POST');
      expect(req.request.url).toBe(base);
      req.flush(mockAppointment);
    });

    it('should send exactly slotId, userId and notes, and never a customerId', () => {
      service.bookAppointment(bookingRequest).subscribe({ error: () => undefined });

      const req = httpMock.expectOne(base);
      // D66: the only role that can book answers 403 on `GET /api/customers`, so
      // the body must not carry a customerId it cannot have resolved. This is
      // asserted before the whole-body equality so the guard is self-standing.
      expect(req.request.body).not.toHaveProperty('customerId');
      expect(Object.keys(req.request.body).sort()).toEqual(['notes', 'slotId', 'userId']);
      expect(req.request.body).toEqual(bookingRequest);
      req.flush(mockAppointment);
    });

    it('should return the created appointment as the response', async () => {
      const promise = firstValueFrom(service.bookAppointment(bookingRequest));

      const req = httpMock.expectOne(base);
      req.flush(mockAppointment);

      const appointment = await promise;
      expect(appointment).toEqual(mockAppointment);
      expect(appointment.statusName).toBe('Scheduled');
    });

    it('should rethrow a failed booking instead of swallowing it', () => {
      let caught: unknown;
      service.bookAppointment(bookingRequest).subscribe({
        error: (err: unknown) => {
          caught = err;
        },
      });

      httpMock
        .expectOne(base)
        .flush({ message: 'Slot full' }, { status: 409, statusText: 'Conflict' });

      // "Rethrown untouched" is the claim under test: the caller must receive the
      // original HttpErrorResponse, not a wrapper that hides the status.
      expect(caught).toBeInstanceOf(HttpErrorResponse);
      const httpError = caught as HttpErrorResponse;
      expect(httpError.status).toBe(409);
      expect(httpError.error).toEqual({ message: 'Slot full' });
    });

    it('should leave the global error notification enabled, since the dialog owns only its own state', () => {
      service.bookAppointment(bookingRequest).subscribe({ error: () => undefined });

      const req = httpMock.expectOne(base);
      // No SKIP_ERROR_NOTIFICATION token: the interceptor's global toast fires for
      // every failed write, the ones the dialog maps by name included, so a failed
      // booking shows the dialog's banner and the generic toast in parallel. That
      // double signal is the app-wide write convention (the merged
      // `scheduling-activity-edit` behaves the same way) and the record declares it
      // as a gap (G36; G27 is its read-side twin) rather than silencing it.
      expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(false);
      req.flush(mockAppointment);
    });
  });

  describe('updateStatus', () => {
    const statusRequest: SchedulingAppointmentStatusRequest = { statusId: 'status-confirmed' };

    it('should PATCH the status sub-path with exactly { statusId }', () => {
      service.updateStatus('appointment-1', statusRequest).subscribe({ error: () => undefined });

      const req = httpMock.expectOne(`${base}/appointment-1/status`);
      expect(req.request.method).toBe('PATCH');
      // The field is `statusId`, never a name: the server resolves by id and rejects
      // a status of the wrong type with 400 (E42). Object.keys pins that no second
      // field travels.
      expect(Object.keys(req.request.body)).toEqual(['statusId']);
      expect(req.request.body).toEqual({ statusId: 'status-confirmed' });
      req.flush({ ...mockAppointment, statusId: 'status-confirmed', statusName: 'Confirmed' });
    });

    it('should return the updated appointment as the response', async () => {
      const promise = firstValueFrom(service.updateStatus('appointment-1', statusRequest));

      const updated = { ...mockAppointment, statusId: 'status-confirmed', statusName: 'Confirmed' };
      httpMock.expectOne(`${base}/appointment-1/status`).flush(updated);

      await expect(promise).resolves.toEqual(updated);
    });

    it('should leave the global error notification enabled, since the service owns no error signal', () => {
      service.updateStatus('appointment-1', statusRequest).subscribe({ error: () => undefined });

      const req = httpMock.expectOne(`${base}/appointment-1/status`);
      // D75 covers every write, not only booking: the method sets no
      // SKIP_ERROR_NOTIFICATION token, so the interceptor's global toast is not silenced.
      expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(false);
      req.flush(mockAppointment);
    });

    it('should rethrow a 409 illegal-transition error untouched', () => {
      let caught: unknown;
      service.updateStatus('appointment-1', statusRequest).subscribe({
        error: (err: unknown) => {
          caught = err;
        },
      });

      httpMock
        .expectOne(`${base}/appointment-1/status`)
        .flush(
          { message: 'Invalid status transition: Scheduled -> Completed' },
          { status: 409, statusText: 'Conflict' },
        );

      // Nothing catches, rewraps or swallows the error (D75), so the caller receives the
      // original HttpErrorResponse and can map the server's own message.
      expect(caught).toBeInstanceOf(HttpErrorResponse);
      const httpError = caught as HttpErrorResponse;
      expect(httpError.status).toBe(409);
      expect(httpError.error).toEqual({
        message: 'Invalid status transition: Scheduled -> Completed',
      });
    });
  });

  describe('reschedule', () => {
    const rescheduleRequest: SchedulingAppointmentRescheduleRequest = { slotId: 'slot-2' };

    it('should PUT the appointment path with exactly { slotId }', () => {
      service.reschedule('appointment-1', rescheduleRequest).subscribe({ error: () => undefined });

      const req = httpMock.expectOne(`${base}/appointment-1`);
      expect(req.request.method).toBe('PUT');
      expect(Object.keys(req.request.body)).toEqual(['slotId']);
      expect(req.request.body).toEqual({ slotId: 'slot-2' });
      req.flush({ ...mockAppointment, slotId: 'slot-2' });
    });

    it('should leave the global error notification enabled, since the service owns no error signal', () => {
      service.reschedule('appointment-1', rescheduleRequest).subscribe({ error: () => undefined });

      const req = httpMock.expectOne(`${base}/appointment-1`);
      expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(false);
      req.flush(mockAppointment);
    });

    it('should rethrow a 409 full-destination error untouched', () => {
      let caught: unknown;
      service.reschedule('appointment-1', rescheduleRequest).subscribe({
        error: (err: unknown) => {
          caught = err;
        },
      });

      httpMock.expectOne(`${base}/appointment-1`).flush(
        {
          message: 'Scheduling slot slot-2 is not bookable: it is full (booked 3 of capacity 3)',
        },
        { status: 409, statusText: 'Conflict' },
      );

      expect(caught).toBeInstanceOf(HttpErrorResponse);
      expect((caught as HttpErrorResponse).status).toBe(409);
    });

    it('should rethrow a 404 missing-appointment error untouched', () => {
      let caught: unknown;
      service.reschedule('appointment-1', rescheduleRequest).subscribe({
        error: (err: unknown) => {
          caught = err;
        },
      });

      httpMock
        .expectOne(`${base}/appointment-1`)
        .flush(
          { message: 'Scheduling appointment appointment-1 not found' },
          { status: 404, statusText: 'Not Found' },
        );

      expect(caught).toBeInstanceOf(HttpErrorResponse);
      const httpError = caught as HttpErrorResponse;
      expect(httpError.status).toBe(404);
      expect(httpError.error).toEqual({
        message: 'Scheduling appointment appointment-1 not found',
      });
    });
  });

  describe('remove', () => {
    it('should DELETE the appointment path and complete on a 204', async () => {
      const promise = firstValueFrom(service.remove('appointment-1'));

      const req = httpMock.expectOne(`${base}/appointment-1`);
      expect(req.request.method).toBe('DELETE');
      expect(req.request.body).toBeNull();
      req.flush(null, { status: 204, statusText: 'No Content' });

      // `HttpClient.delete<void>` is typed `void` but emits the parsed empty body, which is
      // `null` at runtime: this asserts the framework's behaviour, not a contract of ours.
      await expect(promise).resolves.toBeNull();
    });

    it('should leave the global error notification enabled, since the service owns no error signal', () => {
      service.remove('appointment-1').subscribe({ error: () => undefined });

      const req = httpMock.expectOne(`${base}/appointment-1`);
      expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(false);
      req.flush(null, { status: 204, statusText: 'No Content' });
    });

    it('should rethrow a 404 missing-appointment error untouched', () => {
      let caught: unknown;
      service.remove('appointment-1').subscribe({
        error: (err: unknown) => {
          caught = err;
        },
      });

      // DELETE never answers 409 (E44), so the reachable failure this method must not
      // swallow is the 404 for a missing appointment.
      httpMock
        .expectOne(`${base}/appointment-1`)
        .flush(
          { message: 'Scheduling appointment appointment-1 not found' },
          { status: 404, statusText: 'Not Found' },
        );

      expect(caught).toBeInstanceOf(HttpErrorResponse);
      const httpError = caught as HttpErrorResponse;
      expect(httpError.status).toBe(404);
      expect(httpError.error).toEqual({
        message: 'Scheduling appointment appointment-1 not found',
      });
    });
  });
});
