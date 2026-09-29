import { FormControl } from '@angular/forms';

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

/** The fields the form edits, before the page turns them into a request. */
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
