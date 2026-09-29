import { FormArray, FormControl, FormGroup } from '@angular/forms';

/**
 * A scheduling activity as returned by `GET /api/scheduling/activities`.
 *
 * All JSON is camelCase. `version` is the entity's optimistic-locking version and
 * always travels back so the edit page can echo it in a later `PUT` and detect a
 * lost update with a 412 instead of silently overwriting another writer.
 */
export interface SchedulingActivity {
  id: string;
  companyStoreId: string;
  userId: string | null;
  activityName: string;
  description: string | null;
  durationMinutes: number;
  capacityPerSlot: number;
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * Feature-local Spring `Page` envelope.
 *
 * Declared per feature on purpose: `products`, `sales`, `purchases`, `companies`
 * and `products/suppliers` each declare their own copy and none share it, so
 * introducing a shared `Page<T>` would be a cross-feature refactor.
 */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

/**
 * Request body of `POST` and `PUT /api/scheduling/activities`.
 *
 * Each optional field is optional because the backend treats its absence
 * differently, not because every caller may omit it:
 *
 * - `companyStoreId` is **required on create** and **ignored on update**, so the
 *   form sends it on create only;
 * - `enabled` is deliberately never sent from the form: the flag is owned by
 *   `PATCH /{id}/enable` and `DELETE /{id}`;
 * - `version` is consumed by `PUT` only and is the optional precondition.
 */
export interface SchedulingActivityRequest {
  companyStoreId?: string | null;
  userId?: string | null;
  activityName: string;
  description?: string | null;
  durationMinutes: number;
  capacityPerSlot: number;
  enabled?: boolean;
  version?: number;
}

/**
 * The fields the form edits, before the page turns them into a request.
 */
export interface SchedulingActivityFormValue {
  activityName: string;
  description: string | null;
  durationMinutes: number;
  capacityPerSlot: number;
  userId: string | null;
}

/**
 * Typed control map of the activity form.
 *
 * `description` and `userId` are nullable because the backend overwrites both on
 * update — including with `null` — so an empty box must send `null`, not an
 * omitted key.
 */
export interface SchedulingActivityControl {
  activityName: FormControl<string>;
  description: FormControl<string | null>;
  durationMinutes: FormControl<number>;
  capacityPerSlot: FormControl<number>;
  userId: FormControl<string | null>;
}

/**
 * Server cap on one availability request set (`@Size(max = 50)` on
 * `SchedulingAvailabilityRequest`), mirrored by the editor.
 */
export const MAX_AVAILABILITY_WINDOWS = 50;

/**
 * One availability window as the API returns it in `GET`/`PUT .../availability`.
 *
 * `id` is the database row id. The row is deleted and re-inserted on every save,
 * so it changes after every `PUT` and is **not** a stable identity; the request
 * does not accept it. `startTime`/`endTime` are `"HH:mm:ss"` and
 * `validFrom`/`validTo` are `"yyyy-MM-dd"`. `dayOfWeek` is ISO-8601 `1..7` with
 * `MONDAY = 1`. The response exposes no `enabled` field.
 */
export interface SchedulingAvailabilityWindow {
  id: string;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  validFrom: string;
  validTo: string;
}

/** The whole availability template of one activity, in the server's canonical order. */
export interface SchedulingAvailabilityResponse {
  activityId: string;
  windows: SchedulingAvailabilityWindow[];
}

/**
 * One availability window of a request body.
 *
 * Deliberately carries **no `id`** and no `enabled`: the endpoint replaces the
 * whole set, the stored row is regenerated on every save, and the response never
 * exposes an enabled flag.
 */
export interface SchedulingAvailabilityWindowRequest {
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  validFrom: string;
  validTo: string;
}

/** Request body of `PUT /api/scheduling/activities/{id}/availability`. */
export interface SchedulingAvailabilityRequest {
  windows: SchedulingAvailabilityWindowRequest[];
}

/**
 * One editor row as held in component state.
 *
 * `key` is a client-side local identity and never travels on the wire (D44): the
 * server's window `id` is not stable across saves, so the UI keys its rows with a
 * client-generated value that re-seeding cannot collide with. `startTime`/
 * `endTime` are native `<input type="time">` values (`"HH:mm"`); the conversion
 * to and from the API strings lives in `scheduling-availability-wire.ts`.
 */
export interface SchedulingAvailabilityRowValue {
  key: string;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  validFrom: string;
  validTo: string;
}

/** Typed control map of one availability window row. */
export interface SchedulingAvailabilityRowControl {
  key: FormControl<string>;
  dayOfWeek: FormControl<number>;
  startTime: FormControl<string>;
  endTime: FormControl<string>;
  validFrom: FormControl<string>;
  validTo: FormControl<string>;
}

/** Typed control map of the availability editor form. */
export interface SchedulingAvailabilityFormControl {
  windows: FormArray<FormGroup<SchedulingAvailabilityRowControl>>;
}
