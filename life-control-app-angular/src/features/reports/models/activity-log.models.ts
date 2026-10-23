/**
 * Wire shape of one `activity_logs` row, as `GET /api/activity-logs` serializes it.
 *
 * `username` is the actor's display name captured at write time and `userId` its
 * Keycloak subject; both are `null`-able because an entry may outlive the
 * account it names (the log is immutable). `payloadJson` and `userAgent` travel
 * on the wire but the list does not render them.
 */
export interface ActivityLogEntry {
  id: string;
  userId: string | null;
  username: string | null;
  process: string;
  event: string;
  httpMethod: string;
  httpStatus: number;
  requestPath: string;
  ipAddress: string | null;
  userAgent: string | null;
  payloadJson: string | null;
  createdAt: string;
}

/**
 * A Spring Data `Page<ActivityLogResponse>`, which is what the read answers.
 *
 * The page is **server-side**: `number`/`size` are the window the API applied and
 * `totalElements` is what the paginator's `length` renders.
 */
export interface ActivityLogPage {
  content: ActivityLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

/**
 * The six filters the endpoint accepts — exactly these, no more.
 *
 * `from`/`to` are ISO dates (`yyyy-MM-dd`). Every field is optional and a blank
 * one is never sent, so an empty object reads the unfiltered trail.
 */
export interface ActivityLogFilters {
  from?: string;
  to?: string;
  process?: string;
  event?: string;
  userId?: string;
  httpMethod?: string;
}
