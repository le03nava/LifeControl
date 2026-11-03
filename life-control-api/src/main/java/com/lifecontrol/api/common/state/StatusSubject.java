package com.lifecontrol.api.common.state;

/**
 * The five document families ADR-0004 types, out of the seven families {@code statuses} carries.
 *
 * <p>The constant name <em>is</em> the string in two columns, and that is the point: it is the value of
 * {@code status_transition.document_type} and, for the five families that have a domain enum, the value
 * of {@code status_types.status_type_name} that {@code statuses.code} hangs from. One spelling, so the
 * parity test can join the two without a mapping table.</p>
 *
 * <p><b>Why two of the seven are absent, by decision and not by oversight.</b> {@code APPOINTMENT} is a
 * calendar fact and {@code EMPLOYEE_STATUS} is an HR attribute; ADR-0004 declares both out of scope, so
 * neither gets a domain enum, neither gets a code, and {@code scheduling_appointments} and
 * {@code employees} keep their {@code status_id} untouched. A constant here for them would be a machine
 * nobody asked for.</p>
 *
 * <p><b>Why the item families are here.</b> An item's state change is a fact too, so
 * {@code status_transition.document_type} is wider than ADR-0003's {@code doc_type}: it names documents
 * and their lines. {@link com.lifecontrol.api.common.document.DocumentType} stays what it is — the
 * numbering vocabulary — and the three document constants that exist in both spell the same string.</p>
 */
public enum StatusSubject {
    /** {@code sales_orders}. */
    SALES_ORDER,
    /** {@code sales_order_items}. */
    SALES_ORDER_ITEM,
    /** {@code purchase_orders}. */
    PURCHASE_ORDER,
    /** {@code purchase_order_details}. */
    PURCHASE_ORDER_DETAIL,
    /** {@code goods_receipts}. */
    GOODS_RECEIPT
}
