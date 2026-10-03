/**
 * Wire model of a company-scoped employee.
 *
 * Mirrors `EmployeeResponse`. The nullable fields are exactly the columns V20
 * leaves nullable — `maternalLastName`, `phoneNumber`, `terminationDate`,
 * `addressId` and `keycloakUserId` — plus nothing else: `statusId`/`statusName`
 * come from the `NOT NULL` `employment_status_id` join, and `email`,
 * `employeeNumber`, both required names and the two required dates are non-null.
 *
 * `keycloakUserId` is exposed read-only (decision T9/T17): it tells the form
 * whether the email is frozen, and it is never accepted on a write.
 *
 * Dates are the wire's ISO strings: `'YYYY-MM-DD'` for the three date fields and
 * timestamps for `createdAt`/`updatedAt`.
 */
export interface Employee {
  id: string;
  companyId: string;
  employeeNumber: string;
  firstName: string;
  paternalLastName: string;
  maternalLastName: string | null;
  email: string;
  phoneNumber: string | null;
  birthDate: string;
  hireDate: string;
  terminationDate: string | null;
  addressId: string | null;
  statusId: string;
  statusName: string;
  keycloakUserId: string | null;
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * Write payload for an employee, shared by create and update.
 *
 * Mirrors `EmployeeRequest`, which is one record for both operations. There is
 * deliberately **no** `keycloakUserId` (the access-provisioning flow owns that
 * column, T17) and **no** `enabled` (the dedicated enable route sets it):
 * adding either here would invent a field the backend never accepts.
 */
export interface EmployeeRequest {
  employeeNumber: string;
  firstName: string;
  paternalLastName: string;
  maternalLastName?: string | null;
  email?: string | null;
  phoneNumber?: string | null;
  birthDate: string;
  hireDate: string;
  terminationDate?: string | null;
  addressId?: string | null;
  statusId?: string | null;
}

/**
 * Answer of `GET …/employees/suggest-email`.
 *
 * Exactly one of the two fields is set: the free candidate address, or a
 * machine-readable reason the form turns into a blocking instruction.
 */
export interface EmployeeEmailSuggestion {
  email: string | null;
  reason: string | null;
}
