/**
 * One customer as `GET /api/customers` answers it.
 *
 * The sixteen fields are `CustomerResponse.java:19-34`, in the order the record declares them. They are
 * declared **all**, including the seven fiscal ones the sibling unit's form will write: an interface
 * that omits what the endpoint sends is how a client drops a value it already received, and the fiscal
 * profile cannot be edited later by a screen that never had it. Which of them are nullable is the
 * entity's own nullability (`Customer.java:20-72`): `name`, `salesChannel` and `enabled` are
 * `NOT NULL`; `email`, `phone`, `rfc` and the seven fiscal columns are nullable, and a customer with no
 * fiscal profile is a complete cash customer, not a half-saved row.
 */
import { FormControl, FormGroup } from '@angular/forms';
import type { FiscalProfileValue } from '../validation/fiscal-profile-complete';

export interface Customer {
  id: string;
  name: string;
  email: string | null;
  phone: string | null;
  rfc: string | null;
  salesChannel: string;
  enabled: boolean;
  legalName: string | null;
  /** A `c_RegimenFiscal` code (`601`, `612`, …), resolved by the service against the SAT vocabulary. */
  regimenFiscal: string | null;
  fiscalStreet: string | null;
  fiscalZipCode: string | null;
  fiscalState: string | null;
  fiscalCountryId: string | null;
  /** A `c_UsoCFDI` code (`G03`, …), resolved by the service against the SAT vocabulary. */
  defaultUsoCfdi: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * The Spring `Page` envelope, with its own field names.
 *
 * **Deliberate duplication of `features/companies/companies/models/company.models.ts:86-95`**, which
 * declares an identical interface. It is copied rather than imported because crossing features for a
 * transport envelope couples two features that share nothing else: the customer screen would depend on
 * the companies feature to describe a page of its own rows, and a change made for companies would move
 * under a screen that never asked for it. The envelope is Spring's, not this repository's, so a second
 * declaration cannot disagree with a first: both are pinned by their own service spec.
 */
/**
 * The wire body `POST`/`PUT /api/customers` accepts, key by key.
 *
 * The thirteen components of `CustomerRequest.java`, and **only** them: the response model carries `id`,
 * `createdAt` and `updatedAt`, which the request DTO does not declare, and the API is configured with
 * `fail-on-unknown-properties`, so it refuses such a body before validating any field. The edit page builds
 * it key by key for exactly that reason.
 *
 * The nullable ones are typed `| null` rather than `?`: the API normalizes blank to `null` on all of them, and
 * a `null` is how this screen says "nothing declared" without pretending the empty string is a value.
 */
export interface CustomerRequest {
  name: string;
  email: string | null;
  phone: string | null;
  rfc: string | null;
  salesChannel: string;
  enabled: boolean;
  legalName: string | null;
  regimenFiscal: string | null;
  fiscalStreet: string | null;
  fiscalZipCode: string | null;
  fiscalState: string | null;
  fiscalCountryId: string | null;
  defaultUsoCfdi: string | null;
}

/**
 * The form value the edit page consumes: the six trade fields beside the row id the route carries and the
 * fiscal sub-group.
 *
 * `id` is in the form and not in the request: it decides create versus update, and it never travels on the
 * wire (`CustomerRequest` does not declare it). The trade fields are non-nullable strings because a text
 * input has no `null`; the page turns blank into `null` when it builds the body.
 */
export interface CustomerFormValue {
  id: string;
  name: string;
  email: string;
  phone: string;
  rfc: string;
  salesChannel: string;
  enabled: boolean;
  fiscal: FiscalProfileValue;
}

/** One `FormControl` per fiscal component, all non-nullable strings; the profile itself is optional. */
export type FiscalProfileControl = {
  [K in keyof FiscalProfileValue]: FormControl<string>;
};

export interface CustomerControl {
  id: FormControl<string>;
  name: FormControl<string>;
  email: FormControl<string>;
  phone: FormControl<string>;
  rfc: FormControl<string>;
  salesChannel: FormControl<string>;
  enabled: FormControl<boolean>;
  /**
   * The fiscal profile as its own group.
   *
   * A sub-group and not seven flat controls: the all-or-nothing rule is a rule **about that set**, so the
   * validator is attached where the set lives and the rule cannot be half-applied.
   */
  fiscal: FormGroup<FiscalProfileControl>;
}

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
