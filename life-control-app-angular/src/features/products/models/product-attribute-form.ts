import { AbstractControl, FormControl, FormGroup, ValidatorFn, Validators } from '@angular/forms';
import { ProductAttributeDefinition, ProductAttributeMeasurement } from './product.models';

/**
 * Suffixes of the two controls a `MEASUREMENT` renders.
 *
 * Deliberately not a `.`: `AbstractControl.get` splits a string path on dots, so `<code>.value` would
 * look for a nested group that does not exist. The pair is internal to this module and the field-set,
 * and the JSON is keyed by the definition's `code` alone (S5).
 */
export const MEASUREMENT_VALUE_SUFFIX = '__value';
export const MEASUREMENT_UNIT_SUFFIX = '__unit';

/** Control name of the number half of a measurement. */
export function measurementValueName(definition: ProductAttributeDefinition): string {
  return `${definition.code}${MEASUREMENT_VALUE_SUFFIX}`;
}

/** Control name of the unit half of a measurement. */
export function measurementUnitName(definition: ProductAttributeDefinition): string {
  return `${definition.code}${MEASUREMENT_UNIT_SUFFIX}`;
}

/**
 * Builds the field-set of one profile level, seeded from the values already stored.
 *
 * Seeding is by `code` and never by position, and it is *filtered* by the definitions it is given: a
 * code the previous category stored and this one does not declare is dropped, which is exactly what
 * the API's write rule requires (an unknown code at that level is a `400`) and what stops the
 * attributes of the previous branch from surviving a category change invisibly (record L4).
 *
 * A stored value that the definition cannot represent — an enum code it no longer offers, a
 * measurement without a number — is treated as absent rather than shown as a phantom selection.
 */
export function createAttributeForm(
  definitions: ProductAttributeDefinition[],
  saved: Record<string, unknown>,
): FormGroup {
  const controls: Record<string, AbstractControl> = {};

  for (const definition of definitions) {
    const stored = saved[definition.code];

    switch (definition.kind) {
      case 'TEXT': {
        controls[definition.code] = new FormControl<string | null>(asText(stored), {
          validators: requiredWhen(definition),
        });
        break;
      }

      case 'ENUM': {
        const storedCode = asText(stored);
        const offered =
          storedCode !== null && definition.values.some((value) => value.code === storedCode);
        controls[definition.code] = new FormControl<string | null>(offered ? storedCode : null, {
          validators: requiredWhen(definition),
        });
        break;
      }

      case 'MULTI_ENUM': {
        const storedCodes = asCodeArray(stored).filter((code) =>
          definition.values.some((value) => value.code === code),
        );
        controls[definition.code] = new FormControl<string[]>(storedCodes, {
          validators: definition.required ? nonEmpty : [],
        });
        break;
      }

      case 'BOOLEAN': {
        // A flag has no "empty" state: the toggle always shows a definite value, so it is always
        // written. There is nothing for `required` to add here.
        controls[definition.code] = new FormControl<boolean>(stored === true, {
          nonNullable: true,
        });
        break;
      }

      case 'MEASUREMENT': {
        const measurement = asMeasurement(stored);
        const valueControl = new FormControl<number | null>(measurement?.value ?? null, {
          validators: definition.required
            ? [Validators.required, Validators.min(0)]
            : [Validators.min(0)],
        });
        // The unit is what the API stores with the number, so a value without one is refused there;
        // asking for it here is what keeps the operator from discovering that as a `400`. The
        // definition's `unitLabel` is only the default: writing a different unit is legal.
        const unitControl = new FormControl<string | null>(
          measurement?.unit ?? definition.unitLabel ?? null,
          { validators: unitWhenValue(valueControl) },
        );
        // The unit's validity depends on its sibling, so it is refreshed when the number changes. The
        // subscription lives and dies with the controls it links, which are replaced together with the
        // group on every profile change.
        valueControl.valueChanges.subscribe(() => unitControl.updateValueAndValidity());
        controls[measurementValueName(definition)] = valueControl;
        controls[measurementUnitName(definition)] = unitControl;
        break;
      }
    }
  }

  return new FormGroup(controls);
}

