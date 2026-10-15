/**
 * Wire models of the company-scoped position catalog.
 *
 * `Position` and `PositionSalaryBand` were declared in `contract.models.ts`
 * because the contract dialog was the first screen to read the catalog; this
 * slice gives them the home the rest of the feature already follows — one model
 * file per resource (`department.models.ts`, `employee.models.ts`,
 * `store-assignment.models.ts`). Nothing was duplicated: the interfaces moved,
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
