import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import {
  SchedulingAppointment,
  SchedulingAppointmentBookingRequest,
  SchedulingAppointmentRescheduleRequest,
  SchedulingAppointmentStatusRequest,
} from '../models/scheduling-appointment.models';

/**
 * HTTP access to `/api/scheduling/appointments`.
 *
 * Flat and store-derived, like the rest of the scheduling domain: the path carries
 * no store segment and the booking is addressed by the slot id in the body, the
 * store being derived from the slot's activity server-side.
 *
 * Unlike {@link SchedulingActivityService}, this service owns **no** error or
 * loading signal: the booking dialog owns its own error state. That contract holds
 * for **every** write here — booking, the status change, the reschedule and the
 * removal — not only for the booking (D75): each rethrows the error untouched, so
 * the caller can discriminate the failure by the server's own message, and none
 * sets a `SKIP_ERROR_NOTIFICATION` token, so the interceptor's global toast fires
 * alongside the caller's own banner (G36).
 */
@Injectable({
  providedIn: 'root',
})
export class SchedulingAppointmentService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private get appointmentsUrl(): string {
    return `${this.configService.apiUrl}/scheduling/appointments`;
  }

  /**
   * Books an appointment in the named slot: `POST /api/scheduling/appointments`
   * answers `201` with the created appointment, the store being derived from the
   * slot's activity.
   *
   * The error is rethrown untouched and the method owns no error/loading signal, so
   * the caller owns the error state and the dialog's banner is the message that names
   * the cause.
   *
   * It also sets no `SKIP_ERROR_NOTIFICATION`, so the interceptor's global toast fires
   * for **every** failed write, the ones the dialog maps by name included: a capacity
   * conflict shows the dialog's banner **and** the generic toast — two parallel
   * signals, not a fallback. That double signal is the app-wide write convention
   * (`scheduling-activity-edit` behaves the same way), and the record declares this
   * write-side duplicate-toast cost as a gap (**G36**; **G27** is its read-side twin
   * in the activity service) rather than silencing it; removing it, if ever wanted, is
   * a service-or-interceptor change and not this dialog's business.
   */
  bookAppointment(request: SchedulingAppointmentBookingRequest): Observable<SchedulingAppointment> {
    return this.http.post<SchedulingAppointment>(this.appointmentsUrl, request);
  }

  /**
   * Moves the appointment to the status named by the request's `statusId`:
   * `PATCH /api/scheduling/appointments/{id}/status` answers `200` with the
   * updated appointment.
   *
   * The server validates the status type and the transition edge and answers a
   * wrong status type with `400` and an illegal edge with `409`; the error is
   * rethrown untouched so the caller can discriminate it by the server's own
   * message, and this method owns no error or loading signal (D75).
   */
  updateStatus(
    id: string,
    request: SchedulingAppointmentStatusRequest,
  ): Observable<SchedulingAppointment> {
    return this.http.patch<SchedulingAppointment>(`${this.appointmentsUrl}/${id}/status`, request);
  }

  /**
   * Moves the appointment to the slot named by the request's `slotId`:
   * `PUT /api/scheduling/appointments/{id}` answers `200` with the updated
   * appointment, keeping its current status (D29).
   *
   * The server answers a terminal appointment or an unbookable destination with
   * `409`; the error is rethrown untouched and this method owns no error or
   * loading signal (D75).
   */
  reschedule(
    id: string,
    request: SchedulingAppointmentRescheduleRequest,
  ): Observable<SchedulingAppointment> {
    return this.http.put<SchedulingAppointment>(`${this.appointmentsUrl}/${id}`, request);
  }

  /**
   * Soft-deletes the appointment: `DELETE /api/scheduling/appointments/{id}`
   * answers `204`. A Scheduled or Confirmed appointment is also moved to Cancelled
   * and its capacity released; a terminal one keeps its status and its capacity
   * (E45).
   *
   * The error is rethrown untouched and this method owns no error or loading
   * signal (D75). The emission on success is the empty `204` response body, not a
   * value this service synthesizes.
   */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.appointmentsUrl}/${id}`);
  }
}
