/**
 * Wire models of the appointment booking lifecycle
 * (`POST /api/scheduling/appointments`).
 *
 * These mirror `SchedulingAppointmentResponse` field for field and add **no
 * derived field**: `startAt`/`endAt` are read from the referenced slot by the
 * server and `statusName` is resolved from `statusId` server-side, so the client
 * never recomputes either.
 */

/**
 * One appointment as the booking and lifecycle endpoints return it.
 *
 * `startAt`/`endAt` are the **slot's** window, not a second time the appointment
 * stores; `statusName` is the server's resolution of `statusId`, which stays the
 * client's handle for a later transition (W6c). `userId` and `customerId` are
 * nullable — an appointment may be booked without an assigned attendee. `version`
 * is the entity's optimistic-locking version.
 */
export interface SchedulingAppointment {
  id: string;
  slotId: string;
  /** ISO local wall-clock, no offset and no `Z` (`YYYY-MM-DDTHH:mm:ss`). */
  startAt: string;
  endAt: string;
  activityId: string;
  companyStoreId: string;
  userId: string | null;
  customerId: string | null;
  statusId: string;
  statusName: string;
  notes: string | null;
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * Request body of `POST /api/scheduling/appointments`.
 *
 * Deliberately carries **no** `customerId`: `GET /api/customers` is gated on
 * `hasAnyRole('lc-admin','lc-sales')`, so the only role that can book answers 403
 * on a customer read. Booking sends the slot, the optional attendee and the notes
 * only; do not add `customerId` back by reflex (D66).
 */
export interface SchedulingAppointmentBookingRequest {
  slotId: string;
  userId: string | null;
  notes: string | null;
}
