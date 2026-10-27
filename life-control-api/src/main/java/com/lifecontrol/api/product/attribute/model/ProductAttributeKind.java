package com.lifecontrol.api.product.attribute.model;

/**
 * The shape of a value an attribute definition accepts, and therefore the control the form renders.
 *
 * <p>The set is closed on purpose and mirrors the {@code ck_product_attribute_definitions_kind} of
 * {@code V37__product_attribute_catalog.sql}: an open string would make every reader guess.</p>
 *
 * <ul>
 *   <li>{@link #TEXT} — a free string, the fallback rather than the default.</li>
 *   <li>{@link #ENUM} — exactly one {@link ProductAttributeValue} of its definition.</li>
 *   <li>{@link #MULTI_ENUM} — a non-empty array of them; "Conectividad" is the case.</li>
 *   <li>{@link #MEASUREMENT} — {@code {"value": <number>, "unit": "<code>"}}. Storing "1/2 pulgada" as a
 *       string would make it impossible to compare, sort or convert.</li>
 *   <li>{@link #BOOLEAN} — a flag.</li>
 * </ul>
 */
public enum ProductAttributeKind {
    TEXT,
    ENUM,
    MULTI_ENUM,
    MEASUREMENT,
    BOOLEAN
}
