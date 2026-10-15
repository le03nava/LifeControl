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
