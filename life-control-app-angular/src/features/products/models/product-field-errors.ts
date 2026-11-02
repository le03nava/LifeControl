/**
 * Attribution of the catalog endpoints' message-only `400`s to the field each one names.
 *
 * The product and variant endpoints refuse ADR-0006's rules in two different shapes, and the
 * difference is not a detail of the client:
 *
 * - A **bean-validation** refusal arrives as `errors: { <field>: <message> }` — `sku`, `name`,
 *   `variantMode` (the closed vocabulary) and, on the variant endpoint, `measureUnitId` (`@NotNull`)
 *   and `variantName`. The screens already route that map to the control of the same name.
 * - A **rule** refusal arrives with **no `errors` map at all**: `VariantsRequiredException`,
 *   `VariantModeMismatchException`, `BarcodeRequiredException` and `MeasureUnitRequiredException` are
 *   all `IllegalArgumentException`, which `GlobalExceptionHandler` answers with `buildErrorResponse`
 *   — a `400` whose only payload is `message`. The list of variants is not `@Valid`-cascaded, so
 *   `variants[0].barCode` never exists as a key either.
 *
 * Without this module those refusals would land in a generic banner, which is the one place the
 * operator cannot act on: `variants` and `variantMode` have no input to fix from a banner, and
 * `barCode`/`measureUnitId` are per-row. So the field is read from the message's own opening word —
 * every one of those messages begins with the field it names, which is the contract this reads (the
 * messages are quoted, not paraphrased, so the match is exact):
 *
 * - `variants is required: a product is created with at least one variant — …`
 * - `variantMode SIMPLE requires exactly one variant, but the request declares 2`
 * - `barCode is required: the product <id> is MULTI and every one of its variants …`
 * - `measureUnitId is required: a variant declares the unit its quantities are expressed in …`
 *
 * A message that names no field returns `null` and keeps travelling to the general banner, which is
 * the honest answer for a refusal the screen has no control for.
 */
const ATTRIBUTABLE_FIELDS = ['variantMode', 'measureUnitId', 'barCode', 'variants'] as const;

/** The four refusals this slice can turn into a field error. */
export type ProductFieldErrorKey = (typeof ATTRIBUTABLE_FIELDS)[number];

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
