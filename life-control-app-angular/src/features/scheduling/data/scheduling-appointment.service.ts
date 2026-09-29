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
   * The error is rethrown untouched and the method owns no error/loading signal,
   * because the booking dialog owns its error state. It also sets no
   * `SKIP_ERROR_NOTIFICATION`, so a failed booking still reaches the global toast —
   * the app-wide net for anything the dialog does not map by name.
   */
  bookAppointment(request: SchedulingAppointmentBookingRequest): Observable<SchedulingAppointment> {
    return this.http.post<SchedulingAppointment>(this.appointmentsUrl, request);
  }
}
