/**
 * The three name parts every employee-bearing payload carries, whatever its shape: the employee
 * list's `Employee`, the decision queue's `AccessProvisioningQueueEntry`, and any later read that
 * shows a person.
 */
export interface EmployeeNameParts {
  firstName: string;
  paternalLastName: string;
  maternalLastName: string | null;
}

/**
 * Composes an employee's display name from its three parts, tolerating a `null` maternal last name.
 *
 * This is the app's **single** display-name rule. It was extracted from `employee-list`'s own
 * `fullName` so the pending-decisions inbox can render a queue row's person without writing a second
 * composition (record T96): the list page and the inbox both delegate here, and a name is joined in
 * exactly one place.
 */
export function composeEmployeeFullName(parts: EmployeeNameParts): string {
  return [parts.firstName, parts.paternalLastName, parts.maternalLastName]
    .filter((part): part is string => !!part)
    .join(' ');
}
