import {
  PRODUCT_FIELD_ERROR_MESSAGES,
  productControlPath,
  productFieldErrors,
  productFieldFromMessage,
} from './product-field-errors';

/**
 * The contract this module reads, stated once: the API's field map is the attribution, the wording is
 * only the fallback for a body that carries no map.
 */
describe('product-field-errors', () => {
  describe('productFieldErrors', () => {
    it('should attribute by the errors key, not by the wording', () => {
      // The sentence is deliberately not the `… is required` shape the module used to match on: a screen
      // that read the message could not put anything on `barCode` from it.
      const errors = productFieldErrors({
        message: 'Esta variante necesita su código de barras.',
        errors: { barCode: 'Esta variante necesita su código de barras.' },
      });

      expect(errors).toEqual({
        barCode: PRODUCT_FIELD_ERROR_MESSAGES.barCode,
      });
    });

    it('should keep the API wording for a refusal that is not the rule one', () => {
      // The same control, a different fact: a duplicated barcode is not `barCode is required`, so it must
      // not borrow that refusal's copy.
      const errors = productFieldErrors({ errors: { barCode: 'Código ya registrado' } });

      expect(errors).toEqual({ barCode: 'Código ya registrado' });
    });

    it('should translate a nested variants[N] key into the row control path', () => {
      const errors = productFieldErrors({
        message: 'Validation failed',
        errors: { 'variants[1].measureUnitId': 'measureUnitId is required' },
      });

      expect(errors).toEqual({ 'variants.1.measureUnitId': 'measureUnitId is required' });
    });

    it('should keep a first-level key and a nested key side by side', () => {
      const errors = productFieldErrors({
        message: 'Validation failed',
        errors: {
          sku: 'sku is required',
          'variants[0].variantName': 'variantName is required',
        },
      });

      expect(Object.keys(errors)).toEqual(['sku', 'variants.0.variantName']);
    });

    it('should fall back to the wording only for a body with no map', () => {
      const errors = productFieldErrors({
        message: 'variants is required: a product is created with at least one variant',
      });

      expect(errors).toEqual({ variants: PRODUCT_FIELD_ERROR_MESSAGES.variants });
    });

    it('should attribute nothing when the body names no field and carries no map', () => {
      expect(productFieldErrors({ message: 'Error interno del servidor' })).toEqual({});
      expect(productFieldErrors({ errors: {} })).toEqual({});
      expect(productFieldErrors(null)).toEqual({});
    });
  });

  describe('productControlPath', () => {
    it('should turn every bracket index into a dot segment', () => {
      expect(productControlPath('variants[0].measureUnitId')).toBe('variants.0.measureUnitId');
      expect(productControlPath('barCode')).toBe('barCode');
    });
  });

  describe('productFieldFromMessage', () => {
    it('should name the field a message-only refusal opens with', () => {
      expect(productFieldFromMessage('barCode is required: the product x is MULTI')).toBe(
        'barCode',
      );
      expect(productFieldFromMessage('variantMode SIMPLE requires exactly one variant')).toBe(
        'variantMode',
      );
      expect(productFieldFromMessage('Measure unit is disabled')).toBeNull();
      expect(productFieldFromMessage(undefined)).toBeNull();
    });
  });
});
