package com.lifecontrol.api.common.document;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Renders a document number from its identity parts, once, and never from a read of the stored value.
 *
 * <p>ADR-0003 decision 5 makes the parts the identity and the rendered string presentation: the database
 * enforces {@code (company_id, doc_type, seq_period, seq_value)}, and {@code doc_number} is written once so
 * a reprint shows the number that was printed. That is why this class is the only producer of the string —
 * a reader that re-renders from the parts could disagree with the row, and the row is what was printed.</p>
 *
 * <p>The two shapes of decision 6 are two methods on purpose. A period-numbered document renders
 * {@code {prefix}-{period}-{seq}} and a derived one renders {@code GR-{parent.doc_number}-{NN}}; calling the
 * wrong one is a compile-time choice here instead of a runtime branch that a wrong {@code doc_type} could
 * take.</p>
 */
public final class DocumentNumberRenderer {

    /**
     * The schema's own constraint on {@code document_sequence.period} ({@code V64}'s
     * {@code ck_document_sequence_period}). Restated here so an impossible period is refused by the caller
     * that built it instead of by a constraint violation mid-transaction.
     */
    private static final Pattern PERIOD_PATTERN = Pattern.compile("^[A-Z0-9]{1,8}$");

    /** {@code SO-202610-00001}: five digits by default, widened when the value needs more. */
    private static final int MIN_SEQUENCE_WIDTH = 5;

    /** {@code GR-PO-202610-00007-01}: the ordinal is scoped to its parent, so two digits are plenty. */
    private static final int MIN_ORDINAL_WIDTH = 2;

    /**
     * {@code uuuu} and not {@code yyyy}: the pattern is the proleptic year, so no date can silently be
     * read as a different year-of-era. The rendered text is identical for every business date.
     */
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("uuuuMM");

    private DocumentNumberRenderer() {}

    /**
     * The period of a document, from the document's own date.
     *
     * @param documentDate the date the document carries, frozen before the allocation runs
     * @return the period string, {@code YYYYMM}
     */
    public static String periodOf(LocalDate documentDate) {
        if (documentDate == null) {
            throw new IllegalArgumentException("A document period needs the document's own date");
        }
        return documentDate.format(PERIOD_FORMAT);
    }

    /**
     * Renders the number of a period-numbered document.
     *
     * @param type the document type, which must allocate from a counter
     * @param period the document's frozen period
     * @param sequenceValue the value the allocator handed out, starting at 1
     * @return the rendered number, {@code {prefix}-{period}-{value}}
     */
    public static String renderPeriodNumber(DocumentType type, String period, long sequenceValue) {
        requireAllocatingType(type);
        requireValidPeriod(period);
        if (sequenceValue < 1) {
            throw new IllegalArgumentException(
                    "A document sequence value starts at 1, and " + sequenceValue + " cannot be rendered");
        }
        return type.prefix() + "-" + period + "-" + pad(sequenceValue, MIN_SEQUENCE_WIDTH);
    }

    /**
     * Renders the number of a derived document from its parent's frozen number.
     *
     * @param type the derived document type
     * @param parentDocNumber the parent's stored {@code doc_number}, read from the row and never re-rendered
     * @param ordinal the receipt's position within its parent, starting at 1
     * @return the rendered number, {@code GR-{parent}-{ordinal}}
     */
    public static String renderDerivedNumber(DocumentType type, String parentDocNumber, long ordinal) {
        if (type == null) {
            throw new IllegalArgumentException("A derived number needs its document type");
        }
        if (type.periodNumbered()) {
            throw new IllegalArgumentException("Document type " + type.code()
                    + " allocates from its own counter, so it has no parent to derive from");
        }
        if (parentDocNumber == null || parentDocNumber.isBlank()) {
            throw new IllegalArgumentException("A derived number needs its parent's frozen doc_number");
        }
        if (ordinal < 1) {
            throw new IllegalArgumentException(
                    "A derived document's ordinal starts at 1, and " + ordinal + " cannot be rendered");
        }
        return type.prefix() + "-" + parentDocNumber + "-" + pad(ordinal, MIN_ORDINAL_WIDTH);
    }

    private static void requireAllocatingType(DocumentType type) {
        if (type == null) {
            throw new IllegalArgumentException("A period number needs its document type");
        }
        if (!type.periodNumbered()) {
            throw new IllegalArgumentException("Document type " + type.code()
                    + " is derived and must not open a counter: its number comes from its parent");
        }
    }

    /**
     * The one check of a period's shape, shared by the renderer and the allocator.
     *
     * <p>It is public because the allocator needs the same rule: a period the schema's
     * {@code ck_document_sequence_period} would refuse must be refused by the caller that built it, so the
     * failure names the period instead of surfacing as a constraint violation mid-transaction.</p>
     *
     * @param period the period to check
     */
    public static void requireValidPeriod(String period) {
        if (period == null || !PERIOD_PATTERN.matcher(period).matches()) {
            throw new IllegalArgumentException("Period '" + period + "' is not a valid document period");
        }
    }

    private static String pad(long value, int minWidth) {
        var digits = Long.toString(value);
        if (digits.length() >= minWidth) {
            return digits;
        }
        return "0".repeat(minWidth - digits.length()) + digits;
    }
}
