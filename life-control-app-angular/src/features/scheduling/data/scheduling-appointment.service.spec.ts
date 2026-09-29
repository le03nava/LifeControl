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
      // No SKIP_ERROR_NOTIFICATION token: a failed booking must still reach the
      // app-wide toast as the net for anything the dialog does not map by name.
      expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(false);
      req.flush(mockAppointment);
    });
  });
});
