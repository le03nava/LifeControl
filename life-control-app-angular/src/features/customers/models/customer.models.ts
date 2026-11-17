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
