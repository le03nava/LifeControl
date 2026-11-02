/**
 * Attribution of the catalog endpoints' `400`s to the control each one is about.
 *
 * The API states a refusal in two ways, and the client reads both:
 *
 * - **A field map.** Every refusal that names a field arrives as `errors: { <field>: <message> }`:
 *   bean validation (`sku`, `name`, `variantMode`, `variantName`, `measureUnitId`) and — since the
 *   catalog's rule refusals were retrofitted onto one shared exception — `variants`, `barCode` and
 *   `measureUnitId` too, with the same sentence in `message` and in the map. A nested violation inside
 *   the creation's variant list keeps the key bean validation gives a list element,
 *   `variants[<index>].<field>`, so the map says which **row** it is and not only which list.
 * - **A message only.** A body with no `errors` at all still exists — an older API in front of a newer
 *   bundle, and the refusals that genuinely name no control (a disabled measure unit, a type mismatch).
 *   For those the field is read from the message's own opening word, which is the wording this module
 *   used to depend on entirely:
 *
 *   - `variants is required: a product is created with at least one variant — …`
 *   - `variantMode SIMPLE requires exactly one variant, but the request declares 2`
 *   - `barCode is required: the product <id> is MULTI and every one of its variants …`
 *   - `measureUnitId is required: a variant declares the unit its quantities are expressed in …`
 *
 * The fallback is kept — and is the only path that reads wording — because the message is still the
 * only field attribution such a body carries; the map, when it exists, always wins. A message that
 * names no field returns `null` and keeps travelling to the general banner, which is the honest answer
 * for a refusal the screen has no control for.
 */
const ATTRIBUTABLE_FIELDS = ['variantMode', 'measureUnitId', 'barCode', 'variants'] as const;

/** The four refusals that have a screen copy of their own. */
export type ProductFieldErrorKey = (typeof ATTRIBUTABLE_FIELDS)[number];

/**
 * The parts of the API's error envelope this module reads.
 *
 * Structural on purpose: `HttpErrorResponse['error']` is `unknown`, and the screens cast it to
 * `ApiError`, so a body type that names only these two fields accepts both without a cast of its own.
 */
export interface ProductErrorBody {
  message?: string | null;
  errors?: Record<string, string> | null;
}

/**
 * The copy shown on the control, in the screen's own language.
 *
 * The API speaks English, and echoing its prose under a Spanish label is worse than saying the same
 * fact in the language the rest of the form uses — the same reasoning `VARIANT_CONFLICT_MESSAGE`
 * records for the 409 it replaces.
 */
export const PRODUCT_FIELD_ERROR_MESSAGES: Record<ProductFieldErrorKey, string> = {
  variants: 'Un producto se crea con al menos una variante.',
  variantMode:
    'El modo elegido no coincide con las variantes cargadas: un producto simple tiene exactamente una.',
  barCode: 'Una variante de un producto con variantes necesita su código de barras.',
  measureUnitId: 'Elegí la unidad de medida de la variante.',
};

/** Whether `field` is one of the four refusals {@link PRODUCT_FIELD_ERROR_MESSAGES} can render. */
export function isProductFieldErrorKey(field: string): field is ProductFieldErrorKey {
  return (ATTRIBUTABLE_FIELDS as readonly string[]).includes(field);
}

/**
 * The field a message-only refusal names, or `null` when it names none.
 *
 * `variantMode` is matched by bare prefix because its message is not the `… is required` shape: it
 * states the count it refuses (`variantMode SIMPLE requires exactly one variant`). The other three
 * all open with `<field> is required`.
 */
export function productFieldFromMessage(
  message: string | null | undefined,
): ProductFieldErrorKey | null {
  if (!message) {
    return null;
  }
  for (const field of ATTRIBUTABLE_FIELDS) {
    if (message.startsWith(`${field} is required`)) {
      return field;
    }
  }
  if (message.startsWith('variantMode ')) {
    return 'variantMode';
  }
  return null;
}

/**
 * A form control path for a field key: `variants[2].barCode` → `variants.2.barCode`.
 *
 * The API speaks bean-validation notation (brackets for an index) and Angular's `AbstractControl.get()`
 * speaks dot paths, so the difference is translated here, once, instead of in every form that applies a
 * server error. A key with no index is returned unchanged.
 */
export function productControlPath(field: string): string {
  return field.replace(/\[(\d+)\]/g, '.$1');
}

/** The last segment of a field key without its index: `variants[2].barCode` → `barCode`. */
function fieldOfKey(field: string): string {
  const last = field.split('.').pop() ?? field;
  return last.replace(/\[\d+\]$/, '');
}

/**
 * The map to hand to a form, keyed by control path.
 *
 * A body that carries `errors` is read from it and from nothing else — the sentence may say anything.
 * Each key becomes a control path, and the value is the screen's own copy for the four rule refusals
 * ({@link PRODUCT_FIELD_ERROR_MESSAGES}) — recognized structurally: a field violation repeats its
 * sentence in `message` and in `errors[field]`, so a body whose map value is that same sentence is the
 * refusal those copies were written for. Every other entry keeps the API's message verbatim, because a
 * **different** fact about the same control (a duplicate barcode, a bean-validation `required`) is not
 * the rule refusal and must not borrow its copy. A body with no map falls back to
 * {@link productFieldFromMessage}; a body with neither yields `{}`, which is what sends the refusal to
 * the general banner.
 */
export function productFieldErrors(
  body: ProductErrorBody | null | undefined,
): Record<string, string> {
  const errors = body?.errors;
  if (errors && Object.keys(errors).length > 0) {
    const message = body?.message ?? null;
    const attributed: Record<string, string> = {};
    for (const [field, fieldMessage] of Object.entries(errors)) {
      const known = fieldOfKey(field);
      const isTheRuleRefusal =
        message !== null && fieldMessage === message && isProductFieldErrorKey(known);
      attributed[productControlPath(field)] = isTheRuleRefusal
        ? PRODUCT_FIELD_ERROR_MESSAGES[known]
        : fieldMessage;
    }
    return attributed;
  }

  const field = productFieldFromMessage(body?.message);
  return field ? { [field]: PRODUCT_FIELD_ERROR_MESSAGES[field] } : {};
}
