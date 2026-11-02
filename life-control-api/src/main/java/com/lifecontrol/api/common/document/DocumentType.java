package com.lifecontrol.api.common.document;

/**
 * The closed document-type vocabulary of ADR-0003 decision 7.
 *
 * <p>A {@code doc_type} is an identity part, so its spelling is a contract and not a label: one trailing
 * space would open a second number space and nothing would fail. The constant name <em>is</em> the
 * string stored in {@code doc_type} and the string {@code document_sequence} admits, and it is the same
 * spelling ADR-0004's {@code status_transition.document_type} must reuse — that is a spelling contract,
 * not a schema dependency.</p>
 *
 * <p>{@link #periodNumbered()} separates the two shapes of decision 6: only a period-numbered document
 * allocates from a counter, and a derived document takes its number from its parent. A derived type
 * asking for an allocation is refused instead of quietly opening a counter of its own.</p>
 */
public enum DocumentType {
    SALES_ORDER("SO", true),
    PURCHASE_ORDER("PO", true),
    GOODS_RECEIPT("GR", false);

    private final String prefix;
    private final boolean periodNumbered;

    DocumentType(String prefix, boolean periodNumbered) {
        this.prefix = prefix;
        this.periodNumbered = periodNumbered;
    }

    /** The {@code doc_type} column value: the constant's own name, which is the closed vocabulary. */
    public String code() {
        return name();
    }

    /** The leading token of a rendered number; presentation only, never an identity part. */
    public String prefix() {
        return prefix;
    }

    /** Whether this type allocates from {@code document_sequence}, or inherits its parent's number. */
    public boolean periodNumbered() {
        return periodNumbered;
    }
}
