import { FormControl } from '@angular/forms';

/**
 * Wire model of a company-scoped department.
 *
 * Mirrors `DepartmentResponse`. Note what it does **not** carry: there is no
 * position count and no endpoint that returns one, so the list renders the
 * columns this shape actually supports (code, name, description, order, status)
 * and the missing count is a recorded gap.
 */
export interface Department {
  id: string;
  companyId: string;
  departmentCode: string;
  departmentName: string;
  description: string | null;
  displayOrder: number | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Write payload for a department, shared by create and update.
 *
 * Mirrors `DepartmentRequest`, which is one record for both operations because
 * the catalog has no `version` column and therefore no 412 precondition (T2).
 * `enabled` is optional and the backend defaults it to `true`; `description` and
 * `displayOrder` are optional.
 */
export interface DepartmentRequest {
  departmentCode: string;
  departmentName: string;
  description?: string | null;
  displayOrder?: number | null;
  enabled?: boolean;
}

/** Typed control map for the department edit form's self-contained `FormGroup`. */
export interface DepartmentControl {
  departmentCode: FormControl<string>;
  departmentName: FormControl<string>;
  description: FormControl<string>;
  displayOrder: FormControl<number | null>;
  enabled: FormControl<boolean>;
}
