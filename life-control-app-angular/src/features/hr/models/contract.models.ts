/**
 * Wire models of an employee's contract history and the catalogs the contract
 * dialog reads.
 *
 * The contract side mirrors the three backend DTOs exactly: `ContractRequest` is
 * the create payload, `CloseContractRequest` the optional body of the close
 * route, and `Contract` the `ContractResponse` read model. There is
 * deliberately **no** contract update payload: a salary change or a promotion is
 * a new contract, which is the reason the history table exists at all.
 *
 * Dates are the wire's ISO strings, like `employees.termination_date`.
 * `endDate` is the **last day covered**, inclusive — an open-ended contract is
 * `null`, and `null` is **not** the same fact as "current" (decision T11). The
 * column stores the exclusive bound and the backend converts at the DTO
 * boundary (decision D14), so the value sent is the value read back.
 *
 * The catalog models mirror `PositionResponse`, `SeniorityLevelResponse` and
 * `PositionSalaryBandResponse`, with the same nullability the migration V19
 * declares: a position's `description`, `reportsToPositionId` and `displayOrder`
 * are nullable columns, while a level's `rank` and a band's salaries are not.
 */
export type ContractType = 'PERMANENT' | 'FIXED_TERM' | 'TEMPORARY' | 'INTERNSHIP' | 'CONTRACTOR';

/**
 * Spanish labels for the five legal forms of D13.
 *
 * Keys are the server's enum names exactly. `PART_TIME` is deliberately absent:
 * `contract_type` names the legal form, not the workload (gap G9), so a
 * workload-valued constant here would be the first step to closing G9 in the
 * wrong place. A value this map does not know falls back to the raw value
 * through {@link contractTypeLabel}.
 */
export const CONTRACT_TYPE_LABELS: Readonly<Record<ContractType, string>> = {
  PERMANENT: 'Permanente',
  FIXED_TERM: 'Plazo fijo',
  TEMPORARY: 'Temporal',
  INTERNSHIP: 'Pasantía',
  CONTRACTOR: 'Contratista',
};

/**
 * The Spanish label for `contractType`, or the raw value when the map does not
 * know it — never `undefined` and never an empty string.
 *
 * `Object.hasOwn` guards the prototype chain: a bare lookup would let a value
 * like `'constructor'` reach `Object.prototype` and return a function instead of
 * the fallback.
 */
export function contractTypeLabel(contractType: ContractType): string {
  return Object.hasOwn(CONTRACT_TYPE_LABELS, contractType)
    ? CONTRACT_TYPE_LABELS[contractType]
    : contractType;
}

/** Read model of one row of an employee's contract history. */
export interface Contract {
  id: string;
  employeeId: string;
  positionId: string;
  positionName: string;
  seniorityLevelId: string;
  seniorityLevelName: string;
  contractType: ContractType;
  monthlySalary: number;
  startDate: string;
  endDate: string | null;
  enabled: boolean;
}

/**
 * Write payload for a new contract, shared by nothing: create is the only
 * contract write. There is deliberately no `enabled` field — a new contract is
 * always enabled, and the only mutation a contract allows is being closed once.
 */
export interface ContractRequest {
  positionId: string;
  seniorityLevelId: string;
  contractType: ContractType;
  monthlySalary: number;
  startDate: string;
  endDate?: string | null;
}

/**
 * Optional body of `PATCH …/contracts/{id}/close`.
 *
 * An omitted body and a `null` `endDate` both mean "close today"; when present,
 * `endDate` is the last day covered, inclusive, like `ContractRequest.endDate`.
 */
export interface CloseContractRequest {
  endDate?: string | null;
}

/** Read model of a company-scoped position, following the catalog id-only convention. */
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

/** Read model of a global seniority level. */
export interface SeniorityLevel {
  id: string;
  levelCode: string;
  levelName: string;
  rank: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** Read model of a position's salary band for one seniority level. */
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
