import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import {
  SchedulingAppointment,
  SchedulingAppointmentBookingRequest,
} from '../models/scheduling-appointment.models';

/**
 * HTTP access to `/api/scheduling/appointments`.
 *
 * Flat and store-derived, like the rest of the scheduling domain: the path carries
 * no store segment and the booking is addressed by the slot id in the body, the
 * store being derived from the slot's activity server-side.
 *
 * Unlike {@link SchedulingActivityService}, this service owns **no** error or
 * loading signal: the booking dialog owns its own error state.
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
}
