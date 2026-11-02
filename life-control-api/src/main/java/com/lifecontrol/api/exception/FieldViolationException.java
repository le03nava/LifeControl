package com.lifecontrol.api.exception;

/**
 * A refusal that names <b>the field it is about</b>, so a client can mark that control instead of
 * reading the sentence.
 *
 * <p>Until this class existed, a refusal the catalog writes was served as a message only: the
 * {@code GlobalExceptionHandler} answers every bare {@link IllegalArgumentException} with
 * {@link GlobalExceptionHandler.ErrorResponse}, which has no field map at all. The consequence was
 * measured on the client — {@code models/product-field-errors.ts} attributed each refusal by matching
 * the <b>first word of the message</b>, a coupling to backend wording that a reword broke silently.</p>
 *
 * <p>The sentence is <b>not</b> replaced, because it is what a human reads in a log or a mail; the
 * field is carried <b>alongside</b> it ({@link #getField()}) and the handler serves both, as
 * {@code message} and as {@code errors: {field: message}}. One sentence, two readers.</p>
 *
 * <p>The type is <b>abstract on purpose</b>: no caller can invent a field map by throwing this class,
 * only by stating the rule it enforces through a subclass ({@code BarcodeRequiredException},
 * {@code MeasureUnitRequiredException}, {@code VariantsRequiredException},
 * {@code VariantModeMismatchException}). That is the whole widening this contract allows — most of the
 * other {@code IllegalArgumentException} throw sites in this API are not about a field at all (a
 * disabled measure unit, a type mismatch, an invalid state transition), so they keep the message-only
 * envelope they have always served.</p>
 *
 * <p>It stays a subclass of {@link IllegalArgumentException} so that any code that catches the parent
 * keeps catching it, and so the status stays <b>400</b> — a request that names no value for a field
 * that needs one is a client mistake, exactly as before this change.</p>
 */
public abstract class FieldViolationException extends IllegalArgumentException {

    private final String field;

    /**
     * @param field the request field the refusal is about, in the key shape the response uses
     *     ({@code barCode}, {@code variants[N].measureUnitId}); never blank, because a blank key is a
     *     map entry no client can act on
     * @param message the sentence a human reads, kept verbatim as the response's {@code message}
     */
    protected FieldViolationException(String field, String message) {
        super(message);
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("A field violation must name the field it is about");
        }
        this.field = field;
    }

    /** The request field this refusal is about — the key of the response's {@code errors} entry. */
    public String getField() {
        return field;
    }
}
