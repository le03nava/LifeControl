import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/**
 * The all-or-nothing rule of the receiver's fiscal profile, stated once on the client.
 *
 * `FiscalProfileCompleteValidator` on the API refuses a request that states a **partial** profile: four facts
 * a fiscal document consumes are stated together or not at all, and a customer that states none of them is a
 * complete cash customer (`S3` of `odd/tasks/customers-fiscal-form.md`, `L3` of
 * `odd/tasks/cfdi-fiscal-document.md`). This module is that rule's copy, so the operator learns which fact is
 * missing **before** the request leaves the browser instead of from a 400.
 *
 * It is a copy and never the only enforcement: the API keeps refusing the same shape on arrival.
 */

/**
 * The facts a stamp consumes, in the order `FiscalProfileCompleteValidator.REQUIRED_FACTS` states them.
 *
 * `fiscalStreet`, `fiscalState` and `fiscalCountryId` are deliberately absent: the API carries them and never
 * consults them, because what a fiscal document cannot omit of the receiver's address is the postal code.
 */
export const FISCAL_PROFILE_FIELDS = [
  'legalName',
  'regimenFiscal',
  'fiscalZipCode',
  'defaultUsoCfdi',
] as const;

/** One of the four facts, named the way the API's own property is named. */
export type FiscalProfileField = (typeof FISCAL_PROFILE_FIELDS)[number];

/**
 * The seven components of `CustomerRequest` that make up the fiscal block, as the form holds them: every
 * control is a non-nullable string, and "not declared" is the empty string — which the page turns into `null`
 * when it builds the wire body, exactly as the API's canonical constructor normalizes blank.
 */
export interface FiscalProfileValue {
  legalName: string;
  regimenFiscal: string;
  fiscalStreet: string;
  fiscalZipCode: string;
  fiscalState: string;
  fiscalCountryId: string;
  defaultUsoCfdi: string;
}

/** The four facts the rule is about, taken out of the profile the form holds. */
export type FiscalProfileFacts = Pick<FiscalProfileValue, FiscalProfileField>;

/** The error key the refusal is published under, on the group and on each of the four controls. */
export const FISCAL_PROFILE_INCOMPLETE = 'fiscalProfileIncomplete';

/**
 * One message per fact, each naming it.
 *
 * The API's sentence names the property (`legalName is required when any of …`); the screen's names the field
 * the operator sees, because the person reading it is choosing a value in a form and not reading a log.
 */
const MESSAGES: Record<FiscalProfileField, string> = {
  legalName: 'Falta la razón social fiscal: el perfil fiscal se declara completo o vacío.',
  regimenFiscal: 'Falta el régimen fiscal: el perfil fiscal se declara completo o vacío.',
  fiscalZipCode: 'Falta el código postal fiscal: el perfil fiscal se declara completo o vacío.',
  defaultUsoCfdi: 'Falta el uso de CFDI por defecto: el perfil fiscal se declara completo o vacío.',
};

/**
 * Whether the profile states one of the four facts.
 *
 * A blank value is not a declaration, for the same reason `CustomerRequest`'s canonical constructor collapses
 * blank to `null`: "stated" and "absent" are the only two honest answers the rule has.
 */
function isStated(value: string | null | undefined): boolean {
  return typeof value === 'string' && value.trim() !== '';
}

/**
 * The violations a profile earns: **one per missing fact**, keyed by the API's own property name, or none at
 * all when the profile is empty or complete.
 *
 * This function is the rule. The group validator publishes its result on the fiscal group, and the form
 * component paints it beside each field — one derivation, so the two cannot disagree.
 */
export function fiscalProfileViolations(
  value: Partial<Record<FiscalProfileField, string | null>> | Partial<FiscalProfileValue>,
): Partial<Record<FiscalProfileField, string>> {
  const stated = FISCAL_PROFILE_FIELDS.filter((field) => isStated(value[field]));

  // None stated is a cash customer and all four stated is a profile; only a partial one is refused.
  if (stated.length === 0 || stated.length === FISCAL_PROFILE_FIELDS.length) {
    return {};
  }

  const violations: Partial<Record<FiscalProfileField, string>> = {};
  for (const field of FISCAL_PROFILE_FIELDS) {
    if (!isStated(value[field])) {
      violations[field] = MESSAGES[field];
    }
  }
  return violations;
}

/**
 * The rule as a validator for the fiscal sub-group.
 *
 * Attached to a `FormGroup`, so `form.invalid` is true for a partial profile and saving is refused by the same
 * mechanism that refuses any other invalid form. The four controls carry **no** `Validators.required`: that
 * would demand the profile from every cash customer, which the rule explicitly does not.
 *
 * The refusal is published as `{ fiscalProfileIncomplete: { legalName: '…', … } }` — the map is keyed by the
 * API's property names, which is what lets the form paint each sentence beside the control it is about.
 */
export const fiscalProfileCompleteValidator: ValidatorFn = (
  group: AbstractControl,
): ValidationErrors | null => {
  const violations = fiscalProfileViolations(group.value ?? {});
  return Object.keys(violations).length > 0 ? { [FISCAL_PROFILE_INCOMPLETE]: violations } : null;
};
