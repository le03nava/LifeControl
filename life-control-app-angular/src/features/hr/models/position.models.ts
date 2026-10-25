import { FormControl } from '@angular/forms';

/**
 * Wire models of the company-scoped position catalog.
 *
 * `Position` and `PositionSalaryBand` were declared in `contract.models.ts`
 * because the contract dialog was the first screen to read the catalog; this
 * slice gives them the home the rest of the feature already follows — one model
 * file per resource (`department.models.ts`, `employee.models.ts`,
 * `scope-assignment.models.ts`). Nothing was duplicated: the interfaces moved,
 * and `contract.models.ts` no longer declares them.
 *
 * `SeniorityLevel` is *not* here: it is global reference data (record D1) and
 * lives in `seniority-level.models.ts` beside the other non-company-scoped
 * shapes.
 *
 * There is no `version` column on these tables (record T2), so no model here
 * carries a precondition and no `If-Match`/412 path exists downstream.
 */
export interface Position {
  id: string;
  companyId: string;
  departmentId: string;
  positionCode: string;
  positionName: string;
  description: string | null;
  reportsToPositionId: string | null;
  displayOrder: number | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Write payload for a position, shared by create and update.
 *
 * Mirrors `PositionRequest`, which is one record for both operations because
 * the catalog has no `version` column and therefore no 412 precondition (T2).
 * `departmentId`, `positionCode` and `positionName` are required; `enabled` is
 * optional and the backend defaults it to `true`.
 */
export interface PositionRequest {
  departmentId: string;
  positionCode: string;
  positionName: string;
  description?: string | null;
  reportsToPositionId?: string | null;
  displayOrder?: number | null;
  enabled?: boolean;
}

/**
 * Read model of a position's salary band for one seniority level.
 *
 * The read returns disabled rows too (record D13): "no band configured" is
 * `enabled = false` rather than a missing row, so the editor must keep the row
 * it is handed instead of rebuilding the set from the catalog.
 */
export interface PositionSalaryBand {
  id: string;
  positionId: string;
  seniorityLevelId: string;
  minimumSalary: number;
  maximumSalary: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Typed control map for the position edit form's self-contained `FormGroup`.
 *
 * `departmentId` is required because a position always belongs to exactly one
 * department (T11). `reportsToPositionId` is nullable — "no direct manager" is
 * a legal state, and the API models it as an absent value (T5). `displayOrder`
 * is nullable for the same reason `DepartmentControl.displayOrder` is: an empty
 * input must not become `NaN`.
 */
export interface PositionControl {
  departmentId: FormControl<string>;
  positionCode: FormControl<string>;
  positionName: FormControl<string>;
  description: FormControl<string>;
  reportsToPositionId: FormControl<string | null>;
  displayOrder: FormControl<number | null>;
  enabled: FormControl<boolean>;
}

/**
 * One band of the settings editor, as the save submits it.
 *
 * Mirrors `PositionSalaryBandRequest`, which carries **no `enabled` field** by
 * design: presence in the request list means enabled and omission means
 * disabled, so "clear this level" has exactly one spelling (T10/D13) and the
 * wire cannot accept an item the omission rule would immediately clear.
 */
export interface PositionSalaryBandRequest {
  seniorityLevelId: string;
  minimumSalary: number;
  maximumSalary: number;
}

/**
 * Write payload of the position's **complete** band set.
 *
 * The API replaces the position's whole set with this list: every submitted
 * `(positionId, seniorityLevelId)` is upserted in place and every stored key the
 * request omits is set `enabled = false` and **kept** (D13), never deleted. An
 * empty list therefore clears the position's bands without removing a row.
 */
export interface PositionSalaryBandsRequest {
  bands: PositionSalaryBandRequest[];
}

/**
 * Typed control map of one row of the settings editor.
 *
 * Both amounts are nullable because the empty case is legal: a row with **no**
 * amount is the level the operator wants without a band, and it is omitted from
 * the save (which is what disables a previously stored one). A row with **one**
 * amount is the invalid case the group validator refuses, so a half-filled row
 * can never be silently dropped. There is no `enabled`/`configured` control on
 * purpose: the wire has no such field, and a stored disabled row is not
 * prefilled, so "this level has no band" and "this band is disabled" cannot be
 * confused by a save that only touches the amounts.
 */
export interface PositionSalaryBandControl {
  minimumSalary: FormControl<number | null>;
  maximumSalary: FormControl<number | null>;
}

/**
 * Read model of one row of a position's role template.
 *
 * `enabled` is part of the contract (T20): the read returns disabled rows too, so
 * the editor can tell an explicitly cleared role from one that was never
 * configured. A row with `enabled = false` is therefore **not** checked in the
 * editor and is omitted from the next save — which is what keeps it disabled.
 */
export interface PositionRole {
  id: string;
  positionId: string;
  roleName: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * One role of the template, as the save submits it.
 *
 * Mirrors `PositionRoleRequest`, which carries the name and **no `enabled` field**:
 * presence in the request list means enabled and omission means disabled, exactly
 * as the salary bands work (T20 mirroring D13).
 */
export interface PositionRoleRequest {
  roleName: string;
}

/**
 * Write payload of the position's **complete** role template.
 *
 * Every submitted name is upserted on `(positionId, roleName)` and every stored
 * name the request omits is set `enabled = false` and kept — never deleted (T20).
 * A name that is not in the frozen allowlist is a 400, which is why the editor
 * draws its candidate set from `PositionGrantableRolesResponse` instead of a copy
 * of its own (D14/D19).
 */
export interface PositionRolesRequest {
  roles: PositionRoleRequest[];
}

/**
 * The roles a position template may grant: the frozen allowlist of D7/D14, read
 * from the API so the checkboxes and the save's own validation share one owner.
 */
export interface PositionGrantableRolesResponse {
  roles: string[];
}