/**
 * The JSON object the field-set writes, keyed by definition `code` (S5).
 *
 * An attribute with nothing in it is **absent**, never `""` and never `null`: the API refuses a `null`
 * inside the map and would read a blank string as a value the operator chose. The single exception is
 * a `BOOLEAN`, which always has a definite value.
 */
export function attributePayload(
  definitions: ProductAttributeDefinition[],
  form: FormGroup,
): Record<string, unknown> {
  const payload: Record<string, unknown> = {};

  for (const definition of definitions) {
    const value = payloadValue(definition, form);
    if (value !== undefined) {
      payload[definition.code] = value;
    }
  }

  return payload;
}

/**
 * The attributes a product or variant already stores, safe to seed from.
 *
 * A blank string, a malformed JSON and a non-object body all mean "nothing saved": the carrier control
 * holds a string, and every one of those states has to render as an empty field-set instead of
 * throwing while the operator opens the screen.
 */
export function parseAttributeJson(raw: string | null | undefined): Record<string, unknown> {
  if (!raw || !raw.trim()) {
    return {};
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return {};
  }

  return asStoredObject(parsed);
}

/** A one-line identity of a definition list, so a re-fetch of the same profile is not a rebuild. */
export function attributeDefinitionsKey(definitions: ProductAttributeDefinition[]): string {
  return definitions
    .map(
      (definition) =>
        `${definition.code}|${definition.kind}|${definition.required}|${definition.unitLabel ?? ''}`,
    )
    .join(',');
}

// ─── internals ───────────────────────────────────────────────────────────────

function requiredWhen(definition: ProductAttributeDefinition): ValidatorFn[] {
  return definition.required ? [Validators.required] : [];
}

/** `MULTI_ENUM` refuses an empty array: the API stores a non-empty list and nothing else. */
const nonEmpty: ValidatorFn = (control: AbstractControl) => {
  const value: unknown = control.value;
  return Array.isArray(value) && value.length > 0 ? null : { required: true };
};

/** A measurement's unit is required as soon as the number is filled in. */
function unitWhenValue(valueControl: AbstractControl): ValidatorFn {
  return (control: AbstractControl) => {
    const value: unknown = valueControl.value;
    if (value === null || value === undefined) {
      return null;
    }
    const unit = typeof control.value === 'string' ? control.value.trim() : '';
    return unit === '' ? { required: true } : null;
  };
}

function payloadValue(
  definition: ProductAttributeDefinition,
  form: FormGroup,
): unknown | undefined {
  switch (definition.kind) {
    case 'TEXT': {
      const text = asText(form.get(definition.code)?.value);
      return text === null || text.trim() === '' ? undefined : text.trim();
    }

    case 'ENUM': {
      const code = asText(form.get(definition.code)?.value);
      return code === null || code === '' ? undefined : code;
    }

    case 'MULTI_ENUM': {
      const codes = asCodeArray(form.get(definition.code)?.value);
      return codes.length > 0 ? codes : undefined;
    }

    case 'BOOLEAN':
      return form.get(definition.code)?.value === true;

    case 'MEASUREMENT': {
      const value = form.get(measurementValueName(definition))?.value;
      const unit = asText(form.get(measurementUnitName(definition))?.value);
      if (typeof value !== 'number' || Number.isNaN(value) || unit === null || unit.trim() === '') {
        return undefined;
      }
      const measurement: ProductAttributeMeasurement = { value, unit: unit.trim() };
      return measurement;
    }
  }
}

function asText(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function asCodeArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((entry): entry is string => typeof entry === 'string')
    : [];
}

function asMeasurement(value: unknown): ProductAttributeMeasurement | null {
  const stored = asStoredObject(value);
  const storedValue = stored['value'];
  const storedUnit = asText(stored['unit']);
  if (typeof storedValue !== 'number' || Number.isNaN(storedValue) || storedUnit === null) {
    return null;
  }
  return { value: storedValue, unit: storedUnit };
}

function asStoredObject(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}
