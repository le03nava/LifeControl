import { FormControl } from '@angular/forms';

/**
 * Wire model of the global seniority-level catalog.
 *
 * Global reference data, not company-scoped (record D1): it follows
 * `measure_units`/`payment_methods`, so its path carries no `companyId`, it has
 * no `ScopeLevel` role and disabling a level affects every company. The
 * per-company policy is the salary band, which is scoped through its position.
 *
 * Moved here from `contract.models.ts`, where it lived only because the
 * contract dialog read it first; one model file per resource is this feature's
 * convention.
 */
export interface SeniorityLevel {
  id: string;
  levelCode: string;
  levelName: string;
  rank: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Write payload for a seniority level, shared by create and update.
 *
 * Mirrors `SeniorityLevelRequest`: `levelCode`, `levelName` and `rank` are
 * required and `enabled` is optional, defaulting to `true` on the server. `rank`
 * is the ordering fact the salary-band editor reads the global catalog by, so
 * it is not an optional decoration.
 */
export interface SeniorityLevelRequest {
  levelCode: string;
  levelName: string;
  rank: number;
  enabled?: boolean;
}

/**
 * Typed control map for the seniority-level edit form's `FormGroup`.
 *
 * `rank` is required and nullable at once for the same reason the other
 * optional numbers in this feature are: an empty input must not become `NaN`.
 * Here the empty case is not "no value" but "invalid", which is why the form
 * marks it required and `min(1)` — the API accepts no rank below 1.
 */
export interface SeniorityLevelControl {
  levelCode: FormControl<string>;
  levelName: FormControl<string>;
  rank: FormControl<number | null>;
  enabled: FormControl<boolean>;
}
