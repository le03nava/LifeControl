import { FormControl, FormGroup } from '@angular/forms';
import {
  FISCAL_PROFILE_FIELDS,
  FISCAL_PROFILE_INCOMPLETE,
  FiscalProfileValue,
  fiscalProfileCompleteValidator,
  fiscalProfileViolations,
} from './fiscal-profile-complete';

/**
 * `S3` of `odd/tasks/customers-fiscal-form.md`: the four facts a stamp consumes are stated together or not
 * at all, the refusal names **each** missing one under the API's own property name, and a customer that
 * declares none of them is a complete cash customer.
 *
 * The rule is copied from `FiscalProfileCompleteValidator`, which keeps enforcing it on arrival: this suite
 * pins the copy, it does not replace the original.
 */
describe('fiscalProfileViolations', () => {
  /** A profile with nothing declared — the cash customer, which is legal. */
  const emptyProfile: FiscalProfileValue = {
    legalName: '',
    regimenFiscal: '',
    fiscalStreet: '',
    fiscalZipCode: '',
    fiscalState: '',
    fiscalCountryId: '',
    defaultUsoCfdi: '',
  };

  /** A profile with all four required facts declared. */
  const completeProfile: FiscalProfileValue = {
    ...emptyProfile,
    legalName: 'Cliente Uno SA de CV',
    regimenFiscal: '601',
    fiscalZipCode: '06600',
    defaultUsoCfdi: 'G03',
  };

  it('accepts a customer that declares no fiscal profile at all', () => {
    // S3: the profile is required to issue, never to sell.
    expect(fiscalProfileViolations(emptyProfile)).toEqual({});
  });

  it('accepts a profile that states all four facts', () => {
    expect(fiscalProfileViolations(completeProfile)).toEqual({});
  });

  it('refuses a partial profile naming every missing fact under the API property name', () => {
    const violations = fiscalProfileViolations({ ...completeProfile, legalName: '' });

    // One violation per missing field, keyed the way `FiscalProfileCompleteValidator` keys them, so the
    // message lands beside that control instead of in a banner.
    expect(Object.keys(violations)).toEqual(['legalName']);
    expect(violations.legalName).toBeDefined();
  });

  it('names each missing fact in its own message, one per field', () => {
    const violations = fiscalProfileViolations({
      ...emptyProfile,
      legalName: 'Cliente Uno SA de CV',
    });

    expect(Object.keys(violations)).toEqual(['regimenFiscal', 'fiscalZipCode', 'defaultUsoCfdi']);

    // Each sentence names the fact it is about: the operator reads which one, not that "something" is missing.
    expect(violations.regimenFiscal).toContain('régimen fiscal');
    expect(violations.fiscalZipCode).toContain('código postal fiscal');
    expect(violations.defaultUsoCfdi).toContain('uso de CFDI');
  });

  it('refuses each single fact stated alone', () => {
    for (const field of FISCAL_PROFILE_FIELDS) {
      const violations = fiscalProfileViolations({
        ...emptyProfile,
        [field]: completeProfile[field],
      });

      expect(Object.keys(violations)).toHaveLength(FISCAL_PROFILE_FIELDS.length - 1);
      expect(Object.keys(violations)).not.toContain(field);
    }
  });

  it('counts a whitespace-only value as absent, the way the API normalizes blank to null', () => {
    const violations = fiscalProfileViolations({ ...completeProfile, legalName: '   ' });

    expect(Object.keys(violations)).toEqual(['legalName']);
  });

  it('never consults street, state or country, which the rule does not require', () => {
    // `FiscalProfileCompleteValidator` reads four facts and nothing else: a domicile typed on a customer
    // with no régime is a domicile, not half a profile.
    const violations = fiscalProfileViolations({
      ...emptyProfile,
      fiscalStreet: 'Av. Reforma 100',
      fiscalState: 'Ciudad de México',
      fiscalCountryId: 'country-1',
    });

    expect(violations).toEqual({});
  });
});

describe('fiscalProfileCompleteValidator', () => {
  function buildFiscalGroup(value: Partial<FiscalProfileValue> = {}) {
    return new FormGroup(
      {
        legalName: new FormControl(value.legalName ?? '', { nonNullable: true }),
        regimenFiscal: new FormControl(value.regimenFiscal ?? '', { nonNullable: true }),
        fiscalStreet: new FormControl(value.fiscalStreet ?? '', { nonNullable: true }),
        fiscalZipCode: new FormControl(value.fiscalZipCode ?? '', { nonNullable: true }),
        fiscalState: new FormControl(value.fiscalState ?? '', { nonNullable: true }),
        fiscalCountryId: new FormControl(value.fiscalCountryId ?? '', { nonNullable: true }),
        defaultUsoCfdi: new FormControl(value.defaultUsoCfdi ?? '', { nonNullable: true }),
      },
      { validators: [fiscalProfileCompleteValidator] },
    );
  }

  it('leaves the group valid when nothing is declared', () => {
    expect(buildFiscalGroup().valid).toBe(true);
  });

  it('leaves the group valid when all four facts are declared', () => {
    const group = buildFiscalGroup({
      legalName: 'Cliente Uno SA de CV',
      regimenFiscal: '601',
      fiscalZipCode: '06600',
      defaultUsoCfdi: 'G03',
    });

    expect(group.valid).toBe(true);
  });

  it('marks the group invalid and keys every missing fact by its API property name', () => {
    const group = buildFiscalGroup({ fiscalZipCode: '06600' });

    expect(group.valid).toBe(false);
    expect(group.getError(FISCAL_PROFILE_INCOMPLETE)).toEqual({
      legalName: expect.any(String),
      regimenFiscal: expect.any(String),
      defaultUsoCfdi: expect.any(String),
    });
  });

  it('does not demand the profile from a control it never required', () => {
    const group = buildFiscalGroup();

    // Not `Validators.required` on the four controls: each of them is valid while empty, and it is the
    // group that refuses the partial shape.
    expect(group.controls.legalName.valid).toBe(true);
    expect(group.controls.regimenFiscal.valid).toBe(true);
    expect(group.controls.fiscalZipCode.valid).toBe(true);
    expect(group.controls.defaultUsoCfdi.valid).toBe(true);
  });
});
