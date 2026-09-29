/**
 * Wire models of the scheduling calendar.
 *
 * These mirror the merged HTTP contract field by field and add **no derived
 * field**: `available` is the server's own derivation (`capacity - booked`,
 * D21) and travels on the wire, so the client never recomputes it and cannot
 * disagree with the counter the grid renders.
 *
 * There is deliberately no `SchedulingCalendarEntry`-level `startAt` fallback:
 * a block's time is the **slot's** `startAt`/`endAt` (D62), and an appointment
 * carries no second time of its own.
 */

/**
 * One materialized bookable slot as `GET /api/scheduling/slots?activityId=`
 * returns it, inside the bare array response.
 *
 * That read is this domain's only **materializing** read (D16): calling it
 * inserts the slots the activity's availability implies for the range,
 * idempotently. It is the reason the calendar page must fan out before it reads
 * the projection at all (D50).
 */
export interface SchedulingSlot {
  id: string;
  activityId: string;
  /** ISO local wall-clock, no offset and no `Z` (`YYYY-MM-DDTHH:mm:ss`). */
  startAt: string;
  endAt: string;
  capacity: number;
  booked: number;
  /** `capacity - booked`, derived by the server and never stored. */
  available: number;
  status: string;
  enabled: boolean;
}

/**
 * One appointment inside a calendar entry.
 *
 * The projection carries **soft-deleted** appointments with `enabled = false`
 * (D36), because a soft-deleted appointment still holds capacity by status and
 * is still counted by the entry's `booked`; hiding it would make the calendar
 * contradict the counter it renders. `userId` is the attending employee's
 * Keycloak `sub` and is nullable (an activity may have none, G21).
 */
export interface SchedulingCalendarAppointment {
  id: string;
  userId: string | null;
  customerId: string | null;
  customerName: string | null;
  statusId: string;
  statusName: string;
  notes: string | null;
  enabled: boolean;
}

/**
 * One entry of `GET /api/scheduling/calendar`, which answers a **bare JSON
 * array** and never materializes (D33, G19).
 *
 * `activityEnabled` is exposed by the projection on purpose: the read never
 * filters by it (D37), so a retired activity's existing slots still render and
 * this flag is what lets the grid mark them as retired (D57).
 */
export interface SchedulingCalendarEntry {
  slotId: string;
  activityId: string;
  activityName: string;
  activityEnabled: boolean;
  /** ISO local wall-clock, no offset and no `Z` (`YYYY-MM-DDTHH:mm:ss`). */
  startAt: string;
  endAt: string;
  capacity: number;
  booked: number;
  /** `capacity - booked`, derived by the server. */
  available: number;
  status: string;
  appointments: SchedulingCalendarAppointment[];
}

/**
 * The visible range the week read materializes and then projects.
 *
 * `to` is **exclusive** (the server's `startAt >= from AND startAt < to`), so it
 * is always the next Monday `T00:00:00` of a Monday-anchored ISO week (D53).
 * `activityId` narrows both the fan-out and the projection when the filter is
 * active (D63); `null`/absent means "every enabled activity of the store".
 */
export interface SchedulingCalendarWeekRequest {
  storeId: string;
  from: string;
  to: string;
  activityId?: string | null;
}
